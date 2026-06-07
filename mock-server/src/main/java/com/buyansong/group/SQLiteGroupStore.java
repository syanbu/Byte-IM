package com.buyansong.imserver.group;

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
import java.util.Optional;

public final class SQLiteGroupStore implements GroupStore {
    private final String jdbcUrl;

    public SQLiteGroupStore(Path databasePath) {
        try {
            Path parent = databasePath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception error) {
            throw new IllegalStateException("Unable to create group database directory", error);
        }
        this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath().toUri();
        initialize();
    }

    @Override
    public synchronized Optional<GroupService.GroupRecord> findById(String groupId) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     """
                     SELECT group_id, name, owner_id, created_at, updated_at
                     FROM groups
                     WHERE group_id = ?
                     """
             )) {
            statement.setString(1, groupId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(readGroup(connection, resultSet));
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to find group", error);
        }
    }

    @Override
    public synchronized List<GroupService.GroupRecord> findByMember(String userId) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     """
                     SELECT g.group_id, g.name, g.owner_id, g.created_at, g.updated_at
                     FROM groups g
                     JOIN group_members gm ON gm.group_id = g.group_id
                     WHERE gm.user_id = ?
                     ORDER BY g.group_id ASC
                     """
             )) {
            statement.setString(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<GroupService.GroupRecord> groups = new ArrayList<>();
                while (resultSet.next()) {
                    groups.add(readGroup(connection, resultSet));
                }
                return groups;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to list groups", error);
        }
    }

    @Override
    public synchronized void save(GroupService.GroupRecord group) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement groupStatement = connection.prepareStatement(
                    """
                    INSERT OR REPLACE INTO groups(group_id, name, owner_id, created_at, updated_at)
                    VALUES(?, ?, ?, ?, ?)
                    """
            );
                 PreparedStatement deleteMembersStatement = connection.prepareStatement(
                         "DELETE FROM group_members WHERE group_id = ?"
                 );
                 PreparedStatement memberStatement = connection.prepareStatement(
                         """
                         INSERT INTO group_members(group_id, user_id, role, joined_at, updated_at)
                         VALUES(?, ?, ?, ?, ?)
                         """
                 )) {
                groupStatement.setString(1, group.groupId());
                groupStatement.setString(2, group.name());
                groupStatement.setString(3, group.ownerId());
                groupStatement.setLong(4, group.createdAt());
                groupStatement.setLong(5, group.updatedAt());
                groupStatement.executeUpdate();

                deleteMembersStatement.setString(1, group.groupId());
                deleteMembersStatement.executeUpdate();

                for (String memberUserId : group.memberUserIds()) {
                    memberStatement.setString(1, group.groupId());
                    memberStatement.setString(2, memberUserId);
                    memberStatement.setString(3, memberUserId.equals(group.ownerId()) ? "OWNER" : "MEMBER");
                    memberStatement.setLong(4, group.createdAt());
                    memberStatement.setLong(5, group.updatedAt());
                    memberStatement.addBatch();
                }
                memberStatement.executeBatch();
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to save group", error);
        }
    }

    @Override
    public synchronized long maxGroupNumber() {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("SELECT group_id FROM groups");
             ResultSet resultSet = statement.executeQuery()) {
            long max = 1000L;
            while (resultSet.next()) {
                max = Math.max(max, groupNumber(resultSet.getString("group_id")));
            }
            return max;
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to read max group number", error);
        }
    }

    private GroupService.GroupRecord readGroup(Connection connection, ResultSet resultSet) throws SQLException {
        String groupId = resultSet.getString("group_id");
        return new GroupService.GroupRecord(
                groupId,
                resultSet.getString("name"),
                resultSet.getString("owner_id"),
                members(connection, groupId),
                resultSet.getLong("created_at"),
                resultSet.getLong("updated_at")
        );
    }

    private List<String> members(Connection connection, String groupId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT user_id
                FROM group_members
                WHERE group_id = ?
                ORDER BY joined_at ASC, rowid ASC
                """
        )) {
            statement.setString(1, groupId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> members = new ArrayList<>();
                while (resultSet.next()) {
                    members.add(resultSet.getString("user_id"));
                }
                return members;
            }
        }
    }

    private void initialize() {
        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS groups (
                      group_id TEXT PRIMARY KEY,
                      name TEXT NOT NULL,
                      owner_id TEXT NOT NULL,
                      created_at INTEGER NOT NULL,
                      updated_at INTEGER NOT NULL
                    )
                    """
            );
            statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS group_members (
                      group_id TEXT NOT NULL,
                      user_id TEXT NOT NULL,
                      role TEXT NOT NULL DEFAULT 'MEMBER',
                      joined_at INTEGER NOT NULL,
                      updated_at INTEGER NOT NULL,
                      PRIMARY KEY(group_id, user_id)
                    )
                    """
            );
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_group_members_user ON group_members(user_id)");
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to initialize group database", error);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private static long groupNumber(String groupId) {
        if (groupId == null || !groupId.startsWith("g_")) {
            return 0L;
        }
        try {
            return Long.parseLong(groupId.substring(2));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
