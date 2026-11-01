package dev.hyeseong.lifelog.collector.core

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/** 로컬 큐(SQLite). Windows LifeLog.Core/Queue.cs와 같은 규칙: 같은 키의 upsert는 최신 하나만, 삭제는 대기 upsert를 지운다. */
class Queue(path: String) : AutoCloseable {
    private val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(path, null).apply {
        execSQL("CREATE TABLE IF NOT EXISTS ops (seq INTEGER PRIMARY KEY AUTOINCREMENT, op TEXT NOT NULL, key TEXT NOT NULL, tbl TEXT NOT NULL, payload TEXT NOT NULL, created_at TEXT NOT NULL)")
        execSQL("CREATE INDEX IF NOT EXISTS ops_key ON ops(key)")
    }

    class Op(val seq: Long, val kind: String, val table: String, val payload: String)

    @Synchronized
    fun enqueueUpsert(row: Row) {
        db.beginTransaction()
        try {
            val now = java.time.Instant.now().toString()
            db.compileStatement("UPDATE ops SET payload = ?, created_at = ? WHERE key = ? AND op = 'upsert'").use { st ->
                st.bindString(1, row.toJson()); st.bindString(2, now); st.bindString(3, row.key)
                if (st.executeUpdateDelete() == 0) {
                    db.compileStatement("INSERT INTO ops (op, key, tbl, payload, created_at) VALUES ('upsert', ?, ?, ?, ?)").use { ins ->
                        ins.bindString(1, row.key); ins.bindString(2, row.table); ins.bindString(3, row.toJson()); ins.bindString(4, now); ins.executeInsert()
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun enqueueDelete(table: String, device: String, id: String) {
        val key = "$table|$device|$id"
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM ops WHERE key = ?", arrayOf(key))
            db.execSQL("INSERT INTO ops (op, key, tbl, payload, created_at) VALUES ('delete', ?, ?, ?, ?)",
                arrayOf(key, table, JSONObject().put("device", device).put("id", id).toString(), java.time.Instant.now().toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun peek(limit: Int): List<Op> = db.rawQuery("SELECT seq, op, tbl, payload FROM ops ORDER BY seq LIMIT $limit", null).use { c ->
        val out = ArrayList<Op>()
        while (c.moveToNext()) out.add(Op(c.getLong(0), c.getString(1), c.getString(2), c.getString(3)))
        out
    }

    @Synchronized
    fun ack(seqs: List<Long>) {
        db.beginTransaction()
        try { for (s in seqs) db.execSQL("DELETE FROM ops WHERE seq = ?", arrayOf(s)); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    @Synchronized
    fun count(): Long = db.compileStatement("SELECT count(*) FROM ops").use { it.simpleQueryForLong() }

    override fun close() = db.close()
}
