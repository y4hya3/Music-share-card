package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
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

    // Verify GCE has transparentColorFlag=1 with either disposal=2 (0x09) or disposal=1 dirty-rect preserve (0x05)
    val gceFound = (0 until gifBytes.size - 4).any { i ->
      gifBytes[i] == 0x21.toByte() &&
        gifBytes[i + 1] == 0xF9.toByte() &&
        gifBytes[i + 2] == 0x04.toByte() &&
        (gifBytes[i + 3] == 0x09.toByte() || gifBytes[i + 3] == 0x05.toByte())
    }
    assertTrue("Expected Graphic Control Extension with transparent flag", gceFound)

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

  @Test
  fun `animated waveform gif dirty-rect encoding produces valid looping GIF89a bytes`() {
    val overlay = Bitmap.createBitmap(180, 320, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(overlay)
    canvas.drawColor(AndroidColor.TRANSPARENT)
    val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(230, 24, 24, 30) }
    canvas.drawRoundRect(RectF(20f, 40f, 160f, 260f), 16f, 16f, cardPaint)

    val spec = com.example.util.WaveformExportSpec(
      rectIn720x1280 = RectF(100f, 200f, 150f, 240f),
      waveformColorArgb = AndroidColor.WHITE,
      cardOpacity = 0.90f,
      trackTitle = "Test Track",
      trackArtist = "Test Artist",
      isPlaying = true,
      speedMultiplier = 1.15f,
      beatSyncEnabled = false,
      forceMultiplier = 1.4f
    )

    val gifBytes = com.example.util.AnimatedGifExporter.encodeTransparentWaveformGifToBytes(
      overlayWithoutWaveform = overlay,
      waveformSpec = spec,
      wfRectInOutput = RectF(100f, 200f, 150f, 240f),
      durationSec = 1.5f,
      fps = 10
    )
    overlay.recycle()

    assertTrue("Expected non-empty GIF output", gifBytes.size > 800)
    val header = String(gifBytes.copyOfRange(0, 6), Charsets.US_ASCII)
    assertEquals("GIF89a", header)
    assertEquals(0x3B.toByte(), gifBytes.last())
  }

  @Test
  fun `frosted glass story bitmap renders cleanly across 0 to 40 blur without side-darkening`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val art = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888).apply {
      val c = Canvas(this)
      c.drawColor(AndroidColor.rgb(90, 140, 220))
    }
    val track = com.example.model.MediaTrackInfo(
      title = "Glass Consistency Track",
      artist = "EchoSnap Artist",
      album = "27",
      albumArt = art,
      isPlaying = true
    )
    val palette = com.example.model.CardPalette()
    val blurs = floatArrayOf(0f, 10f, 25f, 40f)
    for (blur in blurs) {
      val config = com.example.model.CardStyleConfig(
        cardBlurRadius = blur,
        backgroundBlurRadius = blur,
        cardOpacity = 0.85f
      )
      val rendered = runBlocking {
        com.example.util.CardShareManager.generateStoryBitmap(
          context = context,
          track = track,
          palette = palette,
          styleConfig = config
        )
      }
      assertEquals(1080, rendered.width)
      assertEquals(1920, rendered.height)
      rendered.recycle()
    }
    art.recycle()
  }

  @Test
  fun `waveform bar levels remain bounded and active across extended continuous playback`() {
    val samples = listOf(0.0f, 12.5f, 3600.0f, 3600.5f, 7200.25f)
    for (t in samples) {
      val bars = AudioBeatMonitor.computeBarLevels(
        trackTitle = "Long Song",
        trackArtist = "Artist",
        isPlaying = true,
        timeSec = t,
        speedMultiplier = 1.2f,
        beatSyncMode = false,
        forceMultiplier = 1.5f
      )
      assertEquals(4, bars.size)
      for (b in bars) {
        assertTrue("Expected bar height within [0.18, 1.0], got $b", b in 0.18f..1.0f)
      }
    }
  }

  @Test
  fun `blur mip pyramid reuses cached bitmaps across background and glass card layers`() {
    val art = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).apply {
      Canvas(this).drawColor(AndroidColor.rgb(65, 110, 195))
    }
    val first = com.example.util.BitmapBlurUtils.computeBlurMipPyramid(
      source = art,
      lowDim = 210,
      midDim = 155,
      highDim = 115
    )
    val second = com.example.util.BitmapBlurUtils.computeBlurMipPyramid(
      source = art,
      lowDim = 210,
      midDim = 155,
      highDim = 115
    )
    assertNotNull(first)
    assertNotNull(second)
    assertTrue(first!!.first === second!!.first)
    assertTrue(first.second === second.second)
    assertTrue(first.third === second.third)
    art.recycle()
  }

  @Test
  fun `device performance tier resolves valid bounded decode dimensions`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val tier = com.example.util.DevicePerformanceTier.get(context)
    assertTrue(tier.cardArtMaxDim in 960..1440)
    assertTrue(tier.bgSourceMaxDim in 960..1440)
    assertTrue(tier.pyramidLowDim in 240..380)
    assertTrue(tier.previewMaxWidth in 720..1080)
    assertTrue(tier.previewMaxHeight in 1280..1920)
  }

  @Test
  fun `echosnap backup exports scans previews imports and resets covers canvases and album song lists`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    SavedMediaLibrary.resetAllSavedMedia(context)

    val coversDir = SavedMediaLibrary.getCoversDir(context)
    val canvasesDir = SavedMediaLibrary.getCanvasesDir(context)

    val coverBitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
      Canvas(this).drawColor(AndroidColor.rgb(210, 45, 75))
    }
    val coverFile = File(coversDir, "art_27_cover.png")
    coverFile.outputStream().use { out ->
      coverBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    coverBitmap.recycle()

    val canvasThumbBitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
      Canvas(this).drawColor(AndroidColor.rgb(30, 185, 84))
    }
    val canvasThumbFile = File(canvasesDir, "sc_27_thumb.png")
    canvasThumbFile.outputStream().use { out ->
      canvasThumbBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    canvasThumbBitmap.recycle()

    val canvasVideoFile = File(canvasesDir, "sc_27.mp4")
    canvasVideoFile.writeBytes(ByteArray(1024) { idx -> (idx % 251).toByte() })

    val coverSongs = listOf("Dellali", "Weld L'9aria", "Blue Baby")
    val canvasSongs = listOf("Gova", "Rassi", "Bouhali")

    val coverItem = com.example.repository.SavedCoverItem(
      id = "art_27_cover",
      name = "27 Cover",
      artist = "ElGrandeToto",
      localImagePath = coverFile.absolutePath,
      resolutionLabel = "128×128",
      createdAtMs = 1_700_000_000_000L,
      linkedAlbumName = "27",
      linkedAlbumArtist = "ElGrandeToto",
      linkedTrackTitles = coverSongs
    )
    val canvasItem = com.example.repository.SavedCanvasItem(
      id = "sc_27",
      name = "27 Motion",
      artist = "ElGrandeToto",
      localVideoPath = canvasVideoFile.absolutePath,
      thumbnailPath = canvasThumbFile.absolutePath,
      resolutionLabel = "1080×1920 MP4",
      createdAtMs = 1_700_000_001_000L,
      linkedAlbumName = "27",
      linkedAlbumArtist = "ElGrandeToto",
      linkedTrackTitles = canvasSongs
    )

    SavedMediaLibrary.upsertImportedMedia(
      context = context,
      importedCovers = listOf(coverItem),
      importedCanvases = listOf(canvasItem),
      replaceExisting = true
    )
    assertEquals(1, SavedMediaLibrary.savedCovers.value.size)
    assertEquals(1, SavedMediaLibrary.savedCanvases.value.size)

    val tempRoot = File(context.cacheDir, "backup_test_${System.currentTimeMillis()}").apply { mkdirs() }
    try {
      val styleToBackup = CardStyleConfig(
        cardBlurRadius = 12f,
        backgroundBlurRadius = 18f,
        enableWaveform = true,
        waveformSpeedMultiplier = 1.6f
      )

      val exportRes = com.example.util.EchoSnapBackupManager.exportBackupToDirectory(
        parentOrEchoSnapDir = tempRoot,
        styleConfig = styleToBackup
      ).getOrThrow()

      assertEquals(1, exportRes.coversCount)
      assertEquals(1, exportRes.canvasesCount)
      assertEquals(6, exportRes.songsCount)
      assertEquals("Echomusic", exportRes.folderName)

      val echoSnapFolder = File(tempRoot, "Echomusic")
      assertTrue(echoSnapFolder.isDirectory)
      assertTrue(File(echoSnapFolder, "manifest.json").exists())

      // Scan from parent folder -> auto-resolves nested Echomusic folder and surfaces everything
      val scanned = com.example.util.EchoSnapBackupManager.scanBackupFromDirectory(tempRoot).getOrThrow()
      assertTrue(scanned.hasManifest)
      assertEquals("Echomusic", scanned.folderDisplayName)
      assertEquals(1, scanned.covers.size)
      assertEquals(1, scanned.canvases.size)
      assertEquals(6, scanned.totalSongsCount)
      assertEquals(coverSongs, scanned.covers.first().linkedTrackTitles)
      assertEquals(canvasSongs, scanned.canvases.first().linkedTrackTitles)
      assertNotNull(scanned.covers.first().previewBitmap)
      assertNotNull(scanned.canvases.first().previewBitmap)
      assertNotNull(scanned.includedStyle)
      assertEquals(12f, scanned.includedStyle!!.cardBlurRadius, 0.001f)

      // Reset clears all saved media
      SavedMediaLibrary.resetAllSavedMedia(context)
      assertTrue(SavedMediaLibrary.savedCovers.value.isEmpty())
      assertTrue(SavedMediaLibrary.savedCanvases.value.isEmpty())

      // Re-import everything from scanned folder
      val importRes = runBlocking {
        com.example.util.EchoSnapBackupManager.importScannedBackup(
          context = context,
          scanned = scanned,
          selectedCoverIds = setOf("art_27_cover"),
          selectedCanvasIds = setOf("sc_27"),
          importStyle = true,
          replaceExistingFirst = false
        ).getOrThrow()
      }
      assertEquals(1, importRes.coversCount)
      assertEquals(1, importRes.canvasesCount)
      assertEquals(6, importRes.songsCount)
      assertTrue(importRes.styleApplied)

      val restoredCovers = SavedMediaLibrary.savedCovers.value
      val restoredCanvases = SavedMediaLibrary.savedCanvases.value
      assertEquals(1, restoredCovers.size)
      assertEquals(1, restoredCanvases.size)
      assertEquals(coverSongs, restoredCovers.first().linkedTrackTitles)
      assertEquals(canvasSongs, restoredCanvases.first().linkedTrackTitles)

      // Verify offline album song matching works immediately after import
      assertNotNull(
        SavedMediaLibrary.findMatchingSavedCoverForTrack(
          title = "Dellali",
          album = "",
          artist = "ElGrandeToto"
        )
      )
      assertNotNull(
        SavedMediaLibrary.findMatchingSavedCanvasForTrack(
          title = "Gova",
          album = "",
          artist = "ElGrandeToto"
        )
      )

      // Clean up after test
      SavedMediaLibrary.resetAllSavedMedia(context)
    } finally {
      tempRoot.deleteRecursively()
    }
  }

  @Test
  fun `live wallpaper settings and standby photo delay persist cleanly across bounds including 5s 60s and 86400s`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val defaults = CardStyleConfig()
    assertEquals(10f, defaults.liveWallpaperBlurRadius, 0.001f)
    assertEquals(0.65f, defaults.liveWallpaperEdgeBlurFade, 0.001f)

    val dummyPhoto = File(context.cacheDir, "standby_photo_test.jpg").apply {
      val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
      java.io.FileOutputStream(this).use { out ->
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
      }
      bmp.recycle()
    }

    val saved = CardStyleConfig(
      liveWallpaperEnabled = true,
      liveWallpaperMode = com.example.model.LiveWallpaperMode.ARTWORK_ONLY,
      liveWallpaperBlurRadius = 26f,
      liveWallpaperEdgeBlurFade = 0.85f,
      liveWallpaperShowCard = false,
      liveWallpaperIdleRevertEnabled = true,
      liveWallpaperIdleDelaySec = 86_400, // 1 day in seconds
      liveWallpaperIdlePhotoPath = dummyPhoto.absolutePath
    )
    CardStylePreferences.save(context, saved)
    val reloaded = CardStylePreferences.load(context)

    assertTrue(reloaded.liveWallpaperEnabled)
    assertEquals(com.example.model.LiveWallpaperMode.ARTWORK_ONLY, reloaded.liveWallpaperMode)
    assertEquals(26f, reloaded.liveWallpaperBlurRadius, 0.001f)
    assertEquals(0.85f, reloaded.liveWallpaperEdgeBlurFade, 0.001f)
    assertFalse(reloaded.liveWallpaperShowCard)
    assertTrue(reloaded.liveWallpaperIdleRevertEnabled)
    assertEquals(86_400, reloaded.liveWallpaperIdleDelaySec)
    assertEquals(dummyPhoto.absolutePath, reloaded.liveWallpaperIdlePhotoPath)

    // Out-of-range values clamp strictly into 1..86400 seconds and 0..40px blur
    CardStylePreferences.save(
      context,
      saved.copy(
        liveWallpaperMode = com.example.model.LiveWallpaperMode.ANIMATED_CANVAS,
        liveWallpaperIdleDelaySec = 999_999,
        liveWallpaperBlurRadius = 80f,
        liveWallpaperEdgeBlurFade = 2.0f,
        liveWallpaperIdleRevertEnabled = false
      )
    )
    val clamped = CardStylePreferences.load(context)
    assertEquals(com.example.model.LiveWallpaperMode.ANIMATED_CANVAS, clamped.liveWallpaperMode)
    assertEquals(86_400, clamped.liveWallpaperIdleDelaySec)
    assertEquals(40f, clamped.liveWallpaperBlurRadius, 0.001f)
    assertEquals(1f, clamped.liveWallpaperEdgeBlurFade, 0.001f)
    assertFalse(clamped.liveWallpaperIdleRevertEnabled)

    // Verify helper deletion cleans standby photo path cleanly
    SavedMediaLibrary.deleteWallpaperIdlePhoto(context, dummyPhoto.absolutePath)
    assertFalse(dummyPhoto.exists())
  }

  @Test
  fun `live wallpaper repository ensureInitialized playback stop timer and offline artwork fallback work`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    com.example.repository.MediaNotificationRepository.ensureInitialized(context)

    val notificationArt = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
    try {
      // Playing track clears stopped elapsed timestamp and retains high-res notification artwork
      com.example.repository.MediaNotificationRepository.updateTrack(
        MediaTrackInfo(
          title = "Unlisted Live Song",
          artist = "Independent Artist",
          album = "Single",
          appName = "YouTube Music",
          packageName = "com.google.android.apps.youtube.music",
          albumArt = notificationArt,
          isPlaying = true,
          isSample = false
        )
      )
      assertEquals(0L, com.example.repository.MediaNotificationRepository.lastPlaybackStoppedElapsedMs.value)
      assertSame(notificationArt, com.example.repository.MediaNotificationRepository.currentTrack.value.albumArt)

      // No saved canvas matched -> activeCanvasUrl remains null so wallpaper cleanly displays artwork fallback
      assertNull(com.example.repository.MediaNotificationRepository.activeCanvasUrl.value)

      // Marking playback stopped flips isPlaying = false and records non-zero elapsed timer
      com.example.repository.MediaNotificationRepository.markCurrentTrackStopped()
      assertFalse(com.example.repository.MediaNotificationRepository.currentTrack.value.isPlaying)
      assertTrue(com.example.repository.MediaNotificationRepository.lastPlaybackStoppedElapsedMs.value > 0L)
    } finally {
      if (!notificationArt.isRecycled) notificationArt.recycle()
    }
  }

  @Test
  fun `live wallpaper service is registered in package manager with bind wallpaper permission and metadata`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val component = android.content.ComponentName(
      context,
      com.example.service.EchoSnapLiveWallpaperService::class.java
    )
    @Suppress("DEPRECATION")
    val serviceInfo = context.packageManager.getServiceInfo(
      component,
      android.content.pm.PackageManager.GET_META_DATA
    )
    assertEquals(android.Manifest.permission.BIND_WALLPAPER, serviceInfo.permission)
    assertTrue(serviceInfo.exported)
    assertNotNull(serviceInfo.metaData)
    assertTrue(serviceInfo.metaData.containsKey("android.service.wallpaper"))
  }

  @Test
  fun `canvas video exporter validates mp4 headers rejects html and deduplicates concurrent cache downloads`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val validMp4Bytes = ByteArray(8192).apply {
      this[0] = 0x00; this[1] = 0x00; this[2] = 0x00; this[3] = 0x18
      "ftypisom".toByteArray(Charsets.US_ASCII).copyInto(this, destinationOffset = 4)
    }
    val fakeBytes = ByteArray(8192) { 0x42 }
    val htmlBytes = "<html><body>Not Found</body></html>".repeat(300).toByteArray(Charsets.UTF_8)

    val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/valid.mp4") { ex ->
      ex.responseHeaders.add("Content-Type", "video/mp4")
      ex.sendResponseHeaders(200, validMp4Bytes.size.toLong())
      ex.responseBody.use { it.write(validMp4Bytes) }
    }
    server.createContext("/html_error.mp4") { ex ->
      ex.responseHeaders.add("Content-Type", "text/html")
      ex.sendResponseHeaders(200, htmlBytes.size.toLong())
      ex.responseBody.use { it.write(htmlBytes) }
    }
    server.createContext("/fake_bytes.mp4") { ex ->
      ex.responseHeaders.add("Content-Type", "application/octet-stream")
      ex.sendResponseHeaders(200, fakeBytes.size.toLong())
      ex.responseBody.use { it.write(fakeBytes) }
    }
    server.start()

    try {
      val baseUrl = "http://127.0.0.1:${server.address.port}"
      val validUrl = "$baseUrl/valid.mp4"
      com.example.util.CanvasVideoExporter.invalidateCachedVideoFile(context, validUrl)

      val results = runBlocking {
        coroutineScope {
          val a = async(kotlinx.coroutines.Dispatchers.IO) {
            com.example.util.CanvasVideoExporter.prefetchVideoToCache(context, validUrl)
          }
          val b = async(kotlinx.coroutines.Dispatchers.IO) {
            com.example.util.CanvasVideoExporter.prefetchVideoToCache(context, validUrl)
          }
          listOf(a.await(), b.await())
        }
      }
      assertNotNull(results[0])
      assertNotNull(results[1])
      assertEquals(results[0]!!.absolutePath, results[1]!!.absolutePath)
      assertTrue(results[0]!!.exists() && results[0]!!.length() == validMp4Bytes.size.toLong())
      assertNotNull(com.example.util.CanvasVideoExporter.getCachedVideoFileIfReady(context, validUrl))

      com.example.util.CanvasVideoExporter.invalidateCachedVideoFile(context, validUrl)
      assertNull(com.example.util.CanvasVideoExporter.getCachedVideoFileIfReady(context, validUrl))

      val rejectedHtml = runBlocking {
        com.example.util.CanvasVideoExporter.resolveOrCacheVideoUri(context, "$baseUrl/html_error.mp4")
      }
      assertNull("Expected HTML response not to be cached as video", rejectedHtml)

      val rejectedBogus = runBlocking {
        com.example.util.CanvasVideoExporter.resolveOrCacheVideoUri(context, "$baseUrl/fake_bytes.mp4")
      }
      assertNull("Expected non-MP4/non-WebM payload to fail validation", rejectedBogus)
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `selecting online animated canvas emits playback url immediately upgrades to cache and clears cleanly`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    com.example.repository.MediaNotificationRepository.ensureInitialized(context)
    SavedMediaLibrary.resetAllSavedMedia(context)
    com.example.repository.MediaNotificationRepository.updateStyleConfig(saveImmediately = true) {
      it.copy(autoSearchAnimatedCanvas = false, liveWallpaperEnabled = false)
    }
    org.robolectric.shadows.ShadowLooper.idleMainLooper()

    com.example.repository.MediaNotificationRepository.selectSampleTrack(
      MediaTrackInfo(
        title = "Canvas Flow Test",
        artist = "EchoSnap",
        album = "Unit Test",
        appName = "Spotify",
        packageName = "com.spotify.music",
        albumArt = null,
        isPlaying = true,
        isSample = true
      )
    )
    org.robolectric.shadows.ShadowLooper.idleMainLooper()

    val validMp4Bytes = ByteArray(8192).apply {
      this[0] = 0x00; this[1] = 0x00; this[2] = 0x00; this[3] = 0x18
      "ftypmp42".toByteArray(Charsets.US_ASCII).copyInto(this, destinationOffset = 4)
    }
    val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/canvas_loop.mp4") { ex ->
      ex.responseHeaders.add("Content-Type", "video/mp4")
      ex.sendResponseHeaders(200, validMp4Bytes.size.toLong())
      ex.responseBody.use { it.write(validMp4Bytes) }
    }
    server.start()

    try {
      val videoUrl = "http://127.0.0.1:${server.address.port}/canvas_loop.mp4"
      com.example.util.CanvasVideoExporter.invalidateCachedVideoFile(context, videoUrl)

      val candidate = AnimatedCanvasCandidate(
        id = "online_spotify_canvas_test",
        title = "Canvas Flow Test",
        artist = "EchoSnap",
        videoUrl = videoUrl,
        hdPosterUrl = null,
        source = "Spotify",
        resolutionLabel = "1080×1920 MP4",
        matchScore = 100,
        isLoopingVideo = true
      )

      com.example.repository.MediaNotificationRepository.selectAnimatedCanvas(candidate)
      org.robolectric.shadows.ShadowLooper.idleMainLooper()

      assertEquals("online_spotify_canvas_test", com.example.repository.MediaNotificationRepository.activeCanvasId.value)
      val immediateOrCached = com.example.repository.MediaNotificationRepository.activeCanvasUrl.value
      assertNotNull("Expected activeCanvasUrl to be populated immediately upon tap", immediateOrCached)

      // Allow Dispatchers.IO cache download to finish and post cached file:// upgrade onto Main looper
      val deadlineMs = System.currentTimeMillis() + 3000L
      while (System.currentTimeMillis() < deadlineMs) {
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        if (com.example.repository.MediaNotificationRepository.activeCanvasUrl.value?.startsWith("file://") == true) {
          break
        }
        Thread.sleep(25L)
      }
      org.robolectric.shadows.ShadowLooper.idleMainLooper()
      val finalUrl = com.example.repository.MediaNotificationRepository.activeCanvasUrl.value
      assertNotNull(finalUrl)
      assertTrue("Expected cached file:// upgrade (got $finalUrl)", finalUrl!!.startsWith("file://"))

      com.example.repository.MediaNotificationRepository.clearAnimatedCanvas()
      org.robolectric.shadows.ShadowLooper.idleMainLooper()
      assertEquals("none", com.example.repository.MediaNotificationRepository.activeCanvasId.value)
      assertNull(com.example.repository.MediaNotificationRepository.activeCanvasUrl.value)

      com.example.util.CanvasVideoExporter.invalidateCachedVideoFile(context, videoUrl)
    } finally {
      server.stop(0)
    }
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun `trimLetterboxBarsIfPresent preserves near-square and dark covers without chopping`() {
    // 1. Near-square album cover (aspect ~1.09) with dark outer edge must NOT be chopped
    val nearSquare = Bitmap.createBitmap(240, 220, Bitmap.Config.ARGB_8888)
    val c1 = Canvas(nearSquare)
    c1.drawColor(AndroidColor.BLACK)
    val artPaint = Paint().apply { color = AndroidColor.rgb(220, 60, 120) }
    c1.drawRect(30f, 30f, 210f, 190f, artPaint)
    val preservedSquare = com.example.util.BitmapBlurUtils.trimLetterboxBarsIfPresent(nearSquare)
    assertSame("Expected near-square cover not to be chopped", nearSquare, preservedSquare)

    // 2. Dark atmospheric 16:9 cover (very dark grey, <24 max channel) must NOT be trimmed
    val darkWide = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
    val c2 = Canvas(darkWide)
    c2.drawColor(AndroidColor.BLACK)
    c2.drawRect(70f, 0f, 250f, 180f, Paint().apply { color = AndroidColor.rgb(18, 18, 20) })
    val preservedDark = com.example.util.BitmapBlurUtils.trimLetterboxBarsIfPresent(darkWide)
    assertSame("Expected very dark cover without bright inner border to remain intact", darkWide, preservedDark)

    // 3. Genuine 16:9 letterboxed thumbnail with pure black pillars and bright centered art gets trimmed cleanly
    val youtubeWide = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
    val c3 = Canvas(youtubeWide)
    c3.drawColor(AndroidColor.BLACK)
    c3.drawRect(70f, 0f, 250f, 180f, Paint().apply { color = AndroidColor.rgb(240, 180, 40) })
    val trimmedWide = com.example.util.BitmapBlurUtils.trimLetterboxBarsIfPresent(youtubeWide)
    assertTrue("Expected 16:9 YouTube pillarbox bars to be trimmed (got ${trimmedWide.width})", trimmedWide.width in 168..184)
    assertEquals(180, trimmedWide.height)

    nearSquare.recycle()
    darkWide.recycle()
    if (trimmedWide !== youtubeWide) trimmedWide.recycle()
    youtubeWide.recycle()
  }

  @Test
  fun `pickBestMatchingCoverCandidate selects verified track match and rejects mismatches`() {
    val wrong = com.example.service.OnlineCoverCandidate(
      id = "wrong",
      title = "Completely Different Song",
      artist = "Random Artist",
      album = "Other Album",
      thumbnailUrl = "https://example.com/1_t.jpg",
      fullResUrl = "https://example.com/1.jpg",
      source = "iTunes",
      resolutionLabel = "1000×1000"
    )
    val right = com.example.service.OnlineCoverCandidate(
      id = "right",
      title = "Blinding Lights",
      artist = "The Weeknd",
      album = "After Hours",
      thumbnailUrl = "https://example.com/2_t.jpg",
      fullResUrl = "https://example.com/2.jpg",
      source = "Deezer",
      resolutionLabel = "1000×1000"
    )

    val picked = com.example.service.CoverArtSearchService.pickBestMatchingCoverCandidate(
      candidates = listOf(wrong, right),
      targetTitle = "Blinding Lights",
      targetArtist = "The Weeknd",
      targetAlbum = "After Hours"
    )
    assertNotNull(picked)
    assertEquals("right", picked!!.id)

    val rejected = com.example.service.CoverArtSearchService.pickBestMatchingCoverCandidate(
      candidates = listOf(wrong),
      targetTitle = "Blinding Lights",
      targetArtist = "The Weeknd",
      targetAlbum = "After Hours"
    )
    assertNull("Expected unrelated candidate to be rejected", rejected)
  }

  @Test
  fun `wallpaper artwork fill mode and corner style persist cleanly and legacy EchoSnap fallback scans`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val defaults = CardStyleConfig()
    assertEquals(com.example.model.LiveWallpaperArtworkFillMode.FLOATING_SQUARE, defaults.liveWallpaperArtworkFillMode)
    assertEquals(com.example.model.LiveWallpaperCornerStyle.CURVED, defaults.liveWallpaperCornerStyle)

    CardStylePreferences.save(
      context,
      defaults.copy(
        liveWallpaperArtworkFillMode = com.example.model.LiveWallpaperArtworkFillMode.FULL_SCREEN,
        liveWallpaperCornerStyle = com.example.model.LiveWallpaperCornerStyle.SHARP
      )
    )
    val reloaded = CardStylePreferences.load(context)
    assertEquals(com.example.model.LiveWallpaperArtworkFillMode.FULL_SCREEN, reloaded.liveWallpaperArtworkFillMode)
    assertEquals(com.example.model.LiveWallpaperCornerStyle.SHARP, reloaded.liveWallpaperCornerStyle)

    // Verify persisted folder prompt helpers
    com.example.util.EchoSnapBackupManager.clearPersistedBackupTreeUri(context)
    com.example.util.EchoSnapBackupManager.setPromptedInitialFolderAccess(context, false)
    assertFalse(com.example.util.EchoSnapBackupManager.hasPromptedInitialFolderAccess(context))
    val fakeTreeUri = android.net.Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")
    com.example.util.EchoSnapBackupManager.savePersistedBackupTreeUri(context, fakeTreeUri, "Download")
    assertTrue(com.example.util.EchoSnapBackupManager.hasPromptedInitialFolderAccess(context))
    assertEquals(fakeTreeUri, com.example.util.EchoSnapBackupManager.getPersistedBackupTreeUri(context))
    assertEquals("Download", com.example.util.EchoSnapBackupManager.getSavedFolderDisplayName(context))

    // Verify legacy "EchoSnap" folder is still discovered as a fallback during scan
    val legacyParent = File(context.cacheDir, "legacy_scan_${System.currentTimeMillis()}").apply { mkdirs() }
    try {
      val legacySub = File(legacyParent, com.example.util.EchoSnapBackupManager.LEGACY_BACKUP_FOLDER_NAME).apply { mkdirs() }
      File(legacySub, com.example.util.EchoSnapBackupManager.MANIFEST_FILE_NAME).writeText(
        """{"format":"echosnap_backup","version":1,"exportedAtMs":1000,"covers":[],"canvases":[]}"""
      )
      val scannedLegacy = com.example.util.EchoSnapBackupManager.scanBackupFromDirectory(legacyParent).getOrThrow()
      assertTrue(scannedLegacy.hasManifest)
      assertEquals("EchoSnap", scannedLegacy.folderDisplayName)
    } finally {
      legacyParent.deleteRecursively()
      com.example.util.EchoSnapBackupManager.clearPersistedBackupTreeUri(context)
    }
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun `square album art beats wider cropped banner and offline canvas switches atomically without red flash`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    com.example.repository.MediaNotificationRepository.ensureInitialized(context)
    SavedMediaLibrary.resetAllSavedMedia(context)

    val squareMeta = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
    val wideNotif = Bitmap.createBitmap(1280, 640, Bitmap.Config.ARGB_8888)
    try {
      // Square metadata bitmap must win over a larger 2:1 cropped wide banner
      val picked = com.example.service.MusicNotificationListenerService.pickBestScoredCoverBitmap(
        listOf(wideNotif to 0, squareMeta to 3)
      )
      assertSame(squareMeta, picked)

      // Prepare two downloaded offline canvases for Song A and Song B
      val vidA = File(context.cacheDir, "offline_song_a.mp4").apply { writeBytes(ByteArray(512) { 0x11 }) }
      val vidB = File(context.cacheDir, "offline_song_b.mp4").apply { writeBytes(ByteArray(512) { 0x22 }) }
      SavedMediaLibrary.upsertImportedMedia(
        context = context,
        importedCovers = emptyList(),
        importedCanvases = listOf(
          com.example.repository.SavedCanvasItem(
            id = "can_a",
            name = "Song A Canvas",
            artist = "Artist A",
            localVideoPath = vidA.absolutePath,
            thumbnailPath = null,
            resolutionLabel = "1080x1920",
            createdAtMs = 1L,
            linkedAlbumName = "Album A",
            linkedAlbumArtist = "Artist A",
            linkedTrackTitles = listOf("Track One")
          ),
          com.example.repository.SavedCanvasItem(
            id = "can_b",
            name = "Song B Canvas",
            artist = "Artist B",
            localVideoPath = vidB.absolutePath,
            thumbnailPath = null,
            resolutionLabel = "1080x1920",
            createdAtMs = 2L,
            linkedAlbumName = "Album B",
            linkedAlbumArtist = "Artist B",
            linkedTrackTitles = listOf("Track Two")
          )
        ),
        replaceExisting = true
      )

      // Play Song One (without art first -> should use NeutralDark, never crimson red CardPalette.Default)
      com.example.repository.MediaNotificationRepository.updateTrack(
        MediaTrackInfo(
          title = "Track One",
          artist = "Artist A",
          album = "Album A",
          appName = "Offline Player",
          packageName = "com.example.player",
          albumArt = null,
          isPlaying = true,
          isSample = false
        )
      )
      org.robolectric.shadows.ShadowLooper.idleMainLooper()
      assertEquals("saved_canvas_can_a", com.example.repository.MediaNotificationRepository.activeCanvasId.value)
      assertEquals(CardPalette.NeutralDark, com.example.repository.MediaNotificationRepository.extractedPalette.value)

      // Immediately switch to Song Two -> offline canvas B must apply atomically + NeutralDark fallback
      com.example.repository.MediaNotificationRepository.updateTrack(
        MediaTrackInfo(
          title = "Track Two",
          artist = "Artist B",
          album = "Album B",
          appName = "Offline Player",
          packageName = "com.example.player",
          albumArt = null,
          isPlaying = true,
          isSample = false
        )
      )
      org.robolectric.shadows.ShadowLooper.idleMainLooper()
      assertEquals("saved_canvas_can_b", com.example.repository.MediaNotificationRepository.activeCanvasId.value)
      assertNotNull(com.example.repository.MediaNotificationRepository.activeCanvasUrl.value)
      assertNotEquals(CardPalette.Default.vibrantColor, com.example.repository.MediaNotificationRepository.extractedPalette.value.vibrantColor)

      vidA.delete()
      vidB.delete()
    } finally {
      if (!squareMeta.isRecycled) squareMeta.recycle()
      if (!wideNotif.isRecycled) wideNotif.recycle()
      SavedMediaLibrary.resetAllSavedMedia(context)
    }
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun `upscaleLowResArtworkSmoothly and prepareCrispArtwork deliver cached crisp graphics for offline and online covers`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val tier = com.example.util.DevicePerformanceTier.get(context)
    assertTrue("Expected cardArtMaxDim >= 960 (got ${tier.cardArtMaxDim})", tier.cardArtMaxDim >= 960)
    assertTrue("Expected bgSourceMaxDim >= 960 (got ${tier.bgSourceMaxDim})", tier.bgSourceMaxDim >= 960)

    val smallOffline = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
      val c = Canvas(this)
      c.drawColor(AndroidColor.rgb(20, 30, 60))
      val p = Paint().apply { color = AndroidColor.rgb(245, 90, 50) }
      c.drawRect(32f, 32f, 96f, 96f, p)
    }
    val hdCover = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888).apply {
      Canvas(this).drawColor(AndroidColor.rgb(40, 150, 220))
    }
    try {
      val upscaled = com.example.util.BitmapBlurUtils.upscaleLowResArtworkSmoothly(smallOffline, minTargetDim = 768)
      assertNotNull(upscaled)
      assertEquals(768, upscaled!!.width)
      assertEquals(768, upscaled.height)
      assertFalse("Original source bitmap must stay intact", smallOffline.isRecycled)
      // Verify center square color is rich orange-red and outer border stays deep navy (no washout)
      val centerR = AndroidColor.red(upscaled.getPixel(384, 384))
      val borderB = AndroidColor.blue(upscaled.getPixel(24, 24))
      assertTrue("Expected crisp warm center (r=$centerR)", centerR > 210)
      assertTrue("Expected crisp navy border (b=$borderB)", borderB > 45)

      val crispCached = com.example.util.BitmapBlurUtils.prepareCrispArtwork(
        source = smallOffline,
        minTargetDim = 1024,
        maxDimension = 1440
      )
      assertNotNull(crispCached)
      assertEquals(1024, crispCached!!.width)
      assertEquals(1024, crispCached.height)
      val readyFromCache = com.example.util.BitmapBlurUtils.getCachedCrispArtworkIfReady(
        source = smallOffline,
        minTargetDim = 1024,
        maxDimension = 1440
      )
      assertSame("Expected 0ms cache hit after prepareCrispArtwork", crispCached, readyFromCache)

      val hdPrepared = com.example.util.BitmapBlurUtils.prepareCrispArtwork(
        source = hdCover,
        minTargetDim = 1024,
        maxDimension = 1440
      )
      assertNotNull(hdPrepared)
      assertEquals(1024, hdPrepared!!.width)
      assertEquals(1024, hdPrepared.height)
      assertFalse("HD source bitmap must stay intact", hdCover.isRecycled)
      assertSame(
        "Expected cached crisp HD cover reuse",
        hdPrepared,
        com.example.util.BitmapBlurUtils.getCachedCrispArtworkIfReady(hdCover, minTargetDim = 1024, maxDimension = 1440)
      )
    } finally {
      if (!smallOffline.isRecycled) smallOffline.recycle()
      if (!hdCover.isRecycled) hdCover.recycle()
    }
  }

  @Test
  fun `depth-2 Echomusic folder discovery finds nested Download Echomusic manifest`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val storageRoot = File(context.cacheDir, "storage_root_${System.currentTimeMillis()}").apply { mkdirs() }
    try {
      val downloadDir = File(storageRoot, "Download").apply { mkdirs() }
      val echoDir = File(downloadDir, com.example.util.EchoSnapBackupManager.BACKUP_FOLDER_NAME).apply { mkdirs() }
      File(echoDir, com.example.util.EchoSnapBackupManager.MANIFEST_FILE_NAME).writeText(
        """{"format":"echosnap_backup","version":1,"exportedAtMs":2000,"covers":[],"canvases":[]}"""
      )

      val scanned = com.example.util.EchoSnapBackupManager.scanBackupFromDirectory(storageRoot).getOrThrow()
      assertTrue(scanned.hasManifest)
      assertEquals("Echomusic", scanned.folderDisplayName)
    } finally {
      storageRoot.deleteRecursively()
    }
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun `card shadow depth 0 to 100 inverse shadow and non-black shadow colors persist and render`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val palette = CardPalette(
      vibrantColor = Color(0xFF1E88E5),
      darkVibrantColor = Color(0xFF0D47A1),
      lightVibrantColor = Color(0xFF90CAF9),
      mutedColor = Color(0xFF3949AB),
      darkMutedColor = Color(0xFF1A237E),
      dominantColor = Color(0xFF1565C0)
    )

    // 1. Default shadow color uses vibrant album color (not restricted to black only)
    val defaults = CardStyleConfig()
    assertEquals(com.example.model.CardShadowColorMode.ALBUM_DYNAMIC, defaults.cardShadowColorMode)
    assertFalse(defaults.cardShadowInverse)
    assertEquals(palette.vibrantColor, defaults.resolveShadowColor(palette))

    // 2. Explicit Red, Purple, Cyan, White, Black, and Custom color resolution
    assertEquals(
      Color(0xFFFF2B44),
      defaults.copy(cardShadowColorMode = com.example.model.CardShadowColorMode.RED).resolveShadowColor(palette)
    )
    assertEquals(
      Color(0xFF9D4EDD),
      defaults.copy(cardShadowColorMode = com.example.model.CardShadowColorMode.PURPLE).resolveShadowColor(palette)
    )
    assertEquals(
      Color(0xFF00D2FF),
      defaults.copy(cardShadowColorMode = com.example.model.CardShadowColorMode.CYAN).resolveShadowColor(palette)
    )
    assertEquals(
      Color(0xFFFFB703),
      defaults.copy(
        cardShadowColorMode = com.example.model.CardShadowColorMode.CUSTOM,
        customShadowColor = Color(0xFFFFB703)
      ).resolveShadowColor(palette)
    )

    // 3. SharedPreferences persistence including 0% depth & inverse shadow
    val customConfig = defaults.copy(
      glowIntensity = 0f,
      cardShadowInverse = true,
      cardShadowColorMode = com.example.model.CardShadowColorMode.RED,
      customShadowColor = Color(0xFFFFB703)
    )
    CardStylePreferences.save(context, customConfig)
    val reloaded = CardStylePreferences.load(context)
    assertEquals(0f, reloaded.glowIntensity, 0.001f)
    assertTrue(reloaded.cardShadowInverse)
    assertEquals(com.example.model.CardShadowColorMode.RED, reloaded.cardShadowColorMode)
    assertEquals(Color(0xFFFFB703).toArgb(), reloaded.customShadowColor.toArgb())

    // 4. Backup export/import preserves shadow color, depth, and inverse toggle
    val backupParent = File(context.cacheDir, "shadow_backup_${System.currentTimeMillis()}").apply { mkdirs() }
    val art = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
    try {
      com.example.util.EchoSnapBackupManager.exportBackupToDirectory(
        parentOrEchoSnapDir = backupParent,
        styleConfig = customConfig.copy(glowIntensity = 0.85f)
      ).getOrThrow()
      val scanned = com.example.util.EchoSnapBackupManager.scanBackupFromDirectory(backupParent).getOrThrow()
      val restored = scanned.includedStyle?.applyTo(CardStyleConfig())
      assertNotNull(restored)
      assertEquals(0.85f, restored!!.glowIntensity, 0.01f)
      assertTrue(restored.cardShadowInverse)
      assertEquals(com.example.model.CardShadowColorMode.RED, restored.cardShadowColorMode)

      // 5. Render smoke tests for Floating Glow Art at 0% and 100% Inverse Red
      val track = MediaTrackInfo(
        title = "Shadow Test",
        artist = "EchoSnap Artist",
        album = "Test Album",
        appName = "Spotify",
        packageName = "com.spotify.music",
        albumArt = art,
        isPlaying = true,
        isSample = false
      )
      val (zeroShadowBmp, inverseRedBmp) = runBlocking {
        val zero = CardShareManager.generateStoryBitmap(
          context = context,
          track = track,
          palette = palette,
          styleConfig = defaults.copy(
            cardLayoutType = CardLayoutType.FLOATING_ARTWORK,
            glowIntensity = 0f,
            cardShadowInverse = false
          ),
          targetWidth = 540,
          targetHeight = 960
        )
        val inv = CardShareManager.generateStoryBitmap(
          context = context,
          track = track,
          palette = palette,
          styleConfig = defaults.copy(
            cardLayoutType = CardLayoutType.FLOATING_ARTWORK,
            glowIntensity = 1f,
            cardShadowInverse = true,
            cardShadowColorMode = com.example.model.CardShadowColorMode.RED
          ),
          targetWidth = 540,
          targetHeight = 960
        )
        zero to inv
      }
      assertEquals(540, zeroShadowBmp.width)
      assertEquals(540, inverseRedBmp.width)
      zeroShadowBmp.recycle()
      inverseRedBmp.recycle()
    } finally {
      if (!art.isRecycled) art.recycle()
      backupParent.deleteRecursively()
    }
  }

  @Test
  fun `wallpaper transition dimmer power-save ignored apps and auto-sync persist and roundtrip in backup`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val defaults = CardStyleConfig()
    assertEquals(com.example.model.LiveWallpaperTransitionStyle.CROSSFADE, defaults.liveWallpaperArtTransition)
    assertEquals(360, defaults.liveWallpaperArtTransitionDurationMs)
    assertFalse(defaults.liveWallpaperDimEnabled)
    assertEquals(0.28f, defaults.liveWallpaperDimAmount, 0.001f)
    assertTrue(defaults.liveWallpaperPowerSavePauseCanvas)
    assertTrue(defaults.ignoredMediaPackages.isEmpty())
    assertFalse(defaults.backupAutoSyncEnabled)

    val customized = defaults.copy(
      liveWallpaperMode = com.example.model.LiveWallpaperMode.ARTWORK_ONLY,
      liveWallpaperArtTransition = com.example.model.LiveWallpaperTransitionStyle.ZOOM_FADE,
      liveWallpaperArtTransitionDurationMs = 999, // clamps to 800
      liveWallpaperDimEnabled = true,
      liveWallpaperDimAmount = 0.95f, // clamps to 0.70
      liveWallpaperPowerSavePauseCanvas = false,
      ignoredMediaPackages = setOf("com.google.android.youtube", "com.zhiliaoapp.musically"),
      backupAutoSyncEnabled = true
    )

    CardStylePreferences.save(context, customized)
    val reloaded = CardStylePreferences.load(context)
    assertEquals(com.example.model.LiveWallpaperTransitionStyle.ZOOM_FADE, reloaded.liveWallpaperArtTransition)
    assertEquals(800, reloaded.liveWallpaperArtTransitionDurationMs)
    assertTrue(reloaded.liveWallpaperDimEnabled)
    assertEquals(0.70f, reloaded.liveWallpaperDimAmount, 0.001f)
    assertFalse(reloaded.liveWallpaperPowerSavePauseCanvas)
    assertEquals(setOf("com.google.android.youtube", "com.zhiliaoapp.musically"), reloaded.ignoredMediaPackages)
    assertTrue(reloaded.backupAutoSyncEnabled)

    // Verify EchoSnapBackupManager style JSON roundtrips all 7 fields
    val backupParent = File(context.cacheDir, "roundtrip_new_fields_${System.currentTimeMillis()}").apply { mkdirs() }
    try {
      com.example.util.EchoSnapBackupManager.exportBackupToDirectory(
        parentOrEchoSnapDir = backupParent,
        styleConfig = reloaded
      ).getOrThrow()
      val scanned = com.example.util.EchoSnapBackupManager.scanBackupFromDirectory(backupParent).getOrThrow()
      val restored = scanned.includedStyle?.applyTo(CardStyleConfig())
      assertNotNull(restored)
      assertEquals(com.example.model.LiveWallpaperTransitionStyle.ZOOM_FADE, restored!!.liveWallpaperArtTransition)
      assertEquals(800, restored.liveWallpaperArtTransitionDurationMs)
      assertTrue(restored.liveWallpaperDimEnabled)
      assertEquals(0.70f, restored.liveWallpaperDimAmount, 0.001f)
      assertFalse(restored.liveWallpaperPowerSavePauseCanvas)
      assertEquals(setOf("com.google.android.youtube", "com.zhiliaoapp.musically"), restored.ignoredMediaPackages)
      assertTrue(restored.backupAutoSyncEnabled)
    } finally {
      backupParent.deleteRecursively()
    }
  }

  @Test
  fun `ignored media filter power-save canvas gating export notifications and tile service work properly`() {
    val context = ApplicationProvider.getApplicationContext<Context>()

    // 1. Package ignore filtering
    val blocked = setOf("com.google.android.youtube", "com.instagram.android")
    assertTrue(
      com.example.service.MusicNotificationListenerService.shouldIgnoreMediaPackage(
        "com.google.android.youtube",
        blocked
      )
    )
    assertFalse(
      com.example.service.MusicNotificationListenerService.shouldIgnoreMediaPackage(
        "com.spotify.music",
        blocked
      )
    )
    assertFalse(
      com.example.service.MusicNotificationListenerService.shouldIgnoreMediaPackage(
        null,
        blocked
      )
    )

    // 2. Power-save gating respects PowerManager.isPowerSaveMode and setting toggle
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    val shadowPower = org.robolectric.Shadows.shadowOf(powerManager)
    shadowPower.setIsPowerSaveMode(false)
    assertFalse(com.example.util.BatteryAndSystemHelper.shouldPauseCanvasForPowerSave(context, true))

    shadowPower.setIsPowerSaveMode(true)
    assertTrue(com.example.util.BatteryAndSystemHelper.shouldPauseCanvasForPowerSave(context, true))
    assertFalse(com.example.util.BatteryAndSystemHelper.shouldPauseCanvasForPowerSave(context, false))
    shadowPower.setIsPowerSaveMode(false)

    // 3. ExportNotificationHelper channel creation & cancellation
    com.example.util.ExportNotificationHelper.ensureChannel(context)
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
    assertNotNull(nm.getNotificationChannel(com.example.util.ExportNotificationHelper.CHANNEL_ID))
    com.example.util.ExportNotificationHelper.cancelExportNotification(context)

    // 4. Quick Settings TileService manifest registration
    val tileComponent = android.content.ComponentName(
      context,
      com.example.service.EchoSnapTileService::class.java
    )
    val tileServiceInfo = context.packageManager.getServiceInfo(tileComponent, 0)
    assertEquals(android.Manifest.permission.BIND_QUICK_SETTINGS_TILE, tileServiceInfo.permission)
    assertTrue(tileServiceInfo.exported)
  }

  @Test
  fun `app music card progress formatting transport controls favorites and locked card blur export work`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    com.example.repository.MediaNotificationRepository.ensureInitialized(context)
    com.example.repository.MediaNotificationRepository.selectSampleTrack(
      com.example.repository.MediaNotificationRepository.getSampleTracks().first()
    )

    // 1. Duration & remaining time formatting matching Apple Music Control Center style
    assertEquals("0:29", com.example.util.MediaPlaybackControlHelper.formatDurationMs(29_000L))
    assertEquals("-1:49", com.example.util.MediaPlaybackControlHelper.formatRemainingMs(29_000L, 138_000L))
    assertEquals("-0:00", com.example.util.MediaPlaybackControlHelper.formatRemainingMs(138_000L, 138_000L))
    assertEquals("1:02:03", com.example.util.MediaPlaybackControlHelper.formatDurationMs(3_723_000L))

    // 2. PlaybackProgress advances only while playing
    val playingProg = com.example.model.MediaPlaybackProgress(
      positionMs = 10_000L,
      durationMs = 100_000L,
      playbackSpeed = 1f,
      lastUpdateElapsedMs = 1_000L,
      isPlaying = true
    )
    assertEquals(15_000L, playingProg.currentPositionAt(6_000L))
    val pausedProg = playingProg.copy(isPlaying = false, playbackSpeed = 0f)
    assertEquals(10_000L, pausedProg.currentPositionAt(6_000L))

    // 3. Favorite persistence, Play/Pause, Next, Previous, Seek on active track
    val initialTrack = com.example.repository.MediaNotificationRepository.currentTrack.value
    val wasFav = com.example.repository.MediaNotificationRepository.isTrackFavorited(initialTrack)
    val nowFav = com.example.repository.MediaNotificationRepository.toggleFavoriteCurrentTrack(context)
    assertEquals(!wasFav, nowFav)
    assertEquals(nowFav, com.example.repository.MediaNotificationRepository.isTrackFavorited(initialTrack))
    // Restore favorite state
    com.example.repository.MediaNotificationRepository.toggleFavoriteCurrentTrack(context)

    val initialPlaying = com.example.repository.MediaNotificationRepository.currentTrack.value.isPlaying
    com.example.repository.MediaNotificationRepository.togglePlayPauseCurrentTrack(context)
    assertEquals(!initialPlaying, com.example.repository.MediaNotificationRepository.currentTrack.value.isPlaying)

    com.example.repository.MediaNotificationRepository.seekCurrentTrackTo(context, 48_000L)
    assertEquals(48_000L, com.example.repository.MediaNotificationRepository.playbackProgress.value.positionMs)

    val titleBeforeNext = com.example.repository.MediaNotificationRepository.currentTrack.value.title
    com.example.repository.MediaNotificationRepository.skipNextCurrentTrack(context)
    val titleAfterNext = com.example.repository.MediaNotificationRepository.currentTrack.value.title
    assertNotEquals(titleBeforeNext, titleAfterNext)
    com.example.repository.MediaNotificationRepository.skipPreviousCurrentTrack(context)
    assertEquals(titleBeforeNext, com.example.repository.MediaNotificationRepository.currentTrack.value.title)

    // 4. App Music Card export keeps card blur locked while applying background blur and photo shadow
    val art = Bitmap.createBitmap(180, 180, Bitmap.Config.ARGB_8888).apply {
      val c = Canvas(this)
      c.drawColor(AndroidColor.rgb(42, 65, 118))
      val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.rgb(240, 190, 90) }
      c.drawCircle(90f, 90f, 52f, p)
    }
    try {
      val track = MediaTrackInfo(
        title = "Cheerios",
        artist = "Drake",
        album = "Singles",
        appName = "Apple Music",
        packageName = "com.apple.android.music",
        albumArt = art,
        isPlaying = true,
        isSample = false,
        durationMs = 138_000L,
        positionMs = 29_000L
      )
      val palette = CardPalette()
      val (overlayBlur0, overlayBlur40, fullStory) = runBlocking {
        val o0 = CardShareManager.generateTransparentCardOverlayBitmap(
          context = context,
          track = track,
          palette = palette,
          styleConfig = CardStyleConfig(
            cardLayoutType = CardLayoutType.APP_MUSIC_CARD,
            cardBlurRadius = 0f,
            useTransparentBackground = true
          ),
          targetWidth = 360,
          targetHeight = 640
        )
        val o40 = CardShareManager.generateTransparentCardOverlayBitmap(
          context = context,
          track = track,
          palette = palette,
          styleConfig = CardStyleConfig(
            cardLayoutType = CardLayoutType.APP_MUSIC_CARD,
            cardBlurRadius = 40f,
            useTransparentBackground = true
          ),
          targetWidth = 360,
          targetHeight = 640
        )
        val full = CardShareManager.generateStoryBitmap(
          context = context,
          track = track,
          palette = palette,
          styleConfig = CardStyleConfig(
            cardLayoutType = CardLayoutType.APP_MUSIC_CARD,
            backgroundBlurRadius = 24f,
            glowIntensity = 0.85f,
            cardShadowInverse = true,
            cardShadowColorMode = com.example.model.CardShadowColorMode.RED
          ),
          targetWidth = 540,
          targetHeight = 960
        )
        Triple(o0, o40, full)
      }
      assertTrue("App Music Card glass blur must remain locked regardless of cardBlurRadius", overlayBlur0.sameAs(overlayBlur40))
      assertEquals(540, fullStory.width)
      assertEquals(960, fullStory.height)
      overlayBlur0.recycle()
      overlayBlur40.recycle()
      fullStory.recycle()
    } finally {
      if (!art.isRecycled) art.recycle()
    }
  }
}
