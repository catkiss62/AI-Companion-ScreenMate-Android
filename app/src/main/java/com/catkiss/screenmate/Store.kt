package com.catkiss.screenmate

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

 data class Session(val id: Long, val title: String, val mode: String, val state: String, val summary: String, val cursor: Long, val created: Long)
 data class Entry(val id: Long, val kind: String, val body: String, val time: Long)

class Store(context: Context, name: String = "screenmate.db") : SQLiteOpenHelper(context, name, null, 1) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,mode TEXT NOT NULL,state TEXT NOT NULL,summary TEXT NOT NULL DEFAULT '',cursor INTEGER NOT NULL DEFAULT 0,created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY AUTOINCREMENT,session INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,kind TEXT NOT NULL,body TEXT NOT NULL,time INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX entry_session ON entries(session,id)")
        db.execSQL("CREATE TABLE usage(day TEXT NOT NULL,lane TEXT NOT NULL,count INTEGER NOT NULL,PRIMARY KEY(day,lane))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized fun create(title: String, mode: String): Long = writableDatabase.insertOrThrow("sessions", null, ContentValues().apply {
        put("title", title.take(120)); put("mode", mode); put("state", "active"); put("created", System.currentTimeMillis())
    })
    fun sessions(): List<Session> = readableDatabase.rawQuery("SELECT id,title,mode,state,summary,cursor,created FROM sessions ORDER BY id DESC", null).use { c ->
        buildList { while(c.moveToNext()) add(Session(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getLong(5),c.getLong(6))) }
    }
    fun session(id: Long) = sessions().firstOrNull { it.id == id }
    @Synchronized fun add(id: Long, kind: String, body: String, capturedAt: Long = System.currentTimeMillis()): Long {
        if (session(id) == null) return -1
        return writableDatabase.insertOrThrow("entries", null, ContentValues().apply {
            put("session", id); put("kind", kind); put("body", body.take(16000)); put("time", capturedAt)
        })
    }
    fun entries(id: Long, after: Long = 0, limit: Int = 50): List<Entry> = readableDatabase.rawQuery(
        "SELECT id,kind,body,time FROM entries WHERE session=? AND id>? ORDER BY id LIMIT ?", arrayOf("$id","$after","$limit")).use { c ->
        buildList { while(c.moveToNext()) add(Entry(c.getLong(0),c.getString(1),c.getString(2),c.getLong(3))) }
    }
    fun recent(id: Long, limit: Int = 24): List<Entry> = readableDatabase.rawQuery(
        "SELECT id,kind,body,time FROM entries WHERE session=? ORDER BY id DESC LIMIT ?", arrayOf("$id","$limit")).use { c ->
        buildList { while(c.moveToNext()) add(Entry(c.getLong(0),c.getString(1),c.getString(2),c.getLong(3))) }.reversed()
    }
    fun lastChat(id: Long): Entry? = readableDatabase.rawQuery(
        "SELECT id,kind,body,time FROM entries WHERE session=? AND kind IN ('user','assistant') ORDER BY id DESC LIMIT 1",arrayOf("$id")).use { c ->
        if(c.moveToFirst()) Entry(c.getLong(0),c.getString(1),c.getString(2),c.getLong(3)) else null
    }
    @Synchronized fun finish(id: Long) { writableDatabase.execSQL("UPDATE sessions SET state='pending' WHERE id=? AND state='active'", arrayOf(id)) }
    @Synchronized fun recover(): List<Long> {
        writableDatabase.execSQL("UPDATE sessions SET state='pending' WHERE state='active'")
        return sessions().filter { it.state == "pending" }.map { it.id }
    }
    @Synchronized fun summary(id: Long, text: String, cursor: Long) {
        writableDatabase.execSQL("UPDATE sessions SET summary=?,cursor=? WHERE id=? AND cursor<=?", arrayOf(text.take(12000),cursor,id,cursor))
    }
    @Synchronized fun markArchivedIfCaughtUp(id: Long): Boolean {
        val s = session(id) ?: return false
        if (s.state == "pending" && entries(id,s.cursor,1).isEmpty()) {
            writableDatabase.execSQL("UPDATE sessions SET state='archived' WHERE id=?", arrayOf(id)); return true
        }
        return false
    }
    @Synchronized fun delete(id: Long) { writableDatabase.delete("sessions","id=?",arrayOf("$id")) }
    fun memories(except: Long): String = sessions().asSequence().filter { it.id != except && it.summary.isNotBlank() }
        .take(5).joinToString("\n") { "${it.title}（过去会话）：${it.summary.take(1200)}" }.take(6000)
    private fun day(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/Los_Angeles") }.format(Date())
    @Synchronized fun usage(lane: String): Int = readableDatabase.rawQuery("SELECT count FROM usage WHERE day=? AND lane=?",arrayOf(day(),lane)).use { if(it.moveToFirst()) it.getInt(0) else 0 }
    @Synchronized fun count(lane: String) { val date=day(); writableDatabase.execSQL("INSERT OR IGNORE INTO usage(day,lane,count) VALUES(?,?,0)", arrayOf(date,lane)); writableDatabase.execSQL("UPDATE usage SET count=count+1 WHERE day=? AND lane=?", arrayOf(date,lane)) }
}
