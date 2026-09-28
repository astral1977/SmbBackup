package de.astral.smbbackup.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.getLongOrNull

enum class RunStatus(val label: String) {
    RUNNING("Läuft"),
    SUCCESS("Erfolgreich"),
    WARNING("Teilweise fehlgeschlagen"),
    FAILED("Fehlgeschlagen"),
    SKIPPED("Übersprungen"),
    CANCELLED("Abgebrochen"),
}

enum class Trigger(val label: String) { SCHEDULED("Geplant"), MANUAL("Manuell") }

data class RunRecord(
    val id: Long,
    val startedAt: Long,
    val finishedAt: Long?,
    val trigger: Trigger,
    val status: RunStatus,
    val checked: Int,
    val uploaded: Int,
    val skipped: Int,
    val failed: Int,
    val bytes: Long,
    val message: String,
    val details: String,
)

/** Protokoll aller Sicherungsläufe (SQLite). */
class RunLog(context: Context) : SQLiteOpenHelper(context, "runlog.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE runs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                finished_at INTEGER,
                trigger TEXT NOT NULL,
                status TEXT NOT NULL,
                checked INTEGER NOT NULL DEFAULT 0,
                uploaded INTEGER NOT NULL DEFAULT 0,
                skipped INTEGER NOT NULL DEFAULT 0,
                failed INTEGER NOT NULL DEFAULT 0,
                bytes INTEGER NOT NULL DEFAULT 0,
                message TEXT NOT NULL DEFAULT '',
                details TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX runs_started ON runs(started_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun start(trigger: Trigger): Long = writableDatabase.insert(
        "runs",
        null,
        ContentValues().apply {
            put("started_at", System.currentTimeMillis())
            put("trigger", trigger.name)
            put("status", RunStatus.RUNNING.name)
        },
    )

    fun finish(
        id: Long,
        status: RunStatus,
        message: String,
        checked: Int = 0,
        uploaded: Int = 0,
        skipped: Int = 0,
        failed: Int = 0,
        bytes: Long = 0,
        details: String = "",
    ) {
        writableDatabase.update(
            "runs",
            ContentValues().apply {
                put("finished_at", System.currentTimeMillis())
                put("status", status.name)
                put("message", message)
                put("checked", checked)
                put("uploaded", uploaded)
                put("skipped", skipped)
                put("failed", failed)
                put("bytes", bytes)
                put("details", details)
            },
            "id = ?",
            arrayOf(id.toString()),
        )
        prune()
    }

    /** Läufe, die beim Beenden des Prozesses noch als "läuft" markiert waren. */
    fun markInterrupted() {
        writableDatabase.update(
            "runs",
            ContentValues().apply {
                put("status", RunStatus.CANCELLED.name)
                put("message", "Unterbrochen (App wurde vom System beendet)")
                put("finished_at", System.currentTimeMillis())
            },
            "status = ?",
            arrayOf(RunStatus.RUNNING.name),
        )
    }

    fun recent(limit: Int = 200): List<RunRecord> =
        readableDatabase.query("runs", null, null, null, null, null, "started_at DESC", limit.toString()).use { c ->
            buildList { while (c.moveToNext()) add(c.toRecord()) }
        }

    fun lastSuccessAt(): Long? = readableDatabase.rawQuery(
        "SELECT MAX(finished_at) FROM runs WHERE status IN (?, ?)",
        arrayOf(RunStatus.SUCCESS.name, RunStatus.WARNING.name),
    ).use { c -> if (c.moveToFirst()) c.getLongOrNull(0) else null }

    fun oldestRunAt(): Long? = readableDatabase.rawQuery("SELECT MIN(started_at) FROM runs", null)
        .use { c -> if (c.moveToFirst()) c.getLongOrNull(0) else null }

    fun clear() {
        writableDatabase.delete("runs", "status != ?", arrayOf(RunStatus.RUNNING.name))
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - 180L * 24 * 60 * 60 * 1000
        writableDatabase.delete("runs", "started_at < ?", arrayOf(cutoff.toString()))
        writableDatabase.execSQL("DELETE FROM runs WHERE id NOT IN (SELECT id FROM runs ORDER BY started_at DESC LIMIT 1000)")
    }

    private fun Cursor.toRecord() = RunRecord(
        id = getLong(getColumnIndexOrThrow("id")),
        startedAt = getLong(getColumnIndexOrThrow("started_at")),
        finishedAt = getLongOrNull(getColumnIndexOrThrow("finished_at")),
        trigger = runCatching { Trigger.valueOf(getString(getColumnIndexOrThrow("trigger"))) }.getOrDefault(Trigger.MANUAL),
        status = runCatching { RunStatus.valueOf(getString(getColumnIndexOrThrow("status"))) }.getOrDefault(RunStatus.FAILED),
        checked = getInt(getColumnIndexOrThrow("checked")),
        uploaded = getInt(getColumnIndexOrThrow("uploaded")),
        skipped = getInt(getColumnIndexOrThrow("skipped")),
        failed = getInt(getColumnIndexOrThrow("failed")),
        bytes = getLong(getColumnIndexOrThrow("bytes")),
        message = getString(getColumnIndexOrThrow("message")),
        details = getString(getColumnIndexOrThrow("details")),
    )
}
