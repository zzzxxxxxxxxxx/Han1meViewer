package io.github.daisukikaffuchino.han1meviewer.logic

import io.github.daisukikaffuchino.han1meviewer.EMPTY_STRING
import io.github.daisukikaffuchino.han1meviewer.logic.entity.mylist.LocalMylistTombstoneEntity
import io.github.daisukikaffuchino.han1meviewer.logic.entity.mylist.LocalPlaylistEntity
import io.github.daisukikaffuchino.han1meviewer.logic.entity.mylist.LocalPlaylistItemEntity
import io.github.daisukikaffuchino.han1meviewer.logic.entity.mylist.LocalVideoEntity
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeInfo
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeVideo
import io.github.daisukikaffuchino.han1meviewer.logic.model.MyListType
import io.github.daisukikaffuchino.han1meviewer.logic.model.Playlists
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageLoadingState
import io.github.daisukikaffuchino.han1meviewer.logic.state.WebsiteState
import io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.AppViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull

/**
 * 本地收藏 / 稍后观看 / 播放清单 与云端的同步引擎。
 *
 * 未登录时的操作全部写入本地数据库（mylist.db），
 * 登录成功后、以及打开收藏 / 稍后观看 / 播放清单界面时调用 [sync]
 * 执行双向合并（未登录时直接返回）：
 * - 推送：本地删除墓碑（云端删除）→ 本地脏数据（收藏 / 稍后观看 / 播放清单）推送到云端
 * - 拉取：云端全量列表拉取后合并到本地，云端优先（server-wins）
 *
 * @project Han1meViewer
 */
object MylistSyncManager {

    @Volatile
    private var isSyncing = false

    /**
     * 执行双向合并。登录成功后或打开列表界面时调用，
     * 未登录、未开启本地化功能或正在同步中直接返回，失败不影响调用方。
     *
     * 顺序统一为「先推后拉」：墓碑 → 本地脏数据推送 → 云端拉取合并。
     */
    suspend fun sync() {
        if (isSyncing) return
        isSyncing = true
        try {
            if (!SettingsRepository.isAlreadyLogin) return
            if (!SettingsRepository.isLocalMylistEnabled) return
            val userId = SettingsRepository.savedUserId
            if (userId.isBlank()) return
            val token = obtainCsrfToken() ?: return
            pushTombstones(userId, token)
            pushDirtyFavorites(userId, token)
            pushDirtyWatchLater(userId, token)
            pullFavorites(userId)
            pullWatchLater(userId)
            syncPlaylists(userId, token)
        } finally {
            isSyncing = false
        }
    }

    private suspend fun obtainCsrfToken(): String? {
        AppViewModel.csrfToken?.takeIf { it.isNotBlank() }?.let { return it }
        return runCatching {
            NetworkRepo.getHomePage().firstOrNull()?.let { state ->
                (state as? WebsiteState.Success)?.info?.csrfToken
            }
        }.getOrNull()
    }

    //<editor-fold desc="推送：删除墓碑">

