package io.github.daisukikaffuchino.han1meviewer.ui.navigation.main

import android.content.Intent
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import kotlinx.serialization.json.Json

/**
 * 未登录时拦截的抽屉入口。
 * 订阅始终需要登录；本地化功能未开启时，收藏 / 稍后观看 / 播放清单
 * 也由抽屉直接拦截（弹门禁对话框），而不是导航后由路由层拦截。
 */
private fun loginRequiredDrawerItems(): Set<MainDrawerDestination> {
    val items = mutableSetOf(MainDrawerDestination.Subscription)
    if (!SettingsRepository.isLocalMylistEnabled) {
        items += MainDrawerDestination.FavVideo
        items += MainDrawerDestination.WatchLater
        items += MainDrawerDestination.Playlist
    }
    return items
}

const val EXTRA_OPEN_DAILY_CHECK_IN = "openDailyCheckIn"
const val ACTION_OPEN_CLOUDFLARE_VERIFICATION =
    "io.github.daisukikaffuchino.han1meviewer.action.OPEN_CLOUDFLARE_VERIFICATION"
const val EXTRA_CLOUDFLARE_URL = "cloudflare_url"
const val EXTRA_CLOUDFLARE_HOST = "cloudflare_host"

fun TopLevelBackStack<HanimeScreen>.navigateDrawerDestination(
    destination: MainDrawerDestination,
    isLoggedIn: Boolean,
    onRequireLogin: (MainDrawerDestination) -> Unit,
): Boolean {
    if (destination in loginRequiredDrawerItems() && !isLoggedIn) {
        onRequireLogin(destination)
        return false
    }

    addTopLevel(destination.route)
    return true
}

fun TopLevelBackStack<HanimeScreen>.handleMainIntent(intent: Intent) {
    if (intent.action == ACTION_OPEN_CLOUDFLARE_VERIFICATION) {
        val url = intent.getStringExtra(EXTRA_CLOUDFLARE_URL)
        val host = intent.getStringExtra(EXTRA_CLOUDFLARE_HOST)
        intent.removeExtra(EXTRA_CLOUDFLARE_URL)
        intent.removeExtra(EXTRA_CLOUDFLARE_HOST)
        intent.action = null
        if (!url.isNullOrBlank() && !host.isNullOrBlank()) {
            add(CloudflareRoute(url = url, host = host), launchSingleTop = true)
        }
        return
    }

    intent.data?.let { uri ->
        when (uri.scheme) {
            "http", "https" -> {
                val videoCode = uri.getQueryParameter("v")
                if (videoCode != null) {
                    add(VideoRoute(videoCode))
                    return
                }
            }

            "file", "content" -> {
                add(VideoRoute("-1", uri.toString()))
                return
            }
        }
    }

    if (intent.getBooleanExtra(EXTRA_OPEN_DAILY_CHECK_IN, false)) {
        intent.removeExtra(EXTRA_OPEN_DAILY_CHECK_IN)
        add(DailyCheckInRoute, launchSingleTop = true)
        return
    }

    intent.getStringExtra("startSearchFromTag")?.let { tag ->
        intent.removeExtra("startSearchFromTag")
        add(SearchRoute(query = tag))
        return
    }

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    val map = intent.getSerializableExtra("startSearchFromMap") as? HashMap<String, String>
    if (map != null) {
        intent.removeExtra("startSearchFromMap")
        add(SearchRoute(advancedSearchJson = Json.encodeToString(map)))
        return
    }

    val videoCode = intent.getStringExtra("startVideoCode")
    if (!videoCode.isNullOrEmpty()) {
        intent.removeExtra("startVideoCode")
        add(VideoRoute(videoCode))
    }
}
