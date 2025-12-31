// com/ebani/sinage/data/db/AppDatabase.kt
package com.ebani.sinage.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.ebani.sinage.data.model.AnalyticsEntity
import com.ebani.sinage.data.model.AssetEntity
import com.ebani.sinage.data.model.DeviceEntity
import com.ebani.sinage.data.model.PlaylistEntity
import com.ebani.sinage.data.model.PlaylistItemEntity

@Database(
    entities = [AssetEntity::class, PlaylistEntity::class, PlaylistItemEntity::class, DeviceEntity::class,  AnalyticsEntity::class   ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun assets(): AssetDao
    abstract fun playlists(): PlaylistDao
    abstract fun playlistItems(): PlaylistItemDao
    abstract fun device(): DeviceDao
    abstract fun analytics(): AnalyticsDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(ctx: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    ctx.applicationContext,
                    AppDatabase::class.java,
                    "player.db"
                ).build().also { INSTANCE = it }
            }
    }
}
