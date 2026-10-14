package com.gng.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * On-device cache of decrypted messages, reactions and small lists, so chats open instantly
 * and work offline. Lives in app-private storage (encrypted by Android's file-based encryption).
 */
class ChatCache(context: Context) : SQLiteOpenHelper(context, "gng_cache.db", null, 1) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """create table messages (
                id integer primary key, client_id text unique, group_id text not null, user_id text,
                kind text not null, created_at integer not null, payload text not null, ref_id text,
                state integer not null default 1)""",
        )
        db.execSQL("create index messages_group on messages (group_id, id)")
        db.execSQL(
            """create table reactions (message_id integer not null, user_id text not null, group_id text not null,
                emoji text not null, primary key (message_id, user_id))""",
        )
        db.execSQL("create index reactions_group on reactions (group_id)")
        db.execSQL("create table kv (k text primary key, v text not null)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        listOf("messages", "reactions", "kv").forEach { db.execSQL("drop table if exists $it") }
        onCreate(db)
    }

    // --- messages ---------------------------------------------------------------

    suspend fun latest(groupId: String, limit: Int = 120): List<ChatMessage> = io {
        query("select * from messages where group_id = ? and id > 0 order by id desc limit $limit", groupId)
            .reversed() + query("select * from messages where group_id = ? and id < 0 order by created_at", groupId)
    }

    suspend fun before(groupId: String, beforeId: Long, limit: Int = 60): List<ChatMessage> = io {
        query("select * from messages where group_id = ? and id > 0 and id < ? order by id desc limit $limit",
            groupId, beforeId.toString()).reversed()
    }

    suspend fun get(id: Long): ChatMessage? = io { query("select * from messages where id = ?", id.toString()).firstOrNull() }

    suspend fun maxId(groupId: String): Long = io {
        readableDatabase.rawQuery("select max(id) from messages where group_id = ? and id > 0", arrayOf(groupId)).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
        }
    }

    suspend fun lockedIds(groupId: String): List<Long> = io {
        readableDatabase.rawQuery(
            "select id from messages where group_id = ? and payload like '{\"t\":\"locked\"%'", arrayOf(groupId),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
    }

    suspend fun put(messages: List<ChatMessage>) = io {
        if (messages.isEmpty()) return@io
        writableDatabase.transaction {
            messages.forEach { m ->
                // A confirmed message replaces its optimistic local copy.
                if (m.id > 0 && m.clientId != null) delete("messages", "client_id = ? and id < 0", arrayOf(m.clientId))
                insertWithOnConflict("messages", null, m.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
        messages.map { it.groupId }.distinct().forEach(::prune)
    }

    suspend fun setState(id: Long, state: SendState) = io {
        writableDatabase.update("messages", ContentValues().apply { put("state", state.ordinal) }, "id = ?", arrayOf(id.toString()))
    }

    suspend fun remove(id: Long) = io { writableDatabase.delete("messages", "id = ?", arrayOf(id.toString())) }

    suspend fun clearGroup(groupId: String) = io {
        writableDatabase.delete("messages", "group_id = ?", arrayOf(groupId))
        writableDatabase.delete("reactions", "group_id = ?", arrayOf(groupId))
    }

    private fun prune(groupId: String) {
        writableDatabase.execSQL(
            "delete from messages where group_id = ? and id > 0 and id not in " +
                "(select id from messages where group_id = ? and id > 0 order by id desc limit $KEEP_PER_GROUP)",
            arrayOf(groupId, groupId),
        )
    }

    // --- reactions -------------------------------------------------------------

    suspend fun reactions(groupId: String): Map<Long, Map<String, String>> = io {
        readableDatabase.rawQuery("select message_id, user_id, emoji from reactions where group_id = ?", arrayOf(groupId)).use { c ->
            val out = mutableMapOf<Long, MutableMap<String, String>>()
            while (c.moveToNext()) out.getOrPut(c.getLong(0)) { mutableMapOf() }[c.getString(1)] = c.getString(2)
            out
        }
    }

    suspend fun putReaction(groupId: String, messageId: Long, userId: String, emoji: String?) = io {
        if (emoji == null) {
            writableDatabase.delete("reactions", "message_id = ? and user_id = ?", arrayOf(messageId.toString(), userId))
        } else {
            writableDatabase.insertWithOnConflict("reactions", null, ContentValues().apply {
                put("message_id", messageId)
                put("user_id", userId)
                put("group_id", groupId)
                put("emoji", emoji)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    // --- small JSON blobs (conversation list, members) ----------------------------

    suspend fun getRaw(key: String): String? = io {
        readableDatabase.rawQuery("select v from kv where k = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
    }

    suspend fun putRaw(key: String, value: String) = io {
        writableDatabase.insertWithOnConflict("kv", null, ContentValues().apply {
            put("k", key)
            put("v", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun wipe() = io { writableDatabase.transaction { listOf("messages", "reactions", "kv").forEach { delete(it, null, null) } } }

    // --- plumbing -------------------------------------------------------------

    private fun query(sql: String, vararg args: String): List<ChatMessage> =
        readableDatabase.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(c.toMessage()) } }

    private fun Cursor.toMessage() = ChatMessage(
        id = getLong(getColumnIndexOrThrow("id")),
        clientId = getString(getColumnIndexOrThrow("client_id")),
        groupId = getString(getColumnIndexOrThrow("group_id")),
        userId = getString(getColumnIndexOrThrow("user_id")),
        kind = getString(getColumnIndexOrThrow("kind")),
        createdAt = Instant.ofEpochMilli(getLong(getColumnIndexOrThrow("created_at"))),
        payload = runCatching { json.decodeFromString<Payload>(getString(getColumnIndexOrThrow("payload"))) }
            .getOrDefault(Payload("locked")),
        refId = getString(getColumnIndexOrThrow("ref_id")),
        state = SendState.entries[getInt(getColumnIndexOrThrow("state"))],
    )

    private fun ChatMessage.toValues() = ContentValues().apply {
        put("id", id)
        put("client_id", clientId)
        put("group_id", groupId)
        put("user_id", userId)
        put("kind", kind)
        put("created_at", createdAt.toEpochMilli())
        put("payload", json.encodeToString(Payload.serializer(), payload))
        put("ref_id", refId)
        put("state", state.ordinal)
    }

    private inline fun SQLiteDatabase.transaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        const val KEEP_PER_GROUP = 1500
    }
}
