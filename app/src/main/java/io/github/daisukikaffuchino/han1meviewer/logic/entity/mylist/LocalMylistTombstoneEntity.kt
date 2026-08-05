package io.github.daisukikaffuchino.han1meviewer.logic.entity.mylist

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 本地删除墓碑（视频 / 清单维度）。
 *
 * 未登录时删除的条目记录在这里，登录后由同步引擎先推送到云端（删除），
 * 再拉取合并，避免被云端数据「复活」。支持三类，同一行内标志合并
 * （[upsertTombstoneMerged] 按 OR 合并，不同类型并存时互不覆盖）：
 * - [isFav]：收藏删除（[videoCode] 为视频 code）
 * - [isWatchLater]：稍后观看删除（[videoCode] 为视频 code）
 * - [isPlaylist]：播放清单删除（[videoCode] 为清单 code）
 *
 * 清单内视频删除（同一视频可能从多个清单删除，需按「视频 + 清单」
 * 逐条记录）走独立表 [LocalPlaylistItemTombstoneEntity]（复合主键）。
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
    val deletedTime: Long,
)
