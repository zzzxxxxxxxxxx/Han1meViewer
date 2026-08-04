package io.github.daisukikaffuchino.han1meviewer.ui.navigation.main

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.daisukikaffuchino.han1meviewer.logic.MylistSyncManager
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.ui.activity.MainActivity
import io.github.daisukikaffuchino.han1meviewer.ui.navigation.settings.HomeSettingsRoute
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.VideoGridScreen
import io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.MyListViewModel
import io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.mylist.LocalVideoListViewModel

/**
 * 未登录且未开启本地化功能时的门禁提示：
 * 引导用户去登录，或去设置开启实验性的本地化功能。
 */
@Composable
fun LocalMylistGateScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text(stringResource(R.string.login_required)) },
        text = { Text(stringResource(R.string.local_mylist_gate_message)) },
        confirmButton = {
            TextButton(
                onClick = { (context as? MainActivity)?.mainBackStack?.add(LoginRoute) }
            ) {
                Text(stringResource(R.string.go_to_login))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { (context as? MainActivity)?.mainBackStack?.add(HomeSettingsRoute) }
            ) {
                Text(stringResource(R.string.go_to_settings))
            }
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
fun FavVideoRouteScreen(
    onBack: () -> Unit,
    onNavigateToVideo: (String) -> Unit,
) {
    MyVideoListRoute(
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
    MyVideoListRoute(
        isFavoriteMode = false,
        onBack = onBack,
        onNavigateToVideo = onNavigateToVideo,
    )
}

/**
 * 收藏 / 稍后观看列表路由的三分支：
 * - 未登录且未开启本地化 → 门禁提示（去登录 / 去设置）
 * - 已登录且未开启本地化 → 纯在线分页（与上游一致）
 * - 已开启本地化 → 本地数据库数据源（已登录时触发同步）
 */
@Composable
private fun MyVideoListRoute(
    isFavoriteMode: Boolean,
    onBack: () -> Unit,
    onNavigateToVideo: (String) -> Unit,
) {
    val settings by SettingsRepository.settings.collectAsStateWithLifecycle()
    LaunchedEffect(settings.isAlreadyLogin, settings.enableLocalMylist) {
        if (settings.isAlreadyLogin && settings.enableLocalMylist) MylistSyncManager.sync()
    }
    when {
        !settings.isAlreadyLogin && !settings.enableLocalMylist ->
            LocalMylistGateScreen(onBack = onBack)

        !settings.enableLocalMylist ->
            if (isFavoriteMode) {
                OnlineFavVideoRouteScreen(onBack = onBack, onNavigateToVideo = onNavigateToVideo)
            } else {
                OnlineWatchLaterRouteScreen(onBack = onBack, onNavigateToVideo = onNavigateToVideo)
            }

        else ->
            LocalVideoListRoute(
                isFavoriteMode = isFavoriteMode,
                onBack = onBack,
                onNavigateToVideo = onNavigateToVideo,
            )
    }
}

@Composable
private fun OnlineFavVideoRouteScreen(
    onBack: () -> Unit,
    onNavigateToVideo: (String) -> Unit,
) {
    val viewModel: MyListViewModel = viewModel()
    val fav = viewModel.fav
    val items = fav.favVideoFlow.collectAsStateWithLifecycle().value
    val state = fav.favVideoStateFlow.collectAsStateWithLifecycle().value
    val loadedPageCount = fav.loadedPageCount.collectAsStateWithLifecycle().value
    val isLoadingMore = fav.isLoadingMore.collectAsStateWithLifecycle().value

    VideoGridScreen(
        items = items,
        state = state,
        deleteStateFlow = fav.deleteMyFavVideoFlow,
        loadedPageCount = loadedPageCount,
        isLoadingMore = isLoadingMore,
        titleRes = R.string.fav_video,
        helpMessageRes = R.string.long_press_to_cancel_fav,
        deleteTitleRes = R.string.delete_fav,
        onBack = onBack,
        onOpenVideo = { onNavigateToVideo(it.videoCode) },
        onDeleteItem = { item ->
            val position = items.indexOfFirst { it.videoCode == item.videoCode }
            if (position >= 0) fav.deleteMyFavVideo(item.videoCode, position)
        },
        onRefresh = {
            fav.favVideoPage = 1
            fav.clearMyListItems()
            fav.getMyFavVideoItems(SettingsRepository.savedUserId, 1)
            fav.favVideoPage = 2
        },
        onLoadMore = {
            val page = fav.favVideoPage
            fav.getMyFavVideoItems(SettingsRepository.savedUserId, page)
            fav.favVideoPage = page + 1
        },
    )
}

@Composable
private fun OnlineWatchLaterRouteScreen(
    onBack: () -> Unit,
    onNavigateToVideo: (String) -> Unit,
) {
    val viewModel: MyListViewModel = viewModel()
    val wl = viewModel.watchLater
    val items = wl.watchLaterFlow.collectAsStateWithLifecycle().value
    val state = wl.watchLaterStateFlow.collectAsStateWithLifecycle().value
    val loadedPageCount = wl.loadedPageCount.collectAsStateWithLifecycle().value
    val isLoadingMore = wl.isLoadingMore.collectAsStateWithLifecycle().value

    VideoGridScreen(
        items = items,
        state = state,
        deleteStateFlow = wl.deleteMyWatchLaterFlow,
        loadedPageCount = loadedPageCount,
        isLoadingMore = isLoadingMore,
        titleRes = R.string.watch_later,
        helpMessageRes = R.string.long_press_to_cancel_watch_later,
        deleteTitleRes = R.string.delete_watch_later,
        onBack = onBack,
        onOpenVideo = { onNavigateToVideo(it.videoCode) },
        onDeleteItem = { item ->
            val position = items.indexOfFirst { it.videoCode == item.videoCode }
            if (position >= 0) wl.deleteMyWatchLater(item.videoCode, position)
        },
        onRefresh = {
            wl.watchLaterPage = 1
            wl.clearMyListItems()
            wl.getMyWatchLaterItems(1)
            wl.watchLaterPage = 2
        },
        onLoadMore = {
            val page = wl.watchLaterPage
            wl.getMyWatchLaterItems(page)
            wl.watchLaterPage = page + 1
        },
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
