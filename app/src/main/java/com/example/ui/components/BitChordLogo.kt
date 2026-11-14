package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.CardBrandMode
import com.example.model.LogoColorMode

/**
 * Brand Logo & Wordmark Component.
 * Supports switching in Edit between:
 * - Bitchord: uploaded Bitchord squircle soundwave badge + "Bitchord" text
 * - Spotify: classic circular 3-arc soundwave badge + "Spotify" text
 * - Apple Music: rounded squircle badge + beamed dual music note + "Apple Music" text
 */
@Composable
fun BitChordBadge(
    modifier: Modifier = Modifier,
    brandMode: CardBrandMode = CardBrandMode.BITCHORD,
    badgeColor: Color = Color(0xFFFF2B44),
    logoColorMode: LogoColorMode = LogoColorMode.RED,
    size: Dp = 24.dp
) {
    val contrastColor = if (badgeColor.luminance() > 0.72f) Color(0xFF141418) else Color.White

    Box(
        modifier = modifier
            .size(size)
            .testTag("bitchord_logo_badge"),
        contentAlignment = Alignment.Center
    ) {
        when (brandMode) {
            CardBrandMode.BITCHORD -> {
                if (logoColorMode == LogoColorMode.RED) {
                    Image(
                        painter = painterResource(R.drawable.ic_bitchord_badge),
                        contentDescription = "Bitchord logo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Image(
                        painter = painterResource(R.drawable.ic_bitchord_squircle),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.tint(badgeColor),
                        modifier = Modifier.fillMaxSize()
                    )
                    Image(
                        painter = painterResource(R.drawable.ic_bitchord_waves),
                        contentDescription = "Bitchord logo",
                        contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.tint(contrastColor),
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            CardBrandMode.SPOTIFY -> {
                val waveColor = if (badgeColor.luminance() > 0.35f) Color(0xFF121216) else Color.White
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val diameter = this.size.minDimension
                    val radius = diameter / 2f
                    val center = Offset(this.size.width / 2f, this.size.height / 2f)

                    drawCircle(
                        color = badgeColor,
                        radius = radius,
                        center = center
                    )

                    rotate(degrees = 7f, pivot = center) {
                        val strokeTop = Stroke(width = diameter * 0.105f, cap = StrokeCap.Round)
                        val strokeMid = Stroke(width = diameter * 0.088f, cap = StrokeCap.Round)
                        val strokeBot = Stroke(width = diameter * 0.075f, cap = StrokeCap.Round)

                        val topPath = Path().apply {
                            moveTo(center.x - radius * 0.56f, center.y - radius * 0.22f)
                            quadraticTo(
                                center.x + radius * 0.02f, center.y - radius * 0.44f,
                                center.x + radius * 0.56f, center.y - radius * 0.16f
                            )
                        }
                        drawPath(topPath, color = waveColor, style = strokeTop)

                        val midPath = Path().apply {
                            moveTo(center.x - radius * 0.48f, center.y + radius * 0.04f)
                            quadraticTo(
                                center.x + radius * 0.01f, center.y - radius * 0.14f,
                                center.x + radius * 0.46f, center.y + radius * 0.09f
                            )
                        }
                        drawPath(midPath, color = waveColor, style = strokeMid)

                        val botPath = Path().apply {
                            moveTo(center.x - radius * 0.39f, center.y + radius * 0.28f)
                            quadraticTo(
                                center.x, center.y + radius * 0.14f,
                                center.x + radius * 0.36f, center.y + radius * 0.32f
                            )
                        }
                        drawPath(botPath, color = waveColor, style = strokeBot)
                    }
                }
            }
            CardBrandMode.APPLE_MUSIC -> {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val d = this.size.minDimension
                    val corner = d * 0.24f
                    drawRoundRect(
                        color = badgeColor,
                        topLeft = Offset.Zero,
                        size = Size(d, d),
                        cornerRadius = CornerRadius(corner, corner)
                    )

                    // Beamed double eighth note inside badge
                    val stemStroke = d * 0.09f
                    val leftStemX = d * 0.39f
                    val rightStemX = d * 0.69f
                    val topYLeft = d * 0.29f
                    val topYRight = d * 0.23f
                    val botYLeft = d * 0.67f
                    val botYRight = d * 0.61f

                    // Slanted top beam
                    val beamPath = Path().apply {
                        moveTo(leftStemX - stemStroke * 0.45f, topYLeft)
                        lineTo(rightStemX + stemStroke * 0.45f, topYRight)
                        lineTo(rightStemX + stemStroke * 0.45f, topYRight + d * 0.12f)
                        lineTo(leftStemX - stemStroke * 0.45f, topYLeft + d * 0.12f)
                        close()
                    }
                    drawPath(beamPath, color = contrastColor)

                    // Left & right stems
                    drawLine(
                        color = contrastColor,
                        start = Offset(leftStemX, topYLeft + d * 0.04f),
                        end = Offset(leftStemX, botYLeft),
                        strokeWidth = stemStroke,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = contrastColor,
                        start = Offset(rightStemX, topYRight + d * 0.04f),
                        end = Offset(rightStemX, botYRight),
                        strokeWidth = stemStroke,
                        cap = StrokeCap.Round
                    )

                    // Left & right note heads
                    val headRx = d * 0.115f
                    val headRy = d * 0.085f
                    drawOval(
                        color = contrastColor,
                        topLeft = Offset(leftStemX - headRx * 1.55f, botYLeft - headRy * 0.9f),
                        size = Size(headRx * 2f, headRy * 2f)
                    )
                    drawOval(
                        color = contrastColor,
                        topLeft = Offset(rightStemX - headRx * 1.55f, botYRight - headRy * 0.9f),
                        size = Size(headRx * 2f, headRy * 2f)
                    )
                }
            }
        }
    }
}

@Composable
fun BitChordBranding(
    modifier: Modifier = Modifier,
    brandMode: CardBrandMode = CardBrandMode.BITCHORD,
    badgeColor: Color = Color(0xFFFF2B44),
    logoColorMode: LogoColorMode = LogoColorMode.RED,
    badgeSize: Dp = 22.dp,
    textSize: TextUnit = 15.sp,
    textColor: Color = Color.White,
    showWordmark: Boolean = true
) {
    Row(
        modifier = modifier.testTag("bitchord_branding_row"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BitChordBadge(
            brandMode = brandMode,
            badgeColor = badgeColor,
            logoColorMode = logoColorMode,
            size = badgeSize
        )

        if (showWordmark) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = brandMode.wordmark,
                color = textColor,
                fontSize = textSize,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.35).sp,
                modifier = Modifier.testTag("bitchord_wordmark_text")
            )
        }
    }
}
