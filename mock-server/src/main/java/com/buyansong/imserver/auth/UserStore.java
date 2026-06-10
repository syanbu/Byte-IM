package com.buyansong.imserver.auth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class UserStore {
    private final String jdbcUrl;

    public UserStore(Path databasePath) {
        try {
            Path parent = databasePath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception error) {
            throw new IllegalStateException("Unable to create database directory", error);
        }
        this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath();
        initialize();
    }

    public synchronized boolean insert(UserRecord record) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     """
                     INSERT OR IGNORE INTO users(
                       phone, salt, password_hash, nickname, avatar_url, avatar_object_key,
                       avatar_updated_at, updated_at, created_at, gender, signature, profile_version
                     ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """
             )) {
            statement.setString(1, record.phone());
            statement.setString(2, record.salt());
            statement.setString(3, record.passwordHash());
            statement.setString(4, record.nickname());
            statement.setString(5, record.avatarUrl());
            statement.setString(6, record.avatarObjectKey());
            statement.setLong(7, record.avatarUpdatedAt());
            statement.setLong(8, record.updatedAt());
            statement.setLong(9, record.createdAt());
            statement.setString(10, record.gender());
            statement.setString(11, record.signature());
            statement.setLong(12, record.profileVersion());
            return statement.executeUpdate() == 1;
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to insert user", error);
        }
    }

    public synchronized Optional<UserRecord> findByPhone(String phone) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     """
                     SELECT phone, salt, password_hash, nickname, avatar_url, avatar_object_key,
                            avatar_updated_at, updated_at, created_at, gender, signature, profile_version
                     FROM users
                     WHERE phone = ?
                     """
             )) {
            statement.setString(1, phone);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(readUser(resultSet));
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to find user", error);
        }
    }

    public synchronized List<UserRecord> findByPhones(List<String> phones) {
        List<String> normalizedPhones = phones.stream()
                .filter(phone -> phone != null && !phone.isBlank())
                .distinct()
                .toList();
        if (normalizedPhones.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", normalizedPhones.stream().map(ignored -> "?").toList());
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT phone, salt, password_hash, nickname, avatar_url, avatar_object_key, " +
                             "avatar_updated_at, updated_at, created_at, gender, signature, profile_version " +
                             "FROM users WHERE phone IN (" + placeholders + ")"
             )) {
            for (int index = 0; index < normalizedPhones.size(); index++) {
                statement.setString(index + 1, normalizedPhones.get(index));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<UserRecord> records = new ArrayList<>();
                while (resultSet.next()) {
                    records.add(readUser(resultSet));
                }
                return records;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to find users by phones", error);
        }
    }

    public synchronized long profileUpdatedAtByPhone(String phone) {
        return findByPhone(phone)
                .map(UserRecord::updatedAt)
                .orElse(0L);
    }

    public synchronized Optional<UserRecord> updateProfile(String phone, String nickname, String avatarUrl, String avatarObjectKey, String gender, String signature, long nowMillis) {
        Optional<UserRecord> current = findByPhone(phone);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        String nextNickname = nickname == null || nickname.isBlank() ? current.get().nickname() : nickname.trim();
        String nextGender = gender == null ? current.get().gender() : gender;
        String nextSignature = signature == null ? current.get().signature() : signature;
        String nextAvatarUrl = avatarUrl == null ? current.get().avatarUrl() : avatarUrl;
        String nextAvatarObjectKey = avatarObjectKey == null && nextAvatarUrl != null && nextAvatarUrl.equals(current.get().avatarUrl())
                ? current.get().avatarObjectKey()
                : avatarObjectKey;
        long avatarUpdatedAt = nextAvatarUrl == null || nextAvatarUrl.equals(current.get().avatarUrl())
                ? current.get().avatarUpdatedAt()
                : nowMillis;
        long nextProfileVersion = current.get().profileVersion() + 1;
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     """
                     UPDATE users
                     SET nickname = ?, avatar_url = ?, avatar_object_key = ?, avatar_updated_at = ?, updated_at = ?,
                         gender = ?, signature = ?, profile_version = ?
                     WHERE phone = ?
                     """
             )) {
            statement.setString(1, nextNickname);
            statement.setString(2, nextAvatarUrl);
            statement.setString(3, nextAvatarObjectKey);
            statement.setLong(4, avatarUpdatedAt);
            statement.setLong(5, nowMillis);
            statement.setString(6, nextGender);
            statement.setString(7, nextSignature);
            statement.setLong(8, nextProfileVersion);
            statement.setString(9, phone);
            statement.executeUpdate();
            return findByPhone(phone);
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to update user profile", error);
        }
    }

    public synchronized void saveRefreshToken(String phone, String tokenHash, long expiresAt, long issuedAt) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT OR REPLACE INTO refresh_tokens(token_hash, phone, expires_at, issued_at, revoked_at) VALUES(?, ?, ?, ?, NULL)"
             )) {
            statement.setString(1, tokenHash);
            statement.setString(2, phone);
            statement.setLong(3, expiresAt);
            statement.setLong(4, issuedAt);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to save refresh token", error);
        }
    }

    public synchronized Optional<RefreshTokenRecord> findActiveRefreshToken(String tokenHash, long nowMillis) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     """
                     SELECT phone, token_hash, expires_at, revoked_at
                     FROM refresh_tokens
                     WHERE token_hash = ? AND expires_at > ? AND revoked_at IS NULL
                     """
             )) {
            statement.setString(1, tokenHash);
            statement.setLong(2, nowMillis);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new RefreshTokenRecord(
                        resultSet.getString("phone"),
                        resultSet.getString("token_hash"),
                        resultSet.getLong("expires_at"),
                        null
                ));
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to find refresh token", error);
        }
    }

    public synchronized void revokeRefreshToken(String tokenHash, long revokedAt) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE refresh_tokens SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL"
             )) {
            statement.setLong(1, revokedAt);
            statement.setString(2, tokenHash);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to revoke refresh token", error);
        }
    }

    public synchronized void rotateRefreshToken(
            String currentTokenHash,
            String phone,
            String nextTokenHash,
            long nextExpiresAt,
            long issuedAt
    ) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement revokeStatement = connection.prepareStatement(
                    "UPDATE refresh_tokens SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL"
            );
                 PreparedStatement insertStatement = connection.prepareStatement(
                         "INSERT INTO refresh_tokens(token_hash, phone, expires_at, issued_at, revoked_at) VALUES(?, ?, ?, ?, NULL)"
                 )) {
                revokeStatement.setLong(1, issuedAt);
                revokeStatement.setString(2, currentTokenHash);
                revokeStatement.executeUpdate();

                insertStatement.setString(1, nextTokenHash);
                insertStatement.setString(2, phone);
                insertStatement.setLong(3, nextExpiresAt);
                insertStatement.setLong(4, issuedAt);
                insertStatement.executeUpdate();

                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to rotate refresh token", error);
        }
    }

    private void initialize() {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     """
                     CREATE TABLE IF NOT EXISTS users (
                       phone VARCHAR(11) PRIMARY KEY,
                       salt VARCHAR(128) NOT NULL,
                       password_hash VARCHAR(512) NOT NULL,
                       nickname VARCHAR(128),
                       avatar_url TEXT,
                       avatar_object_key TEXT,
                       avatar_updated_at BIGINT NOT NULL DEFAULT 0,
                       updated_at BIGINT NOT NULL DEFAULT 0,
                       created_at BIGINT NOT NULL,
                       gender TEXT,
                       signature TEXT,
                       profile_version BIGINT NOT NULL DEFAULT 0
                     )
                     """
             )) {
            statement.executeUpdate();
            ensureUserProfileColumns(connection);
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to initialize user database", error);
        }
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     """
                     CREATE TABLE IF NOT EXISTS refresh_tokens (
                       token_hash VARCHAR(128) PRIMARY KEY,
                       phone VARCHAR(11) NOT NULL,
                       expires_at BIGINT NOT NULL,
                       issued_at BIGINT NOT NULL,
                       revoked_at BIGINT
                     )
                     """
             )) {
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to initialize refresh token database", error);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private UserRecord readUser(ResultSet resultSet) throws SQLException {
        String phone = resultSet.getString("phone");
        String nickname = resultSet.getString("nickname");
        long createdAt = resultSet.getLong("created_at");
        long updatedAt = resultSet.getLong("updated_at");
        return new UserRecord(
                phone,
                resultSet.getString("salt"),
                resultSet.getString("password_hash"),
                nickname == null || nickname.isBlank() ? phone : nickname,
                resultSet.getString("avatar_url"),
                resultSet.getString("avatar_object_key"),
                resultSet.getLong("avatar_updated_at"),
                updatedAt == 0L ? createdAt : updatedAt,
                createdAt,
                resultSet.getString("gender"),
                resultSet.getString("signature"),
                resultSet.getLong("profile_version")
        );
    }

    private void ensureUserProfileColumns(Connection connection) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA table_info(users)");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                columns.add(resultSet.getString("name"));
            }
        }
        addColumnIfMissing(connection, columns, "nickname", "ALTER TABLE users ADD COLUMN nickname VARCHAR(128)");
        addColumnIfMissing(connection, columns, "avatar_url", "ALTER TABLE users ADD COLUMN avatar_url TEXT");
        addColumnIfMissing(connection, columns, "avatar_object_key", "ALTER TABLE users ADD COLUMN avatar_object_key TEXT");
        addColumnIfMissing(connection, columns, "avatar_updated_at", "ALTER TABLE users ADD COLUMN avatar_updated_at BIGINT NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, columns, "updated_at", "ALTER TABLE users ADD COLUMN updated_at BIGINT NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, columns, "gender", "ALTER TABLE users ADD COLUMN gender TEXT");
        addColumnIfMissing(connection, columns, "signature", "ALTER TABLE users ADD COLUMN signature TEXT");
        addColumnIfMissing(connection, columns, "profile_version", "ALTER TABLE users ADD COLUMN profile_version BIGINT NOT NULL DEFAULT 0");
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE users SET nickname = phone WHERE nickname IS NULL OR nickname = ''"
        )) {
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE users SET updated_at = created_at WHERE updated_at = 0"
        )) {
            statement.executeUpdate();
        }
    }

    private void addColumnIfMissing(Connection connection, Set<String> columns, String column, String sql) throws SQLException {
        if (columns.contains(column)) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }
}
