package com.codex.im.storage

import android.database.sqlite.SQLiteDatabase

interface TransactionRunner {
    fun runInTransaction(block: () -> Unit)

    companion object {
        fun immediate(): TransactionRunner {
            return object : TransactionRunner {
                override fun runInTransaction(block: () -> Unit) {
                    block()
                }
            }
        }
    }
}

class AndroidTransactionRunner(
    private val database: SQLiteDatabase
) : TransactionRunner {
    override fun runInTransaction(block: () -> Unit) {
        database.beginTransaction()
        try {
            block()
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }
}
