package io.github.daisukikaffuchino.han1meviewer.ui.navigation.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.getHanimeShareText
import io.github.daisukikaffuchino.han1meviewer.logic.MylistSyncManager
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.myplaylist.PlaylistScreen
import io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.MyPlayListViewModel
import io.github.daisukikaffuchino.utils.rememberCopyTextToClipboard
import io.github.daisukikaffuchino.utils.SonnerToast

@Composable
fun MyPlaylistRouteScreen(
    onBack: () -> Unit,
    onNavigateToVideo: (String) -> Unit,
) {
    val viewModel: MyPlayListViewModel = viewModel()
    val copyTextToClipboard = rememberCopyTextToClipboard()
    val isLoggedIn by SettingsRepository.loginStateFlow.collectAsStateWithLifecycle()
    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn) MylistSyncManager.sync()
    }
    PlaylistScreen(
        viewModel = viewModel,
        navigateBack = onBack,
        onClickItem = onNavigateToVideo,
        onLongClickItem = { videoCode, title ->
            copyTextToClipboard(getHanimeShareText(title, videoCode))
            SonnerToast.success(R.string.copy_to_clipboard)
        },
    )
}
