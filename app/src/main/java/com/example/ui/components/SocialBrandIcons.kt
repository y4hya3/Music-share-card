package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Crisp circular real-brand vector logos for bottom share actions:
 * WhatsApp, Instagram, Snapchat, and Discord.
 */

@Composable
fun WhatsAppCircularLogo(
    modifier: Modifier = Modifier,
    size: Dp = 52.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        val radius = minOf(w, h) / 2f

        // Official WhatsApp green circle
        drawCircle(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF3CE375), Color(0xFF20BD5A)),
                start = Offset(0f, 0f),
                end = Offset(w, h)
            ),
            radius = radius
        )

        // White speech bubble outline + bottom-left tail
        val ringR = radius * 0.56f
        val strokeW = radius * 0.115f
        val bubblePath = Path().apply {
            addArc(
                oval = Rect(cx - ringR, cy - ringR, cx + ringR, cy + ringR),
                startAngleDegrees = 138f,
                sweepAngleDegrees = 326f
            )
            // Tail extending toward bottom-left (~138..104 deg)
            val tailTipX = cx - ringR * 1.05f
            val tailTipY = cy + ringR * 1.02f
            lineTo(tailTipX, tailTipY)
            close()
        }
        drawPath(
            path = bubblePath,
            color = Color.White,
            style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // White curved phone handset inside bubble
        val phonePath = Path().apply {
            moveTo(cx - ringR * 0.36f, cy - ringR * 0.38f)
            cubicTo(
                cx - ringR * 0.44f, cy - ringR * 0.28f,
                cx - ringR * 0.38f, cy + ringR * 0.04f,
                cx - ringR * 0.14f, cy + ringR * 0.24f
            )
            cubicTo(
                cx + ringR * 0.06f, cy + ringR * 0.42f,
                cx + ringR * 0.34f, cy + ringR * 0.44f,
                cx + ringR * 0.44f, cy + ringR * 0.30f
            )
            lineTo(cx + ringR * 0.46f, cy + ringR * 0.18f)
            quadraticTo(cx + ringR * 0.34f, cy + ringR * 0.08f, cx + ringR * 0.22f, cy + ringR * 0.15f)
            quadraticTo(cx + ringR * 0.12f, cy + ringR * 0.20f, cx - ringR * 0.03f, cy + ringR * 0.06f)
            quadraticTo(cx - ringR * 0.17f, cy - ringR * 0.08f, cx - ringR * 0.12f, cy - ringR * 0.18f)
            quadraticTo(cx - ringR * 0.06f, cy - ringR * 0.29f, cx - ringR * 0.18f, cy - ringR * 0.42f)
            close()
        }
        drawPath(path = phonePath, color = Color.White)
    }
}

@Composable
fun InstagramCircularLogo(
    modifier: Modifier = Modifier,
    size: Dp = 52.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        val radius = minOf(w, h) / 2f

        // Iconic Instagram diagonal gradient circle
        drawCircle(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF4F5BD5),
                    Color(0xFF962FBF),
                    Color(0xFFD62976),
                    Color(0xFFFA7E1E),
                    Color(0xFFFEDA75)
                ),
                start = Offset(w * 0.15f, 0f),
                end = Offset(w * 0.85f, h)
            ),
            radius = radius
        )

        val camSize = radius * 1.06f
        val halfCam = camSize / 2f
        val strokeW = radius * 0.115f

        // Rounded square camera frame
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(cx - halfCam, cy - halfCam),
            size = Size(camSize, camSize),
            cornerRadius = CornerRadius(camSize * 0.28f, camSize * 0.28f),
            style = Stroke(width = strokeW)
        )

        // Inner lens ring
        drawCircle(
            color = Color.White,
            radius = camSize * 0.23f,
            center = Offset(cx, cy),
            style = Stroke(width = strokeW)
        )

        // Top-right flash dot
        drawCircle(
            color = Color.White,
            radius = strokeW * 0.62f,
            center = Offset(cx + halfCam * 0.56f, cy - halfCam * 0.56f)
        )
    }
}

