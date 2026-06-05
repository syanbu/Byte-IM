package com.codex.imserver.friend;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class FriendStore {
    private final String jdbcUrl;

    public FriendStore(Path databasePath) {
        try {
            Path parent = databasePath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception error) {
            throw new IllegalStateException("Unable to create friend database directory", error);
        }
        this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath();
        initialize();
    }

    public synchronized void addMutualFriendship(String firstUserId, String secondUserId, long createdAt) {
        String first = trim(firstUserId);
        String second = trim(secondUserId);
        if (first.isEmpty() || second.isEmpty() || first.equals(second)) {
            return;
        }
        insertFriendship(first, second, createdAt);
        insertFriendship(second, first, createdAt);
    }

    public synchronized List<String> friendsOf(String userId) {
        String owner = trim(userId);
        if (owner.isEmpty()) {
            return List.of();
        }
        List<String> friends = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     """
                     SELECT friend_user_id
                     FROM friendships
                     WHERE owner_user_id = ?
                     ORDER BY friend_user_id ASC
                     """
             )) {
            statement.setString(1, owner);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    friends.add(resultSet.getString("friend_user_id"));
                }
            }
            return friends;
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to load friends", error);
        }
    }

    private void insertFriendship(String ownerUserId, String friendUserId, long createdAt) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     """
                     INSERT OR IGNORE INTO friendships(owner_user_id, friend_user_id, created_at)
                     VALUES(?, ?, ?)
                     """
             )) {
            statement.setString(1, ownerUserId);
            statement.setString(2, friendUserId);
            statement.setLong(3, createdAt);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to insert friendship", error);
        }
    }

    private void initialize() {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     """
                     CREATE TABLE IF NOT EXISTS friendships (
                       owner_user_id VARCHAR(11) NOT NULL,
                       friend_user_id VARCHAR(11) NOT NULL,
                       created_at BIGINT NOT NULL,
                       PRIMARY KEY(owner_user_id, friend_user_id)
                     )
                     """
             )) {
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to initialize friend database", error);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
