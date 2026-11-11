package org.kirundi.voicestudio.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProjectEntity::class,
        ParticipantEntity::class,
        SessionEntity::class,
        RecordingEntity::class,
        MeasureEntity::class,
        ExportEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun dao(): AppDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                val again = instance
                if (again != null) {
                    again
                } else {
                    val created = Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "kirundi_voice_studio.db"
                    ).build()
                    instance = created
                    created
                }
            }
        }
    }
}
