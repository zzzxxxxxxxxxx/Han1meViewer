package io.github.daisukikaffuchino.han1meviewer.logic.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.github.daisukikaffuchino.han1meviewer.logic.entity.mylist.LocalMylistTombstoneEntity
import io.github.daisukikaffuchino.han1meviewer.logic.entity.mylist.LocalPlaylistItemTombstoneEntity
import io.github.daisukikaffuchino.han1meviewer.logic.entity.mylist.LocalPlaylistEntity
import io.github.daisukikaffuchino.han1meviewer.logic.entity.mylist.LocalPlaylistItemEntity
import io.github.daisukikaffuchino.han1meviewer.logic.entity.mylist.LocalVideoEntity
import kotlinx.coroutines.flow.Flow

/**
 * 本地收藏 / 稍后观看 / 播放清单的 DAO。
 *
 * @project Han1meViewer
 */
@Dao
interface LocalMylistDao {

    //<editor-fold desc="LocalVideoEntity（收藏 / 稍后观看）">

    @Query("SELECT * FROM LocalVideoEntity WHERE isFav = 1 ORDER BY favTime DESC")
    fun observeFavorites(): Flow<List<LocalVideoEntity>>

    @Query("SELECT * FROM LocalVideoEntity WHERE isWatchLater = 1 ORDER BY watchLaterTime DESC")
    fun observeWatchLater(): Flow<List<LocalVideoEntity>>

    @Query("SELECT * FROM LocalVideoEntity WHERE videoCode = :videoCode LIMIT 1")
    suspend fun findBy(videoCode: String): LocalVideoEntity?

    @Query("SELECT * FROM LocalVideoEntity")
    suspend fun getAll(): List<LocalVideoEntity>

    @Query("SELECT videoCode FROM LocalVideoEntity WHERE isFav = 1")
    suspend fun getFavCodes(): List<String>

    @Query("SELECT videoCode FROM LocalVideoEntity WHERE isWatchLater = 1")
    suspend fun getWatchLaterCodes(): List<String>

    @Query("SELECT * FROM LocalVideoEntity WHERE isFav = 1 AND favSynced = 0")
    suspend fun getDirtyFavorites(): List<LocalVideoEntity>

