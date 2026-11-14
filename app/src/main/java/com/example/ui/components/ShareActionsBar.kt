package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShareActionsBar(
    onCopyLink: () -> Unit,
    onSaveToGallery: () -> Unit,
    onShareWhatsApp: () -> Unit,
    onShareMessages: () -> Unit,
    onShareStories: () -> Unit,
    onNativeShare: () -> Unit,
    modifier: Modifier = Modifier,
    isGridLayout: Boolean = false
) {
    if (isGridLayout) {
        FlowRow(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 3
        ) {
            ShareActionItemsContent(
                onCopyLink = onCopyLink,
                onSaveToGallery = onSaveToGallery,
                onShareWhatsApp = onShareWhatsApp,
                onShareMessages = onShareMessages,
                onShareStories = onShareStories,
                onNativeShare = onNativeShare
            )
        }
    } else {
        val scrollState = rememberScrollState()
        Row(
            modifier = modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ShareActionItemsContent(
                onCopyLink = onCopyLink,
                onSaveToGallery = onSaveToGallery,
                onShareWhatsApp = onShareWhatsApp,
                onShareMessages = onShareMessages,
                onShareStories = onShareStories,
                onNativeShare = onNativeShare
            )
        }
    }
}

@Composable
private fun ShareActionItemsContent(
    onCopyLink: () -> Unit,
    onSaveToGallery: () -> Unit,
    onShareWhatsApp: () -> Unit,
    onShareMessages: () -> Unit,
    onShareStories: () -> Unit,
    onNativeShare: () -> Unit
) {
    ShareActionItem(
        painter = painterResource(R.drawable.ic_copy),
        label = "Copy link",
        bgColor = Color.White,
        iconTint = Color.Black,
        onClick = onCopyLink,
        tag = "action_copy_link"
    )

    ShareActionItem(
        painter = painterResource(R.drawable.ic_download),
        label = "Save image",
        bgColor = Color(0xFFFF2B44),
        iconTint = Color.White,
        onClick = onSaveToGallery,
        tag = "action_save_image"
    )

    ShareActionItem(
        painter = painterResource(R.drawable.ic_chat),
        label = "WhatsApp",
        bgColor = Color(0xFF25D366),
        iconTint = Color.White,
        onClick = onShareWhatsApp,
        tag = "action_whatsapp"
    )

    ShareActionItem(
        painter = painterResource(R.drawable.ic_send),
        label = "Messages",
        bgColor = Color(0xFF833AB4),
        iconTint = Color.White,
        onClick = onShareMessages,
        tag = "action_messages"
    )

    ShareActionItem(
        painter = painterResource(R.drawable.ic_stories),
        label = "Stories",
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF833AB4), Color(0xFFFD1D1D), Color(0xFFFCB045))
        ),
        iconTint = Color.White,
        onClick = onShareStories,
        tag = "action_stories"
    )

    ShareActionItem(
        painter = painterResource(R.drawable.ic_send),
        label = "More",
        bgColor = Color(0xFF2C2C34),
        iconTint = Color.White,
        onClick = onNativeShare,
        tag = "action_native_share"
    )
}

@Composable
private fun ShareActionItem(
    painter: Painter,
    label: String,
    iconTint: Color,
    onClick: () -> Unit,
    tag: String,
    bgColor: Color = Color.Transparent,
    brush: Brush? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag(tag)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .then(
                    if (brush != null) {
                        Modifier.background(brush)
                    } else {
                        Modifier.background(bgColor)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painter,
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(23.dp)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = label,
            color = Color.White.copy(alpha = 0.94f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