@Composable
fun SnapchatCircularLogo(
    modifier: Modifier = Modifier,
    size: Dp = 52.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        val radius = minOf(w, h) / 2f

        // Official Snapchat yellow circle
        drawCircle(color = Color(0xFFFFFC00), radius = radius)

        // Classic Snapchat ghost silhouette
        val s = radius * 0.66f
        val ghostPath = Path().apply {
            // Top of head
            moveTo(cx, cy - s * 0.82f)
            // Right side of head down to shoulder
            cubicTo(
                cx + s * 0.52f, cy - s * 0.82f,
                cx + s * 0.44f, cy - s * 0.22f,
                cx + s * 0.46f, cy - s * 0.06f
            )
            // Right arm stub
            cubicTo(
                cx + s * 0.64f, cy - s * 0.08f,
                cx + s * 0.78f, cy + s * 0.02f,
                cx + s * 0.68f, cy + s * 0.14f
            )
            cubicTo(
                cx + s * 0.56f, cy + s * 0.20f,
                cx + s * 0.48f, cy + s * 0.18f,
                cx + s * 0.52f, cy + s * 0.30f
            )
            // Flared right skirt -> wavy bottom
            cubicTo(
                cx + s * 0.62f, cy + s * 0.48f,
                cx + s * 0.82f, cy + s * 0.54f,
                cx + s * 0.72f, cy + s * 0.66f
            )
            cubicTo(
                cx + s * 0.56f, cy + s * 0.72f,
                cx + s * 0.40f, cy + s * 0.70f,
                cx + s * 0.26f, cy + s * 0.78f
            )
            quadraticTo(cx, cy + s * 0.90f, cx - s * 0.26f, cy + s * 0.78f)
            cubicTo(
                cx - s * 0.40f, cy + s * 0.70f,
                cx - s * 0.56f, cy + s * 0.72f,
                cx - s * 0.72f, cy + s * 0.66f
            )
            cubicTo(
                cx - s * 0.82f, cy + s * 0.54f,
                cx - s * 0.62f, cy + s * 0.48f,
                cx - s * 0.52f, cy + s * 0.30f
            )
            // Left arm stub
            cubicTo(
                cx - s * 0.48f, cy + s * 0.18f,
                cx - s * 0.56f, cy + s * 0.20f,
                cx - s * 0.68f, cy + s * 0.14f
            )
            cubicTo(
                cx - s * 0.78f, cy + s * 0.02f,
                cx - s * 0.64f, cy - s * 0.08f,
                cx - s * 0.46f, cy - s * 0.06f
            )
            // Left side up to crown
            cubicTo(
                cx - s * 0.44f, cy - s * 0.22f,
                cx - s * 0.52f, cy - s * 0.82f,
                cx, cy - s * 0.82f
            )
            close()
        }

        // White fill with crisp black outline matching Snapchat brand icon
        drawPath(path = ghostPath, color = Color.White)
        drawPath(
            path = ghostPath,
            color = Color(0xFF111111),
            style = Stroke(width = radius * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
fun DiscordCircularLogo(
    modifier: Modifier = Modifier,
    size: Dp = 52.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        val radius = minOf(w, h) / 2f

        // Official Discord Blurple circle
        drawCircle(color = Color(0xFF5865F2), radius = radius)

        // White Discord Clyde emblem
        val s = radius * 0.64f
        val clydePath = Path().apply {
            // Top bridge between ears
            moveTo(cx - s * 0.32f, cy - s * 0.48f)
            lineTo(cx - s * 0.24f, cy - s * 0.34f)
            quadraticTo(cx, cy - s * 0.38f, cx + s * 0.24f, cy - s * 0.34f)
            lineTo(cx + s * 0.32f, cy - s * 0.48f)
            // Right ear / side curve down to bottom right handle
            quadraticTo(cx + s * 0.68f, cy - s * 0.38f, cx + s * 0.84f, cy + s * 0.28f)
            quadraticTo(cx + s * 0.62f, cy + s * 0.50f, cx + s * 0.34f, cy + s * 0.48f)
            lineTo(cx + s * 0.24f, cy + s * 0.34f)
            // Bottom chin curve
            quadraticTo(cx, cy + s * 0.38f, cx - s * 0.24f, cy + s * 0.34f)
            lineTo(cx - s * 0.34f, cy + s * 0.48f)
            quadraticTo(cx - s * 0.62f, cy + s * 0.50f, cx - s * 0.84f, cy + s * 0.28f)
            // Left ear up
            quadraticTo(cx - s * 0.68f, cy - s * 0.38f, cx - s * 0.32f, cy - s * 0.48f)
            close()
        }
        drawPath(path = clydePath, color = Color.White)

        // Dual blurple eyes
        val eyeRadiusX = s * 0.145f
        val eyeRadiusY = s * 0.175f
        val eyeY = cy + s * 0.04f
        drawOval(
            color = Color(0xFF5865F2),
            topLeft = Offset(cx - s * 0.31f - eyeRadiusX, eyeY - eyeRadiusY),
            size = Size(eyeRadiusX * 2f, eyeRadiusY * 2f)
        )
        drawOval(
            color = Color(0xFF5865F2),
            topLeft = Offset(cx + s * 0.31f - eyeRadiusX, eyeY - eyeRadiusY),
            size = Size(eyeRadiusX * 2f, eyeRadiusY * 2f)
        )
    }
}