    @Query("SELECT * FROM LocalVideoEntity WHERE isWatchLater = 1 AND watchLaterSynced = 0")
    suspend fun getDirtyWatchLater(): List<LocalVideoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVideo(entity: LocalVideoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVideos(entities: List<LocalVideoEntity>)

    @Query("DELETE FROM LocalVideoEntity WHERE videoCode = :videoCode")
    suspend fun deleteVideo(videoCode: String)

    @Query("DELETE FROM LocalVideoEntity")
    suspend fun deleteAllVideos()

    //</editor-fold>

    //<editor-fold desc="LocalPlaylistEntity（播放清单）">

    @Query("SELECT * FROM LocalPlaylistEntity ORDER BY createdTime DESC, code ASC")
    fun observePlaylists(): Flow<List<LocalPlaylistEntity>>

    @Query("SELECT * FROM LocalPlaylistEntity ORDER BY createdTime DESC, code ASC")
    suspend fun getAllPlaylists(): List<LocalPlaylistEntity>

    @Query("SELECT * FROM LocalPlaylistEntity WHERE code = :code LIMIT 1")
    suspend fun findPlaylist(code: String): LocalPlaylistEntity?

    @Query("SELECT * FROM LocalPlaylistEntity WHERE synced = 0")
    suspend fun getDirtyPlaylists(): List<LocalPlaylistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(entity: LocalPlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylists(entities: List<LocalPlaylistEntity>)

    @Query("DELETE FROM LocalPlaylistEntity WHERE code = :code")
    suspend fun deletePlaylist(code: String)

    @Query("DELETE FROM LocalPlaylistEntity")
    suspend fun deleteAllPlaylists()

    //</editor-fold>

    //<editor-fold desc="LocalPlaylistItemEntity（清单内视频）">

    @Query("SELECT * FROM LocalPlaylistItemEntity WHERE playlistCode = :playlistCode ORDER BY position ASC")
    fun observePlaylistItems(playlistCode: String): Flow<List<LocalPlaylistItemEntity>>

    @Query("SELECT * FROM LocalPlaylistItemEntity")
    fun observeAllPlaylistItems(): Flow<List<LocalPlaylistItemEntity>>

    @Query("SELECT * FROM LocalPlaylistItemEntity WHERE playlistCode = :playlistCode ORDER BY position ASC")
    suspend fun getPlaylistItems(playlistCode: String): List<LocalPlaylistItemEntity>

    @Query("SELECT * FROM LocalPlaylistItemEntity WHERE playlistCode = :playlistCode AND videoCode IN (:videoCodes)")
    suspend fun getPlaylistItemCodes(playlistCode: String, videoCodes: List<String>): List<LocalPlaylistItemEntity>

    @Query("SELECT * FROM LocalPlaylistItemEntity WHERE synced = 0")
    suspend fun getDirtyPlaylistItems(): List<LocalPlaylistItemEntity>

    @Query("SELECT * FROM LocalPlaylistItemEntity")
    suspend fun getAllPlaylistItems(): List<LocalPlaylistItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylistItem(entity: LocalPlaylistItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylistItems(entities: List<LocalPlaylistItemEntity>)

    @Query("DELETE FROM LocalPlaylistItemEntity WHERE playlistCode = :playlistCode AND videoCode = :videoCode")
    suspend fun deletePlaylistItem(playlistCode: String, videoCode: String)

    @Query("DELETE FROM LocalPlaylistItemEntity WHERE playlistCode = :playlistCode")
    suspend fun deleteAllPlaylistItems(playlistCode: String)

    @Query("DELETE FROM LocalPlaylistItemEntity")
    suspend fun deleteAllPlaylistItems()

    //</editor-fold>

    //<editor-fold desc="LocalMylistTombstoneEntity（删除墓碑）">

    @Query("SELECT * FROM LocalMylistTombstoneEntity ORDER BY deletedTime ASC")
    suspend fun getTombstones(): List<LocalMylistTombstoneEntity>

    @Query("SELECT * FROM LocalMylistTombstoneEntity WHERE videoCode = :videoCode LIMIT 1")
    suspend fun findTombstone(videoCode: String): LocalMylistTombstoneEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTombstone(entity: LocalMylistTombstoneEntity)

    @Query("DELETE FROM LocalMylistTombstoneEntity")
    suspend fun deleteAllTombstones()

    @Query("DELETE FROM LocalMylistTombstoneEntity WHERE videoCode = :videoCode")
    suspend fun deleteTombstone(videoCode: String)

    //</editor-fold>

    //<editor-fold desc="LocalPlaylistItemTombstoneEntity（清单内视频删除墓碑）">

    @Query("SELECT * FROM LocalPlaylistItemTombstoneEntity ORDER BY deletedTime ASC")
    suspend fun getPlaylistItemTombstones(): List<LocalPlaylistItemTombstoneEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylistItemTombstone(entity: LocalPlaylistItemTombstoneEntity)

    @Query("DELETE FROM LocalPlaylistItemTombstoneEntity WHERE videoCode = :videoCode AND playlistCode = :playlistCode")
    suspend fun deletePlaylistItemTombstone(videoCode: String, playlistCode: String)

    @Query("DELETE FROM LocalPlaylistItemTombstoneEntity")
    suspend fun deleteAllPlaylistItemTombstones()

    //</editor-fold>

    /**
     * 备份导入：清空并写入全部本地收藏数据，整体包在单个事务中，
     * 避免中途失败留下半清空状态。
     *
     * @param videos 已按 pushToCloud 处理（标记未同步）的收藏 / 稍后观看数据
     * @param playlists 已按 pushToCloud 处理的播放清单
     * @param playlistItems 已按 pushToCloud 处理的清单内视频
     * @param tombstones 视频 / 清单维度删除墓碑
     * @param playlistItemTombstones 清单内视频删除墓碑
     */
    @Transaction
    suspend fun importLocalMylist(
        videos: List<LocalVideoEntity>,
        playlists: List<LocalPlaylistEntity>,
        playlistItems: List<LocalPlaylistItemEntity>,
        tombstones: List<LocalMylistTombstoneEntity>,
        playlistItemTombstones: List<LocalPlaylistItemTombstoneEntity>,
    ) {
        deleteAllVideos()
        deleteAllPlaylists()
        deleteAllPlaylistItems()
        deleteAllTombstones()
        deleteAllPlaylistItemTombstones()
        if (videos.isNotEmpty()) upsertVideos(videos)
        if (playlists.isNotEmpty()) upsertPlaylists(playlists)
        if (playlistItems.isNotEmpty()) upsertPlaylistItems(playlistItems)
        tombstones.forEach { upsertTombstone(it) }
        playlistItemTombstones.forEach { upsertPlaylistItemTombstone(it) }
    }

    /** 清空全部本地收藏数据（单事务，中途失败不留半清空状态）。 */
    @Transaction
    suspend fun clearAllLocalMylist() {
        deleteAllVideos()
        deleteAllPlaylists()
        deleteAllPlaylistItems()
        deleteAllTombstones()
        deleteAllPlaylistItemTombstones()
    }
}
