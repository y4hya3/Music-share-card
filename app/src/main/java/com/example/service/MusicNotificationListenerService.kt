package com.example.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.SystemClock
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
import kotlinx.coroutines.withContext
import java.io.File
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
        MediaNotificationRepository.ensureInitialized(applicationContext)
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
        MediaNotificationRepository.ensureInitialized(applicationContext)
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
            val label = resolveAppLabel(this, sbn.packageName)
            MediaNotificationRepository.recordDetectedMediaApp(sbn.packageName, label)
            if (shouldIgnoreMediaPackage(sbn.packageName)) return
            processNotification(this, sbn)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return
        val currentTrack = MediaNotificationRepository.currentTrack.value
        if (currentTrack.packageName == sbn.packageName && !currentTrack.isSample) {
            Log.d(TAG, "Active music notification removed for package: ${sbn.packageName}")
            val stillHasTrack = syncNow(applicationContext)
            val afterTrack = MediaNotificationRepository.currentTrack.value
            if (!stillHasTrack || (afterTrack.packageName == sbn.packageName && !afterTrack.isPlaying)) {
                MediaNotificationRepository.markCurrentTrackStopped()
            }
        }
    }

    companion object {
        private const val TAG = "BitChordMediaListener"

        @Volatile
        private var activeInstance: MusicNotificationListenerService? = null

        @Volatile
        private var cachedAppContext: Context? = null

        private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val registeredCallbacks = ConcurrentHashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
        private val appLabelCache = ConcurrentHashMap<String, String>()
        private val uriArtMemoryCache = android.util.LruCache<String, Bitmap>(12)
        private var sessionsListenerRegistered = false
        private var sessionManagerRef: MediaSessionManager? = null
        private var localUriLoadJob: kotlinx.coroutines.Job? = null
        private var remoteUriLoadJob: kotlinx.coroutines.Job? = null
        private var queuePrewarmJob: kotlinx.coroutines.Job? = null
        @Volatile
        private var lastUriLookupTrackKey: String? = null
        @Volatile
        private var lastQueuePrewarmSignature: String? = null

        private val activeSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            val appCtx = activeInstance?.applicationContext ?: cachedAppContext ?: return@OnActiveSessionsChangedListener
            controllers?.let {
                registerControllerCallbacks(appCtx, it)
                processControllersList(appCtx, it)
            }
        }

        fun hasActiveListenerBinding(): Boolean = activeInstance != null

        fun shouldIgnoreMediaPackage(
            packageName: String?,
            ignoredPackages: Set<String> = MediaNotificationRepository.styleConfig.value.ignoredMediaPackages
        ): Boolean {
            val pkg = packageName?.trim().orEmpty()
            return pkg.isNotEmpty() && pkg in ignoredPackages
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
            cachedAppContext = appCtx
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

        private fun resolveTargetMediaController(context: Context): MediaController? {
            val active = MediaNotificationRepository.currentTrack.value
            val targetPkg = active.packageName.takeIf { !active.isSample && it.isNotBlank() }

            val sessionControllers = if (isNotificationAccessGranted(context)) {
                try {
                    val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                    val component = ComponentName(context, MusicNotificationListenerService::class.java)
                    manager?.getActiveSessions(component).orEmpty()
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }

            val candidates = (sessionControllers + registeredCallbacks.values.map { it.first })
                .distinctBy { it.sessionToken }
                .filterNot { shouldIgnoreMediaPackage(it.packageName) }
            if (candidates.isEmpty()) return null

            if (targetPkg != null) {
                candidates.firstOrNull {
                    it.packageName == targetPkg && it.playbackState?.state == PlaybackState.STATE_PLAYING
                }?.let { return it }
                candidates.firstOrNull { it.packageName == targetPkg }?.let { return it }
            }
            return candidates.maxByOrNull { controller ->
                when (controller.playbackState?.state) {
                    PlaybackState.STATE_PLAYING -> 3
                    PlaybackState.STATE_BUFFERING -> 2
                    PlaybackState.STATE_PAUSED -> 1
                    else -> 0
                }
            }
        }

        fun togglePlayPause(context: Context): Boolean {
            val controller = resolveTargetMediaController(context) ?: return false
            return try {
                val state = controller.playbackState?.state
                if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING) {
                    controller.transportControls.pause()
                } else {
                    controller.transportControls.play()
                }
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to toggle play/pause", e)
                false
            }
        }

        fun skipToNext(context: Context): Boolean {
            val controller = resolveTargetMediaController(context) ?: return false
            return try {
                controller.transportControls.skipToNext()
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to skip to next", e)
                false
            }
        }

        fun skipToPrevious(context: Context): Boolean {
            val controller = resolveTargetMediaController(context) ?: return false
            return try {
                controller.transportControls.skipToPrevious()
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to skip to previous", e)
                false
            }
        }

        fun seekTo(context: Context, positionMs: Long): Boolean {
            val controller = resolveTargetMediaController(context) ?: return false
            return try {
                controller.transportControls.seekTo(positionMs.coerceAtLeast(0L))
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to seek media", e)
                false
            }
        }

        fun sendHeartRatingIfSupported(context: Context, favorited: Boolean): Boolean {
            val controller = resolveTargetMediaController(context) ?: return false
            return try {
                val rating = when (controller.ratingType) {
                    Rating.RATING_HEART -> Rating.newHeartRating(favorited)
                    Rating.RATING_THUMB_UP_DOWN -> Rating.newThumbRating(favorited)
                    Rating.RATING_5_STARS -> Rating.newStarRating(Rating.RATING_5_STARS, if (favorited) 5f else 0f)
                    else -> null
                } ?: return false
                controller.transportControls.setRating(rating)
                true
            } catch (_: Exception) {
                false
            }
        }

        private fun attachSessionListener(context: Context) {
            cachedAppContext = context.applicationContext
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

                    override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) {
                        if (!shouldIgnoreMediaPackage(controller.packageName)) {
                            prewarmUpcomingQueueArt(appCtx, controller, queue)
                        }
                    }

                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        val st = state?.state ?: return
                        val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L)
                            ?: MediaNotificationRepository.playbackProgress.value.durationMs
                        val pos = (state.position).coerceAtLeast(0L)
                        val spd = state.playbackSpeed.let { if (it <= 0f) 1f else it }
                        val upd = state.lastPositionUpdateTime.let { if (it > 0L) it else SystemClock.elapsedRealtime() }
                        val playing = st == PlaybackState.STATE_PLAYING

                        val current = MediaNotificationRepository.currentTrack.value
                        if (!current.isSample && current.packageName == controller.packageName) {
                            MediaNotificationRepository.updatePlaybackProgress(
                                positionMs = pos,
                                durationMs = duration,
                                playbackSpeed = spd,
                                lastUpdateElapsedMs = upd,
                                isPlaying = playing
                            )
                        }

                        if (st == PlaybackState.STATE_PLAYING) {
                            extractFromController(appCtx, controller, notificationBitmapFallback = null)
                        } else if (st == PlaybackState.STATE_PAUSED ||
                            st == PlaybackState.STATE_STOPPED ||
                            st == PlaybackState.STATE_NONE ||
                            st == PlaybackState.STATE_ERROR
                        ) {
                            if (!current.isSample && current.packageName == controller.packageName) {
                                extractFromController(appCtx, controller, notificationBitmapFallback = null)
                                MediaNotificationRepository.markCurrentTrackStopped()
                            }
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

            controllers.forEach { ctrl ->
                val label = resolveAppLabel(context, ctrl.packageName)
                MediaNotificationRepository.recordDetectedMediaApp(ctrl.packageName, label)
            }

            // Prefer actively playing non-ignored session first
            val sorted = controllers
                .filterNot { shouldIgnoreMediaPackage(it.packageName) }
                .sortedByDescending { controller ->
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
                        val label = resolveAppLabel(service, sbn.packageName)
                        MediaNotificationRepository.recordDetectedMediaApp(sbn.packageName, label)
                        if (shouldIgnoreMediaPackage(sbn.packageName)) continue
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
            if (shouldIgnoreMediaPackage(sbn.packageName)) return false
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

            val existing = MediaNotificationRepository.currentTrack.value
            if (!existing.isSample &&
                existing.packageName == sbn.packageName &&
                existing.title.equals(title, ignoreCase = true) &&
                existing.artist.isNotBlank() &&
                !existing.artist.equals(existing.appName, ignoreCase = true)
            ) {
                if (notifFallbackArt != null) {
                    MediaNotificationRepository.updateTrackArtworkIfMatching(
                        packageName = existing.packageName,
                        title = existing.title,
                        artist = existing.artist,
                        albumArt = notifFallbackArt
                    )
                }
                return true
            }

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
            val appName = resolveAppLabel(context, controller.packageName)
            MediaNotificationRepository.recordDetectedMediaApp(controller.packageName, appName)
            if (shouldIgnoreMediaPackage(controller.packageName)) return false

            val metadata = controller.metadata ?: return false
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            if (title.isNullOrBlank()) return false

            prewarmUpcomingQueueArt(context, controller)

            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_AUTHOR)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_WRITER)
            val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
            val playbackState = controller.playbackState
            val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
            val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
            val positionMs = (playbackState?.position ?: 0L).coerceAtLeast(0L)
            val playbackSpeed = (playbackState?.playbackSpeed ?: 1f).let { if (it <= 0f) 1f else it }
            val updateElapsedMs = (playbackState?.lastPositionUpdateTime ?: 0L).let {
                if (it > 0L) it else SystemClock.elapsedRealtime()
            }

            val candidateBitmaps = mutableListOf<Pair<Bitmap, Int>>()
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let {
                if (!it.isRecycled && it.width > 0 && it.height > 0) candidateBitmaps.add(it to 3)
            }
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let {
                if (!it.isRecycled && it.width > 0 && it.height > 0) candidateBitmaps.add(it to 2)
            }
            metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)?.let {
                if (!it.isRecycled && it.width > 0 && it.height > 0) candidateBitmaps.add(it to 1)
            }
            notificationBitmapFallback?.let {
                if (!it.isRecycled && it.width > 0 && it.height > 0) candidateBitmaps.add(it to 0)
            }
            val maxMetadataDim = candidateBitmaps.maxOfOrNull { maxOf(it.first.width, it.first.height) } ?: 0
            if (maxMetadataDim < 720 || candidateBitmaps.none { isNearSquareBitmap(it.first) }) {
                findNotificationArtworkForPackage(context, controller.packageName)?.let {
                    if (!it.isRecycled && it.width > 0 && it.height > 0) candidateBitmaps.add(it to 0)
                }
            }

            val bestRawArt = pickBestScoredCoverBitmap(candidateBitmaps)
            val resolvedArtist = artist?.takeIf { it.isNotBlank() } ?: appName
            val rawMediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
                ?: metadata.getString("com.spotify.music.extra.TRACK_URI")
            val spotifyTrackId = AnimatedCanvasSearchService.extractSpotifyTrackId(
                rawInput = rawMediaId,
                packageNameHint = controller.packageName
            )

            // Short-circuit duplicate MediaSession callbacks on the exact same song & playback state
            val repoTrack = MediaNotificationRepository.currentTrack.value
            val isSameRepoTrack = !repoTrack.isSample &&
                repoTrack.packageName == controller.packageName &&
                repoTrack.title == title &&
                repoTrack.artist == resolvedArtist &&
                repoTrack.album == album &&
                repoTrack.spotifyTrackId == spotifyTrackId
            val repoArt = repoTrack.albumArt
            val repoPixels = if (repoArt != null && !repoArt.isRecycled) repoArt.width.toLong() * repoArt.height.toLong() else 0L
            val rawPixels = if (bestRawArt != null && !bestRawArt.isRecycled) bestRawArt.width.toLong() * bestRawArt.height.toLong() else 0L
            val repoIsSquare = repoArt != null && !repoArt.isRecycled && isNearSquareBitmap(repoArt)
            val rawIsSquare = bestRawArt != null && !bestRawArt.isRecycled && isNearSquareBitmap(bestRawArt)
            val repoMaxDim = if (repoArt != null && !repoArt.isRecycled) maxOf(repoArt.width, repoArt.height) else 0
            val uris = listOfNotNull(
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
                metadata.getString(MediaMetadata.METADATA_KEY_ART_URI),
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI),
                metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
            ).map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .sortedByDescending { u ->
                    if (u.startsWith("content://") || u.startsWith("file://") || u.startsWith("/")) 1 else 0
                }
            val lookupKey = "${controller.packageName}|$title|$resolvedArtist"
            val uriLookupSig = "$lookupKey|${uris.joinToString(",")}"
            val canShortCircuitLowResUriLookup = (repoIsSquare && repoMaxDim >= 900) || lastUriLookupTrackKey == uriLookupSig
            if (isSameRepoTrack && repoPixels > 0L && (repoPixels >= rawPixels || (repoIsSquare && !rawIsSquare)) && canShortCircuitLowResUriLookup) {
                MediaNotificationRepository.updatePlaybackProgress(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    playbackSpeed = playbackSpeed,
                    lastUpdateElapsedMs = updateElapsedMs,
                    isPlaying = isPlaying
                )
                if (repoTrack.isPlaying == isPlaying) {
                    return true
                }
                MediaNotificationRepository.updateTrack(repoTrack.copy(isPlaying = isPlaying))
                return true
            }

            val safeSoftware = BitmapBlurUtils.toSafeSoftwareBitmap(bestRawArt, maxDimension = 1400)
            val safeArt = if (safeSoftware != null) {
                val trimmed = BitmapBlurUtils.trimLetterboxBarsIfPresent(safeSoftware)
                if (safeSoftware !== bestRawArt && trimmed !== safeSoftware && !safeSoftware.isRecycled) {
                    safeSoftware.recycle()
                }
                trimmed
            } else {
                null
            }

            val trackInfo = MediaTrackInfo(
                title = title,
                artist = resolvedArtist,
                album = album,
                packageName = controller.packageName,
                appName = appName,
                albumArt = safeArt,
                isPlaying = isPlaying,
                isSample = false,
                durationMs = durationMs,
                positionMs = positionMs,
                spotifyTrackId = spotifyTrackId
            )
            MediaNotificationRepository.updatePlaybackProgress(
                positionMs = positionMs,
                durationMs = durationMs,
                playbackSpeed = playbackSpeed,
                lastUpdateElapsedMs = updateElapsedMs,
                isPlaying = isPlaying
            )

            val bestMaxDim = maxOf(safeArt?.width ?: 0, safeArt?.height ?: 0)
            val bestIsSquare = safeArt != null && !safeArt.isRecycled && isNearSquareBitmap(safeArt)
            val localUris = uris.filter { it.startsWith("content://") || it.startsWith("file://") || it.startsWith("/") }
            val remoteUris = uris.filterNot { it in localUris }

            val needsArtUpgrade = safeArt == null || bestMaxDim < 900 || !bestIsSquare
            if (needsArtUpgrade && localUris.isNotEmpty() && !isSameRepoTrack) {
                val cachedLocalBmp = localUris.firstNotNullOfOrNull { uriStr ->
                    synchronized(uriArtMemoryCache) { uriArtMemoryCache.get(uriStr) }?.takeIf { !it.isRecycled }
                }
                if (cachedLocalBmp != null) {
                    localUriLoadJob?.cancel()
                    lastUriLookupTrackKey = uriLookupSig
                    MediaNotificationRepository.updateTrack(trackInfo.copy(albumArt = cachedLocalBmp))
                    return true
                }

                // Fast local content:// / file:// cover decode (~3-8ms off-main) BEFORE publishing,
                // preventing any choppy/pixelated square flash on next-song transitions.
                lastUriLookupTrackKey = uriLookupSig
                val appCtxCopy = context.applicationContext
                val pkgCopy = controller.packageName
                localUriLoadJob?.cancel()
                localUriLoadJob = serviceScope.launch(Dispatchers.IO) {
                    var bestLocalBmp: Bitmap? = null
                    var bestLocalScore = -1L
                    for (localUri in localUris) {
                        try {
                            val raw = fetchBitmapFromUriString(appCtxCopy, localUri)
                            val trimmed = if (raw != null) {
                                val t = BitmapBlurUtils.trimLetterboxBarsIfPresent(raw)
                                if (t !== raw && !raw.isRecycled) raw.recycle()
                                t
                            } else null
                            if (trimmed != null && !trimmed.isRecycled) {
                                synchronized(uriArtMemoryCache) { uriArtMemoryCache.put(localUri, trimmed) }
                                val px = trimmed.width.toLong() * trimmed.height.toLong()
                                val sqBonus = if (isNearSquareBitmap(trimmed)) 1_000_000_000L else 0L
                                val score = sqBonus + px.coerceAtMost(20_000_000L)
                                if (score > bestLocalScore) {
                                    bestLocalBmp = trimmed
                                    bestLocalScore = score
                                }
                                if (maxOf(trimmed.width, trimmed.height) >= 900 && isNearSquareBitmap(trimmed)) {
                                    break
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to load local album art URI: $localUri", e)
                        }
                    }

                    val chosenArt = bestLocalBmp ?: safeArt
                    withContext(Dispatchers.Main) {
                        MediaNotificationRepository.updateTrack(
                            trackInfo.copy(albumArt = chosenArt)
                        )
                        val chosenMax = maxOf(chosenArt?.width ?: 0, chosenArt?.height ?: 0)
                        val chosenSquare = chosenArt != null && !chosenArt.isRecycled && isNearSquareBitmap(chosenArt)
                        if (remoteUris.isNotEmpty() && (chosenArt == null || chosenMax < 900 || !chosenSquare)) {
                            loadArtFromUrisAsync(
                                context = appCtxCopy,
                                uriStrings = remoteUris,
                                packageName = pkgCopy,
                                title = title,
                                artist = resolvedArtist
                            )
                        }
                    }
                }
                return true
            }

            Log.d(
                TAG,
                "Synced track: '${trackInfo.title}' by '${trackInfo.artist}' (art=${safeArt?.width ?: 0}x${safeArt?.height ?: 0})"
            )
            MediaNotificationRepository.updateTrack(trackInfo)

            // Fetch high-resolution square art from METADATA_KEY_*_URI whenever embedded art is missing, < 900px, or non-square
            val updatedRepoTrack = MediaNotificationRepository.currentTrack.value
            val repoHasHighResForSameTrack = !updatedRepoTrack.isSample &&
                updatedRepoTrack.packageName == controller.packageName &&
                updatedRepoTrack.title == title &&
                updatedRepoTrack.artist == resolvedArtist &&
                updatedRepoTrack.albumArt?.let { !it.isRecycled && isNearSquareBitmap(it) && maxOf(it.width, it.height) >= 900 } == true

            if (!repoHasHighResForSameTrack && needsArtUpgrade && uris.isNotEmpty()) {
                lastUriLookupTrackKey = uriLookupSig
                loadArtFromUrisAsync(
                    context = context.applicationContext,
                    uriStrings = uris,
                    packageName = controller.packageName,
                    title = title,
                    artist = resolvedArtist
                )
            }

            return true
        }

        private fun prewarmUpcomingQueueArt(
            context: Context,
            controller: MediaController,
            queueOverride: List<MediaSession.QueueItem>? = null
        ) {
            val queue = try {
                queueOverride ?: controller.queue
            } catch (_: Exception) {
                null
            }
            if (queue.isNullOrEmpty()) {
                MediaNotificationRepository.predictNextTrackFromHistory()?.let { predicted ->
                    MediaNotificationRepository.prewarmUpcomingCanvases(listOf(predicted), fromPlayerQueue = false)
                }
                return
            }

            val activeQueueId = controller.playbackState?.activeQueueItemId ?: MediaSession.QueueItem.UNKNOWN_ID.toLong()
            val currentTitle = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
            val upcoming = queue.asSequence()
                .dropWhile { item ->
                    (activeQueueId != MediaSession.QueueItem.UNKNOWN_ID.toLong() && item.queueId != activeQueueId)
                }
                .filter { item ->
                    val qTitle = item.description?.title?.toString()?.trim().orEmpty()
                    (activeQueueId == MediaSession.QueueItem.UNKNOWN_ID.toLong() || item.queueId != activeQueueId) &&
                        (currentTitle.isBlank() || !qTitle.equals(currentTitle, ignoreCase = true))
                }
                .ifEmpty {
                    queue.asSequence().filter { item ->
                        val qTitle = item.description?.title?.toString()?.trim().orEmpty()
                        qTitle.isNotEmpty() && !qTitle.equals(currentTitle, ignoreCase = true)
                    }
                }
                .take(2)
                .toList()
            if (upcoming.isEmpty()) {
                MediaNotificationRepository.predictNextTrackFromHistory()?.let { predicted ->
                    MediaNotificationRepository.prewarmUpcomingCanvases(listOf(predicted), fromPlayerQueue = false)
                }
                return
            }

            val upcomingHints = upcoming.mapNotNull { item ->
                val desc = item.description ?: return@mapNotNull null
                val nextTitle = desc.title?.toString()?.trim().orEmpty()
                if (nextTitle.isEmpty()) return@mapNotNull null
                val nextSubtitle = desc.subtitle?.toString()?.trim().orEmpty()
                val rawMediaUri = desc.mediaUri?.toString() ?: desc.mediaId
                val spotifyTrackId = AnimatedCanvasSearchService.extractSpotifyTrackId(
                    rawInput = rawMediaUri,
                    packageNameHint = controller.packageName
                )
                MediaNotificationRepository.UpcomingTrackHint(
                    title = nextTitle,
                    artist = nextSubtitle,
                    spotifyTrackId = spotifyTrackId,
                    packageName = controller.packageName,
                    sourceReason = "Player Queue"
                )
            }
            if (upcomingHints.isNotEmpty()) {
                MediaNotificationRepository.prewarmUpcomingCanvases(upcomingHints, fromPlayerQueue = true)
            }

            val signature = upcoming.joinToString("|") {
                "${it.queueId}:${it.description?.title}:${it.description?.iconUri}"
            }
            if (signature == lastQueuePrewarmSignature) return
            lastQueuePrewarmSignature = signature

            val appCtx = context.applicationContext
            val prepMinDim = if (com.example.util.DevicePerformanceTier.get(appCtx).isLowRam) 768 else 1024
            val prepMaxDim = if (com.example.util.DevicePerformanceTier.get(appCtx).isLowRam) 1280 else 1600

            queuePrewarmJob?.cancel()
            queuePrewarmJob = serviceScope.launch(Dispatchers.IO) {
                for (item in upcoming) {
                    val desc = item.description ?: continue
                    val nextTitle = desc.title?.toString()?.trim().orEmpty()
                    val nextSubtitle = desc.subtitle?.toString()?.trim().orEmpty()
                    if (nextTitle.isNotEmpty()) {
                        MediaNotificationRepository.prewarmQueueTrackArtwork(
                            title = nextTitle,
                            artist = nextSubtitle
                        )
                    }

                    val uriStr = desc.iconUri?.toString()?.trim().orEmpty()
                    if (uriStr.isNotEmpty()) {
                        val existing = synchronized(uriArtMemoryCache) { uriArtMemoryCache.get(uriStr) }
                            ?.takeIf { !it.isRecycled }
                        val bmp = existing ?: runCatching {
                            val raw = fetchBitmapFromUriString(appCtx, uriStr)
                            if (raw != null && !raw.isRecycled) {
                                val trimmed = BitmapBlurUtils.trimLetterboxBarsIfPresent(raw)
                                if (trimmed !== raw && !raw.isRecycled) raw.recycle()
                                if (!trimmed.isRecycled) {
                                    synchronized(uriArtMemoryCache) { uriArtMemoryCache.put(uriStr, trimmed) }
                                }
                                trimmed
                            } else {
                                null
                            }
                        }.getOrNull()
                        if (bmp != null && !bmp.isRecycled) {
                            BitmapBlurUtils.prepareCrispArtwork(bmp, minTargetDim = prepMinDim, maxDimension = prepMaxDim)
                        }
                    } else {
                        desc.iconBitmap?.takeIf { !it.isRecycled }?.let { rawBmp ->
                            BitmapBlurUtils.prepareCrispArtwork(rawBmp, minTargetDim = prepMinDim, maxDimension = prepMaxDim)
                        }
                    }
                }
            }
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
            remoteUriLoadJob?.cancel()
            remoteUriLoadJob = serviceScope.launch(Dispatchers.IO) {
                var bestUriBitmap: Bitmap? = null
                var bestScore = -1L

                for (uriString in uriStrings) {
                    try {
                        val cached = synchronized(uriArtMemoryCache) { uriArtMemoryCache.get(uriString) }
                            ?.takeIf { !it.isRecycled }
                        val decoded = cached ?: run {
                            val rawDecoded = fetchBitmapFromUriString(context, uriString)
                            if (rawDecoded != null) {
                                val trimmed = BitmapBlurUtils.trimLetterboxBarsIfPresent(rawDecoded)
                                if (trimmed !== rawDecoded && !rawDecoded.isRecycled) {
                                    rawDecoded.recycle()
                                }
                                if (!trimmed.isRecycled) {
                                    synchronized(uriArtMemoryCache) { uriArtMemoryCache.put(uriString, trimmed) }
                                }
                                trimmed
                            } else {
                                null
                            }
                        }
                        if (decoded != null && !decoded.isRecycled) {
                            val pixels = decoded.width.toLong() * decoded.height.toLong()
                            val sqBonus = if (isNearSquareBitmap(decoded)) 1_000_000_000L else 0L
                            val score = sqBonus + pixels.coerceAtMost(20_000_000L)
                            if (score > bestScore) {
                                bestUriBitmap = decoded
                                bestScore = score
                            }
                            if (maxOf(decoded.width, decoded.height) >= 900 && isNearSquareBitmap(decoded)) {
                                break
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to load album art URI: $uriString", e)
                    }
                }

                if (bestUriBitmap != null) {
                    withContext(Dispatchers.Main) {
                        MediaNotificationRepository.updateTrackArtworkIfMatching(
                            packageName = packageName,
                            title = title,
                            artist = artist,
                            albumArt = bestUriBitmap
                        )
                    }
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
                        setRequestProperty("User-Agent", "EchoSnap/1.3.0 Android")
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
                                BitmapBlurUtils.decodeSampledFromStream(it, maxDimension = 1400)
                            }
                        }
                        return null
                    } finally {
                        conn.disconnect()
                    }
                }
                null
            } else if (uriString.startsWith("spotify:image:", ignoreCase = true)) {
                val imageHash = uriString.substringAfterLast(':').trim()
                if (imageHash.isNotEmpty()) {
                    fetchBitmapFromUriString(context, "https://i.scdn.co/image/$imageHash")
                } else {
                    null
                }
            } else {
                val uri = if (uriString.startsWith("/")) Uri.fromFile(File(uriString)) else Uri.parse(uriString)
                BitmapBlurUtils.decodeHighResFromUri(context, uri, maxDimension = 1440)
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
                val candidates = mutableListOf<Pair<Bitmap, Int>>()

                fun addDrawableFromIcon(icon: Icon?, rank: Int) {
                    val drawable = icon?.loadDrawable(context) ?: return
                    if (drawable is android.graphics.drawable.BitmapDrawable && drawable.bitmap != null) {
                        candidates.add(drawable.bitmap to rank)
                    } else {
                        val w = drawable.intrinsicWidth.coerceAtLeast(512)
                        val h = drawable.intrinsicHeight.coerceAtLeast(512)
                        candidates.add(drawable.toBitmap(width = w, height = h, config = Bitmap.Config.ARGB_8888) to rank)
                    }
                }

                addDrawableFromIcon(notification.getLargeIcon(), rank = 2)

                @Suppress("DEPRECATION")
                val extraBig = extras?.get(Notification.EXTRA_LARGE_ICON_BIG)
                when (extraBig) {
                    is Bitmap -> candidates.add(extraBig to 2)
                    is Icon -> addDrawableFromIcon(extraBig, rank = 2)
                }

                @Suppress("DEPRECATION")
                val extraStd = extras?.get(Notification.EXTRA_LARGE_ICON)
                when (extraStd) {
                    is Bitmap -> candidates.add(extraStd to 2)
                    is Icon -> addDrawableFromIcon(extraStd, rank = 2)
                }

                @Suppress("DEPRECATION")
                val extraPic = extras?.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)
                if (extraPic != null) {
                    candidates.add(extraPic to 0)
                }

                val best = pickBestScoredCoverBitmap(candidates)

                val safe = BitmapBlurUtils.toSafeSoftwareBitmap(best, maxDimension = 1400)
                if (safe != null) {
                    val trimmed = BitmapBlurUtils.trimLetterboxBarsIfPresent(safe)
                    if (safe !== best && trimmed !== safe && !safe.isRecycled) {
                        safe.recycle()
                    }
                    trimmed
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting notification artwork", e)
                null
            }
        }

        internal fun isNearSquareBitmap(bitmap: Bitmap): Boolean {
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return false
            val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
            return aspect in 0.85f..1.18f
        }

        internal fun pickBestScoredCoverBitmap(candidates: List<Pair<Bitmap, Int>>): Bitmap? {
            return candidates
                .filter { (bmp, _) -> !bmp.isRecycled && bmp.width > 0 && bmp.height > 0 }
                .maxByOrNull { (bmp, sourceRank) ->
                    val minDim = minOf(bmp.width, bmp.height)
                    val isSquare = isNearSquareBitmap(bmp)
                    val tier = when {
                        isSquare && minDim >= 180 -> 3_000_000_000L
                        isSquare -> 1_000_000_000L
                        minDim >= 180 -> 200_000_000L
                        else -> 0L
                    }
                    val rankBonus = sourceRank.coerceAtLeast(0).toLong() * 20_000_000L
                    val pixelArea = (bmp.width.toLong() * bmp.height.toLong()).coerceAtMost(15_000_000L)
                    tier + rankBonus + pixelArea
                }?.first
        }

        private fun resolveAppLabel(context: Context, packageName: String): String {
            appLabelCache[packageName]?.let { return it }
            val resolved = try {
                val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
                context.packageManager.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                packageName
            }
            appLabelCache[packageName] = resolved
            return resolved
        }
    }
}
