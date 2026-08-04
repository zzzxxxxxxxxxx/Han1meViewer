package io.github.daisukikaffuchino.han1meviewer.logic.entity.mylist

import androidx.room.Entity
import kotlinx.serialization.Serializable

/**
 * 清单内视频删除墓碑（videoCode + playlistCode 复合主键）。
 *
 * 同一视频可能同时存在于多个播放清单中，离线删除时必须按
 * 「视频 + 清单」逐条记录，登录后由同步引擎逐条推送到云端删除，
 * 避免只推送其中一个清单、其他清单中的该视频被拉取「复活」。
 *
 * @project Han1meViewer
 */
@Serializable
@Entity(
    tableName = "LocalPlaylistItemTombstoneEntity",
    primaryKeys = ["videoCode", "playlistCode"],
)
data class LocalPlaylistItemTombstoneEntity(
    val videoCode: String,
    val playlistCode: String,
    val deletedTime: Long,
)
