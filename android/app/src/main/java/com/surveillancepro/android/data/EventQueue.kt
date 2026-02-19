package com.surveillancepro.android.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import com.google.gson.Gson

class EventQueue private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "event_queue.db", null, 1) {

    private val gson = Gson()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS queue (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                payload TEXT NOT NULL DEFAULT '{}',
                createdAt TEXT NOT NULL,
                retryCount INTEGER DEFAULT 0
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

    fun enqueue(type: String, payload: Map<String, Any>) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("type", type)
            put("payload", gson.toJson(payload))
            put("createdAt", java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US
            ).format(java.util.Date()))
            put("retryCount", 0)
        }
        db.insert("queue", null, values)
        trimOldEntries(db)
    }

    data class QueuedEvent(
        val id: Long,
        val type: String,
        val payload: Map<String, Any>,
        val createdAt: String,
        val retryCount: Int
    )

    fun peek(limit: Int = 50): List<QueuedEvent> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT id, type, payload, createdAt, retryCount FROM queue ORDER BY id ASC LIMIT ?",
            arrayOf(limit.toString())
        )
        val events = mutableListOf<QueuedEvent>()
        while (cursor.moveToNext()) {
            @Suppress("UNCHECKED_CAST")
            val payload = try {
                gson.fromJson(cursor.getString(2), Map::class.java) as Map<String, Any>
            } catch (_: Exception) { emptyMap() }

            events.add(QueuedEvent(
                id = cursor.getLong(0),
                type = cursor.getString(1),
                payload = payload,
                createdAt = cursor.getString(3),
                retryCount = cursor.getInt(4)
            ))
        }
        cursor.close()
        return events
    }

    fun remove(ids: List<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        val placeholders = ids.joinToString(",") { "?" }
        db.execSQL(
            "DELETE FROM queue WHERE id IN ($placeholders)",
            ids.map { it.toString() }.toTypedArray()
        )
    }

    fun incrementRetry(ids: List<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        val placeholders = ids.joinToString(",") { "?" }
        db.execSQL(
            "UPDATE queue SET retryCount = retryCount + 1 WHERE id IN ($placeholders)",
            ids.map { it.toString() }.toTypedArray()
        )
    }

    fun count(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM queue", null)
        cursor.moveToFirst()
        val c = cursor.getInt(0)
        cursor.close()
        return c
    }

    private fun trimOldEntries(db: SQLiteDatabase) {
        db.execSQL("DELETE FROM queue WHERE retryCount > 10")
        db.execSQL("""
            DELETE FROM queue WHERE id NOT IN (
                SELECT id FROM queue ORDER BY id DESC LIMIT 5000
            )
        """)
    }

    companion object {
        @Volatile private var instance: EventQueue? = null
        fun getInstance(context: Context): EventQueue =
            instance ?: synchronized(this) {
                instance ?: EventQueue(context).also { instance = it }
            }
    }
}