    private suspend fun pushTombstones(userId: String, token: String?) {
        val tombstones = DatabaseRepo.LocalMylist.getTombstones()
        for (tombstone in tombstones) {
            var isFav = tombstone.isFav
            var isWatchLater = tombstone.isWatchLater
            var isPlaylist = tombstone.isPlaylist
            var isPlaylistItem = tombstone.isPlaylistItem
            if (isFav) {
                val succeeded = runCatching {
                    NetworkRepo.addToMyFavVideo(
                        videoCode = tombstone.videoCode,
                        likeStatus = true,
                        currentUserId = userId,
                        token = token,
                    ).first()
                }.getOrNull() is WebsiteState.Success
                if (succeeded) isFav = false
            }
            if (isWatchLater) {
                val succeeded = runCatching {
                    NetworkRepo.addToMyList(
                        listCode = HanimeVideo.MyList.SAVE_CODE,
                        videoCode = tombstone.videoCode,
                        isChecked = false,
                        position = 0,
                        csrfToken = token,
                    ).first()
                }.getOrNull() is WebsiteState.Success
                if (succeeded) isWatchLater = false
            }
            if (isPlaylist) {
                // 云端删除播放清单（videoCode 存的是清单 code）
                val succeeded = runCatching {
                    NetworkRepo.modifyPlaylist(
                        listCode = tombstone.videoCode,
                        title = "",
                        description = "",
                        delete = true,
                        csrfToken = token,
                    ).first()
                }.getOrNull() is WebsiteState.Success
                if (succeeded) isPlaylist = false
            }
            if (isPlaylistItem) {
                // 云端从清单中移除视频
                val succeeded = runCatching {
                    NetworkRepo.addToMyList(
                        listCode = tombstone.playlistCode.orEmpty(),
                        videoCode = tombstone.videoCode,
                        isChecked = false,
                        position = 0,
                        csrfToken = token,
                    ).first()
                }.getOrNull() is WebsiteState.Success
                if (succeeded) isPlaylistItem = false
            }
            if (!isFav && !isWatchLater && !isPlaylist && !isPlaylistItem) {
                DatabaseRepo.LocalMylist.deleteTombstone(tombstone.videoCode)
            } else if (isFav != tombstone.isFav || isWatchLater != tombstone.isWatchLater ||
                isPlaylist != tombstone.isPlaylist || isPlaylistItem != tombstone.isPlaylistItem
            ) {
                DatabaseRepo.LocalMylist.upsertTombstone(
                    tombstone.copy(
                        isFav = isFav,
                        isWatchLater = isWatchLater,
                        isPlaylist = isPlaylist,
                        isPlaylistItem = isPlaylistItem,
                    )
                )
            }
        }
    }

    //</editor-fold>

    //<editor-fold desc="拉取：收藏 / 稍后观看">

    private suspend fun pullFavorites(userId: String) {
        val remoteCodes = pullMyList(userId, MyListType.FAV_VIDEO) { hanimeInfo, local, index ->
            LocalVideoEntity(
                videoCode = hanimeInfo.videoCode,
                title = hanimeInfo.title,
                coverUrl = hanimeInfo.coverUrl,
                duration = hanimeInfo.duration,
                views = hanimeInfo.views,
                reviews = hanimeInfo.reviews,
                currentArtist = hanimeInfo.currentArtist,
                uploadTime = hanimeInfo.uploadTime,
                isFav = true,
                favSynced = true,
                favTime = System.currentTimeMillis() - index,
                isWatchLater = local?.isWatchLater ?: false,
                watchLaterSynced = local?.watchLaterSynced ?: false,
                watchLaterTime = local?.watchLaterTime ?: 0L,
            )
        }
        // 云端已取消收藏的已同步条目：同步清除本地收藏标志（仅完整拉取时）
        if (remoteCodes != null) {
            removeMissingSyncedEntries(isFav = true, remoteCodes)
        }
    }

    private suspend fun pullWatchLater(userId: String) {
        val remoteCodes = pullMyList(userId, MyListType.WATCH_LATER) { hanimeInfo, local, index ->
            LocalVideoEntity(
                videoCode = hanimeInfo.videoCode,
                title = hanimeInfo.title,
                coverUrl = hanimeInfo.coverUrl,
                duration = hanimeInfo.duration,
                views = hanimeInfo.views,
                reviews = hanimeInfo.reviews,
                currentArtist = hanimeInfo.currentArtist,
                uploadTime = hanimeInfo.uploadTime,
                isWatchLater = true,
                watchLaterSynced = true,
                watchLaterTime = System.currentTimeMillis() - index,
                isFav = local?.isFav ?: false,
                favSynced = local?.favSynced ?: false,
                favTime = local?.favTime ?: 0L,
            )
        }
        if (remoteCodes != null) {
            removeMissingSyncedEntries(isFav = false, remoteCodes)
        }
    }

    /**
     * 拉取合并后，清除本地已同步（synced=true）但云端列表已不存在的条目。
     * 本地未同步（synced=false）的脏数据不受影响。
     */
    private suspend fun removeMissingSyncedEntries(isFav: Boolean, remoteCodes: Set<String>) {
        for (entity in DatabaseRepo.LocalMylist.getAll()) {
            val present = if (isFav) entity.isFav else entity.isWatchLater
            val synced = if (isFav) entity.favSynced else entity.watchLaterSynced
            if (!present || !synced || entity.videoCode in remoteCodes) continue
            val remaining = if (isFav) {
                entity.copy(isFav = false, favSynced = false)
            } else {
                entity.copy(isWatchLater = false, watchLaterSynced = false)
            }
            if (remaining.isFav || remaining.isWatchLater) {
                DatabaseRepo.LocalMylist.upsertVideo(remaining)
            } else {
                DatabaseRepo.LocalMylist.deleteVideo(entity.videoCode)
            }
        }
    }

