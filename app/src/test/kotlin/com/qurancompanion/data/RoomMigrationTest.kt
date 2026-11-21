package com.qurancompanion.data

import com.qurancompanion.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Room migration 1 → 2 on a real SQLite (sqlite-jdbc): builds the v1 schema from the exported
 * `schemas/…/1.json`, inserts data, runs [AppDatabase.MIGRATION_1_2_SQL] and checks the result
 * against `2.json` (what Room validates on open), plus the ON DELETE CASCADE of flagged words.
 */
class RoomMigrationTest {
    private val schemaDir = File(System.getProperty("user.dir"), "schemas/com.qurancompanion.data.db.AppDatabase")

    /** tableName → (createSql of the table, createSqls of its indices), `${TABLE_NAME}` substituted. */
    private fun schema(version: Int): Map<String, Pair<String, List<String>>> {
        val json = File(schemaDir, "$version.json").readText()
        assertTrue(json.contains("\"version\": $version"))
        val out = LinkedHashMap<String, Pair<String, MutableList<String>>>()
        var table: String? = null
        val re = Regex("\"(tableName|createSql)\": \"((?:[^\"\\\\]|\\\\.)*)\"")
        for (m in re.findAll(json)) {
            val (key, value) = m.destructured
            if (key == "tableName") { table = value; continue }
            val t = table ?: continue
            val sql = value.replace("\${TABLE_NAME}", t).replace("\\\"", "\"")
            val cur = out[t]
            if (cur == null) out[t] = sql to mutableListOf() else cur.second += sql
        }
        return out
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
    private fun Connection.count(sql: String): Int = createStatement().use { st -> st.executeQuery(sql).use { it.next(); it.getInt(1) } }
    private fun Connection.masterSql(type: String, name: String): String? =
        prepareStatement("SELECT sql FROM sqlite_master WHERE type = ? AND name = ?").use { ps ->
            ps.setString(1, type); ps.setString(2, name)
            ps.executeQuery().use { if (it.next()) it.getString(1) else null }
        }

    /** SQLite stores the statement without "IF NOT EXISTS". */
    private fun norm(sql: String) = sql.replace("IF NOT EXISTS ", "").trim()

    @Test fun migrate1To2MatchesExportedSchemaAndKeepsData() {
        val v1 = schema(1)
        val v2 = schema(2)
        assertEquals(setOf("sessions", "bookmarks"), v1.keys)
        assertEquals(setOf("sessions", "bookmarks", "flagged_words"), v2.keys)

        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            v1.values.forEach { (t, idx) -> db.exec(t); idx.forEach { db.exec(it) } }
            db.exec("INSERT INTO sessions (startedAt, durationMs, status, startSurah, startAyah, lastSurah, lastAyah, lastWord) VALUES (1, 2, 'COMPLETED', 1, 1, 1, 7, 3)")
            db.exec("INSERT INTO sessions (startedAt, durationMs, status, startSurah, startAyah, lastSurah, lastAyah, lastWord) VALUES (5, 6, 'PAUSED', 2, 1, 2, 4, 0)")

            AppDatabase.MIGRATION_1_2_SQL.forEach { db.exec(it) }

            // Every v2 table and index exists exactly as Room expects.
            for ((name, def) in v2) {
                assertEquals(name, norm(def.first), db.masterSql("table", name)?.let(::norm))
                for (idx in def.second) {
                    val idxName = Regex("INDEX IF NOT EXISTS `([^`]+)`").find(idx)!!.groupValues[1]
                    assertEquals(idxName, norm(idx), db.masterSql("index", idxName)?.let(::norm))
                }
            }
            assertEquals(2, db.count("SELECT COUNT(*) FROM sessions"))

            // Flags are deleted with their session.
            db.exec("PRAGMA foreign_keys = ON")
            db.exec("INSERT INTO flagged_words (sessionId, surah, ayah, wordIndex, mistakeType, flaggedAt) VALUES (1, 1, 7, 3, 'SUBSTITUTION', 10)")
            db.exec("INSERT INTO flagged_words (sessionId, surah, ayah, wordIndex, mistakeType, flaggedAt) VALUES (2, 2, 4, 1, 'DELETION', 11)")
            db.exec("DELETE FROM sessions WHERE id = 1")
            assertEquals(1, db.count("SELECT COUNT(*) FROM flagged_words"))
            assertEquals(0, db.count("SELECT COUNT(*) FROM flagged_words WHERE sessionId = 1"))
        }
    }

    @Test fun migrationIsRegistered() {
        assertEquals(1, AppDatabase.MIGRATIONS.single().startVersion)
        assertEquals(2, AppDatabase.MIGRATIONS.single().endVersion)
    }
}
