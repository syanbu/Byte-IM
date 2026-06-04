package com.codex.im.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ImDatabaseHelper(
    context: Context,
    databaseName: String = DATABASE_NAME
) : SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        createMessagesTable(db)
        createConversationsTable(db)
        createPendingMessagesTable(db)
        createUserProfilesTable(db)
        createGroupsTable(db)
        createGroupMembersTable(db)
    }

    private fun createMessagesTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE messages (
              local_id INTEGER PRIMARY KEY AUTOINCREMENT,
              message_id TEXT NOT NULL UNIQUE,
              conversation_id TEXT NOT NULL,
              conversation_type TEXT NOT NULL DEFAULT 'SINGLE',
              group_id TEXT,
              sender_id TEXT NOT NULL,
              receiver_id TEXT NOT NULL,
              client_seq INTEGER NOT NULL,
              server_seq INTEGER,
              content TEXT NOT NULL,
              mentions_json TEXT,
              message_type TEXT NOT NULL,
              image_url TEXT,
              thumbnail_url TEXT,
              image_width INTEGER,
              image_height INTEGER,
              mime_type TEXT,
              file_size_bytes INTEGER,
              local_original_path TEXT,
              local_thumbnail_path TEXT,
              is_recalled INTEGER NOT NULL DEFAULT 0,
              recalled_at INTEGER,
              recalled_by TEXT,
              status TEXT NOT NULL,
              direction TEXT NOT NULL,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_messages_conversation_time ON messages(conversation_id, created_at DESC)")
        db.execSQL("CREATE INDEX idx_messages_conversation_seq ON messages(conversation_id, server_seq ASC)")
    }

    private fun createConversationsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE conversations (
              conversation_id TEXT PRIMARY KEY,
              peer_id TEXT NOT NULL,
              peer_name TEXT NOT NULL,
              conversation_type TEXT NOT NULL DEFAULT 'SINGLE',
              title TEXT,
              avatar_url TEXT,
              last_message_id TEXT,
              last_message_preview TEXT NOT NULL,
              last_message_time INTEGER NOT NULL,
              unread_count INTEGER NOT NULL DEFAULT 0,
              mention_unread_count INTEGER NOT NULL DEFAULT 0,
              updated_at INTEGER NOT NULL,
              peer_read_up_to_server_seq INTEGER,
              peer_read_at INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_conversations_last_time ON conversations(last_message_time DESC)")
    }

    private fun createPendingMessagesTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE pending_messages (
              message_id TEXT PRIMARY KEY,
              packet_cmd INTEGER NOT NULL,
              packet_body TEXT NOT NULL,
              retry_count INTEGER NOT NULL DEFAULT 0,
              next_retry_at INTEGER NOT NULL,
              created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_pending_next_retry ON pending_messages(next_retry_at ASC)")
    }

    private fun createUserProfilesTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS user_profiles (
              user_id TEXT PRIMARY KEY,
              phone TEXT NOT NULL,
              nickname TEXT NOT NULL,
              avatar_url TEXT,
              avatar_updated_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL,
              gender TEXT,
              signature TEXT
            )
            """.trimIndent()
        )
    }

    private fun createGroupsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS groups (
              group_id TEXT PRIMARY KEY,
              name TEXT NOT NULL,
              avatar_url TEXT,
              owner_id TEXT NOT NULL,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun createGroupMembersTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS group_members (
              group_id TEXT NOT NULL,
              user_id TEXT NOT NULL,
              display_name TEXT NOT NULL,
              avatar_url TEXT,
              role TEXT NOT NULL DEFAULT 'MEMBER',
              joined_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL,
              PRIMARY KEY(group_id, user_id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_group_members_user ON group_members(user_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createUserProfilesTable(db)
        }
        db.execSQL("DROP TABLE IF EXISTS pending_messages")
        db.execSQL("DROP TABLE IF EXISTS user_profiles")
        db.execSQL("DROP TABLE IF EXISTS group_members")
        db.execSQL("DROP TABLE IF EXISTS groups")
        db.execSQL("DROP TABLE IF EXISTS conversations")
        db.execSQL("DROP TABLE IF EXISTS messages")
        onCreate(db)
    }

    companion object {
        const val DATABASE_NAME = "self_hosted_im.db"
        const val DATABASE_VERSION = 7
    }
}
