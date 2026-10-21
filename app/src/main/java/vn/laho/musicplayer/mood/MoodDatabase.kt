// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.mood

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import vn.laho.musicplayer.metadata.MetadataDao
import vn.laho.musicplayer.metadata.MetadataSuggestion
import vn.laho.musicplayer.metadata.RoomMetadataStore

@Dao
interface MoodDao {
    @Query("SELECT * FROM moods")
    suspend fun all(): List<MoodRecord>

    @Upsert
    suspend fun upsert(records: List<MoodRecord>)

    @Query("DELETE FROM moods")
    suspend fun clear()
}

/**
 * LaHo's own database (separate from the upstream Rhythm database, so no migration of
 * upstream schema is needed).
 */
@Database(entities = [MoodRecord::class, MetadataSuggestion::class], version = 2, exportSchema = true)
abstract class LahoMoodDatabase : RoomDatabase() {
    abstract fun moodDao(): MoodDao
    abstract fun metadataDao(): MetadataDao

    companion object {
        /** v1 → v2: adds the metadata suggestions table; the mood index is kept as is. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `metadata_suggestions` (`songId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`artist` TEXT NOT NULL, `status` TEXT NOT NULL, `source` TEXT, `confidence` REAL, " +
                        "`matchedTitle` TEXT, `matchedArtist` TEXT, `album` TEXT, `albumArtist` TEXT, `year` INTEGER, " +
                        "`trackNumber` INTEGER, `discNumber` INTEGER, `genre` TEXT, `recordingMbid` TEXT, " +
                        "`releaseMbid` TEXT, `releaseGroupMbid` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`songId`))",
                )
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2)

        @Volatile
        private var instance: LahoMoodDatabase? = null

        fun getInstance(context: Context): LahoMoodDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LahoMoodDatabase::class.java, "laho_mood.db")
                .addMigrations(*MIGRATIONS)
                .build()
                .also { instance = it }
        }
    }
}

fun metadataStore(context: Context) = RoomMetadataStore(LahoMoodDatabase.getInstance(context).metadataDao())

class RoomMoodStore(private val dao: MoodDao) : MoodStore {
    override suspend fun all() = dao.all()
    override suspend fun upsert(records: List<MoodRecord>) = dao.upsert(records)
    override suspend fun clear() = dao.clear()

    companion object {
        fun get(context: Context) = RoomMoodStore(LahoMoodDatabase.getInstance(context).moodDao())
    }
}
