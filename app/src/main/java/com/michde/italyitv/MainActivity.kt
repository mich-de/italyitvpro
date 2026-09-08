package com.michde.italyitv

import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.michde.italyitv.data.remote.DliveResolver
import com.michde.italyitv.ui.AppViewModel
import com.michde.italyitv.ui.channels.ChannelsScreen
import com.michde.italyitv.ui.player.PlayerScreen
import com.michde.italyitv.ui.player.WebPlayerScreen
import com.michde.italyitv.ui.settings.SettingsScreen
import com.michde.italyitv.ui.splash.SplashScreen
import com.michde.italyitv.ui.theme.AppTheme

sealed interface Screen {
    data object Splash : Screen
    data object Channels : Screen
    data class Player(val channelKey: String) : Screen
    data object Settings : Screen
}

class MainActivity : ComponentActivity() {

    private var autoPip = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                val app = application as App
                val vm: AppViewModel = viewModel(
                    factory = AppViewModel.Factory(app.container.repository, app.container.settings)
                )
                val stack = remember { mutableStateListOf<Screen>(Screen.Splash) }
                val current = stack.last()

                fun go(s: Screen) { stack.add(s) }
                fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else finish() }

                BackHandler(enabled = current !is Screen.Splash) {
                    if (current is Screen.Player) autoPip = false
                    back()
                }

                AnimatedContent(
                    targetState = current,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    label = "screen",
                ) { s ->
                    when (s) {
                        is Screen.Splash -> SplashScreen(vm) {
                            stack.clear(); stack.add(Screen.Channels)
                        }
                        is Screen.Channels -> ChannelsScreen(
                            vm = vm,
                            onOpenChannel = { go(Screen.Player(it.key)) },
                            onOpenSettings = { go(Screen.Settings) },
                        )
                        is Screen.Settings -> SettingsScreen(vm) { back() }
                        is Screen.Player -> {
                            val ch = vm.channel(s.channelKey)
                            if (ch != null && DliveResolver.isDlive(ch.url)) {
                                WebPlayerScreen(
                                    vm = vm,
                                    channelKey = s.channelKey,
                                    onBack = { autoPip = false; back() },
                                    onSetAutoPip = { autoPip = it },
                                    onEnterPipNow = { enterPip() },
                                )
                            } else {
                                PlayerScreen(
                                    vm = vm,
                                    channelKey = s.channelKey,
                                    onBack = { autoPip = false; back() },
                                    onSetAutoPip = { autoPip = it },
                                    onEnterPipNow = { enterPip() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature("android.software.picture_in_picture")
        ) {
            runCatching {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                )
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (autoPip) enterPip()
    }
}
