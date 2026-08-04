package io.github.daisukikaffuchino.han1meviewer.logic.entity.mylist

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 本地删除墓碑。
 *
 * 未登录时删除的条目记录在这里，登录后由同步引擎先推送到云端（删除），
 * 再拉取合并，避免被云端数据「复活」。支持四类：
 * - [isFav]：收藏删除（[videoCode] 为视频 code）
 * - [isWatchLater]：稍后观看删除（[videoCode] 为视频 code）
 * - [isPlaylist]：播放清单删除（[videoCode] 为清单 code）
 * - [isPlaylistItem]：清单内视频删除（[videoCode] 为视频 code，[playlistCode] 为清单 code）
 *
 * @project Han1meViewer
 */
@Serializable
@Entity(tableName = "LocalMylistTombstoneEntity")
data class LocalMylistTombstoneEntity(
    @PrimaryKey val videoCode: String,
    val isFav: Boolean = false,
    val isWatchLater: Boolean = false,
    val isPlaylist: Boolean = false,
    val isPlaylistItem: Boolean = false,
    val playlistCode: String? = null,
    val deletedTime: Long,
)
