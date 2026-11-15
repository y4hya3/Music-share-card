package com.example

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.repository.MediaNotificationRepository
import com.example.service.SpotifyAuthManager
import com.example.ui.BitChordShareScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK)
        )
        handleSpotifyRedirectIfPresent(intent)

        setContent {
            MyApplicationTheme(darkTheme = true) {
                var showLaunchSplash by remember { mutableStateOf(true) }
                val logoScale = remember { Animatable(0.88f) }
                val logoAlpha = remember { Animatable(0f) }

                LaunchedEffect(Unit) {
                    launch {
                        logoAlpha.animateTo(1f, animationSpec = tween(260))
                    }
                    launch {
                        logoScale.animateTo(
                            1f,
                            animationSpec = tween(380, easing = FastOutSlowInEasing)
                        )
                    }
                    delay(850)
                    showLaunchSplash = false
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color.Black
                    ) {
                        BitChordShareScreen()
                    }

                    AnimatedVisibility(
                        visible = showLaunchSplash,
                        exit = fadeOut(animationSpec = tween(280))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black)
                                .testTag("echosnap_black_splash"),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_echosnap_splash_mark),
                                contentDescription = "EchoSnap",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .size(116.dp)
                                    .scale(logoScale.value)
                                    .alpha(logoAlpha.value)
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSpotifyRedirectIfPresent(intent)
    }

    override fun onResume() {
        super.onResume()
        MediaNotificationRepository.refreshFromActiveMedia(this)
    }

    private fun handleSpotifyRedirectIfPresent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "echosnap" && data.host == "spotify-callback") {
            lifecycleScope.launch {
                val result = SpotifyAuthManager.handlePkceCallbackUri(this@MainActivity, data)
                if (result != null) {
                    val label = SpotifyAuthManager.fetchSpotifyProfileLabel(result.accessToken)
                    MediaNotificationRepository.applySpotifyLoginResult(
                        result.copy(accountLabel = label.ifBlank { "Spotify OAuth" })
                    )
                    Toast.makeText(this@MainActivity, "Spotify connected!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Spotify login cancelled or failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