    /**
     * 分页拉取云端列表并合并到本地。
     * 返回拉取到的全部 code 集合；任一页请求失败时返回 null（拉取不完整，
     * 调用方应跳过「清除云端缺失条目」的逻辑，避免误删本地数据）。
     */
    private suspend fun pullMyList(
        userId: String,
        listType: MyListType,
        toEntity: (HanimeInfo, LocalVideoEntity?, Int) -> LocalVideoEntity,
    ): Set<String>? {
        val localAll = DatabaseRepo.LocalMylist.getAll().associateBy { it.videoCode }
        val remoteCodes = mutableSetOf<String>()
        val merged = mutableListOf<LocalVideoEntity>()
        var page = 1
        var index = 0
        var completed = true
        while (page <= 100) {
            val state = runCatching {
                NetworkRepo.getMyListItems(userId, listType, page).first()
            }.getOrNull()
            val items = (state as? PageLoadingState.Success)?.info?.hanimeInfo
            if (items == null) {
                completed = false
                break
            }
            if (items.isEmpty()) break
            for (hanimeInfo in items) {
                remoteCodes += hanimeInfo.videoCode
                merged += toEntity(hanimeInfo, localAll[hanimeInfo.videoCode], index++)
            }
            page++
        }
        if (merged.isNotEmpty()) {
            DatabaseRepo.LocalMylist.upsertVideos(merged)
        }
        return if (completed) remoteCodes else null
    }

    //</editor-fold>

    //<editor-fold desc="推送：本地脏数据">

    private suspend fun pushDirtyFavorites(userId: String, token: String?) {
        for (entity in DatabaseRepo.LocalMylist.getDirtyFavorites()) {
            val succeeded = runCatching {
                NetworkRepo.addToMyFavVideo(
                    videoCode = entity.videoCode,
                    likeStatus = false,
                    currentUserId = userId,
                    token = token,
                ).first()
            }.getOrNull() is WebsiteState.Success
            if (succeeded) {
                DatabaseRepo.LocalMylist.upsertVideo(entity.copy(favSynced = true))
            }
        }
    }

    private suspend fun pushDirtyWatchLater(userId: String, token: String?) {
        for (entity in DatabaseRepo.LocalMylist.getDirtyWatchLater()) {
            val succeeded = runCatching {
                NetworkRepo.addToMyList(
                    listCode = HanimeVideo.MyList.SAVE_CODE,
                    videoCode = entity.videoCode,
                    isChecked = true,
                    position = 0,
                    csrfToken = token,
                ).first()
            }.getOrNull() is WebsiteState.Success
            if (succeeded) {
                DatabaseRepo.LocalMylist.upsertVideo(entity.copy(watchLaterSynced = true))
            }
        }
    }

    //</editor-fold>

    //<editor-fold desc="播放清单同步">

    private suspend fun syncPlaylists(userId: String, token: String?) {
        // 先推送本地脏清单（云端同名则直接映射，否则创建），
        // 再拉取合并，避免云端清单先写入本地导致同名映射失效、重复创建
        pushDirtyPlaylists(token)

        val remotePlaylists = pullPlaylistList(userId)
        if (remotePlaylists.isNotEmpty()) {
            val localAll = DatabaseRepo.LocalMylist.getAllPlaylists().associateBy { it.code }
            DatabaseRepo.LocalMylist.upsertPlaylists(
                remotePlaylists.map { remote ->
                    localAll[remote.listCode]?.let { local ->
                        if (local.synced) {
                            local.copy(name = remote.title, synced = true)
                        } else {
                            // 待推送改名的脏清单：保留本地名称，避免云端旧名覆盖
                            local
                        }
                    } ?: LocalPlaylistEntity(
                        code = remote.listCode,
                        name = remote.title,
                        createdTime = System.currentTimeMillis(),
                        synced = true,
                    )
                }
            )
        }
        // 云端已删除的已同步清单：本地同步删除（含清单内视频）
        val remoteCodes = remotePlaylists.mapTo(mutableSetOf()) { it.listCode }
        for (playlist in DatabaseRepo.LocalMylist.getAllPlaylists()) {
            if (playlist.synced && playlist.code !in remoteCodes) {
                DatabaseRepo.LocalMylist.deletePlaylist(playlist.code)
                DatabaseRepo.LocalMylist.deleteAllPlaylistItems(playlist.code)
            }
        }

        pushDirtyPlaylistItems(token)

        val finalPlaylists = DatabaseRepo.LocalMylist.getAllPlaylists()
        for (playlist in finalPlaylists) {
            if (playlist.synced) {
                pullPlaylistItems(playlist.code)
            }
        }
    }

