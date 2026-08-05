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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * 双模式：
 * - 本地化模式（已登录且开启实验性开关）：数据源为本地数据库（mylist.db），
 *   由路由层触发 [MylistSyncManager.sync] 与云端双向合并，数据库更新后页面自动刷新
 * - 在线模式（默认）：数据来自云端（NetworkRepo），与上游行为一致
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
    var currentPage = 1
    var isLoadingMore = false
        private set

    var playlistPage = 1
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
        // 登录+开关：进入页面即触发同步并在首帧前置位 isSyncing，
        // 同步期间列表显示加载动画，避免闪现空态或旧数据误导用户
        // （路由层 LaunchedEffect 仍会触发 sync，tryLock 幂等无副作用）。
        viewModelScope.launch {
            if (SettingsRepository.isAlreadyLogin && SettingsRepository.isLocalMylistEnabled) {
                MylistSyncManager.sync()
            }
        }
        viewModelScope.launch {
            // 仅订阅本地化开关，其他设置变化（主题 / 语言等）不触发重订阅与重载
            SettingsRepository.settings
                .map { it.enableLocalMylist }
                .distinctUntilChanged()
                .flatMapLatest { enabled ->
                    if (enabled) {
                        // 同步期间：清空缓存并置 Loading（复用中央 LoadingIndicator），
                        // 避免同步完成前闪现空态或旧数据误导用户
                        combine(
                            observeLocalPlaylists(),
                            MylistSyncManager.isSyncing,
                        ) { playlists, syncing ->
                            if (syncing) {
                                _cachedMyPlayList.value = emptyList()
                                _myPlaylistsFlow.value = WebsiteState.Loading
                                _isLoadingMorePlaylists.value = false
                                _noMorePlaylists.value = true
                            } else {
                                _cachedMyPlayList.value = playlists
                                _myPlaylistsFlow.value = WebsiteState.Success(Playlists(playlists))
                                _isLoadingMorePlaylists.value = false
                                _noMorePlaylists.value = true
                                runCatching { _refreshCompleted.emit(Unit) }
                            }
                        }.map { Unit }
                    } else {
                        _cachedMyPlayList.value = emptyList()
                        _myPlaylistsFlow.value = WebsiteState.Loading
                        loadMyPlayList(1, forceReload = true)
                        flow {
                            emit(Unit)
                        }
                    }
                }.collect()
        }
    }

    /**
     * 本地化数据源判断：仅由实验性开关控制。
     * 开关开启时列表页 / 操作统一走本地数据库（未登录直接本地，登录后由
     * 同步引擎合并云端）；开关关闭时为纯在线模式（仅登录用户可达，未登录被路由门禁拦截）。
     */
    private fun useLocalMode() =
        SettingsRepository.isLocalMylistEnabled

    /**
     * 观察本地清单列表：同时观察清单表与清单内视频表，
     * 保证封面（取自清单第一个视频）在任何 items 变化后都会重算刷新。
     */
    private fun observeLocalPlaylists() =
        combine(
            DatabaseRepo.LocalMylist.observePlaylists(),
            DatabaseRepo.LocalMylist.observeAllPlaylistItems(),
        ) { playlists, _ -> playlists }
            .flatMapLatest { playlists ->
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
     * 下拉刷新：本地化模式触发云端同步（数据库更新后列表自动刷新），
     * 在线模式重新加载云端数据，未登录本地模式仅结束刷新指示器。
     */
    fun refresh() {
        viewModelScope.launch {
            when {
                useLocalMode() -> MylistSyncManager.sync()
                SettingsRepository.isAlreadyLogin -> loadMyPlayList(1, forceReload = true)
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

    // 加载所有playlist（仅在线模式；本地化模式由 Room 流驱动）
    fun loadMyPlayList(page: Int = 1, forceReload: Boolean = false) {
        if (useLocalMode()) return
        if (page > 1 && (_isLoadingMorePlaylists.value || _noMorePlaylists.value)) return
        if (page == 1 || forceReload) {
            playlistPage = 1
            _noMorePlaylists.value = false
        }
        if (page > 1) {
            _isLoadingMorePlaylists.value = true
        }
        val userId = SettingsRepository.savedUserId
        viewModelScope.launch {
            NetworkRepo.getPlaylists(page, userId).collect { state ->
                when (state) {
                    is WebsiteState.Loading -> {
                        if (page == 1 || forceReload) {
                            _myPlaylistsFlow.value = state
                        }
                    }
                    is WebsiteState.Error -> {
                        _myPlaylistsFlow.value = state
                        _isLoadingMorePlaylists.value = false
                    }
                    is WebsiteState.Success -> {
                        val newList = state.info.playlists
                        if (page == 1 || forceReload) {
                            _cachedMyPlayList.value = newList
                        } else {
                            _cachedMyPlayList.value = (_cachedMyPlayList.value + newList)
                                .distinctBy(Playlists.Playlist::listCode)
                            playlistPage = page
                        }
                        if (newList.isEmpty()) {
                            _noMorePlaylists.value = true
                        }
                        _myPlaylistsFlow.value = state
                        _isLoadingMorePlaylists.value = false
                        _refreshCompleted.emit(Unit)
                    }
                }
            }
        }
    }

    // 获取单个playlist内容
    fun getPlaylistItems(page: Int = 1, listCode: String, refresh: Boolean = false) {
        if (useLocalMode()) {
            localItemsJob?.cancel()
            localItemsJob = viewModelScope.launch {
                if (listCode.isBlank()) return@launch
                DatabaseRepo.LocalMylist.observePlaylistItems(listCode).collect { items ->
                    val hanimeInfos = items.map { it.toHanimeInfo() }
                    _playlistDesc.value = DatabaseRepo.LocalMylist.findPlaylist(listCode)?.description
                    _playlistFlow.value = hanimeInfos
                    _playlistStateFlow.value = if (hanimeInfos.isEmpty()) {
                        PageLoadingState.NoMoreData
                    } else {
                        PageLoadingState.Success(MyListItems(hanimeInfos))
                    }
                }
            }
            return
        }
        if (isLoadingMore) return
        isLoadingMore = true
        viewModelScope.launch {
            if (listCode.isBlank()) return@launch
            if (page == 1 || refresh) {
                _playlistFlow.value = emptyList()
                _playlistDesc.value = null
                _playlistStateFlow.value = PageLoadingState.Loading
            } else {
                _playlistStateFlow.value = PageLoadingState.Loading
            }
            NetworkRepo.getMyPlayListItems(page, listCode).collect { state ->
                when (state) {
                    is PageLoadingState.Success -> {
                        _playlistDesc.value = state.info.desc
                        val newList = state.info.hanimeInfo
                        if (newList.isEmpty()) {
                            _playlistStateFlow.value = PageLoadingState.NoMoreData
                        } else {
                            _playlistFlow.update { prevList ->
                                val baseList = if (page == 1 || refresh) emptyList() else prevList
                                (baseList + newList).distinctBy(HanimeInfo::videoCode)
                            }
                            _playlistStateFlow.value = PageLoadingState.Success(state.info)
                        }
                    }

                    is PageLoadingState.Error -> {
                        _playlistStateFlow.value = PageLoadingState.Error(state.throwable)
                    }

                    is PageLoadingState.Loading -> {
                        if (page == 1 || refresh) {
                            _playlistFlow.value = emptyList()
                        }
                    }

                    is PageLoadingState.NoMoreData -> {
                        _playlistStateFlow.value = PageLoadingState.NoMoreData
                    }
                }
            }
            isLoadingMore = false
        }
    }

    private var localItemsJob: kotlinx.coroutines.Job? = null

    private val _deleteFromPlaylistFlow = MutableSharedFlow<WebsiteState<Int>>()
    val deleteFromPlaylistFlow = _deleteFromPlaylistFlow.asSharedFlow()
    // 从详情页删除某视频
    fun deleteFromPlaylist(listCode: String, videoCode: String, position: Int) {
        viewModelScope.launch {
            if (useLocalMode()) {
                val item = DatabaseRepo.LocalMylist
                    .getPlaylistItemCodes(listCode, listOf(videoCode))
                    .firstOrNull()
                if (item == null) {
                    _deleteFromPlaylistFlow.emit(WebsiteState.Error(IllegalStateException("cannot delete it ?!")))
                    return@launch
                }
                // 本地操作即时生效
                DatabaseRepo.LocalMylist.deletePlaylistItem(listCode, videoCode)
                _deleteFromPlaylistFlow.emit(WebsiteState.Success(position))
                // 按 videoCode 过滤而非按 position 移除：Room 流可能已先发出
                // 新列表，CAS 重试时旧快照的 position 会越界崩溃
                _playlistFlow.update { prevList ->
                    prevList.filterNot { it.videoCode == videoCode }
                }
                if (SettingsRepository.isAlreadyLogin) {
                    // 已登录：立即删除云端（并行），失败则记录墓碑（登录同步时补偿）
                    viewModelScope.launch {
                        NetworkRepo.deleteMyListItems(listCode, videoCode, position, csrfToken).collect { state ->
                            if (state is WebsiteState.Error && item.synced) {
                                DatabaseRepo.LocalMylist.upsertPlaylistItemTombstone(
                                    videoCode = videoCode,
                                    playlistCode = listCode,
                                )
                            }
                        }
                    }
                } else if (item.synced) {
                    // 已同步的条目记录墓碑，登录同步时推送到云端删除，避免拉取复活
                    DatabaseRepo.LocalMylist.upsertPlaylistItemTombstone(
                        videoCode = videoCode,
                        playlistCode = listCode,
                    )
                }
                return@launch
            }
            NetworkRepo.deleteMyListItems(listCode, videoCode, position, csrfToken).collect {
                _deleteFromPlaylistFlow.emit(it)
                _playlistFlow.update { prevList ->
                    if (it is WebsiteState.Success) {
                        prevList.filterNot { it.videoCode == videoCode }
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
            if (useLocalMode()) {
                if (delete) {
                    val wasSynced = DatabaseRepo.LocalMylist
                        .findPlaylist(listCode)?.synced == true
                    // 本地操作即时生效
                    DatabaseRepo.LocalMylist.deletePlaylist(listCode)
                    DatabaseRepo.LocalMylist.deleteAllPlaylistItems(listCode)
                    _modifyPlaylistFlow.emit(
                        WebsiteState.Success(ModifiedPlaylistArgs(title, desc, delete))
                    )
                    clearMyListItems()
                    if (SettingsRepository.isAlreadyLogin) {
                        // 已登录：立即删除云端（并行），失败则记录墓碑（登录同步时补偿）
                        viewModelScope.launch {
                            NetworkRepo.modifyPlaylist(
                                listCode, "", "", delete = true, csrfToken
                            ).collect { state ->
                                if (state is WebsiteState.Error && wasSynced) {
                                    DatabaseRepo.LocalMylist.upsertTombstoneMerged(
                                        videoCode = listCode,
                                        isPlaylist = true,
                                    )
                                }
                            }
                        }
                    } else if (wasSynced) {
                        // 已同步的清单记录墓碑，登录同步时推送到云端删除，避免拉取复活
                        DatabaseRepo.LocalMylist.upsertTombstoneMerged(
                            videoCode = listCode,
                            isPlaylist = true,
                        )
                    }
                } else {
                    DatabaseRepo.LocalMylist.findPlaylist(listCode)?.let { playlist ->
                        // 标记未同步，登录同步时更新云端（保留云端 code）
                        DatabaseRepo.LocalMylist.upsertPlaylist(
                            playlist.copy(name = title, description = desc, synced = false)
                        )
                        if (SettingsRepository.isAlreadyLogin) {
                            // 已登录：立即更新云端（并行），成功则标记已同步，失败保持脏数据下次同步重试
                            viewModelScope.launch {
                                NetworkRepo.modifyPlaylist(
                                    listCode, title, desc, delete = false, csrfToken
                                ).collect { state ->
                                    if (state is WebsiteState.Success) {
                                        DatabaseRepo.LocalMylist.findPlaylist(listCode)?.let { updated ->
                                            DatabaseRepo.LocalMylist.upsertPlaylist(
                                                updated.copy(name = title, description = desc, synced = true)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    _modifyPlaylistFlow.emit(
                        WebsiteState.Success(ModifiedPlaylistArgs(title, desc, delete))
                    )
                }
                return@launch
            }
            NetworkRepo.modifyPlaylist(listCode, title, desc, delete, csrfToken).collect {
                _modifyPlaylistFlow.emit(it)
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
            if (useLocalMode()) {
                DatabaseRepo.LocalMylist.upsertPlaylist(
                    LocalPlaylistEntity(
                        code = UUID.randomUUID().toString(),
                        name = title,
                        description = description,
                        createdTime = System.currentTimeMillis(),
                        synced = false,
                    )
                )
                if (SettingsRepository.isAlreadyLogin) {
                    // 已登录：立即推送到云端（并行，尽力），成功后立即映射本地 code，
                    // 消除「UUID 未映射期」，后续修改可直接用云端 code 即时推送
                    viewModelScope.launch {
                        val state = runCatching {
                            NetworkRepo.createPlaylist(
                                EMPTY_STRING, title, description, csrfToken
                            ).first()
                        }.getOrNull()
                        if (state is WebsiteState.Success) {
                            runCatching { MylistSyncManager.mapLocalPlaylistCodes() }
                        }
                    }
                }
                _createPlaylistFlow.emit(WebsiteState.Success(Unit))
                return@launch
            }
            NetworkRepo.createPlaylist(EMPTY_STRING, title, description, csrfToken).collect {
                _createPlaylistFlow.emit(it)
            }
        }
    }
}
