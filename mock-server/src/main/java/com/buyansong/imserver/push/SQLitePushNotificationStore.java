package com.buyansong.imserver.push;

import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class SQLitePushNotificationStore implements PushNotificationStore {
    private final String jdbcUrl;

    public SQLitePushNotificationStore(Path databasePath) {
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
    public synchronized boolean enqueueIfAbsent(String userId, JsonObject message, long createdAt) {
        PushNotificationRecord record = InMemoryPushNotificationStore.fromMessage(0L, userId, message, createdAt);
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     """
                     INSERT OR IGNORE INTO push_notifications(
                       user_id, sender_id, conversation_id, message_id, message_type, preview,
                       server_seq, server_time, created_at
                     )
                     VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """
             )) {
            statement.setString(1, record.userId());
            statement.setString(2, record.senderId());
            statement.setString(3, record.conversationId());
            statement.setString(4, record.messageId());
            statement.setString(5, record.messageType());
            statement.setString(6, record.preview());
            statement.setLong(7, record.serverSeq());
            statement.setLong(8, record.serverTime());
            statement.setLong(9, record.createdAt());
            return statement.executeUpdate() > 0;
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to enqueue push notification", error);
        }
    }

    @Override
    public synchronized List<PushNotificationRecord> pending(String userId, long sincePushId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     """
                     SELECT push_id, user_id, sender_id, conversation_id, message_id, message_type,
                            preview, server_seq, server_time, created_at
                     FROM push_notifications
                     WHERE user_id = ? AND delivered_at IS NULL AND push_id > ?
                     ORDER BY push_id ASC
                     LIMIT ?
                     """
             )) {
            statement.setString(1, userId);
            statement.setLong(2, sincePushId);
            statement.setInt(3, safeLimit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<PushNotificationRecord> rows = new ArrayList<>();
                while (resultSet.next()) {
                    rows.add(readRecord(resultSet));
                }
                return rows;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to list pending push notifications", error);
        }
    }

    @Override
    public synchronized int ack(String userId, List<Long> pushIds, long deliveredAt) {
        int count = 0;
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE push_notifications SET delivered_at = ? WHERE user_id = ? AND push_id = ? AND delivered_at IS NULL"
             )) {
            for (Long pushId : pushIds) {
                statement.setLong(1, deliveredAt);
                statement.setString(2, userId);
                statement.setLong(3, pushId);
                count += statement.executeUpdate();
            }
            return count;
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to ack push notifications", error);
        }
    }

    private PushNotificationRecord readRecord(ResultSet resultSet) throws SQLException {
        return new PushNotificationRecord(
                resultSet.getLong("push_id"),
                resultSet.getString("user_id"),
                resultSet.getString("sender_id"),
                resultSet.getString("conversation_id"),
                resultSet.getString("message_id"),
                resultSet.getString("message_type"),
                resultSet.getString("preview"),
                resultSet.getLong("server_seq"),
                resultSet.getLong("server_time"),
                resultSet.getLong("created_at")
        );
    }

    private void initialize() {
        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS push_notifications (
                      push_id INTEGER PRIMARY KEY AUTOINCREMENT,
                      user_id TEXT NOT NULL,
                      sender_id TEXT NOT NULL,
                      conversation_id TEXT NOT NULL,
                      message_id TEXT NOT NULL,
                      message_type TEXT NOT NULL,
                      preview TEXT,
                      server_seq INTEGER NOT NULL,
                      server_time INTEGER NOT NULL,
                      created_at INTEGER NOT NULL,
                      delivered_at INTEGER,
                      UNIQUE(user_id, message_id)
                    )
                    """
            );
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_push_user_pending ON push_notifications(user_id, delivered_at, push_id)"
            );
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to initialize push notification database", error);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }
}