    private suspend fun pullPlaylistList(userId: String): List<Playlists.Playlist> {
        val result = mutableListOf<Playlists.Playlist>()
        var page = 1
        while (page <= 100) {
            val playlists = runCatching {
                NetworkRepo.getPlaylists(page, userId).first() as? WebsiteState.Success
            }.getOrNull()?.info?.playlists ?: break
            if (playlists.isEmpty()) break
            result += playlists
            page++
        }
        return result
    }

    private suspend fun pullPlaylistItems(playlistCode: String) {
        val localItems = DatabaseRepo.LocalMylist
            .getPlaylistItems(playlistCode)
            .associateBy { it.videoCode }
        val remoteCodes = mutableSetOf<String>()
        val merged = mutableListOf<LocalPlaylistItemEntity>()
        var page = 1
        var index = 0
        var completed = true
        while (page <= 100) {
            val state = runCatching {
                NetworkRepo.getMyPlayListItems(page, playlistCode).first()
            }.getOrNull()
            val items = (state as? PageLoadingState.Success)?.info
            if (items == null) {
                completed = false
                break
            }
            if (items.hanimeInfo.isEmpty()) break
            items.desc?.let { desc ->
                DatabaseRepo.LocalMylist.upsertPlaylist(
                    DatabaseRepo.LocalMylist.findPlaylist(playlistCode)?.copy(description = desc)
                        ?: LocalPlaylistEntity(playlistCode, "", desc, System.currentTimeMillis(), true)
                )
            }
            for (info in items.hanimeInfo) {
                remoteCodes += info.videoCode
                merged += localItems[info.videoCode]?.let { local ->
                    if (!local.synced) local else local.updatedFromRemote(info, index)
                } ?: LocalPlaylistItemEntity(
                    playlistCode = playlistCode,
                    videoCode = info.videoCode,
                    title = info.title,
                    coverUrl = info.coverUrl,
                    duration = info.duration,
                    views = info.views,
                    reviews = info.reviews,
                    currentArtist = info.currentArtist,
                    uploadTime = info.uploadTime,
                    position = index,
                    addedTime = System.currentTimeMillis(),
                    synced = true,
                )
                index++
            }
            page++
        }
        if (merged.isNotEmpty()) {
            DatabaseRepo.LocalMylist.upsertPlaylistItems(merged)
        }
        // 云端已从清单移除的已同步条目：本地同步删除（仅完整拉取时，本地未同步的不受影响）
        if (completed) {
            for (item in localItems.values) {
                if (item.synced && item.videoCode !in remoteCodes) {
                    DatabaseRepo.LocalMylist.deletePlaylistItem(playlistCode, item.videoCode)
                }
            }
        }
    }

    private fun LocalPlaylistItemEntity.updatedFromRemote(info: HanimeInfo, index: Int) =
        copy(
            title = info.title,
            coverUrl = info.coverUrl,
            duration = info.duration,
            views = info.views,
            reviews = info.reviews,
            currentArtist = info.currentArtist,
            uploadTime = info.uploadTime,
            position = index,
            synced = true,
        )

