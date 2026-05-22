package com.codex.im.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ImDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE messages (
              local_id INTEGER PRIMARY KEY AUTOINCREMENT,
              message_id TEXT NOT NULL UNIQUE,
              conversation_id TEXT NOT NULL,
              sender_id TEXT NOT NULL,
              receiver_id TEXT NOT NULL,
              client_seq INTEGER NOT NULL,
              server_seq INTEGER,
              content TEXT NOT NULL,
              status TEXT NOT NULL,
              direction TEXT NOT NULL,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_messages_conversation_time ON messages(conversation_id, created_at DESC)")
        db.execSQL("CREATE INDEX idx_messages_conversation_seq ON messages(conversation_id, server_seq ASC)")
        db.execSQL(
            """
            CREATE TABLE conversations (
              conversation_id TEXT PRIMARY KEY,
              peer_id TEXT NOT NULL,
              peer_name TEXT NOT NULL,
              last_message_id TEXT,
              last_message_preview TEXT NOT NULL,
              last_message_time INTEGER NOT NULL,
              unread_count INTEGER NOT NULL DEFAULT 0,
              updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_conversations_last_time ON conversations(last_message_time DESC)")
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

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS pending_messages")
        db.execSQL("DROP TABLE IF EXISTS conversations")
        db.execSQL("DROP TABLE IF EXISTS messages")
        onCreate(db)
    }

    companion object {
        const val DATABASE_NAME = "self_hosted_im.db"
        const val DATABASE_VERSION = 1
    }
}
