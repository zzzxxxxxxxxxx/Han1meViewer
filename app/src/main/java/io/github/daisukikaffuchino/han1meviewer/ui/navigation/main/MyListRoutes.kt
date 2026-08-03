package io.github.daisukikaffuchino.han1meviewer.ui.navigation.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.daisukikaffuchino.han1meviewer.logic.MylistSyncManager
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.VideoGridScreen
import io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.mylist.LocalVideoListViewModel

@Composable
fun FavVideoRouteScreen(
    onBack: () -> Unit,
    onNavigateToVideo: (String) -> Unit,
) {
    val isLoggedIn by SettingsRepository.loginStateFlow.collectAsStateWithLifecycle()
    LaunchedEffect(isLoggedIn) {
        // 进入页面时与云端同步一次（未登录自动跳过），本地数据库更新后页面自动刷新
        if (isLoggedIn) MylistSyncManager.sync()
    }
    LocalVideoListRoute(
        isFavoriteMode = true,
        onBack = onBack,
        onNavigateToVideo = onNavigateToVideo,
    )
}

@Composable
fun WatchLaterRouteScreen(
    onBack: () -> Unit,
    onNavigateToVideo: (String) -> Unit,
) {
    val isLoggedIn by SettingsRepository.loginStateFlow.collectAsStateWithLifecycle()
    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn) MylistSyncManager.sync()
    }
    LocalVideoListRoute(
        isFavoriteMode = false,
        onBack = onBack,
        onNavigateToVideo = onNavigateToVideo,
    )
}

@Composable
private fun LocalVideoListRoute(
    isFavoriteMode: Boolean,
    onBack: () -> Unit,
    onNavigateToVideo: (String) -> Unit,
) {
    val viewModel: LocalVideoListViewModel = viewModel {
        LocalVideoListViewModel(isFavoriteMode)
    }
    val items by viewModel.items.collectAsStateWithLifecycle()
    val state by viewModel.itemsStateFlow.collectAsStateWithLifecycle()
    val deleteStateFlow = viewModel.deleteFlow
    val loadedPageCount by viewModel.loadedPageCount.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()

    VideoGridScreen(
        items = items,
        state = state,
        deleteStateFlow = deleteStateFlow,
        loadedPageCount = loadedPageCount,
        isLoadingMore = isLoadingMore,
        titleRes = if (isFavoriteMode) R.string.fav_video else R.string.watch_later,
        helpMessageRes = if (isFavoriteMode) {
            R.string.long_press_to_cancel_fav
        } else {
            R.string.long_press_to_cancel_watch_later
        },
        deleteTitleRes = if (isFavoriteMode) R.string.delete_fav else R.string.delete_watch_later,
        onBack = onBack,
        onOpenVideo = { onNavigateToVideo(it.videoCode) },
        onDeleteItem = { item ->
            val position = items.indexOfFirst { it.videoCode == item.videoCode }
            if (position >= 0) viewModel.deleteItem(item.videoCode, position)
        },
        onRefresh = { viewModel.refresh() },
        onLoadMore = { viewModel.loadMore() },
    )
}
