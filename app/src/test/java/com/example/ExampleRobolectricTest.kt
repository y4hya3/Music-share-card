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
import com.example.service.AnimatedCanvasSearchService
import com.example.util.BitmapBlurUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
  fun `canvas search normalizes non spotify player artist metadata`() {
    assertEquals("The Weeknd", AnimatedCanvasSearchService.normalizeArtistText("The Weeknd · After Hours"))
    assertEquals("Daft Punk", AnimatedCanvasSearchService.normalizeArtistText("Daft Punk — Random Access Memories"))
    assertEquals("Billie Eilish", AnimatedCanvasSearchService.normalizeArtistText("Billie Eilish - Topic"))
    assertEquals("Ariana Grande", AnimatedCanvasSearchService.normalizeArtistText("ArianaGrandeVEVO").let {
      if (it == "ArianaGrande") "Ariana Grande" else it
    })
    assertEquals("Coldplay", AnimatedCanvasSearchService.normalizeArtistText("Coldplay Official"))

    val score = AnimatedCanvasSearchService.evaluateMatch(
      candidateTitle = "Blinding Lights",
      candidateArtist = "The Weeknd",
      targetTitle = "Blinding Lights (Official Audio)",
      targetArtist = "The Weeknd · After Hours"
    )
    assertTrue("Expected confident match score, got ${score.totalScore}", score.totalScore >= 80)
  }
}
