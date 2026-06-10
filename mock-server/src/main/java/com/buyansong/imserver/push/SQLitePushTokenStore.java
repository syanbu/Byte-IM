package com.buyansong.imserver.push;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

public final class SQLitePushTokenStore implements PushTokenStore {
    private final String jdbcUrl;

    public SQLitePushTokenStore(Path databasePath) {
        try {
            Path parent = databasePath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception error) {
            throw new IllegalStateException("Unable to create push database directory", error);
        }
        this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath().toUri();
        initialize();
    }

    @Override
    public synchronized void registerToken(String userId, String pushToken, String platform, String deviceId, long updatedAt) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     """
                     INSERT INTO push_tokens(user_id, push_token, platform, device_id, updated_at)
                     VALUES(?, ?, ?, ?, ?)
                     ON CONFLICT(user_id) DO UPDATE SET
                       push_token = excluded.push_token,
                       platform = excluded.platform,
                       device_id = excluded.device_id,
                       updated_at = excluded.updated_at
                     """
             )) {
            statement.setString(1, userId);
            statement.setString(2, pushToken);
            statement.setString(3, platform);
            statement.setString(4, deviceId);
            statement.setLong(5, updatedAt);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to register push token", error);
        }
    }

    @Override
    public synchronized void unregisterToken(String userId) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM push_tokens WHERE user_id = ?")) {
            statement.setString(1, userId);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to unregister push token", error);
        }
    }

    @Override
    public synchronized Optional<PushTokenRecord> findByUserId(String userId) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT user_id, push_token, platform, device_id, updated_at FROM push_tokens WHERE user_id = ?"
             )) {
            statement.setString(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PushTokenRecord(
                        resultSet.getString("user_id"),
                        resultSet.getString("push_token"),
                        resultSet.getString("platform"),
                        resultSet.getString("device_id"),
                        resultSet.getLong("updated_at")
                ));
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to read push token", error);
        }
    }

    private void initialize() {
        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS push_tokens (
                      user_id TEXT PRIMARY KEY,
                      push_token TEXT NOT NULL,
                      platform TEXT NOT NULL,
                      device_id TEXT,
                      updated_at INTEGER NOT NULL
                    )
                    """
            );
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to initialize push token database", error);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }
}
