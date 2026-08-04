package io.github.daisukikaffuchino.han1meviewer.logic

import android.content.Context
import android.net.Uri
import androidx.glance.appwidget.updateAll
import io.github.daisukikaffuchino.han1meviewer.BuildConfig
import io.github.daisukikaffuchino.han1meviewer.HanimeApplication
import io.github.daisukikaffuchino.han1meviewer.logic.datastore.DataStoreManager
import io.github.daisukikaffuchino.han1meviewer.logic.network.HanimeNetwork
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxySelector
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.dao.CheckInRecordDatabase
import io.github.daisukikaffuchino.han1meviewer.logic.dao.DownloadDatabase
import io.github.daisukikaffuchino.han1meviewer.logic.dao.HistoryDatabase
import io.github.daisukikaffuchino.han1meviewer.logic.dao.LocalMylistDatabase
import io.github.daisukikaffuchino.han1meviewer.logic.dao.MiscellanyDatabase
import io.github.daisukikaffuchino.han1meviewer.logic.entity.HKeyframeEntity
import io.github.daisukikaffuchino.han1meviewer.logic.entity.CheckInRecordEntity
import io.github.daisukikaffuchino.han1meviewer.logic.entity.WatchHistoryEntity
import io.github.daisukikaffuchino.han1meviewer.logic.entity.download.DownloadCategoryEntity
import io.github.daisukikaffuchino.han1meviewer.logic.entity.download.DownloadGroupEntity
import io.github.daisukikaffuchino.han1meviewer.logic.entity.download.HanimeCategoryCrossRef
import io.github.daisukikaffuchino.han1meviewer.logic.entity.download.HanimeDownloadEntity
import io.github.daisukikaffuchino.han1meviewer.logic.entity.mylist.LocalMylistTombstoneEntity
import io.github.daisukikaffuchino.han1meviewer.logic.entity.mylist.LocalPlaylistItemTombstoneEntity
import io.github.daisukikaffuchino.han1meviewer.logic.entity.mylist.LocalPlaylistEntity
import io.github.daisukikaffuchino.han1meviewer.logic.entity.mylist.LocalPlaylistItemEntity
import io.github.daisukikaffuchino.han1meviewer.logic.entity.mylist.LocalVideoEntity
import io.github.daisukikaffuchino.han1meviewer.ui.widget.CheckInWidget
import io.github.daisukikaffuchino.han1meviewer.util.AppLanguageManager
import io.github.daisukikaffuchino.han1meviewer.worker.HanimeDownloadManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.OutputStream

object BackupManager {
    private const val BACKUP_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @Serializable
    private data class BackupData(
        val version: Int = BACKUP_VERSION,
        val appVersionCode: Int = BuildConfig.VERSION_CODE,
        val appVersionName: String = BuildConfig.VERSION_NAME,
        val exportedAt: Long = System.currentTimeMillis(),
        val settings: Map<String, PreferenceValue>? = null,
        val hKeyframes: List<HKeyframeEntity>? = null,
        val checkInRecords: List<CheckInRecordEntity>? = null,
        val watchHistories: List<WatchHistoryEntity>? = null,
        val downloadGroups: List<DownloadGroupEntity>? = null,
        val downloads: List<HanimeDownloadEntity>? = null,
        val downloadCategories: List<DownloadCategoryEntity>? = null,
        val downloadCategoryCrossRefs: List<HanimeCategoryCrossRef>? = null,
        val localVideos: List<LocalVideoEntity>? = null,
        val localPlaylists: List<LocalPlaylistEntity>? = null,
        val localPlaylistItems: List<LocalPlaylistItemEntity>? = null,
        val localMylistTombstones: List<LocalMylistTombstoneEntity>? = null,
        val localPlaylistItemTombstones: List<LocalPlaylistItemTombstoneEntity>? = null,
    )

    @Serializable
    private sealed interface PreferenceValue {
        @Serializable
        data class BooleanValue(val value: Boolean) : PreferenceValue

        @Serializable
        data class FloatValue(val value: Float) : PreferenceValue

        @Serializable
        data class IntValue(val value: Int) : PreferenceValue

