// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.metadata

import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.lang.reflect.Proxy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.laho.musicplayer.mood.LahoMoodDatabase

class SuggestionFillTest {
    private val found = MetadataSuggestion.found(
        "1", "t", "a",
        MetadataCandidate("MusicBrainz", 0.9, "t", "a", "Alb", null, 2015, 1, 1, "Pop", null, "r", null, null),
        0,
    )
    private val missing = MetadataSuggestion.notFound("1", "t", "a", 0)

    @Test
    fun fileTagsWin() {
        assertEquals("Rock", SuggestionFill.genre("Rock", found))
        assertEquals(1999, SuggestionFill.year(1999, found))
    }

    @Test
    fun suggestionFillsMissingOrUnknownTags() {
        assertEquals("Pop", SuggestionFill.genre(null, found))
        assertEquals("Pop", SuggestionFill.genre("<unknown>", found))
        assertEquals(2015, SuggestionFill.year(0, found))
    }

    @Test
    fun notFoundOrAbsentSuggestionFillsNothing() {
        assertNull(SuggestionFill.genre(null, missing))
        assertNull(SuggestionFill.genre(null, null))
        assertNull(SuggestionFill.year(0, missing))
    }
}

/**
 * Checks the hand-written Room migration against the exported schemas (app/schemas): the SQL
 * it runs must create exactly the table Room expects in version 2, and must not touch "moods".
 */
class MoodDatabaseMigrationTest {
    private fun schema(version: Int): JSONObject {
        val f = File("schemas/${LahoMoodDatabase::class.java.name}/$version.json")
        assertTrue("missing ${f.absolutePath}", f.exists())
        return JSONObject(f.readText(Charsets.UTF_8)).getJSONObject("database")
    }

    private fun createSql(db: JSONObject, table: String): String? {
        val entities = db.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            if (e.getString("tableName") == table) return e.getString("createSql").replace("\${TABLE_NAME}", table)
        }
        return null
    }

    private fun norm(sql: String) = sql.replace(Regex("\\s+"), " ").trim()

    @Test
    fun versionsAreConsecutive() {
        assertEquals(1, LahoMoodDatabase.MIGRATION_1_2.startVersion)
        assertEquals(2, LahoMoodDatabase.MIGRATION_1_2.endVersion)
        assertEquals(1, schema(1).getInt("version"))
        assertEquals(2, schema(2).getInt("version"))
    }

    @Test
    fun migrationCreatesTheVersion2TableAndKeepsMoods() {
        val executed = mutableListOf<String>()
        val db = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL") executed += args[0] as String
            null
        } as SupportSQLiteDatabase
        LahoMoodDatabase.MIGRATION_1_2.migrate(db)

        val v1 = schema(1)
        val v2 = schema(2)
        // "moods" is identical in both versions, so the user's mood index survives untouched.
        assertEquals(createSql(v1, "moods"), createSql(v2, "moods"))
        assertNull(createSql(v1, "metadata_suggestions"))
        val expected = norm(createSql(v2, "metadata_suggestions")!!)
        assertEquals(listOf(expected), executed.map(::norm))
        assertTrue(executed.none { it.contains("moods", ignoreCase = true) || it.contains("DROP", ignoreCase = true) })
    }
}
