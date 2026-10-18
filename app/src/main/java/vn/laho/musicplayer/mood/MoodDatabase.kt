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
@Database(entities = [MoodRecord::class], version = 1, exportSchema = false)
abstract class LahoMoodDatabase : RoomDatabase() {
    abstract fun moodDao(): MoodDao

    companion object {
        @Volatile
        private var instance: LahoMoodDatabase? = null

        fun getInstance(context: Context): LahoMoodDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LahoMoodDatabase::class.java, "laho_mood.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { instance = it }
        }
    }
}

class RoomMoodStore(private val dao: MoodDao) : MoodStore {
    override suspend fun all() = dao.all()
    override suspend fun upsert(records: List<MoodRecord>) = dao.upsert(records)
    override suspend fun clear() = dao.clear()

    companion object {
        fun get(context: Context) = RoomMoodStore(LahoMoodDatabase.getInstance(context).moodDao())
    }
}