        @Serializable
        data class LongValue(val value: Long) : PreferenceValue

        @Serializable
        data class StringValue(val value: String) : PreferenceValue

        @Serializable
        data class StringSetValue(val value: Set<String>) : PreferenceValue
    }

    suspend fun exportTo(context: Context, uri: Uri) {
        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            exportTo(context, outputStream)
        } ?: error("Unable to open backup file")
    }

    /**
     * 导入备份。
     *
     * @param pushToCloud 为 true 时（用于账号迁移 / 封号恢复），导入的本地收藏 /
     * 稍后观看 / 播放清单会被标记为未同步，下次同步全量推送到云端；
     * 为 false 时保持备份中的同步标记，导入后以云端为准合并。
     */
    suspend fun importFrom(context: Context, uri: Uri, pushToCloud: Boolean) {
        val backup = context.contentResolver.openInputStream(uri)?.use { inputStream ->
            json.decodeFromString<BackupData>(inputStream.bufferedReader().readText())
        } ?: error("Unable to open backup file")

        backup.hKeyframes?.let { hKeyframes ->
            MiscellanyDatabase.instance.hKeyframeDao.apply {
                deleteAll()
                insertAll(hKeyframes)
            }
        }

        backup.checkInRecords?.let { checkInRecords ->
            CheckInRecordDatabase.getDatabase(context).checkInDao().apply {
                deleteAll()
                insertAll(checkInRecords)
            }
        }

        backup.watchHistories?.let { watchHistories ->
            HistoryDatabase.instance.watchHistory.apply {
                deleteAll()
                insertAll(watchHistories)
            }
        }

        if (backup.downloadGroups != null || backup.downloads != null ||
            backup.downloadCategories != null || backup.downloadCategoryCrossRefs != null
        ) {
            val downloadGroups = backup.downloadGroups.orEmpty()
            val groupIds = downloadGroups.mapTo(mutableSetOf()) { it.id } +
                    DownloadGroupEntity.DEFAULT_GROUP_ID
            val downloads = backup.downloads.orEmpty().map { download ->
                if (download.groupId in groupIds) {
                    download
                } else {
                    download.copy(groupId = DownloadGroupEntity.DEFAULT_GROUP_ID)
                }
            }
            val downloadCategories = backup.downloadCategories.orEmpty()
            val downloadIds = downloads.mapTo(mutableSetOf()) { it.id }
            val categoryIds = downloadCategories.mapTo(mutableSetOf()) { it.id }
            val crossRefs = backup.downloadCategoryCrossRefs.orEmpty().filter { crossRef ->
                crossRef.videoId in downloadIds && crossRef.categoryId in categoryIds
            }

            DownloadDatabase.instance.apply {
                downloadCategoryDao.deleteAllCrossRefs()
                hanimeDownloadDao.deleteAll()
                downloadCategoryDao.deleteAllCategories()
                downloadGroupDao.deleteAll()
                downloadGroupDao.insertAll(downloadGroups)
                downloadGroupDao.insertDefaultGroup()
                downloadCategoryDao.insertAllCategories(downloadCategories)
                hanimeDownloadDao.insertAll(downloads)
                downloadCategoryDao.insertAllCrossRefs(crossRefs)
            }
        }

        if (backup.localVideos != null || backup.localPlaylists != null ||
            backup.localPlaylistItems != null || backup.localMylistTombstones != null ||
            backup.localPlaylistItemTombstones != null
        ) {
            val dao = LocalMylistDatabase.instance.localMylistDao
            backup.localVideos?.let { localVideos ->
                dao.deleteAllVideos()
                dao.upsertVideos(
                    if (pushToCloud) {
                        // 全量推送模式：导入的数据视为未同步，下次同步推送到云端
                        localVideos.map { it.copy(favSynced = false, watchLaterSynced = false) }
                    } else {
                        localVideos
                    }
                )
            }
            backup.localPlaylists?.let { localPlaylists ->
                dao.deleteAllPlaylists()
                dao.upsertPlaylists(
                    if (pushToCloud) {
                        localPlaylists.map { it.copy(synced = false) }
                    } else {
                        localPlaylists
                    }
                )
            }
            backup.localPlaylistItems?.let { localPlaylistItems ->
                dao.deleteAllPlaylistItems()
                dao.upsertPlaylistItems(
                    if (pushToCloud) {
                        localPlaylistItems.map { it.copy(synced = false) }
                    } else {
                        localPlaylistItems
                    }
                )
            }
            backup.localMylistTombstones?.let { tombstones ->
                dao.deleteAllTombstones()
                tombstones.forEach { dao.upsertTombstone(it) }
            }
            backup.localPlaylistItemTombstones?.let { tombstones ->
                dao.deleteAllPlaylistItemTombstones()
                tombstones.forEach { dao.upsertPlaylistItemTombstone(it) }
            }
        }

        backup.settings?.let { settings ->
            DataStoreManager.restoreBackup(settings.mapValues { (_, value) -> value.rawValue })
            AppLanguageManager.setAppLanguage(SettingsRepository.current.appLanguage)
            HProxySelector.rebuildNetwork()
            HanimeNetwork.rebuildNetwork()
            HanimeDownloadManager.maxConcurrentDownloadCount =
                SettingsRepository.current.downloadCountLimit
            (context.applicationContext as? HanimeApplication)?.switchLauncher(
                SettingsRepository.current.fakeLauncherIcon
            )
        }

        runCatching { CheckInWidget().updateAll(context) }
    }

    private suspend fun exportTo(context: Context, outputStream: OutputStream) {
        val backup = BackupData(
            settings = DataStoreManager.exportBackup().mapValuesNotNull { (_, value) ->
                value.toPreferenceValue()
            },
            hKeyframes = MiscellanyDatabase.instance.hKeyframeDao.getAll(),
            checkInRecords = CheckInRecordDatabase.getDatabase(context).checkInDao().getAllRecords(),
            watchHistories = HistoryDatabase.instance.watchHistory.getAll(),
            downloadGroups = DownloadDatabase.instance.downloadGroupDao.getAllGroupsOnce(),
            downloads = DownloadDatabase.instance.hanimeDownloadDao.getAll(),
            downloadCategories = DownloadDatabase.instance.downloadCategoryDao.getAllCategoriesOnce(),
            downloadCategoryCrossRefs = DownloadDatabase.instance.downloadCategoryDao.getAllCrossRefs(),
            localVideos = LocalMylistDatabase.instance.localMylistDao.getAll(),
            localPlaylists = LocalMylistDatabase.instance.localMylistDao.getAllPlaylists(),
            localPlaylistItems = LocalMylistDatabase.instance.localMylistDao.getAllPlaylistItems(),
            localMylistTombstones = LocalMylistDatabase.instance.localMylistDao.getTombstones(),
            localPlaylistItemTombstones =
                LocalMylistDatabase.instance.localMylistDao.getPlaylistItemTombstones(),
        )
        outputStream.bufferedWriter().use { writer ->
            writer.write(json.encodeToString(backup))
        }
    }

    private inline fun <K, V, R : Any> Map<K, V>.mapValuesNotNull(
        transform: (Map.Entry<K, V>) -> R?
    ): Map<K, R> {
        return mapNotNull { entry -> transform(entry)?.let { entry.key to it } }.toMap()
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.toPreferenceValue(): PreferenceValue? {
        return when (this) {
            is Boolean -> PreferenceValue.BooleanValue(this)
            is Float -> PreferenceValue.FloatValue(this)
            is Int -> PreferenceValue.IntValue(this)
            is Long -> PreferenceValue.LongValue(this)
            is String -> PreferenceValue.StringValue(this)
            is Set<*> -> PreferenceValue.StringSetValue(this.filterIsInstance<String>().toSet())
            else -> null
        }
    }

    private val PreferenceValue.rawValue: Any
        get() = when (this) {
            is PreferenceValue.BooleanValue -> value
            is PreferenceValue.FloatValue -> value
            is PreferenceValue.IntValue -> value
            is PreferenceValue.LongValue -> value
            is PreferenceValue.StringSetValue -> value
            is PreferenceValue.StringValue -> value
        }

}
