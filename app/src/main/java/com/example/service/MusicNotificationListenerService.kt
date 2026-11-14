package com.example.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import com.example.model.MediaTrackInfo
import com.example.repository.MediaNotificationRepository
import com.example.util.BitmapBlurUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Module 1: Notification Listener & MediaSession Listener Service
 * Listens for active media notifications AND MediaSession metadata changes across any
 * music player (Spotify, YouTube Music, Apple Music, Samsung Music, SoundCloud, etc.),
 * automatically replacing track metadata, cover art, and adaptive palette colors.
 */
class MusicNotificationListenerService : NotificationListenerService() {

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        attachSessionListener(applicationContext)
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        detachAllControllerCallbacks()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeInstance = this
        Log.d(TAG, "NotificationListenerService connected. Syncing active media...")
        attachSessionListener(applicationContext)
        syncNow(applicationContext)
    }

    override fun onListenerDisconnected() {
        if (activeInstance === this) {
            activeInstance = null
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        if (isMediaNotification(sbn)) {
            processNotification(this, sbn)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return
        val currentTrack = MediaNotificationRepository.currentTrack.value
        if (currentTrack.packageName == sbn.packageName && !currentTrack.isSample) {
            Log.d(TAG, "Active music notification removed for package: ${sbn.packageName}")
            syncNow(applicationContext)
        }
    }

    companion object {
        private const val TAG = "BitChordMediaListener"

        @Volatile
        private var activeInstance: MusicNotificationListenerService? = null

        private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val registeredCallbacks = ConcurrentHashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
        private var sessionsListenerRegistered = false
        private var sessionManagerRef: MediaSessionManager? = null

        private val activeSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            val appCtx = activeInstance?.applicationContext ?: return@OnActiveSessionsChangedListener
            controllers?.let {
                registerControllerCallbacks(appCtx, it)
                processControllersList(appCtx, it)
            }
        }

        fun isNotificationAccessGranted(context: Context): Boolean {
            val packageName = context.packageName
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )
            if (flat != null && flat.contains(packageName)) {
                return true
            }
            return NotificationManagerCompat.getEnabledListenerPackages(context).contains(packageName)
        }

        fun openNotificationAccessSettings(context: Context) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }

        fun rebindIfPossible(context: Context) {
            try {
                val component = ComponentName(context, MusicNotificationListenerService::class.java)
                requestRebind(component)
            } catch (e: Exception) {
                Log.w(TAG, "Rebind not available or failed", e)
            }
        }

        /**
         * Immediately scans active MediaSessions and active notifications so cover art
         * and track info update automatically on launch, resume, or manual refresh.
         * Returns true if an active media track was found and synced.
         */
        fun syncNow(context: Context): Boolean {
            val appCtx = context.applicationContext
            if (!isNotificationAccessGranted(appCtx)) {
                return false
            }

            attachSessionListener(appCtx)

            // 1. Scan active MediaSession controllers directly (works even if notification wasn't re-posted)
            var foundTrack = scanActiveMediaSessions(appCtx)

            // 2. Also scan active StatusBarNotifications if service is bound (provides notification largeIcon fallback)
            val service = activeInstance
            if (service != null) {
                val notifFound = scanServiceNotifications(service)
                if (notifFound) {
                    foundTrack = true
                }
            } else {
                rebindIfPossible(appCtx)
            }

            return foundTrack
        }

        private fun attachSessionListener(context: Context) {
            if (sessionsListenerRegistered || !isNotificationAccessGranted(context)) return
            try {
                val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                    ?: return
                val component = ComponentName(context, MusicNotificationListenerService::class.java)
                manager.addOnActiveSessionsChangedListener(activeSessionsListener, component)
                sessionManagerRef = manager
                sessionsListenerRegistered = true

                val initial = manager.getActiveSessions(component)
                registerControllerCallbacks(context, initial)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to attach OnActiveSessionsChangedListener", e)
            }
        }

        private fun detachAllControllerCallbacks() {
            registeredCallbacks.values.forEach { (controller, callback) ->
                try {
                    controller.unregisterCallback(callback)
                } catch (_: Exception) {
                }
            }
            registeredCallbacks.clear()
        }

        private fun registerControllerCallbacks(context: Context, controllers: List<MediaController>) {
            val activeTokens = controllers.map { it.sessionToken }.toSet()

            // Remove stale callbacks
            val iterator = registeredCallbacks.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (!activeTokens.contains(entry.key)) {
                    try {
                        entry.value.first.unregisterCallback(entry.value.second)
                    } catch (_: Exception) {
                    }
                    iterator.remove()
                }
            }

            // Register callback on each new active controller so song & cover changes fire automatically
            for (controller in controllers) {
                if (registeredCallbacks.containsKey(controller.sessionToken)) continue
                val appCtx = context.applicationContext
                val callback = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        extractFromController(appCtx, controller, notificationBitmapFallback = null)
                    }

                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        if (state?.state == PlaybackState.STATE_PLAYING) {
                            extractFromController(appCtx, controller, notificationBitmapFallback = null)
                        }
                    }
                }
                try {
                    controller.registerCallback(callback)
                    registeredCallbacks[controller.sessionToken] = Pair(controller, callback)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to register MediaController callback", e)
                }
            }
        }

        private fun scanActiveMediaSessions(context: Context): Boolean {
            return try {
                val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                    ?: return false
                val component = ComponentName(context, MusicNotificationListenerService::class.java)
                val controllers = manager.getActiveSessions(component)
                registerControllerCallbacks(context, controllers)
                processControllersList(context, controllers)
            } catch (e: Exception) {
                Log.w(TAG, "Error querying active MediaSessions", e)
                false
            }
        }

        private fun processControllersList(context: Context, controllers: List<MediaController>): Boolean {
            if (controllers.isEmpty()) return false

            // Prefer actively playing session first
            val sorted = controllers.sortedByDescending { controller ->
                when (controller.playbackState?.state) {
                    PlaybackState.STATE_PLAYING -> 2
                    PlaybackState.STATE_BUFFERING -> 1
                    else -> 0
                }
            }

            for (controller in sorted) {
                val handled = extractFromController(context, controller, notificationBitmapFallback = null)
                if (handled) return true
            }
            return false
        }

        private fun scanServiceNotifications(service: MusicNotificationListenerService): Boolean {
            return try {
                val activeNotifs = service.activeNotifications ?: return false
                // Prioritize notifications whose MediaController is actively playing
                for (sbn in activeNotifs) {
                    if (isMediaNotification(sbn)) {
                        if (processNotification(service, sbn)) {
                            return true
                        }
                    }
                }
                false
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning active notifications", e)
                false
            }
        }

        private fun processNotification(context: Context, sbn: StatusBarNotification): Boolean {
            val notification = sbn.notification ?: return false
            val extras = notification.extras ?: return false
            val notifFallbackArt = extractNotificationArtwork(context, notification)

            val token = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
            }

            if (token != null) {
                try {
                    val controller = MediaController(context, token)
                    registerControllerCallbacks(context, listOf(controller) + registeredCallbacks.values.map { it.first })
                    if (extractFromController(context, controller, notificationBitmapFallback = notifFallbackArt)) {
                        return true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not extract metadata via MediaController", e)
                }
            }

            // Pure Notification extras fallback (for players without standard MediaSession metadata)
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            if (title.isNullOrBlank()) return false

            val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            val appName = resolveAppLabel(context, sbn.packageName)

            val trackInfo = MediaTrackInfo(
                title = title,
                artist = artist ?: appName,
                album = "",
                packageName = sbn.packageName,
                appName = appName,
                albumArt = notifFallbackArt,
                isPlaying = true,
                isSample = false
            )
            MediaNotificationRepository.updateTrack(trackInfo)
            return true
        }

        private fun extractFromController(
            context: Context,
            controller: MediaController,
            notificationBitmapFallback: Bitmap?
        ): Boolean {
            val metadata = controller.metadata ?: return false
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            if (title.isNullOrBlank()) return false

            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_AUTHOR)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_WRITER)
            val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
            val isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING

            val rawArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                ?: notificationBitmapFallback
                ?: findNotificationArtworkForPackage(context, controller.packageName)

            val safeArt = BitmapBlurUtils.toSafeSoftwareBitmap(rawArt, maxDimension = 1024)
            val appName = resolveAppLabel(context, controller.packageName)
            val resolvedArtist = artist?.takeIf { it.isNotBlank() } ?: appName

            val trackInfo = MediaTrackInfo(
                title = title,
                artist = resolvedArtist,
                album = album,
                packageName = controller.packageName,
                appName = appName,
                albumArt = safeArt,
                isPlaying = isPlaying,
                isSample = false
            )

            Log.d(TAG, "Synced track: '${trackInfo.title}' by '${trackInfo.artist}' (hasArt=${safeArt != null})")
            MediaNotificationRepository.updateTrack(trackInfo)

            // If bitmap wasn't embedded directly, load from METADATA_KEY_*_URI asynchronously
            if (safeArt == null) {
                val artUri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                if (!artUri.isNullOrBlank()) {
                    loadArtFromUriAsync(
                        context = context.applicationContext,
                        uriString = artUri,
                        packageName = controller.packageName,
                        title = title,
                        artist = resolvedArtist
                    )
                }
            }

            return true
        }

        private fun findNotificationArtworkForPackage(context: Context, packageName: String): Bitmap? {
            val service = activeInstance ?: return null
            return try {
                val notifs = service.activeNotifications ?: return null
                for (sbn in notifs) {
                    if (sbn.packageName == packageName && isMediaNotification(sbn)) {
                        val art = extractNotificationArtwork(context, sbn.notification)
                        if (art != null) return art
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }

        private fun loadArtFromUriAsync(
            context: Context,
            uriString: String,
            packageName: String,
            title: String,
            artist: String
        ) {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val safe: Bitmap? = when {
                        uriString.startsWith("http://") || uriString.startsWith("https://") -> {
                            val conn = (URL(uriString).openConnection() as HttpURLConnection).apply {
                                connectTimeout = 5000
                                readTimeout = 5000
                                instanceFollowRedirects = true
                            }
                            try {
                                conn.inputStream.use {
                                    BitmapBlurUtils.decodeSampledFromStream(it, maxDimension = 720)
                                }
                            } finally {
                                conn.disconnect()
                            }
                        }
                        else -> {
                            val uri = Uri.parse(uriString)
                            BitmapBlurUtils.decodeSampledFromUri(context, uri, maxDimension = 720)
                        }
                    }
                    if (safe != null) {
                        MediaNotificationRepository.updateTrackArtworkIfMatching(
                            packageName = packageName,
                            title = title,
                            artist = artist,
                            albumArt = safe
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load album art URI: $uriString", e)
                }
            }
        }

        private fun isMediaNotification(sbn: StatusBarNotification): Boolean {
            val notification = sbn.notification ?: return false
            val extras = notification.extras ?: return false

            val hasMediaSession = extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
            val isTransport = notification.category == Notification.CATEGORY_TRANSPORT
            val template = extras.getString(Notification.EXTRA_TEMPLATE)
            val isMediaStyle = template != null && template.contains("MediaStyle", ignoreCase = true)

            return hasMediaSession || isTransport || isMediaStyle
        }

        private fun extractNotificationArtwork(context: Context, notification: Notification): Bitmap? {
            return try {
                val largeIcon: Icon? = notification.getLargeIcon()
                val raw = if (largeIcon != null) {
                    largeIcon.loadDrawable(context)?.toBitmap()
                } else {
                    val extras = notification.extras
                    @Suppress("DEPRECATION")
                    val extraBig = extras?.get(Notification.EXTRA_LARGE_ICON_BIG)
                    @Suppress("DEPRECATION")
                    val extraStd = extras?.get(Notification.EXTRA_LARGE_ICON)
                    @Suppress("DEPRECATION")
                    val extraPic = extras?.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)

                    when (extraBig) {
                        is Bitmap -> extraBig
                        is Icon -> extraBig.loadDrawable(context)?.toBitmap()
                        else -> when (extraStd) {
                            is Bitmap -> extraStd
                            is Icon -> extraStd.loadDrawable(context)?.toBitmap()
                            else -> extraPic
                        }
                    }
                }
                BitmapBlurUtils.toSafeSoftwareBitmap(raw, maxDimension = 1024)
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting notification artwork", e)
                null
            }
        }

        private fun resolveAppLabel(context: Context, packageName: String): String {
            return try {
                val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
                context.packageManager.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                packageName
            }
        }
    }
}
