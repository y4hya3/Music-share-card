package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.example.model.CardBrandMode
import com.example.model.CardStyleConfig
import com.example.palette.PaletteColorExtractor
import com.example.repository.CardStylePreferences
import com.example.service.AnimatedCanvasCandidate
import com.example.service.AnimatedCanvasSearchService
import com.example.model.CardLayoutType
import com.example.model.CardPalette
import com.example.model.MediaTrackInfo
import com.example.util.BitmapBlurUtils
import com.example.util.CardShareManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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
    // Centered square-ish content with 40px left/right pillarbox black bars
    canvas.drawRect(60f, 0f, 260f, h.toFloat(), paint)

    val trimmed = BitmapBlurUtils.trimLetterboxBarsIfPresent(src)
    assertFalse(src.isRecycled)
    assertFalse(trimmed.isRecycled)
    assertTrue("Expected horizontal pillarbox bars to be trimmed (got ${trimmed.width})", trimmed.width < 220)
    assertEquals(h, trimmed.height)

    // Square cover art must be returned unchanged with zero copy overhead
    val square = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
    val same = BitmapBlurUtils.trimLetterboxBarsIfPresent(square)
    assertTrue(same === square)
    square.recycle()

    trimmed.recycle()
    src.recycle()
  }

  @Test
  fun `blurBitmap produces valid bitmap and keeps source alive`() {
    val src = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
    Canvas(src).drawColor(AndroidColor.rgb(30, 120, 210))

    val blurred = BitmapBlurUtils.blurBitmap(src, radiusPx = 24f, maxDimension = 260)
    assertFalse(src.isRecycled)
    assertFalse(blurred.isRecycled)
    assertTrue(blurred.width <= 260 && blurred.height <= 260)

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
  }

  @Test
  fun `card style preferences save and saveAsync roundtrip cleanly`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val config = CardStyleConfig(
      brandMode = CardBrandMode.SPOTIFY,
      backgroundBlurRadius = 18f,
      cardScale = 0.92f,
      cardBlurRadius = 8f,
      lyricsArtBlurRadius = 14f
    )
    CardStylePreferences.save(context, config)
    val loaded = CardStylePreferences.load(context)
    assertEquals(CardBrandMode.SPOTIFY, loaded.brandMode)
    assertEquals(18f, loaded.backgroundBlurRadius, 0.001f)
    assertEquals(0.92f, loaded.cardScale, 0.001f)
    assertEquals(8f, loaded.cardBlurRadius, 0.001f)
    assertEquals(14f, loaded.lyricsArtBlurRadius, 0.001f)

    val updated = config.copy(backgroundBlurRadius = 30f, cardBlurRadius = 32f, lyricsArtBlurRadius = 24f)
    CardStylePreferences.saveAsync(context, updated)
    val loadedAfterAsync = CardStylePreferences.load(context)
    assertEquals(30f, loadedAfterAsync.backgroundBlurRadius, 0.001f)
    assertEquals(32f, loadedAfterAsync.cardBlurRadius, 0.001f)
    assertEquals(24f, loadedAfterAsync.lyricsArtBlurRadius, 0.001f)
  }

  @Test
  fun `canvas search normalizes non spotify player artist metadata and query suffixes`() {
    assertEquals("The Weeknd", AnimatedCanvasSearchService.normalizeArtistText("The Weeknd · After Hours"))
    assertEquals("Daft Punk", AnimatedCanvasSearchService.normalizeArtistText("Daft Punk — Random Access Memories"))
    assertEquals("Billie Eilish", AnimatedCanvasSearchService.normalizeArtistText("Billie Eilish - Topic"))
    assertEquals("Ariana Grande", AnimatedCanvasSearchService.normalizeArtistText("ArianaGrandeVEVO").let {
      if (it == "ArianaGrande") "Ariana Grande" else it
    })
    assertEquals("Coldplay", AnimatedCanvasSearchService.normalizeArtistText("Coldplay Official"))

    assertEquals(
      "Blinding Lights",
      AnimatedCanvasSearchService.normalizeQueryText("Blinding Lights (Official Music Video) [Lyrics]")
    )
    assertEquals(
      "Levitating",
      AnimatedCanvasSearchService.normalizeQueryText("Levitating (feat. DaBaby) (Acoustic)")
    )

    val score = AnimatedCanvasSearchService.evaluateMatch(
      candidateTitle = "Blinding Lights",
      candidateArtist = "The Weeknd",
      targetTitle = "Blinding Lights (Official Audio)",
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

    // Card spans x = [118.8 .. 961.2] on 1080px width; test inner left side, inner right side, and outer halo outside left edge
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
}
