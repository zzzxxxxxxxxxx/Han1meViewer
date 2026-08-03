package io.github.daisukikaffuchino.han1meviewer.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.daisukikaffuchino.han1meviewer.EMPTY_STRING
import io.github.daisukikaffuchino.han1meviewer.logic.DatabaseRepo
import io.github.daisukikaffuchino.han1meviewer.logic.MylistSyncManager
import io.github.daisukikaffuchino.han1meviewer.logic.NetworkRepo
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.entity.mylist.LocalPlaylistEntity
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeInfo
import io.github.daisukikaffuchino.han1meviewer.logic.model.ModifiedPlaylistArgs
import io.github.daisukikaffuchino.han1meviewer.logic.model.MyListItems
import io.github.daisukikaffuchino.han1meviewer.logic.model.Playlists
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageLoadingState
import io.github.daisukikaffuchino.han1meviewer.logic.state.WebsiteState
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.myplaylist.PlaylistUiState
import io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.AppViewModel.csrfToken
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class PlaylistSheetScrollState(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
)

/**
 * 播放清单 ViewModel。
 *
 * 数据源统一为本地数据库（mylist.db），登录时由路由层触发 [MylistSyncManager.sync]
 * 与云端双向合并，本地数据库更新后页面自动刷新。操作函数在未登录时只写本地，
 * 已登录时同时调用云端并写本地镜像。
 *
 * @project Han1meViewer
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MyPlayListViewModel : ViewModel() {

    private val _myPlaylistsFlow = MutableStateFlow<WebsiteState<Playlists>>(WebsiteState.Loading)
    val myPlaylistsFlow: StateFlow<WebsiteState<Playlists>> = _myPlaylistsFlow.asStateFlow()

    private val _cachedMyPlayList = MutableStateFlow<List<Playlists.Playlist>>(emptyList())

    private val _playlistStateFlow =
        MutableStateFlow<PageLoadingState<MyListItems<HanimeInfo>>>(PageLoadingState.Loading)
    val playlistStateFlow = _playlistStateFlow.asStateFlow()
    private val _playlistDesc = MutableStateFlow<String?>(null)
    val playlistDesc = _playlistDesc.asStateFlow()

    private val _playlistFlow = MutableStateFlow(emptyList<HanimeInfo>())
    val playlistFlow = _playlistFlow.asStateFlow()
    private val _currentListInfo = MutableStateFlow<Pair<String, String>?>(null)
    val currentListInfo = _currentListInfo.asStateFlow()
    private val _playlistSheetScrollStates = MutableStateFlow<Map<String, PlaylistSheetScrollState>>(emptyMap())


    private val _refreshCompleted = MutableSharedFlow<Unit>()
    val refreshCompleted: SharedFlow<Unit> = _refreshCompleted

    private val _showSheet = MutableStateFlow(false)
    private val _isLoadingMorePlaylists = MutableStateFlow(false)
    private val _noMorePlaylists = MutableStateFlow(false)

    /** 对外暴露的唯一主页面 UI 状态流。 */
    val mainUiState: StateFlow<PlaylistUiState> = combine(
        _cachedMyPlayList,
        _showSheet,
        _currentListInfo,
        _isLoadingMorePlaylists,
        _noMorePlaylists,
    ) { array ->
        @Suppress("UNCHECKED_CAST")
        PlaylistUiState(
            playlists = array[0] as List<Playlists.Playlist>,
            showSheet = array[1] as Boolean,
            selectedListCode = (array[2] as Pair<String, String>?)?.first ?: "",
            selectedListTitle = (array[2] as Pair<String, String>?)?.second ?: "",
            isLoadingMore = array[3] as Boolean,
            noMorePlaylists = array[4] as Boolean,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlaylistUiState())

    init {
        viewModelScope.launch {
            observeLocalPlaylists().onEach { playlists ->
                _cachedMyPlayList.value = playlists
                _myPlaylistsFlow.value = WebsiteState.Success(Playlists(playlists))
                _isLoadingMorePlaylists.value = false
                _noMorePlaylists.value = true
                runCatching { _refreshCompleted.emit(Unit) }
            }.collect()
        }
    }

    private fun observeLocalPlaylists() =
        DatabaseRepo.LocalMylist.observePlaylists().flatMapLatest { playlists ->
            flow {
                emit(playlists.map { playlist ->
                    val items = DatabaseRepo.LocalMylist.getPlaylistItems(playlist.code)
                    Playlists.Playlist(
                        listCode = playlist.code,
                        title = playlist.name,
                        total = items.size,
                        coverUrl = items.firstOrNull()?.coverUrl,
                    )
                })
            }
        }

    /**
     * 下拉刷新：已登录时触发云端同步（本地数据库更新后列表自动刷新），
     * 未登录时本地数据已是实时，仅结束刷新指示器。
     */
    fun refresh() {
        viewModelScope.launch {
            if (SettingsRepository.isAlreadyLogin) {
                MylistSyncManager.sync()
            }
            runCatching { _refreshCompleted.emit(Unit) }
        }
    }

    fun setShowSheet(value: Boolean) {
        _showSheet.value = value
    }
    fun setListInfo(code: String, title: String) {
        _currentListInfo.value = code to title
    }

    fun updatePlaylistSheetScrollState(
        listCode: String,
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffset: Int,
    ) {
        if (listCode.isBlank()) return
        _playlistSheetScrollStates.update { prev ->
            prev + (
                listCode to PlaylistSheetScrollState(
                    firstVisibleItemIndex = firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
                )
            )
        }
    }

    fun getPlaylistSheetScrollState(listCode: String): PlaylistSheetScrollState {
        return _playlistSheetScrollStates.value[listCode] ?: PlaylistSheetScrollState()
    }

    // 获取单个playlist内容（本地数据源，随数据库变化自动刷新）
    fun getPlaylistItems(page: Int = 1, listCode: String, refresh: Boolean = false) {
        localItemsJob?.cancel()
        localItemsJob = viewModelScope.launch {
            if (listCode.isBlank()) return@launch
            DatabaseRepo.LocalMylist.observePlaylistItems(listCode).collect { items ->
                val hanimeInfos = items.map { it.toHanimeInfo() }
                _playlistDesc.value = null
                _playlistFlow.value = hanimeInfos
                _playlistStateFlow.value = if (hanimeInfos.isEmpty()) {
                    PageLoadingState.NoMoreData
                } else {
                    PageLoadingState.Success(MyListItems(hanimeInfos))
                }
            }
        }
    }

    private var localItemsJob: kotlinx.coroutines.Job? = null

    private val _deleteFromPlaylistFlow = MutableSharedFlow<WebsiteState<Int>>()
    val deleteFromPlaylistFlow = _deleteFromPlaylistFlow.asSharedFlow()
    // 从详情页删除某视频
    fun deleteFromPlaylist(listCode: String, videoCode: String, position: Int) {
        viewModelScope.launch {
            if (!SettingsRepository.isAlreadyLogin) {
                val item = DatabaseRepo.LocalMylist
                    .getPlaylistItemCodes(listCode, listOf(videoCode))
                    .firstOrNull()
                if (item == null) {
                    _deleteFromPlaylistFlow.emit(WebsiteState.Error(IllegalStateException("cannot delete it ?!")))
                    return@launch
                }
                DatabaseRepo.LocalMylist.deletePlaylistItem(listCode, videoCode)
                _deleteFromPlaylistFlow.emit(WebsiteState.Success(position))
                _playlistFlow.update { prevList ->
                    prevList.toMutableList().apply { removeAt(position) }
                }
                return@launch
            }
            NetworkRepo.deleteMyListItems(listCode, videoCode, position, csrfToken).collect {
                _deleteFromPlaylistFlow.emit(it)
                if (it is WebsiteState.Success) {
                    DatabaseRepo.LocalMylist.deletePlaylistItem(listCode, videoCode)
                }
                _playlistFlow.update { prevList ->
                    if (it is WebsiteState.Success) {
                        prevList.toMutableList().apply { removeAt(position) }
                    } else prevList
                }
            }
        }
    }

    private val _modifyPlaylistFlow = MutableSharedFlow<WebsiteState<ModifiedPlaylistArgs>>()
    val modifyPlaylistFlow = _modifyPlaylistFlow.asSharedFlow()
    // 编辑Playlist
    fun modifyPlaylist(listCode: String, title: String, desc: String, delete: Boolean) {
        viewModelScope.launch {
            if (!SettingsRepository.isAlreadyLogin) {
                if (delete) {
                    DatabaseRepo.LocalMylist.deletePlaylist(listCode)
                    DatabaseRepo.LocalMylist.deleteAllPlaylistItems(listCode)
                } else {
                    DatabaseRepo.LocalMylist.findPlaylist(listCode)?.let { playlist ->
                        DatabaseRepo.LocalMylist.upsertPlaylist(
                            playlist.copy(name = title, description = desc)
                        )
                    }
                }
                _modifyPlaylistFlow.emit(
                    WebsiteState.Success(ModifiedPlaylistArgs(title, desc, delete))
                )
                if (delete) {
                    clearMyListItems()
                }
                return@launch
            }
            NetworkRepo.modifyPlaylist(listCode, title, desc, delete, csrfToken).collect {
                _modifyPlaylistFlow.emit(it)
                if (it is WebsiteState.Success) {
                    if (delete) {
                        DatabaseRepo.LocalMylist.deletePlaylist(listCode)
                        DatabaseRepo.LocalMylist.deleteAllPlaylistItems(listCode)
                    } else {
                        DatabaseRepo.LocalMylist.findPlaylist(listCode)?.let { playlist ->
                            DatabaseRepo.LocalMylist.upsertPlaylist(
                                playlist.copy(name = title, description = desc)
                            )
                        }
                    }
                }
                if (delete) {
                    clearMyListItems()
                }
            }
        }
    }
    fun clearMyListItems() {
        _playlistStateFlow.value = PageLoadingState.Loading
    }
    fun clearCurrentList(){
        _playlistFlow.value = emptyList()
    }
    private val _createPlaylistFlow = MutableSharedFlow<WebsiteState<Unit>>()
    val createPlaylistFlow = _createPlaylistFlow.asSharedFlow()
    //创建Playlist
    fun createPlaylist(title: String, description: String) {
        viewModelScope.launch {
            if (!SettingsRepository.isAlreadyLogin) {
                DatabaseRepo.LocalMylist.upsertPlaylist(
                    LocalPlaylistEntity(
                        code = UUID.randomUUID().toString(),
                        name = title,
                        description = description,
                        createdTime = System.currentTimeMillis(),
                        synced = false,
                    )
                )
                _createPlaylistFlow.emit(WebsiteState.Success(Unit))
                return@launch
            }
            NetworkRepo.createPlaylist(EMPTY_STRING, title, description, csrfToken).collect {
                _createPlaylistFlow.emit(it)
                if (it is WebsiteState.Success) {
                    // 本地镜像：同步引擎登录后按名称匹配补齐云端 code
                    DatabaseRepo.LocalMylist.upsertPlaylist(
                        LocalPlaylistEntity(
                            code = UUID.randomUUID().toString(),
                            name = title,
                            description = description,
                            createdTime = System.currentTimeMillis(),
                            synced = false,
                        )
                    )
                }
            }
        }
    }
}
