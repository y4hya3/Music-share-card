package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
    onPreviewCard: () -> Unit,
    onShareWhatsApp: () -> Unit,
    onShareInstagramStory: () -> Unit,
    onShareSnapchatStory: () -> Unit,
    onShareDiscord: () -> Unit,
    onNativeShare: () -> Unit,
    modifier: Modifier = Modifier,
    isGridLayout: Boolean = false
) {
    if (isGridLayout) {
        FlowRow(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 4
        ) {
            ShareActionItemsContent(
                onCopyLink = onCopyLink,
                onSaveToGallery = onSaveToGallery,
                onPreviewCard = onPreviewCard,
                onShareWhatsApp = onShareWhatsApp,
                onShareInstagramStory = onShareInstagramStory,
                onShareSnapchatStory = onShareSnapchatStory,
                onShareDiscord = onShareDiscord,
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
                onPreviewCard = onPreviewCard,
                onShareWhatsApp = onShareWhatsApp,
                onShareInstagramStory = onShareInstagramStory,
                onShareSnapchatStory = onShareSnapchatStory,
                onShareDiscord = onShareDiscord,
                onNativeShare = onNativeShare
            )
        }
    }
}

@Composable
private fun ShareActionItemsContent(
    onCopyLink: () -> Unit,
    onSaveToGallery: () -> Unit,
    onPreviewCard: () -> Unit,
    onShareWhatsApp: () -> Unit,
    onShareInstagramStory: () -> Unit,
    onShareSnapchatStory: () -> Unit,
    onShareDiscord: () -> Unit,
    onNativeShare: () -> Unit
) {
    // 1. Copy Link
    CircularActionItem(
        label = "Copy link",
        onClick = onCopyLink,
        tag = "action_copy_link"
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_copy),
                contentDescription = "Copy link",
                tint = Color.Black,
                modifier = Modifier.size(22.dp)
            )
        }
    }

    // 2. Save Image
    CircularActionItem(
        label = "Save",
        onClick = onSaveToGallery,
        tag = "action_save_image"
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(Color(0xFFFF2B44)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_download),
                contentDescription = "Save image",
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
    }

    // 3. Preview Before Sharing
    CircularActionItem(
        label = "Preview",
        onClick = onPreviewCard,
        tag = "action_preview_card"
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(Color(0xFF23232C))
                .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            PreviewEyeIcon(modifier = Modifier.size(24.dp))
        }
    }

    // 4. WhatsApp (Normal Share to WhatsApp)
    CircularActionItem(
        label = "WhatsApp",
        onClick = onShareWhatsApp,
        tag = "action_whatsapp"
    ) {
        WhatsAppCircularLogo(
            size = 52.dp,
            modifier = Modifier
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
        )
    }

    // 5. Instagram (Story Only)
    CircularActionItem(
        label = "IG Story",
        onClick = onShareInstagramStory,
        tag = "action_instagram_story"
    ) {
        InstagramCircularLogo(
            size = 52.dp,
            modifier = Modifier
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
        )
    }

    // 6. Snapchat (Story Only)
    CircularActionItem(
        label = "Snap Story",
        onClick = onShareSnapchatStory,
        tag = "action_snapchat_story"
    ) {
        SnapchatCircularLogo(
            size = 52.dp,
            modifier = Modifier
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
        )
    }

    // 7. Discord
    CircularActionItem(
        label = "Discord",
        onClick = onShareDiscord,
        tag = "action_discord"
    ) {
        DiscordCircularLogo(
            size = 52.dp,
            modifier = Modifier
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
        )
    }

    // 8. Other
    CircularActionItem(
        label = "Other",
        onClick = onNativeShare,
        tag = "action_native_share"
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(Color(0xFF2C2C34))
                .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = "Other share options",
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun PreviewEyeIcon(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val eyePath = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.08f, cy)
            quadraticTo(cx, h * 0.12f, w * 0.92f, cy)
            quadraticTo(cx, h * 0.88f, w * 0.08f, cy)
            close()
        }
        drawPath(
            path = eyePath,
            color = Color.White,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.09f)
        )
        drawCircle(
            color = Color.White,
            radius = w * 0.14f,
            center = androidx.compose.ui.geometry.Offset(cx, cy)
        )
    }
}

@Composable
private fun CircularActionItem(
    label: String,
    onClick: () -> Unit,
    tag: String,
    iconContent: @Composable () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 6.dp)
            .testTag(tag)
    ) {
        iconContent()
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.94f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
