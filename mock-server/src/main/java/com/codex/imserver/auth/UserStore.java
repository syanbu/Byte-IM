package com.codex.imserver.auth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

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
                     "INSERT OR IGNORE INTO users(phone, salt, password_hash, created_at) VALUES(?, ?, ?, ?)"
             )) {
            statement.setString(1, record.phone());
            statement.setString(2, record.salt());
            statement.setString(3, record.passwordHash());
            statement.setLong(4, record.createdAt());
            return statement.executeUpdate() == 1;
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to insert user", error);
        }
    }

    public synchronized Optional<UserRecord> findByPhone(String phone) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT phone, salt, password_hash, created_at FROM users WHERE phone = ?"
             )) {
            statement.setString(1, phone);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new UserRecord(
                        resultSet.getString("phone"),
                        resultSet.getString("salt"),
                        resultSet.getString("password_hash"),
                        resultSet.getLong("created_at")
                ));
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to find user", error);
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

    private void initialize() {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     """
                     CREATE TABLE IF NOT EXISTS users (
                       phone VARCHAR(11) PRIMARY KEY,
                       salt VARCHAR(128) NOT NULL,
                       password_hash VARCHAR(512) NOT NULL,
                       created_at BIGINT NOT NULL
                     )
                     """
             )) {
            statement.executeUpdate();
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
}
