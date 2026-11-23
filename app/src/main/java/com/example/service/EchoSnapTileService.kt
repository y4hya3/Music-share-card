package com.example.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.repository.MediaNotificationRepository

/**
 * Android Quick Settings Tile allowing 1-tap toggle of EchoSnap Live Wallpaper
 * directly from the system shade without opening the app.
 */
class EchoSnapTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        MediaNotificationRepository.ensureInitialized(applicationContext)
        syncTileUi()
    }

    override fun onClick() {
        super.onClick()
        MediaNotificationRepository.ensureInitialized(applicationContext)
        val nextEnabled = !MediaNotificationRepository.styleConfig.value.liveWallpaperEnabled
        MediaNotificationRepository.updateStyleConfig(saveImmediately = true) { current ->
            current.copy(liveWallpaperEnabled = nextEnabled)
        }
        MediaNotificationRepository.flushStylePreferences()
        if (nextEnabled) {
            MediaNotificationRepository.triggerWallpaperMediaSync(
                context = applicationContext,
                forceCanvasRetry = true
            )
        }
        syncTileUi()
    }

    private fun syncTileUi() {
        val tile = qsTile ?: return
        val enabled = MediaNotificationRepository.styleConfig.value.liveWallpaperEnabled
        val track = MediaNotificationRepository.currentTrack.value
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val subtitleText = when {
            !enabled -> "Off"
            !track.isSample && track.title.isNotBlank() -> track.title
            else -> "Active"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitleText
        }
        tile.updateTile()
    }
}
