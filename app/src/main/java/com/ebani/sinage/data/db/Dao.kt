// com/ebani/sinage/data/db/Dao.kt
package com.ebani.sinage.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.ebani.sinage.data.model.AssetEntity
import com.ebani.sinage.data.model.DeviceEntity
import com.ebani.sinage.data.model.PlaylistEntity
import com.ebani.sinage.data.model.PlaylistItemEntity

/* ---------------- Assets ---------------- */

@Dao
interface AssetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(vararg asset: AssetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(assets: List<AssetEntity>)

    @Query("SELECT * FROM assets WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<AssetEntity>

    @Query("DELETE FROM assets WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT * FROM assets WHERE id = :id LIMIT 1")
    fun findById(id: String): AssetEntity?

    @Query("SELECT id, localPath FROM assets")
    fun allLite(): List<AssetLite>

    @Query("SELECT localPath FROM assets WHERE localPath IS NOT NULL")
    fun allLocalPaths(): List<String>

    @Query("UPDATE assets SET localPath = NULL, downloadedAt = NULL WHERE id = :id")
    fun clearLocal(id: String)



    data class AssetLite(val id: String, val localPath: String?)


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: AssetEntity)

//    @Query("""
//        UPDATE assets
//        SET localPath = :localPath, downloadedAt = :downloadedAt, etag = :etag
//        WHERE id = :id
//    """)
//    fun setDownloaded(id: String, localPath: String, downloadedAt: Long, etag: String?)

    // AssetDao.kt
    @Query("""
        UPDATE assets
        SET localPath = :localPath,
            downloadedAt = :downloadedAt
        WHERE id = :id
        """)
    suspend fun setDownloaded(
        id: String,
        localPath: String,
        downloadedAt: Long
    )


    @Query("DELETE FROM assets")
    fun deleteAll()
}

/* ---------------- Playlists + Items ---------------- */

@Dao
interface PlaylistDao {

    /* basic CRUD */

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplacePlaylist(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplaceItems(items: List<PlaylistItemEntity>)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun deleteItemsForPlaylist(playlistId: String)

    /* queries used by ContentRepository */

    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    suspend fun getPlaylist(playlistId: String): PlaylistEntity?

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun itemsForPlaylist(playlistId: String): List<PlaylistItemEntity>

    @Query("SELECT * FROM playlists ORDER BY name DESC")
    suspend fun allPlaylists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId AND assetId = :assetId LIMIT 1")
    suspend fun getItem(playlistId: String, assetId: String): PlaylistItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entity: PlaylistEntity)

    @Query("DELETE FROM playlists")
    fun deleteAll()

    /* convenience: replace playlist atomically (used in sync) */

    @Transaction
    suspend fun replacePlaylist(
        playlistId: String,
        name: String,
        newItems: List<PlaylistItemEntity>
    ) {
        // clear old
        deleteItemsForPlaylist(playlistId)
        deletePlaylist(playlistId)
        // insert header
        insertOrReplacePlaylist(
            PlaylistEntity(
                id = playlistId,
                name = name,
            )
        )
        // insert items (they must already carry playlistId)
        if (newItems.isNotEmpty()) {
            insertOrReplaceItems(newItems)
        }
    }
}

@Dao
interface PlaylistItemDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entity: PlaylistItemEntity)

    @Query("DELETE FROM playlist_items")
    fun deleteAll()
     @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY orderIndex ASC")
     fun itemsForPlaylist(playlistId: String): List<PlaylistItemEntity>
}

@Dao
interface DeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entity: DeviceEntity)

    @Query("SELECT * FROM device LIMIT 1")
    fun getDeviceConfig(): DeviceEntity?   // <- not List<...>

    @Query("SELECT * FROM device")
    fun getDeviceConfigs(): List<DeviceEntity?>   // <- not List<...>

    @Query("DELETE FROM device")
    fun deleteAll()
}
