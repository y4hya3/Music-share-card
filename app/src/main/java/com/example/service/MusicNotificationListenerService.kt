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

            // 2. Fallback to active StatusBarNotifications only if no active MediaSession yielded a track
            val service = activeInstance
            if (service != null) {
                if (!foundTrack) {
                    foundTrack = scanServiceNotifications(service)
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
                    val current = MediaNotificationRepository.currentTrack.value
                    val isThisPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING
                    // Don't let a paused background player hijack an actively playing session from another app
                    if (!current.isSample && current.isPlaying && current.packageName != controller.packageName && !isThisPlaying) {
                        return false
                    }
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

            val candidateBitmaps = listOfNotNull(
                metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART),
                metadata.getBitmap(MediaMetadata.METADATA_KEY_ART),
                metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
                notificationBitmapFallback,
                findNotificationArtworkForPackage(context, controller.packageName)
            ).filter { !it.isRecycled && it.width > 0 && it.height > 0 }

            val bestRawArt = candidateBitmaps.maxByOrNull { it.width.toLong() * it.height.toLong() }
            val safeArt = BitmapBlurUtils.toSafeSoftwareBitmap(bestRawArt, maxDimension = 1024)?.let {
                BitmapBlurUtils.trimLetterboxBarsIfPresent(it)
            }

            val appName = resolveAppLabel(context, controller.packageName)
            val resolvedArtist = artist?.takeIf { it.isNotBlank() } ?: appName
            val rawMediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
                ?: metadata.getString("com.spotify.music.extra.TRACK_URI")
            val spotifyTrackId = AnimatedCanvasSearchService.extractSpotifyTrackId(
                rawInput = rawMediaId,
                packageNameHint = controller.packageName
            )

            val trackInfo = MediaTrackInfo(
                title = title,
                artist = resolvedArtist,
                album = album,
                packageName = controller.packageName,
                appName = appName,
                albumArt = safeArt,
                isPlaying = isPlaying,
                isSample = false,
                spotifyTrackId = spotifyTrackId
            )

            Log.d(
                TAG,
                "Synced track: '${trackInfo.title}' by '${trackInfo.artist}' (art=${safeArt?.width ?: 0}x${safeArt?.height ?: 0})"
            )
            MediaNotificationRepository.updateTrack(trackInfo)

            // Fetch high-resolution art from METADATA_KEY_*_URI whenever embedded art is missing OR low-res (< 600px)
            val repoTrack = MediaNotificationRepository.currentTrack.value
            val repoHasHighResForSameTrack = !repoTrack.isSample &&
                repoTrack.packageName == controller.packageName &&
                repoTrack.title == title &&
                repoTrack.artist == resolvedArtist &&
                maxOf(repoTrack.albumArt?.width ?: 0, repoTrack.albumArt?.height ?: 0) >= 600

            val bestMaxDim = maxOf(safeArt?.width ?: 0, safeArt?.height ?: 0)
            if (!repoHasHighResForSameTrack && (safeArt == null || bestMaxDim < 600)) {
                val uris = listOfNotNull(
                    metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
                    metadata.getString(MediaMetadata.METADATA_KEY_ART_URI),
                    metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                ).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

                if (uris.isNotEmpty()) {
                    loadArtFromUrisAsync(
                        context = context.applicationContext,
                        uriStrings = uris,
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

        private fun loadArtFromUrisAsync(
            context: Context,
            uriStrings: List<String>,
            packageName: String,
            title: String,
            artist: String
        ) {
            serviceScope.launch(Dispatchers.IO) {
                var bestUriBitmap: Bitmap? = null
                var bestPixels = 0L

                for (uriString in uriStrings) {
                    try {
                        val decoded = fetchBitmapFromUriString(context, uriString)?.let {
                            BitmapBlurUtils.trimLetterboxBarsIfPresent(it)
                        }
                        if (decoded != null && !decoded.isRecycled) {
                            val pixels = decoded.width.toLong() * decoded.height.toLong()
                            if (pixels > bestPixels) {
                                if (bestUriBitmap != null && bestUriBitmap !== decoded && !bestUriBitmap.isRecycled) {
                                    bestUriBitmap.recycle()
                                }
                                bestUriBitmap = decoded
                                bestPixels = pixels
                            } else if (decoded !== bestUriBitmap) {
                                decoded.recycle()
                            }
                            if (maxOf(decoded.width, decoded.height) >= 600) {
                                break
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to load album art URI: $uriString", e)
                    }
                }

                if (bestUriBitmap != null) {
                    MediaNotificationRepository.updateTrackArtworkIfMatching(
                        packageName = packageName,
                        title = title,
                        artist = artist,
                        albumArt = bestUriBitmap
                    )
                }
            }
        }

        private fun fetchBitmapFromUriString(context: Context, uriString: String): Bitmap? {
            return if (uriString.startsWith("http://") || uriString.startsWith("https://")) {
                var currentUrl = uriString
                var redirects = 0
                while (redirects < 4) {
                    val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 6000
                        readTimeout = 6000
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", "BitChord/1.0 Android")
                    }
                    try {
                        val code = conn.responseCode
                        if (code in 300..399) {
                            val location = conn.getHeaderField("Location") ?: break
                            currentUrl = URL(URL(currentUrl), location).toString()
                            redirects++
                            continue
                        }
                        if (code in 200..299) {
                            return conn.inputStream.use {
                                BitmapBlurUtils.decodeSampledFromStream(it, maxDimension = 1024)
                            }
                        }
                        return null
                    } finally {
                        conn.disconnect()
                    }
                }
                null
            } else {
                val uri = Uri.parse(uriString)
                BitmapBlurUtils.decodeSampledFromUri(context, uri, maxDimension = 1024)
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
                val extras = notification.extras
                val candidates = mutableListOf<Bitmap>()

                fun addDrawableFromIcon(icon: Icon?) {
                    val drawable = icon?.loadDrawable(context) ?: return
                    if (drawable is android.graphics.drawable.BitmapDrawable && drawable.bitmap != null) {
                        candidates.add(drawable.bitmap)
                    } else {
                        val w = drawable.intrinsicWidth.coerceAtLeast(128)
                        val h = drawable.intrinsicHeight.coerceAtLeast(128)
                        candidates.add(drawable.toBitmap(width = w, height = h, config = Bitmap.Config.ARGB_8888))
                    }
                }

                addDrawableFromIcon(notification.getLargeIcon())

                @Suppress("DEPRECATION")
                val extraBig = extras?.get(Notification.EXTRA_LARGE_ICON_BIG)
                when (extraBig) {
                    is Bitmap -> candidates.add(extraBig)
                    is Icon -> addDrawableFromIcon(extraBig)
                }

                @Suppress("DEPRECATION")
                val extraStd = extras?.get(Notification.EXTRA_LARGE_ICON)
                when (extraStd) {
                    is Bitmap -> candidates.add(extraStd)
                    is Icon -> addDrawableFromIcon(extraStd)
                }

                @Suppress("DEPRECATION")
                val extraPic = extras?.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)
                if (extraPic != null) {
                    candidates.add(extraPic)
                }

                val best = candidates
                    .filter { !it.isRecycled && it.width > 0 && it.height > 0 }
                    .maxByOrNull { it.width.toLong() * it.height.toLong() }

                BitmapBlurUtils.toSafeSoftwareBitmap(best, maxDimension = 1024)?.let {
                    BitmapBlurUtils.trimLetterboxBarsIfPresent(it)
                }
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
