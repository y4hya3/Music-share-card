package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.model.CardBrandMode
import com.example.model.CardLayoutType
import com.example.model.CardPalette
import com.example.model.CardStyleConfig
import com.example.model.MediaTrackInfo
import com.example.palette.PaletteColorExtractor
import com.example.repository.CardStylePreferences
import com.example.repository.SavedMediaLibrary
import com.example.service.AnimatedCanvasCandidate
import com.example.service.AnimatedCanvasSearchService
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.toArgb
import com.example.service.LyricsSearchService
import com.example.util.AnimatedGifExporter
import com.example.util.AudioBeatMonitor
import com.example.util.BitmapBlurUtils
import com.example.util.CardShareManager
import com.example.util.WaveformExportSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("EchoSnap", appName)
  }

  @Test
  fun `trimLetterboxBarsIfPresent trims symmetric bars without recycling source`() {
    val w = 320
    val h = 180
    val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(src)
    canvas.drawColor(AndroidColor.BLACK)
    val paint = Paint().apply { color = AndroidColor.rgb(220, 40, 80) }
    canvas.drawRect(60f, 0f, 260f, h.toFloat(), paint)

    val trimmed = BitmapBlurUtils.trimLetterboxBarsIfPresent(src)
    assertFalse(src.isRecycled)
    assertFalse(trimmed.isRecycled)
    assertTrue("Expected horizontal pillarbox bars to be trimmed (got ${trimmed.width})", trimmed.width < 220)
    assertEquals(h, trimmed.height)

    val square = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
    val same = BitmapBlurUtils.trimLetterboxBarsIfPresent(square)
    assertTrue(same === square)
    square.recycle()

    trimmed.recycle()
    src.recycle()
  }

  @Test
  fun `blurBitmap and computeBlurMipPyramid produce valid bitmaps and keep source alive`() {
    val src = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
    Canvas(src).drawColor(AndroidColor.rgb(30, 120, 210))

    val blurred = BitmapBlurUtils.blurBitmap(src, radiusPx = 24f, maxDimension = 260)
    assertFalse(src.isRecycled)
    assertFalse(blurred.isRecycled)
    assertTrue(blurred.width <= 260 && blurred.height <= 260)

    val mip = BitmapBlurUtils.computeBlurMipPyramid(src)
    assertNotNull(mip)
    mip?.let { (low, mid, high) ->
      assertFalse(low.isRecycled)
      assertFalse(mid.isRecycled)
      assertFalse(high.isRecycled)
      low.recycle()
      mid.recycle()
      high.recycle()
    }

    val palette = runBlocking { PaletteColorExtractor.extractColors(src) }
    assertNotNull(palette)
    assertFalse(src.isRecycled)

    blurred.recycle()
    src.recycle()
  }

  @Test
  fun `card style defaults preserve existing card appearance`() {
    val defaults = CardStyleConfig()
    assertEquals(20f, defaults.cardBlurRadius, 0.001f)
    assertEquals(0f, defaults.lyricsArtBlurRadius, 0.001f)
    assertEquals(1.0f, defaults.cardOpacity, 0.001f)
    assertFalse(defaults.useTransparentBackground)
    assertFalse(defaults.isFitToScreenPreview)
    assertFalse(defaults.enableWaveform)
  }

  @Test
  fun `card style preferences v130 save and saveAsync roundtrip cleanly`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val config = CardStyleConfig(
      brandMode = CardBrandMode.SPOTIFY,
      backgroundBlurRadius = 18f,
      cardScale = 0.92f,
      cardBlurRadius = 8f,
      cardOpacity = 0.78f,
      lyricsArtBlurRadius = 14f,
      useTransparentBackground = true,
      isFitToScreenPreview = true,
      enableWaveform = true,
      waveformColor = Color(0xFF1DB954),
      waveformBeatSync = true
    )
    CardStylePreferences.save(context, config)
    val loaded = CardStylePreferences.load(context)
    assertEquals(CardBrandMode.SPOTIFY, loaded.brandMode)
    assertEquals(18f, loaded.backgroundBlurRadius, 0.001f)
    assertEquals(0.92f, loaded.cardScale, 0.001f)
    assertEquals(8f, loaded.cardBlurRadius, 0.001f)
    assertEquals(0.78f, loaded.cardOpacity, 0.001f)
    assertEquals(14f, loaded.lyricsArtBlurRadius, 0.001f)
    assertTrue(loaded.useTransparentBackground)
    assertTrue(loaded.isFitToScreenPreview)
    assertTrue(loaded.enableWaveform)
    assertEquals(Color(0xFF1DB954), loaded.waveformColor)
    assertTrue(loaded.waveformBeatSync)

    val updated = config.copy(
      backgroundBlurRadius = 30f,
      cardBlurRadius = 32f,
      cardOpacity = 0.55f,
      useTransparentBackground = false
    )
    CardStylePreferences.saveAsync(context, updated)
    val loadedAfterAsync = CardStylePreferences.load(context)
    assertEquals(30f, loadedAfterAsync.backgroundBlurRadius, 0.001f)
    assertEquals(32f, loadedAfterAsync.cardBlurRadius, 0.001f)
    assertEquals(0.55f, loadedAfterAsync.cardOpacity, 0.001f)
    assertFalse(loadedAfterAsync.useTransparentBackground)
  }

  @Test
  fun `canvas search normalizes offline file names unknown artists and player names`() {
    assertEquals("The Weeknd", AnimatedCanvasSearchService.normalizeArtistText("The Weeknd · After Hours"))
    assertEquals("Daft Punk", AnimatedCanvasSearchService.normalizeArtistText("Daft Punk — Random Access Memories"))
    assertEquals("Billie Eilish", AnimatedCanvasSearchService.normalizeArtistText("Billie Eilish - Topic"))
    assertEquals("", AnimatedCanvasSearchService.normalizeArtistText("<unknown>"))
    assertEquals("", AnimatedCanvasSearchService.normalizeArtistText("unknown artist"))

    assertEquals(
      "Blinding Lights",
      AnimatedCanvasSearchService.normalizeQueryText("01 - Blinding Lights (Official Music Video) [Lyrics].mp3")
    )
    assertEquals(
      "Levitating",
      AnimatedCanvasSearchService.normalizeQueryText("Levitating (feat. DaBaby) (Acoustic).flac")
    )

    val score = AnimatedCanvasSearchService.evaluateMatch(
      candidateTitle = "Blinding Lights",
      candidateArtist = "The Weeknd",
      targetTitle = "02. Blinding Lights (Official Audio).mp3",
      targetArtist = "The Weeknd · After Hours"
    )
    assertTrue("Expected confident match score, got ${score.totalScore}", score.totalScore >= 80)
  }

  @Test
  fun `filterAnimatedOnly removes all static photo candidates`() {
    val staticPhoto1 = AnimatedCanvasCandidate(
      id = "itunes_hd_1",
      title = "Track",
      artist = "Artist",
      videoUrl = null,
      hdPosterUrl = "https://example.com/1000x1000bb.jpg",
      source = "iTunes HD",
      resolutionLabel = "1000×1000",
      matchScore = 95,
      isLoopingVideo = false
    )
    val staticPhoto2 = AnimatedCanvasCandidate(
      id = "sp_hd_1",
      title = "Track",
      artist = "Artist",
      videoUrl = "",
      hdPosterUrl = "https://example.com/sp640.jpg",
      source = "Spotify HD Art",
      resolutionLabel = "640×640",
      matchScore = 90,
      isLoopingVideo = true
    )
    val realCanvas = AnimatedCanvasCandidate(
      id = "sp_canvas_1",
      title = "Track",
      artist = "Artist",
      videoUrl = "https://canvaz.scdn.co/upload/artist/video.mp4",
      hdPosterUrl = "https://example.com/poster.jpg",
      source = "Spotify Canvas",
      resolutionLabel = "Canvas MP4",
      matchScore = 110,
      isLoopingVideo = true
    )
    val appleMotion = AnimatedCanvasCandidate(
      id = "apple_motion_1",
      title = "Track",
      artist = "Artist",
      videoUrl = "https://mvod.itunes.apple.com/motion.mp4",
      hdPosterUrl = null,
      source = "Apple Motion",
      resolutionLabel = "Motion MP4",
      matchScore = 98,
      isLoopingVideo = true
    )

    val filtered = AnimatedCanvasSearchService.filterAnimatedOnly(
      listOf(staticPhoto1, staticPhoto2, realCanvas, appleMotion)
    )
    assertEquals(2, filtered.size)
    assertTrue(filtered.all { it.isLoopingVideo && !it.videoUrl.isNullOrBlank() })
    assertEquals(listOf("sp_canvas_1", "apple_motion_1"), filtered.map { it.id })
  }

  @Test
  fun `lyrics search cleans LRC timestamps and metadata tags`() {
    val rawLrc = """
      [ar: The Weeknd]
      [ti: Blinding Lights]
      [00:12.34] I've been tryna call
      [00:15.60] I've been on my own for long enough
      [00:18.90] &amp; Maybe you can show me how to love
      [00:22.00]
    """.trimIndent()
    val cleaned = LyricsSearchService.parseCleanLines(rawLrc)
    assertEquals(
      listOf(
        "I've been tryna call",
        "I've been on my own for long enough",
        "& Maybe you can show me how to love"
      ),
      cleaned
    )
  }

  @Test
  fun `audio beat monitor produces bounded distinct per track levels`() {
    val levelsA = AudioBeatMonitor.computeBarLevels("SICKO MODE", "Travis Scott", isPlaying = true, timeSec = 1.25f)
    val levelsB = AudioBeatMonitor.computeBarLevels("Clair de Lune", "Debussy", isPlaying = true, timeSec = 1.25f)
    assertEquals(4, levelsA.size)
    assertEquals(4, levelsB.size)
    assertTrue(levelsA.all { it in 0.18f..1.0f })
    assertTrue(levelsB.all { it in 0.18f..1.0f })
    assertFalse(levelsA.contentEquals(levelsB))
  }

  @Test
  fun `saved media library save rename and delete covers and canvases work offline`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    SavedMediaLibrary.initialize(context)

    val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
    Canvas(bmp).drawColor(AndroidColor.MAGENTA)
    val savedCover = runBlocking {
      SavedMediaLibrary.saveCoverFromBitmap(context, bmp, "Neon Cover", "Artist X")
    }
    assertNotNull(savedCover)
    assertEquals("Neon Cover", savedCover!!.name)
    assertTrue(File(savedCover.localImagePath).exists())

    SavedMediaLibrary.renameCover(context, savedCover.id, "Renamed Neon")
    assertEquals("Renamed Neon", SavedMediaLibrary.savedCovers.value.first { it.id == savedCover.id }.name)

    SavedMediaLibrary.deleteCover(context, savedCover.id)
    assertFalse(File(savedCover.localImagePath).exists())
    assertTrue(SavedMediaLibrary.savedCovers.value.none { it.id == savedCover.id })
    bmp.recycle()

    val tempVideo = File(context.cacheDir, "test_canvas_src.mp4").apply {
      writeBytes(ByteArray(8192) { 1 })
    }
    val cand = AnimatedCanvasCandidate(
      id = "local_test",
      title = "My Track",
      artist = "Artist Y",
      videoUrl = android.net.Uri.fromFile(tempVideo).toString(),
      hdPosterUrl = null,
      source = "Test",
      resolutionLabel = "720×1280",
      matchScore = 100,
      isLoopingVideo = true
    )
    val savedCanvas = runBlocking {
      SavedMediaLibrary.saveCanvasFromCandidate(context, cand, "Concert Loop")
    }
    assertNotNull(savedCanvas)
    assertEquals("Concert Loop", savedCanvas!!.name)
    assertTrue(File(savedCanvas.localVideoPath).exists())

    SavedMediaLibrary.renameCanvas(context, savedCanvas.id, "Tour Loop")
    assertEquals("Tour Loop", SavedMediaLibrary.savedCanvases.value.first { it.id == savedCanvas.id }.name)

    SavedMediaLibrary.deleteCanvas(context, savedCanvas.id)
    assertFalse(File(savedCanvas.localVideoPath).exists())
    tempVideo.delete()
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun `transparent background exports pure alpha zero corner pixels across all 4 layouts`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val art = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
    Canvas(art).drawColor(AndroidColor.rgb(240, 80, 90))
    val track = MediaTrackInfo(
      title = "Transparent Test",
      artist = "Tester",
      album = "Album",
      appName = "Samsung Music",
      packageName = "com.sec.android.app.music",
      albumArt = art,
      isPlaying = true,
      isSample = false
    )

    CardLayoutType.values().forEach { layout ->
      val bmp = runBlocking {
        CardShareManager.generateStoryBitmap(
          context = context,
          track = track,
          palette = CardPalette.Default,
          styleConfig = CardStyleConfig(
            cardLayoutType = layout,
            useTransparentBackground = true
          )
        )
      }
      assertEquals("Top-left corner must be transparent for $layout", AndroidColor.TRANSPARENT, bmp.getPixel(5, 5))
      assertEquals("Bottom-right corner must be transparent for $layout", AndroidColor.TRANSPARENT, bmp.getPixel(1074, 1914))
      val centerAlpha = AndroidColor.alpha(bmp.getPixel(540, 880))
      assertTrue("Center card pixels should be opaque/visible for $layout (alpha=$centerAlpha)", centerAlpha > 100)
      bmp.recycle()
    }
    art.recycle()
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun `cardOpacity adjusts frosted glass alpha while keeping album artwork and text crisp opaque`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val art = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
    Canvas(art).drawColor(AndroidColor.WHITE)
    val track = MediaTrackInfo(
      title = "Opacity Song",
      artist = "Opacity Artist",
      album = "Album",
      appName = "YouTube Music",
      packageName = "com.google.android.apps.youtube.music",
      albumArt = art,
      isPlaying = true,
      isSample = false
    )

    val fullBmp = runBlocking {
      CardShareManager.generateStoryBitmap(
        context,
        track,
        CardPalette.Default,
        CardStyleConfig(useTransparentBackground = true, cardOpacity = 1.0f)
      )
    }
    val halfBmp = runBlocking {
      CardShareManager.generateStoryBitmap(
        context,
        track,
        CardPalette.Default,
        CardStyleConfig(useTransparentBackground = true, cardOpacity = 0.5f)
      )
    }
    val zeroBmp = runBlocking {
      CardShareManager.generateStoryBitmap(
        context,
        track,
        CardPalette.Default,
        CardStyleConfig(useTransparentBackground = true, cardOpacity = 0.0f)
      )
    }

    // (145, 900) sits inside the Floating Glass Card left padding strip (119..166) outside the album art
    val glassFullAlpha = AndroidColor.alpha(fullBmp.getPixel(145, 900))
    val glassHalfAlpha = AndroidColor.alpha(halfBmp.getPixel(145, 900))
    val glassZeroAlpha = AndroidColor.alpha(zeroBmp.getPixel(145, 900))
    assertTrue("Expected full frosted glass alpha > 160, got $glassFullAlpha", glassFullAlpha >= 160)
    assertTrue("Expected half frosted glass alpha lower than full (full=$glassFullAlpha, half=$glassHalfAlpha)", glassHalfAlpha in 40..(glassFullAlpha - 40))
    assertEquals("Expected zero frosted glass alpha == 0 when opacity is 0%", 0, glassZeroAlpha)

    // (540, 820) sits inside the album cover artwork -> must remain 100% opaque regardless of glass opacity
    assertEquals(255, AndroidColor.alpha(fullBmp.getPixel(540, 820)))
    assertEquals(255, AndroidColor.alpha(halfBmp.getPixel(540, 820)))
    assertEquals(255, AndroidColor.alpha(zeroBmp.getPixel(540, 820)))

    fullBmp.recycle()
    halfBmp.recycle()
    zeroBmp.recycle()
    art.recycle()
  }

  @Test
  fun `audio beat monitor animates when paused and responds to speedMultiplier and beatSync`() {
    val frameA = AudioBeatMonitor.computeBarLevels(
      trackTitle = "Midnight City",
      trackArtist = "M83",
      isPlaying = false,
      timeSec = 0.5f,
      speedMultiplier = 1.0f,
      animateWhenPaused = true
    )
    val frameB = AudioBeatMonitor.computeBarLevels(
      trackTitle = "Midnight City",
      trackArtist = "M83",
      isPlaying = false,
      timeSec = 1.15f,
      speedMultiplier = 1.0f,
      animateWhenPaused = true
    )
    assertFalse("Expected waveform preview to animate smoothly even when paused", frameA.contentEquals(frameB))

    val slowFrame = AudioBeatMonitor.computeBarLevels(
      trackTitle = "Midnight City",
      trackArtist = "M83",
      isPlaying = true,
      timeSec = 1.0f,
      speedMultiplier = 0.5f,
      beatSyncMode = false
    )
    val fastFrame = AudioBeatMonitor.computeBarLevels(
      trackTitle = "Midnight City",
      trackArtist = "M83",
      isPlaying = true,
      timeSec = 1.0f,
      speedMultiplier = 2.0f,
      beatSyncMode = false
    )
    assertFalse("Expected speedMultiplier to alter waveform phase", slowFrame.contentEquals(fastFrame))

    val beatFrame = AudioBeatMonitor.computeBarLevels(
      trackTitle = "Midnight City",
      trackArtist = "M83",
      isPlaying = true,
      timeSec = 0.05f,
      beatSyncMode = true
    )
    assertTrue("Expected beat-sync levels bounded in [0.18, 1.0]", beatFrame.all { it in 0.18f..1.0f })
  }

  @Test
  fun `card style preferences persists waveform speed, word selection mode, and zero glass opacity`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val cfg = CardStyleConfig(
      cardOpacity = 0.0f,
      waveformSpeedMultiplier = 1.8f,
      lyricsWordSelectionMode = true
    )
    CardStylePreferences.save(context, cfg)
    val loaded = CardStylePreferences.load(context)
    assertEquals(0.0f, loaded.cardOpacity, 0.001f)
    assertEquals(1.8f, loaded.waveformSpeedMultiplier, 0.001f)
    assertTrue(loaded.lyricsWordSelectionMode)
    assertEquals(8_000_000L, com.example.util.CanvasVideoExporter.WAVEFORM_LOOP_DURATION_US)
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun `floating glass card blur alters side edge frosting visibly across 0px 20px 40px`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val art = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
    val artCanvas = Canvas(art)
    artCanvas.drawColor(AndroidColor.rgb(210, 45, 90))
    val stripe = Paint().apply { color = AndroidColor.rgb(40, 195, 240) }
    artCanvas.drawRect(0f, 0f, 120f, 240f, stripe)

    val track = MediaTrackInfo(
      title = "Test Song",
      artist = "Test Artist",
      album = "Test Album",
      appName = "YouTube Music",
      packageName = "com.google.android.apps.youtube.music",
      albumArt = art,
      isPlaying = true,
      isSample = false
    )
    val baseStyle = CardStyleConfig(
      cardLayoutType = CardLayoutType.SPOTIFY_INNER_CARD,
      useBlurredAlbumBackground = true,
      backgroundBlurRadius = 16f
    )

    val bmp0 = runBlocking {
      CardShareManager.generateStoryBitmap(context, track, CardPalette.Default, baseStyle.copy(cardBlurRadius = 0f))
    }
    val bmp20 = runBlocking {
      CardShareManager.generateStoryBitmap(context, track, CardPalette.Default, baseStyle.copy(cardBlurRadius = 20f))
    }
    val bmp40 = runBlocking {
      CardShareManager.generateStoryBitmap(context, track, CardPalette.Default, baseStyle.copy(cardBlurRadius = 40f))
    }

    val leftEdgeX = 135
    val rightEdgeX = 945
    val outerLeftHaloX = 112
    val midY = 1920 / 2

    val left0 = bmp0.getPixel(leftEdgeX, midY)
    val left20 = bmp20.getPixel(leftEdgeX, midY)
    val left40 = bmp40.getPixel(leftEdgeX, midY)

    val right0 = bmp0.getPixel(rightEdgeX, midY)
    val right20 = bmp20.getPixel(rightEdgeX, midY)
    val right40 = bmp40.getPixel(rightEdgeX, midY)

    val halo20 = bmp20.getPixel(outerLeftHaloX, midY)
    val halo40 = bmp40.getPixel(outerLeftHaloX, midY)

    assertNotEquals("Expected 0px clear glass left side to differ from 20px default", left0, left20)
    assertNotEquals("Expected 40px frosted glass left side to differ from 20px default", left20, left40)
    assertNotEquals("Expected 0px clear glass right side to differ from 20px default", right0, right20)
    assertNotEquals("Expected 40px frosted glass right side to differ from 20px default", right20, right40)
    assertNotEquals("Expected 40px outer frosted side halo to differ from 20px default", halo20, halo40)

    bmp0.recycle()
    bmp20.recycle()
    bmp40.recycle()
    art.recycle()
  }

  @Test
  fun `audio beat tracker reacts with zero attack latency to bass kick and treble and decays smoothly`() {
    val tracker = AudioBeatMonitor.BeatEnvelopeTracker()
    val fftBass = ByteArray(256)
    // Inject strong ~90Hz impulse in bin 1
    fftBass[2] = 115
    fftBass[3] = 40

    val t0 = 1_000_000_000L
    val levelsAfterFirstCallback = tracker.ingestFft(fftBass, samplingRateMilliHz = 48_000_000, nowNanos = t0)
    assertTrue(
      "Expected instant 0ms attack on bass bar (got ${levelsAfterFirstCallback[0]})",
      levelsAfterFirstCallback[0] >= 0.70f
    )
    assertTrue(
      "Expected bass bar higher than treble bar on low-frequency kick",
      levelsAfterFirstCallback[0] > levelsAfterFirstCallback[3]
    )
    assertTrue(tracker.hasRecentSignal(t0 + 20_000_000L))

    // Decay check at +120ms without new input
    val decayed = tracker.snapshotAt(t0 + 120_000_000L)
    assertNotNull(decayed)
    assertTrue(
      "Expected smooth decay after 120ms (${decayed!![0]} < ${levelsAfterFirstCallback[0]})",
      decayed[0] < levelsAfterFirstCallback[0]
    )

    // PCM transient waveform ingest (unsigned centered around 128)
    val pcmBurst = ByteArray(256) { idx ->
      if (idx % 2 == 0) 245.toByte() else 12.toByte()
    }
    val afterBurst = tracker.ingestWaveform(pcmBurst, nowNanos = t0 + 140_000_000L)
    assertTrue(
      "Expected PCM kick transient to immediately jump bar 0 (${afterBurst[0]})",
      afterBurst[0] >= 0.75f
    )

    // Quiet pause dip between beats still follows live bands when hasLiveSignal == true
    val liveQuietBands = floatArrayOf(0.19f, 0.19f, 0.19f, 0.19f)
    val computed = AudioBeatMonitor.computeBarLevels(
      trackTitle = "Test Song",
      trackArtist = "Tester",
      isPlaying = true,
      timeSec = 1.0f,
      liveAudioBands = liveQuietBands,
      beatSyncMode = true,
      hasLiveSignal = true
    )
    assertEquals(0.19f, computed[0], 0.001f)
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun `transparent background with waveform enabled routes to looping transparent GIF89a export`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertTrue(
      CardShareManager.isGifExportActive(
        CardStyleConfig(
          cardLayoutType = CardLayoutType.SPOTIFY_INNER_CARD,
          useTransparentBackground = true,
          enableWaveform = true
        )
      )
    )
    assertFalse(
      CardShareManager.isGifExportActive(
        CardStyleConfig(
          cardLayoutType = CardLayoutType.SPOTIFY_INNER_CARD,
          useTransparentBackground = true,
          enableWaveform = false
        )
      )
    )
    assertFalse(
      CardShareManager.isGifExportActive(
        CardStyleConfig(
          cardLayoutType = CardLayoutType.SPOTIFY_INNER_CARD,
          useTransparentBackground = false,
          enableWaveform = true
        )
      )
    )

    val art = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
    Canvas(art).drawColor(AndroidColor.rgb(45, 200, 120))
    val track = MediaTrackInfo(
      title = "Sticker Beat",
      artist = "EchoSnap",
      album = "Single",
      appName = "YouTube Music",
      packageName = "com.google.android.apps.youtube.music",
      albumArt = art,
      isPlaying = true,
      isSample = false
    )
    val style = CardStyleConfig(
      cardLayoutType = CardLayoutType.SPOTIFY_INNER_CARD,
      useTransparentBackground = true,
      enableWaveform = true,
      waveformBeatSync = true,
      waveformColor = Color(0xFF38E8FF)
    )

    val overlay = runBlocking {
      CardShareManager.generateTransparentCardOverlayBitmap(
        context = context,
        track = track,
        palette = CardPalette.Default,
        styleConfig = style,
        targetWidth = AnimatedGifExporter.GIF_WIDTH,
        targetHeight = AnimatedGifExporter.GIF_HEIGHT,
        includeWaveform = false
      )
    }
    val wfRect = CardShareManager.computeFloatingGlassWaveformRect(
      targetWidth = AnimatedGifExporter.GIF_WIDTH,
      targetHeight = AnimatedGifExporter.GIF_HEIGHT,
      track = track,
      styleConfig = style
    )
    val spec = WaveformExportSpec(
      rectIn720x1280 = wfRect,
      waveformColorArgb = Color(0xFF38E8FF).toArgb(),
      cardOpacity = style.cardOpacity,
      trackTitle = track.title,
      trackArtist = track.artist,
      isPlaying = true,
      speedMultiplier = style.waveformSpeedMultiplier,
      beatSyncEnabled = style.waveformBeatSync
    )

    val gifBytes = AnimatedGifExporter.encodeTransparentWaveformGifToBytes(
      overlayWithoutWaveform = overlay,
      waveformSpec = spec,
      wfRectInOutput = wfRect,
      durationSec = 1.0f,
      fps = 10
    )
    val ascii = String(gifBytes.take(512).toByteArray(), Charsets.ISO_8859_1)
    assertTrue("Expected GIF89a signature", ascii.startsWith("GIF89a"))
    assertTrue("Expected NETSCAPE2.0 loop extension", ascii.contains("NETSCAPE2.0") || String(gifBytes, Charsets.ISO_8859_1).contains("NETSCAPE2.0"))
    assertEquals("Expected GIF trailer 0x3B", 0x3B.toByte(), gifBytes.last())

    // Verify GCE has disposal=2 and transparentColorFlag=1 (0x21 0xF9 0x04 0x09)
    val gceFound = (0 until gifBytes.size - 4).any { i ->
      gifBytes[i] == 0x21.toByte() &&
        gifBytes[i + 1] == 0xF9.toByte() &&
        gifBytes[i + 2] == 0x04.toByte() &&
        gifBytes[i + 3] == 0x09.toByte()
    }
    assertTrue("Expected Graphic Control Extension with transparent flag & restore disposal", gceFound)

    val decodedFirstFrame = BitmapFactory.decodeByteArray(gifBytes, 0, gifBytes.size)
    assertNotNull("Expected valid decodable GIF bytes", decodedFirstFrame)
    assertEquals(AnimatedGifExporter.GIF_WIDTH, decodedFirstFrame!!.width)
    assertEquals(AnimatedGifExporter.GIF_HEIGHT, decodedFirstFrame.height)
    assertEquals(
      "Expected corner pixel in decoded GIF to remain transparent",
      AndroidColor.TRANSPARENT,
      decodedFirstFrame.getPixel(5, 5)
    )

    decodedFirstFrame.recycle()
    overlay.recycle()
    art.recycle()
  }

  @Test
  fun `waveform color changes persist cleanly while beat sync is active`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val cfg = CardStyleConfig(
      cardLayoutType = CardLayoutType.SPOTIFY_INNER_CARD,
      enableWaveform = true,
      waveformBeatSync = true,
      waveformColor = Color(0xFFA855F7)
    )
    CardStylePreferences.save(context, cfg)
    val loaded = CardStylePreferences.load(context)
    assertTrue(loaded.waveformBeatSync)
    assertTrue(loaded.enableWaveform)
    assertEquals(Color(0xFFA855F7).toArgb(), loaded.waveformColor.toArgb())
  }

  @Test
  fun `lyrics engine lists Genius first among 16+ providers and parses smart queries`() {
    assertTrue(LyricsSearchService.PROVIDERS.size >= 16)
    assertEquals("Genius", LyricsSearchService.PROVIDERS.first())

    val dashQuery = LyricsSearchService.parseSmartQuery("The Weeknd - Blinding Lights", "")
    assertEquals("Blinding Lights", dashQuery.title)
    assertEquals("The Weeknd", dashQuery.artist)

    val byQuery = LyricsSearchService.parseSmartQuery("Blinding Lights by The Weeknd", "YouTube Music")
    assertEquals("Blinding Lights", byQuery.title)
    assertEquals("The Weeknd", byQuery.artist)

    val fileQuery = LyricsSearchService.parseSmartQuery("01 - Starboy (Official Music Video).mp3", "<unknown>")
    assertEquals("Starboy", fileQuery.title)
    assertEquals("", fileQuery.artist)
  }

  @Test
  fun `lyrics match verification rejects wrong artist and karaoke covers`() {
    val exact = LyricsSearchService.evaluateLyricsMatch(
      candidateTitle = "Creep",
      candidateArtist = "Radiohead",
      targetTitle = "Creep",
      targetArtist = "Radiohead"
    )
    assertTrue("Expected exact Radiohead match to verify", LyricsSearchService.isVerifiedLyricsMatch(exact, hasTargetArtist = true))

    val wrongArtistSameTitle = LyricsSearchService.evaluateLyricsMatch(
      candidateTitle = "Creep",
      candidateArtist = "Stone Temple Pilots",
      targetTitle = "Creep",
      targetArtist = "Radiohead"
    )
    assertFalse("Expected wrong artist with identical title to be rejected", LyricsSearchService.isVerifiedLyricsMatch(wrongArtistSameTitle, hasTargetArtist = true))

    val karaokeMismatch = LyricsSearchService.evaluateLyricsMatch(
      candidateTitle = "Creep (Karaoke Version)",
      candidateArtist = "Radiohead Tribute Band",
      targetTitle = "Creep",
      targetArtist = "Radiohead"
    )
    assertFalse("Expected karaoke/tribute cover to be rejected", LyricsSearchService.isVerifiedLyricsMatch(karaokeMismatch, hasTargetArtist = true))
  }

  @Test
  fun `genius extractor handles nested divs contributor headers and preloaded json fallback`() {
    val modernGeniusHtml = """
      <html><body>
        <div data-lyrics-container="true" class="Lyrics__Container-sc-1">
          <div data-exclude-from-selection="true">
            <div><span>12 Contributors</span><span>Translations</span></div>
            <div><button>Share</button></div>
          </div>
          [Verse 1]<br/>
          <div><span>I said, ooh, I'm blinded by the lights</span></div>
          No, I can't sleep until I feel your touch<br/>
        </div>
        <div data-lyrics-container="true" class="Lyrics__Container-sc-2">
          [Chorus]<br/>
          I said, ooh, I'm drowning in the night<br/>
          Oh, when I'm like this, you're the one I trust
          <div>You might also like</div>
          <div>Embed</div>
        </div>
      </body></html>
    """.trimIndent()

    val extracted = LyricsSearchService.extractGeniusLyricsFromHtml(modernGeniusHtml)
    assertEquals(
      listOf(
        "I said, ooh, I'm blinded by the lights",
        "No, I can't sleep until I feel your touch",
        "I said, ooh, I'm drowning in the night",
        "Oh, when I'm like this, you're the one I trust"
      ),
      extracted
    )

    val preloadedFallbackHtml = """
      <script>
        window.__PRELOADED_STATE__ = {"songPage":{"lyricsData":{"body":{"html":"<p>First line from state<br>Second line from state<br>Third line from state</p>"}}}};
      </script>
    """.trimIndent()
    val fallbackLines = LyricsSearchService.extractGeniusLyricsFromHtml(preloadedFallbackHtml)
    assertEquals(
      listOf(
        "First line from state",
        "Second line from state",
        "Third line from state"
      ),
      fallbackLines
    )
  }

  @Test
  fun `spotify token proactive refresh triggers at 12 minutes and client-token invalidates cleanly`() {
    val now = 1_700_000_000_000L
    // Fresh session (<12 min and expiry > 5 min away) -> no refresh needed
    assertFalse(
      com.example.service.AnimatedCanvasSearchService.shouldRefreshSpotifyToken(
        hasActiveToken = true,
        tokenExpiryMs = now + 30 * 60_000L,
        lastRefreshedAtMs = now - 5 * 60_000L,
        nowMs = now,
        hasRefreshCredential = true
      )
    )
    // 12+ minutes elapsed since last refresh -> proactive refresh triggers even if expiry claims longer
    assertTrue(
      com.example.service.AnimatedCanvasSearchService.shouldRefreshSpotifyToken(
        hasActiveToken = true,
        tokenExpiryMs = now + 40 * 60_000L,
        lastRefreshedAtMs = now - 13 * 60_000L,
        nowMs = now,
        hasRefreshCredential = true
      )
    )
    // Within 5 minutes of expiry -> proactive refresh triggers
    assertTrue(
      com.example.service.AnimatedCanvasSearchService.shouldRefreshSpotifyToken(
        hasActiveToken = true,
        tokenExpiryMs = now + 2 * 60_000L,
        lastRefreshedAtMs = now - 1 * 60_000L,
        nowMs = now,
        hasRefreshCredential = true
      )
    )

    // Captured client-token store + invalidation
    com.example.service.SpotifyAuthManager.captureClientToken("client-token-abc1234567890")
    assertEquals("client-token-abc1234567890", com.example.service.SpotifyAuthManager.getValidCapturedClientToken())
    com.example.service.SpotifyAuthManager.invalidateCapturedClientToken()
    assertNull(com.example.service.SpotifyAuthManager.getValidCapturedClientToken())

    // spotifyTokenLastRefreshedMs persistence
    val context = ApplicationProvider.getApplicationContext<Context>()
    val saved = CardStylePreferences.load(context).copy(spotifyTokenLastRefreshedMs = 1_712_345_678_900L)
    CardStylePreferences.save(context, saved)
    val reloaded = CardStylePreferences.load(context)
    assertEquals(1_712_345_678_900L, reloaded.spotifyTokenLastRefreshedMs)
  }

  @Test
  fun `saved canvas offline album and song list matching applies cleanly even when album tag is missing`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val dummyMp4 = java.io.File(context.cacheDir, "astro_loop_test.mp4").apply {
      writeBytes(ByteArray(8192) { 0x42 })
    }
    val item = com.example.repository.SavedCanvasItem(
      id = "sc_test_astro",
      name = "Astro Loop",
      artist = "Travis Scott",
      localVideoPath = dummyMp4.absolutePath,
      thumbnailPath = null,
      resolutionLabel = "1080×1920 MP4",
      createdAtMs = 1_000L,
      linkedAlbumName = "ASTROWORLD",
      linkedAlbumArtist = "Travis Scott",
      linkedTrackTitles = listOf("STARGAZING", "CAROUSEL", "SICKO MODE", "STOP TRYING TO BE GOD")
    )

    // 1. Matches by album + track
    val albumMatch = com.example.repository.SavedMediaLibrary.findMatchingSavedCanvasForTrack(
      title = "CAROUSEL",
      album = "ASTROWORLD",
      artist = "Travis Scott",
      items = listOf(item)
    )
    assertNotNull("Expected album + track match", albumMatch)
    assertEquals("sc_test_astro", albumMatch?.id)

    // 2. Matches offline even when player omits album tag or plays a numbered MP3 filename
    val trackOnlyOfflineMatch = com.example.repository.SavedMediaLibrary.findMatchingSavedCanvasForTrack(
      title = "04 - Stop Trying To Be God.mp3",
      album = "",
      artist = "Travis Scott",
      items = listOf(item)
    )
    assertNotNull("Expected offline match by linked song title without album metadata", trackOnlyOfflineMatch)
    assertEquals("sc_test_astro", trackOnlyOfflineMatch?.id)

    // 3. Does not match unrelated track/album
    val unrelated = com.example.repository.SavedMediaLibrary.findMatchingSavedCanvasForTrack(
      title = "Blinding Lights",
      album = "After Hours",
      artist = "The Weeknd",
      items = listOf(item)
    )
    assertNull("Expected unrelated song/album not to match", unrelated)

    // 4. Persists linkedAlbumName & linkedTrackTitles cleanly into SavedMediaLibrary index and updates repository offline
    val importedUri = android.net.Uri.fromFile(dummyMp4)
    val imported = runBlocking {
      com.example.repository.SavedMediaLibrary.importCanvasFromUri(context, importedUri, "My Offline Astro")
    }
    assertNotNull(imported)
    com.example.repository.SavedMediaLibrary.updateCanvasMetadata(
      context = context,
      id = imported!!.id,
      newName = "My Offline Astro",
      linkedAlbumName = "ASTROWORLD",
      linkedAlbumArtist = "Travis Scott",
      linkedTrackTitles = listOf("STARGAZING", "CAROUSEL", "SICKO MODE")
    )
    com.example.repository.SavedMediaLibrary.initialize(context)
    val persisted = com.example.repository.SavedMediaLibrary.savedCanvases.value.firstOrNull { it.id == imported.id }
    assertNotNull(persisted)
    assertEquals("ASTROWORLD", persisted?.linkedAlbumName)
    assertEquals(3, persisted?.linkedTrackTitles?.size)

    // Verify repository auto-applies offline canvas when playing track from that album
    com.example.repository.MediaNotificationRepository.initialize(context)
    com.example.repository.MediaNotificationRepository.updateTrack(
      MediaTrackInfo(
        title = "CAROUSEL",
        artist = "Travis Scott",
        album = "",
        appName = "Musicolet",
        packageName = "in.krosbits.musicolet",
        albumArt = null,
        isPlaying = true,
        isSample = false
      )
    )
    assertEquals("saved_canvas_${imported.id}", com.example.repository.MediaNotificationRepository.activeCanvasId.value)
    assertEquals(persisted!!.fileUri, com.example.repository.MediaNotificationRepository.activeCanvasUrl.value)

    com.example.repository.SavedMediaLibrary.deleteCanvas(context, imported.id)
    dummyMp4.delete()
  }

  @Test
  fun `smart album search parser scoring manual songs and compact artist offline matching work for 27 by ElGrandeToto`() {
    val byParsed = com.example.service.AlbumTracklistSearchService.parseSmartAlbumQuery(
      albumQuery = "album 27 by elgrandetoto",
      artistHint = ""
    )
    assertEquals("27", byParsed.album)
    assertEquals("elgrandetoto", byParsed.artist)

    val dashParsed = com.example.service.AlbumTracklistSearchService.parseSmartAlbumQuery(
      albumQuery = "27 - ElGrandeToto",
      artistHint = ""
    )
    assertEquals("27", dashParsed.album)
    assertEquals("ElGrandeToto", dashParsed.artist)

    val explicitParsed = com.example.service.AlbumTracklistSearchService.parseSmartAlbumQuery(
      albumQuery = "album 27",
      artistHint = "ElGrandeToto"
    )
    assertEquals("27", explicitParsed.album)
    assertEquals("ElGrandeToto", explicitParsed.artist)

    // Scoring: exact "27" by "ElGrandeToto" (or spaced "El Grande Toto") must far outscore featured/compilation albums
    val exactScore = com.example.service.AlbumTracklistSearchService.scoreAlbumCandidateMatch(
      candidateAlbum = "27",
      candidateArtist = "El Grande Toto",
      targetAlbum = "27",
      targetArtist = "ElGrandeToto",
      trackCountHint = 17
    )
    val compilationScore = com.example.service.AlbumTracklistSearchService.scoreAlbumCandidateMatch(
      candidateAlbum = "27 Hits",
      candidateArtist = "Various Artists",
      targetAlbum = "27",
      targetArtist = "ElGrandeToto",
      trackCountHint = 20
    )
    val wrongFeaturedAlbumScore = com.example.service.AlbumTracklistSearchService.scoreAlbumCandidateMatch(
      candidateAlbum = "Toto Signé",
      candidateArtist = "Other Artist",
      targetAlbum = "27",
      targetArtist = "ElGrandeToto",
      trackCountHint = 15
    )
    assertTrue("Expected exact 27 score ($exactScore) > compilation ($compilationScore)", exactScore > compilationScore + 300)
    assertTrue("Expected exact 27 score ($exactScore) > wrong album ($wrongFeaturedAlbumScore)", exactScore > wrongFeaturedAlbumScore + 500)

    // Manual song list parser handles numbered lines, semicolons, and deduplicates
    val manualSongs = com.example.service.AlbumTracklistSearchService.parseManualSongNames(
      "1. Dellali\n02 - Weld Laadoul\n; Mghayer ; dellali\n"
    )
    assertEquals(listOf("Dellali", "Weld Laadoul", "Mghayer"), manualSongs)

    // Offline SavedMediaLibrary match works with 2-char album "27", spaced vs unspaced artist, and manual song names
    val dummyMp4 = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "elgrandetoto_27.mp4").apply {
      writeBytes(ByteArray(4096) { 0x11 })
    }
    val linkedItem = com.example.repository.SavedCanvasItem(
      id = "sc_27_toto",
      name = "27 Canvas",
      artist = "Device Media",
      localVideoPath = dummyMp4.absolutePath,
      thumbnailPath = null,
      resolutionLabel = "1080×1920 MP4",
      createdAtMs = 2_000L,
      linkedAlbumName = "27",
      linkedAlbumArtist = "ElGrandeToto",
      linkedTrackTitles = manualSongs
    )
    val offlineMatchByManualSong = SavedMediaLibrary.findMatchingSavedCanvasForTrack(
      title = "Weld Laadoul",
      album = "",
      artist = "El Grande Toto",
      items = listOf(linkedItem)
    )
    assertNotNull("Expected offline match by manual song & space-insensitive artist", offlineMatchByManualSong)
    assertEquals("sc_27_toto", offlineMatchByManualSong?.id)

    val offlineMatchBy2CharAlbum = SavedMediaLibrary.findMatchingSavedCanvasForTrack(
      title = "Some Unlisted Bonus Track",
      album = "27",
      artist = "El Grande Toto",
      items = listOf(linkedItem)
    )
    assertNotNull("Expected offline match by 2-char album name '27'", offlineMatchBy2CharAlbum)
    assertEquals("sc_27_toto", offlineMatchBy2CharAlbum?.id)

    dummyMp4.delete()
  }

  @Test
  fun `inline 1- 2- song parser and offline feat ft avec title equivalence match across localized players`() {
    // 1. Single-line numbered song list "1- ... 2- ... 3- ..." splits cleanly into vertical list items
    val inlineNumbered = com.example.service.AlbumTracklistSearchService.parseManualSongNames(
      "1- lol (feat.toto) 2- Dellali 3- Mghayer"
    )
    assertEquals(listOf("lol (feat.toto)", "Dellali", "Mghayer"), inlineNumbered)

    val inlineTightDash = com.example.service.AlbumTracklistSearchService.parseManualSongNames(
      "1-Blue 2-Red 3-Green"
    )
    assertEquals(listOf("Blue", "Red", "Green"), inlineTightDash)

    // Single song containing a hyphenated number stays intact
    val hyphenTitle = com.example.service.AlbumTracklistSearchService.parseManualSongNames("7-Eleven")
    assertEquals(listOf("7-Eleven"), hyphenTitle)

    // 2. Offline SavedMediaLibrary matches "lol (feat.toto)" when played as "lol (avec toto)", "lol (ft toto)", "lol ft. toto", or "lol avec toto"
    val dummyMp4 = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "feat_avec_test.mp4").apply {
      writeBytes(ByteArray(4096) { 0x22 })
    }
    val item = com.example.repository.SavedCanvasItem(
      id = "sc_feat_avec",
      name = "Collab Canvas",
      artist = "Main Artist",
      localVideoPath = dummyMp4.absolutePath,
      thumbnailPath = null,
      resolutionLabel = "1080×1920 MP4",
      createdAtMs = 3_000L,
      linkedAlbumName = "Collab Tape",
      linkedAlbumArtist = "ElGrandeToto",
      linkedTrackTitles = inlineNumbered
    )

    val frenchAvecParen = SavedMediaLibrary.findMatchingSavedCanvasForTrack(
      title = "lol (avec toto)",
      album = "",
      artist = "El Grande Toto avec Toto",
      items = listOf(item)
    )
    assertNotNull("Expected French '(avec toto)' to match '(feat.toto)' offline", frenchAvecParen)
    assertEquals("sc_feat_avec", frenchAvecParen?.id)

    val frenchAvecUnbracketed = SavedMediaLibrary.findMatchingSavedCanvasForTrack(
      title = "lol avec toto",
      album = "",
      artist = "ElGrandeToto",
      items = listOf(item)
    )
    assertNotNull("Expected unbracketed 'lol avec toto' to match 'lol (feat.toto)' offline", frenchAvecUnbracketed)

    val shortFtVariant = SavedMediaLibrary.findMatchingSavedCanvasForTrack(
      title = "lol ft toto",
      album = "",
      artist = "ElGrandeToto",
      items = listOf(item)
    )
    assertNotNull("Expected 'lol ft toto' to match 'lol (feat.toto)' offline", shortFtVariant)

    // 3. AnimatedCanvasSearchService normalizes (avec ...) and ft without dot too
    assertEquals("lol", AnimatedCanvasSearchService.normalizeQueryText("lol (avec toto)"))
    assertEquals("lol", AnimatedCanvasSearchService.normalizeQueryText("lol ft toto"))
    assertEquals("ElGrandeToto", AnimatedCanvasSearchService.normalizeArtistText("ElGrandeToto avec Toto"))

    dummyMp4.delete()
  }

  @Test
  fun `waveform force control persists in preferences and boosts bar punch when beat sync is off or on`() {
    val context = ApplicationProvider.getApplicationContext<Context>()

    // 1. Default & custom waveformForce persistence + resetStyleValues
    val defaults = CardStyleConfig()
    assertEquals(1.35f, defaults.waveformForce, 0.001f)

    val customCfg = defaults.copy(enableWaveform = true, waveformBeatSync = false, waveformForce = 2.0f)
    CardStylePreferences.save(context, customCfg)
    val loaded = CardStylePreferences.load(context)
    assertEquals(2.0f, loaded.waveformForce, 0.001f)

    com.example.repository.MediaNotificationRepository.initialize(context)
    com.example.repository.MediaNotificationRepository.updateStyleConfig(saveImmediately = true) { customCfg }
    com.example.repository.MediaNotificationRepository.resetStyleValues()
    assertEquals(
      defaults.waveformForce,
      com.example.repository.MediaNotificationRepository.styleConfig.value.waveformForce,
      0.001f
    )

    // 2. When Beat Sync is OFF, higher forceMultiplier raises bar heights cleanly to full peak punch
    var maxLowForce = 0f
    var maxHighForce = 0f
    var sumLowForce = 0f
    var sumHighForce = 0f
    for (step in 0 until 24) {
      val t = step * 0.08f
      val lowBars = AudioBeatMonitor.computeBarLevels(
        trackTitle = "Force Test Track",
        trackArtist = "EchoSnap",
        isPlaying = true,
        timeSec = t,
        beatSyncMode = false,
        forceMultiplier = 0.6f
      )
      val highBars = AudioBeatMonitor.computeBarLevels(
        trackTitle = "Force Test Track",
        trackArtist = "EchoSnap",
        isPlaying = true,
        timeSec = t,
        beatSyncMode = false,
        forceMultiplier = 2.0f
      )
      for (b in 0..3) {
        if (lowBars[b] > maxLowForce) maxLowForce = lowBars[b]
        if (highBars[b] > maxHighForce) maxHighForce = highBars[b]
        sumLowForce += lowBars[b]
        sumHighForce += highBars[b]
      }
    }
    assertTrue("Expected high force peak >= 0.92f (got $maxHighForce)", maxHighForce >= 0.92f)
    assertTrue("Expected high force mean ($sumHighForce) > low force mean ($sumLowForce)", sumHighForce > sumLowForce + 10f)

    // 3. BeatEnvelopeTracker reaches high dynamic peaks even at moderate FFT signal levels
    val tracker = AudioBeatMonitor.BeatEnvelopeTracker()
    val moderateBassFft = ByteArray(256)
    moderateBassFft[2] = 42
    moderateBassFft[3] = 28
    val firstBeat = tracker.ingestFft(moderateBassFft, samplingRateMilliHz = 48_000_000, nowNanos = 2_000_000_000L)
    assertTrue("Expected responsive beat height >= 0.85f on moderate/loud kick (got ${firstBeat[0]})", firstBeat[0] >= 0.85f)
  }

  @Test
  fun `saved cover album linking prefill parser and offline auto-matching work seamlessly`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    SavedMediaLibrary.initialize(context)

    // 1. Smart edit pre-fill splits "27 - Elgrande toto" cleanly without searching
    val parsedFromName = com.example.service.AlbumTracklistSearchService.parseSavedItemInitialAlbumAndArtist(
      itemName = "27 - Elgrande toto",
      itemArtist = "iTunes HD",
      linkedAlbumName = "",
      linkedAlbumArtist = ""
    )
    assertEquals("27", parsedFromName.album)
    assertEquals("Elgrande toto", parsedFromName.artist)

    // Catalog suffix stripped & existing linked album preserved
    val parsedFromCatalog = com.example.service.AlbumTracklistSearchService.parseSavedItemInitialAlbumAndArtist(
      itemName = "UTOPIA - Spotify",
      itemArtist = "Travis Scott",
      linkedAlbumName = "",
      linkedAlbumArtist = ""
    )
    assertEquals("UTOPIA", parsedFromCatalog.album)
    assertEquals("Travis Scott", parsedFromCatalog.artist)

    // 2. Save a cover image, update its metadata via updateCoverMetadata, and verify persistence + offline matching
    val coverBmp = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
    Canvas(coverBmp).drawColor(AndroidColor.rgb(40, 180, 90))
    val saved = runBlocking {
      SavedMediaLibrary.saveCoverFromBitmap(
        context = context,
        bitmap = coverBmp,
        customName = "27 - Elgrande toto",
        artist = "Device Photo"
      )
    }
    coverBmp.recycle()
    assertNotNull(saved)

    SavedMediaLibrary.updateCoverMetadata(
      context = context,
      id = saved!!.id,
      newName = "27 - Elgrande toto",
      linkedAlbumName = parsedFromName.album,
      linkedAlbumArtist = parsedFromName.artist,
      linkedTrackTitles = listOf("lol (feat.toto)", "Dellali", "Mghayer")
    )
    val updated = SavedMediaLibrary.savedCovers.value.firstOrNull { it.id == saved.id }
    assertNotNull(updated)
    assertEquals("27", updated!!.linkedAlbumName)
    assertEquals("Elgrande toto", updated.linkedAlbumArtist)
    assertEquals(listOf("lol (feat.toto)", "Dellali", "Mghayer"), updated.linkedTrackTitles)

    // Reload from SharedPreferences to verify persistence
    SavedMediaLibrary.initialize(context)
    val reloadedCover = SavedMediaLibrary.savedCovers.value.firstOrNull { it.id == saved.id }
    assertNotNull(reloadedCover)
    assertEquals("27", reloadedCover!!.linkedAlbumName)
    assertEquals("Elgrande toto", reloadedCover.linkedAlbumArtist)

    // Offline matching with album "27" & spaced artist, AND manual song list with French "avec" variant
    val matchByAlbum = SavedMediaLibrary.findMatchingSavedCoverForTrack(
      title = "Bonus Track",
      album = "27",
      artist = "El Grande Toto",
      items = listOf(reloadedCover)
    )
    assertNotNull("Expected offline cover match by album '27'", matchByAlbum)
    assertEquals(saved.id, matchByAlbum?.id)

    val matchByAvecTrack = SavedMediaLibrary.findMatchingSavedCoverForTrack(
      title = "lol (avec toto)",
      album = "",
      artist = "El Grande Toto",
      items = listOf(reloadedCover)
    )
    assertNotNull("Expected offline cover match for 'lol (avec toto)'", matchByAvecTrack)
    assertEquals(saved.id, matchByAvecTrack?.id)

    SavedMediaLibrary.deleteCover(context, saved.id)
  }

  @Test
  fun `beat envelope tracker decays cleanly at volume 0 and resumes without glitching`() {
    val tracker = AudioBeatMonitor.BeatEnvelopeTracker()
    val loudFft = ByteArray(256).apply {
      this[2] = 60
      this[3] = 45
      this[8] = 35
      this[9] = 25
    }
    var tNs = 1_000_000_000L
    val kickLevels = tracker.ingestFft(loudFft, 48_000_000, nowNanos = tNs)
    assertTrue("Expected initial loud beat >= 0.80f (got ${kickLevels[0]})", kickLevels[0] >= 0.80f)

    // Simulate volume turned to 0 for 2.5 seconds (silent capture frames every 50ms -> past old 1.5s cutoff)
    val silentFft = ByteArray(256)
    var latestSilent = FloatArray(4)
    repeat(50) {
      tNs += 50_000_000L
      latestSilent = tracker.ingestFft(silentFft, 48_000_000, nowNanos = tNs)
    }

    // Feed must still report active capture so UI doesn't switch to synthetic fallback at volume 0
    assertTrue("Expected capture feed to stay active while receiving silent frames", tracker.hasActiveCaptureFeed(tNs))
    for (i in 0..3) {
      assertTrue(
        "Expected bar $i settled near resting floor at volume 0 (got ${latestSilent[i]})",
        latestSilent[i] <= 0.22f
      )
    }

    // Snapshot while paused/volume-0 stays smoothly at resting level (no random fallback jump)
    val snap = tracker.snapshotAt(tNs)
    assertNotNull("Expected non-null snapshot while capture feed is active at volume 0", snap)
    for (i in 0..3) {
      assertTrue("Expected snapshot bar $i resting <= 0.22f (got ${snap!![i]})", snap[i] <= 0.22f)
    }

    // Resume quiet audio after volume 0: must not over-amplify or jump straight to 1.0
    val softResumeFft = ByteArray(256).apply {
      this[2] = 8
      this[3] = 6
    }
    tNs += 50_000_000L
    val resumedSoft = tracker.ingestFft(softResumeFft, 48_000_000, nowNanos = tNs)
    assertTrue(
      "Expected gentle resume on soft audio after silence without spiking to 1.0 (got ${resumedSoft[0]})",
      resumedSoft[0] < 0.75f
    )
  }
}
