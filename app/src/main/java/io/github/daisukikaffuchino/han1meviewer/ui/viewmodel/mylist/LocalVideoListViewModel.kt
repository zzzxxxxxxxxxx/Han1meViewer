package io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.mylist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.daisukikaffuchino.han1meviewer.logic.DatabaseRepo
import io.github.daisukikaffuchino.han1meviewer.logic.MylistSyncManager
import io.github.daisukikaffuchino.han1meviewer.logic.NetworkRepo
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.entity.mylist.LocalVideoEntity
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeInfo
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeVideo
import io.github.daisukikaffuchino.han1meviewer.logic.model.MyListItems
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageLoadingState
import io.github.daisukikaffuchino.han1meviewer.logic.state.WebsiteState
import io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.AppViewModel.csrfToken
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 本地收藏 / 稍后观看列表 ViewModel。
 *
 * 数据源统一为本地数据库 mylist.db（登录 / 未登录一致），
 * 已登录时由路由层触发 MylistSyncManager.sync 与云端双向合并；
 * 删除时按登录状态实时同步云端或记录删除墓碑（登录后由同步引擎推送）。
 *
 * @project Han1meViewer
 */
class LocalVideoListViewModel(
    private val isFavoriteMode: Boolean,
) : ViewModel() {

    private val _itemsStateFlow = MutableStateFlow<PageLoadingState<MyListItems<HanimeInfo>>>(
        PageLoadingState.Loading
    )
    val itemsStateFlow: StateFlow<PageLoadingState<MyListItems<HanimeInfo>>> =
        _itemsStateFlow.asStateFlow()

    val items: StateFlow<List<HanimeInfo>> = (if (isFavoriteMode) {
        DatabaseRepo.LocalMylist.observeFavorites()
    } else {
        DatabaseRepo.LocalMylist.observeWatchLater()
    }).map { entities ->
        entities.map { it.toHanimeInfo() }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    // 本地数据全量加载，无分页：VideoGridScreen 需要这两个状态位。
    // 同步期间 loadedPageCount 置 0 触发 VideoGridScreen 的 bootstrap 加载动画，
    // 同步结束恢复 1（不再触发）。
    val loadedPageCount: StateFlow<Int> = MylistSyncManager.isSyncing
        .map { if (it) 0 else 1 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1)
    val isLoadingMore: StateFlow<Boolean> = MutableStateFlow(false)

    private val _deleteFlow = MutableSharedFlow<WebsiteState<Boolean>>()
    val deleteFlow = _deleteFlow.asSharedFlow()

    init {
        // 登录+开关：进入页面即触发同步并在首帧前置位 isSyncing，
        // 同步期间列表显示加载动画，避免闪现空态或旧数据误导用户
        // （路由层 LaunchedEffect 仍会触发 sync，tryLock 幂等无副作用）。
        if (SettingsRepository.isAlreadyLogin && SettingsRepository.isLocalMylistEnabled) {
            viewModelScope.launch { MylistSyncManager.sync() }
        }
        viewModelScope.launch {
            combine(MylistSyncManager.isSyncing, items) { syncing, list ->
                when {
                    syncing -> PageLoadingState.Loading
                    list.isEmpty() -> PageLoadingState.NoMoreData
                    else -> PageLoadingState.Success(MyListItems(list))
                }
            }.collect { _itemsStateFlow.value = it }
        }
    }

    /**
     * 下拉刷新：已登录时触发云端同步（本地数据库更新后列表自动刷新），
     * 未登录时本地数据已是实时，无需任何操作。
     */
    fun refresh() {
        if (!SettingsRepository.isAlreadyLogin) return
        viewModelScope.launch {
            MylistSyncManager.sync()
        }
    }

    /**
     * 本地数据全量加载，无分页逻辑，仅满足 VideoGridScreen 的回调要求。
     */
    fun loadMore() = Unit

    fun deleteItem(videoCode: String, position: Int) {
        viewModelScope.launch {
            val entity = DatabaseRepo.LocalMylist.findBy(videoCode)
            if (entity == null) {
                _deleteFlow.emit(WebsiteState.Error(IllegalStateException("not found")))
                return@launch
            }
            val loggedIn = SettingsRepository.isAlreadyLogin
            val synced = if (isFavoriteMode) entity.favSynced else entity.watchLaterSynced
            if (loggedIn && synced) {
                val succeeded = runCatching {
                    val state = if (isFavoriteMode) {
                        NetworkRepo.addToMyFavVideo(
                            videoCode = videoCode,
                            likeStatus = true,
                            currentUserId = SettingsRepository.savedUserId,
                            token = csrfToken,
                        ).first()
                    } else {
                        NetworkRepo.addToMyList(
                            listCode = HanimeVideo.MyList.SAVE_CODE,
                            videoCode = videoCode,
                            isChecked = false,
                            position = position,
                            csrfToken = csrfToken,
                        ).first()
                    }
                    state is WebsiteState.Success
                }.getOrElse { false }
                if (succeeded) {
                    removeLocalVideo(entity)
                    _deleteFlow.emit(WebsiteState.Success(true))
                } else {
                    _deleteFlow.emit(WebsiteState.Error(IllegalStateException("delete failed")))
                }
            } else {
                // 未登录或未同步：仅已同步的条目记录墓碑（云端存在、需推送删除），
                // 未同步条目云端不存在，直接本地删除即可
                if (synced) {
                    DatabaseRepo.LocalMylist.upsertTombstoneMerged(
                        videoCode = videoCode,
                        isFav = isFavoriteMode,
                        isWatchLater = !isFavoriteMode,
                    )
                }
                removeLocalVideo(entity)
                _deleteFlow.emit(WebsiteState.Success(true))
            }
        }
    }

    /**
     * 删除本地行，但若视频仍属于另一个列表（收藏 / 稍后观看并存），
     * 仅清除当前列表的标志而不是整行删除。
     */
    private suspend fun removeLocalVideo(entity: LocalVideoEntity) {
        val remaining = if (isFavoriteMode) {
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