    private suspend fun pushDirtyPlaylists(token: String?) {
        val dirty = DatabaseRepo.LocalMylist.getDirtyPlaylists()
        if (dirty.isEmpty()) return
        if (!SettingsRepository.isAlreadyLogin) return
        val userId = SettingsRepository.savedUserId
        if (userId.isBlank()) return
        val remote = pullPlaylistList(userId)
        val localAll = DatabaseRepo.LocalMylist.getAllPlaylists().associateBy { it.code }
        val remoteByCode = remote.associateBy { it.listCode }

        // 云端已存在同名清单（例如已登录时创建清单后写入的本地镜像）：
        // 直接映射本地 code，避免 createPlaylist 重复创建同名空清单。
        val unmatchedRemote = remote.filter { !localAll.containsKey(it.listCode) }.toMutableList()
        val toCreate = mutableListOf<LocalPlaylistEntity>()
        val toUpdate = mutableListOf<LocalPlaylistEntity>()
        for (playlist in dirty) {
            if (remoteByCode.containsKey(playlist.code)) {
                // 本地 code 即云端 code（登出后改名 / 改描述）：更新云端而非重建
                toUpdate += playlist
            } else {
                val matched = unmatchedRemote.firstOrNull { it.title == playlist.name }
                if (matched != null) {
                    migrateLocalPlaylist(playlist, matched.listCode)
                    unmatchedRemote.remove(matched)
                } else {
                    toCreate += playlist
                }
            }
        }

        // 更新已存在云端的清单（改名 / 改描述）
        for (playlist in toUpdate) {
            val succeeded = runCatching {
                NetworkRepo.modifyPlaylist(
                    listCode = playlist.code,
                    title = playlist.name,
                    description = playlist.description.orEmpty(),
                    delete = false,
                    csrfToken = token,
                ).first()
            }.getOrNull() is WebsiteState.Success
            if (succeeded) {
                DatabaseRepo.LocalMylist.upsertPlaylist(playlist.copy(synced = true))
            }
        }

        // 其余脏清单推送到云端创建
        for (playlist in toCreate) {
            runCatching {
                NetworkRepo.createPlaylist(
                    videoCode = EMPTY_STRING,
                    title = playlist.name,
                    description = playlist.description.orEmpty(),
                    csrfToken = token,
                ).first()
            }
        }

        // 创建成功后云端清单 code 未知，重新拉取并按名称匹配以更新本地 code
        if (toCreate.isEmpty()) return
        val remoteAfterPush = pullPlaylistList(userId)
        val localAllAfter = DatabaseRepo.LocalMylist.getAllPlaylists().associateBy { it.code }
        val unmatchedAfter = remoteAfterPush.filter { !localAllAfter.containsKey(it.listCode) }.toMutableList()
        for (playlist in toCreate) {
            val matched = unmatchedAfter.firstOrNull { it.title == playlist.name } ?: continue
            migrateLocalPlaylist(playlist, matched.listCode)
            unmatchedAfter.remove(matched)
        }
    }

    /**
     * 把本地清单迁移到云端 code：清单内视频的 playlistCode 一并迁移，
     * 覆盖本地 code 并标记 synced，然后删除旧的本地行。
     */
    private suspend fun migrateLocalPlaylist(playlist: LocalPlaylistEntity, cloudCode: String) {
        val items = DatabaseRepo.LocalMylist.getPlaylistItems(playlist.code)
        if (items.isNotEmpty()) {
            DatabaseRepo.LocalMylist.upsertPlaylistItems(
                items.map { it.copy(playlistCode = cloudCode) }
            )
        }
        DatabaseRepo.LocalMylist.upsertPlaylist(
            playlist.copy(code = cloudCode, synced = true)
        )
        DatabaseRepo.LocalMylist.deletePlaylist(playlist.code)
    }

    private suspend fun pushDirtyPlaylistItems(token: String?) {
        for (item in DatabaseRepo.LocalMylist.getDirtyPlaylistItems()) {
            val parent = DatabaseRepo.LocalMylist.findPlaylist(item.playlistCode) ?: continue
            if (!parent.synced) continue
            val succeeded = runCatching {
                NetworkRepo.addToMyList(
                    listCode = parent.code,
                    videoCode = item.videoCode,
                    isChecked = true,
                    position = item.position,
                    csrfToken = token,
                ).first()
            }.getOrNull() is WebsiteState.Success
            if (succeeded) {
                DatabaseRepo.LocalMylist.upsertPlaylistItem(item.copy(synced = true))
            }
        }
    }

    //</editor-fold>
}
