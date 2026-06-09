package com.buyansong.imserver.groupread;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SQLiteGroupReadCursorStore implements GroupReadCursorStore {
    private final String jdbcUrl;

    public SQLiteGroupReadCursorStore(Path databasePath) {
        try {
            Path parent = databasePath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception error) {
            throw new IllegalStateException("Unable to create database directory", error);
        }
        this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath().toUri();
        initialize();
    }

    @Override
    public synchronized boolean upsertIfGreater(String groupId, String readerId, long readUpToServerSeq, long readAt) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                long existing;
                try (PreparedStatement read = connection.prepareStatement(
                        "SELECT read_up_to_server_seq FROM group_read_cursors WHERE group_id = ? AND reader_id = ?")) {
                    read.setString(1, groupId);
                    read.setString(2, readerId);
                    try (ResultSet rs = read.executeQuery()) {
                        existing = rs.next() ? rs.getLong(1) : Long.MIN_VALUE;
                    }
                }
                if (existing >= readUpToServerSeq) {
                    connection.commit();
                    return false;
                }
                try (PreparedStatement write = connection.prepareStatement(
                        """
                        INSERT INTO group_read_cursors(group_id, reader_id, read_up_to_server_seq, read_at)
                        VALUES(?, ?, ?, ?)
                        ON CONFLICT(group_id, reader_id) DO UPDATE SET
                          read_up_to_server_seq = excluded.read_up_to_server_seq,
                          read_at = excluded.read_at
                        """)) {
                    write.setString(1, groupId);
                    write.setString(2, readerId);
                    write.setLong(3, readUpToServerSeq);
                    write.setLong(4, readAt);
                    write.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to upsert group read cursor", error);
        }
    }

    @Override
    public synchronized List<GroupReadCursor> findByMemberOf(List<String> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new HashSet<>(groupIds);
        StringBuilder sql = new StringBuilder(
                "SELECT group_id, reader_id, read_up_to_server_seq, read_at FROM group_read_cursors WHERE group_id IN (");
        for (int i = 0; i < unique.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
        }
        sql.append(")");
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int i = 1;
            for (String groupId : unique) {
                statement.setString(i++, groupId);
            }
            try (ResultSet rs = statement.executeQuery()) {
                List<GroupReadCursor> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new GroupReadCursor(
                            rs.getString("group_id"),
                            rs.getString("reader_id"),
                            rs.getLong("read_up_to_server_seq"),
                            rs.getLong("read_at")
                    ));
                }
                return out;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to read group read cursors", error);
        }
    }

    private void initialize() {
        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS group_read_cursors (
                      group_id              TEXT    NOT NULL,
                      reader_id             TEXT    NOT NULL,
                      read_up_to_server_seq INTEGER NOT NULL,
                      read_at               INTEGER NOT NULL,
                      PRIMARY KEY(group_id, reader_id)
                    )
                    """
            );
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_group_read_cursors_group ON group_read_cursors(group_id)"
            );
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to initialize group read cursor store", error);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }
}
