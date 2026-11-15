package com.example.ui.components

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.CardBrandMode
import com.example.model.CardLayoutType
import com.example.model.CardPalette
import com.example.model.CardStyleConfig
import com.example.model.GradientStyle
import com.example.model.LogoColorMode
import com.example.model.LyricsCardBackgroundMode
import com.example.model.MediaTrackInfo
import com.example.repository.MediaNotificationRepository
import com.example.service.AnimatedCanvasCandidate
import com.example.service.SpotifyAuthManager
import kotlin.math.roundToInt

/**
 * Edit Card Bottom Sheet.
 * All edits persist automatically via CardStylePreferences across track changes,
 * player switches, and app restarts.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditCardBottomSheet(
    palette: CardPalette,
    styleConfig: CardStyleConfig,
    currentTrack: MediaTrackInfo,
    onDismiss: () -> Unit,
    onStyleChange: (CardStyleConfig) -> Unit,
    onTrackSelect: (MediaTrackInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()

    val onlineCovers by MediaNotificationRepository.onlineCoverCandidates.collectAsState()
    val isSearchingCovers by MediaNotificationRepository.isSearchingCoverArt.collectAsState()
    val originalPlayerArt by MediaNotificationRepository.originalPlayerArt.collectAsState()
    val selectedCoverId by MediaNotificationRepository.selectedCoverId.collectAsState()

    val canvasCandidates by MediaNotificationRepository.animatedCanvasCandidates.collectAsState()
    val isSearchingCanvas by MediaNotificationRepository.isSearchingCanvas.collectAsState()
    val canvasSearchStatus by MediaNotificationRepository.canvasSearchStatus.collectAsState()
    val activeCanvasId by MediaNotificationRepository.activeCanvasId.collectAsState()

    var editTitle by remember(currentTrack.title, currentTrack.packageName) {
        mutableStateOf(currentTrack.title)
    }
    var editArtist by remember(currentTrack.artist, currentTrack.packageName) {
        mutableStateOf(currentTrack.artist)
    }
    var coverSearchQuery by remember(currentTrack.title, currentTrack.artist) {
        mutableStateOf("${currentTrack.title} ${currentTrack.artist}".trim())
    }
    var canvasSearchQuery by remember(currentTrack.title, currentTrack.artist) {
        mutableStateOf("${currentTrack.title} ${currentTrack.artist}".trim())
    }
    val context = LocalContext.current
    var revealSpotifyToken by remember { mutableStateOf(false) }
    var showSpotifyLoginDialog by remember { mutableStateOf(false) }
    var showAdvancedSpotifyOptions by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1E1E24),
        contentColor = Color.White,
        modifier = modifier.testTag("edit_card_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 36.dp)
                .verticalScroll(scrollState)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Customize Share Card",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Changes stay saved across songs & app restarts",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.55f)
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_edit_sheet_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 1. CARD BRAND (LOGO & NAME)
            Text(
                text = "Card Logo & Name",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.9f)
            )
            Text(
                text = "Choose whether the share card displays Bitchord, Spotify, or Apple Music branding.",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BrandModeChip(
                    brandMode = CardBrandMode.BITCHORD,
                    previewBadgeColor = Color(0xFFFF2B44),
                    previewLogoColorMode = LogoColorMode.RED,
                    isSelected = styleConfig.brandMode == CardBrandMode.BITCHORD,
                    onClick = {
                        val nextColor = if (styleConfig.logoColorMode == LogoColorMode.GREEN) {
                            LogoColorMode.RED
                        } else {
                            styleConfig.logoColorMode
                        }
                        onStyleChange(
                            styleConfig.copy(
                                brandMode = CardBrandMode.BITCHORD,
                                logoColorMode = nextColor
                            )
                        )
                    },
                    modifier = Modifier.testTag("brand_mode_bitchord")
                )

                BrandModeChip(
                    brandMode = CardBrandMode.SPOTIFY,
                    previewBadgeColor = Color(0xFF1DB954),
                    previewLogoColorMode = LogoColorMode.GREEN,
                    isSelected = styleConfig.brandMode == CardBrandMode.SPOTIFY,
                    onClick = {
                        val nextColor = if (styleConfig.logoColorMode == LogoColorMode.RED) {
                            LogoColorMode.GREEN
                        } else {
                            styleConfig.logoColorMode
                        }
                        onStyleChange(
                            styleConfig.copy(
                                brandMode = CardBrandMode.SPOTIFY,
                                logoColorMode = nextColor
                            )
                        )
                    },
                    modifier = Modifier.testTag("brand_mode_spotify")
                )

                BrandModeChip(
                    brandMode = CardBrandMode.APPLE_MUSIC,
                    previewBadgeColor = Color(0xFFFA243C),
                    previewLogoColorMode = LogoColorMode.RED,
                    isSelected = styleConfig.brandMode == CardBrandMode.APPLE_MUSIC,
                    onClick = {
                        val nextColor = if (styleConfig.logoColorMode == LogoColorMode.GREEN) {
                            LogoColorMode.RED
                        } else {
                            styleConfig.logoColorMode
                        }
                        onStyleChange(
                            styleConfig.copy(
                                brandMode = CardBrandMode.APPLE_MUSIC,
                                logoColorMode = nextColor
                            )
                        )
                    },
                    modifier = Modifier.testTag("brand_mode_apple_music")
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 2. LOGO BADGE COLOR SECTION
            Text(
                text = "Logo Badge Color",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.9f)
            )
            Text(
                text = "Changes the icon badge color while keeping the '${styleConfig.brandMode.wordmark}' text crisp white.",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LogoColorOption(
                    label = "Red",
                    color = Color(0xFFFF2B44),
                    isSelected = styleConfig.logoColorMode == LogoColorMode.RED,
                    onClick = { onStyleChange(styleConfig.copy(logoColorMode = LogoColorMode.RED)) }
                )
                LogoColorOption(
                    label = "Template",
                    color = palette.vibrantColor,
                    isSelected = styleConfig.logoColorMode == LogoColorMode.TEMPLATE_DYNAMIC,
                    onClick = { onStyleChange(styleConfig.copy(logoColorMode = LogoColorMode.TEMPLATE_DYNAMIC)) }
                )
                LogoColorOption(
                    label = "Green",
                    color = Color(0xFF1DB954),
                    isSelected = styleConfig.logoColorMode == LogoColorMode.GREEN,
                    onClick = { onStyleChange(styleConfig.copy(logoColorMode = LogoColorMode.GREEN)) }
                )
                LogoColorOption(
                    label = "White",
                    color = Color.White,
                    isSelected = styleConfig.logoColorMode == LogoColorMode.WHITE,
                    onClick = { onStyleChange(styleConfig.copy(logoColorMode = LogoColorMode.WHITE)) }
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 3. ONLINE HD COVER ART SEARCH & CHOOSER
            Text(
                text = "High-Quality Cover Art (Online)",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.9f)
            )
            Text(
                text = "Auto-search online HD covers or pick between multiple 1000×1000 results.",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF262630),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                        Text(
                            text = "Auto-search HD cover online",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Automatically looks up 1000px covers when songs play",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = styleConfig.autoSearchOnlineArt,
                        onCheckedChange = { enabled ->
                            onStyleChange(styleConfig.copy(autoSearchOnlineArt = enabled))
                            if (enabled && onlineCovers.isEmpty()) {
                                MediaNotificationRepository.searchOnlineCovers(
                                    customQuery = coverSearchQuery,
                                    autoApplyBest = !currentTrack.isSample
                                )
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFFFF2B44)
                        ),
                        modifier = Modifier.testTag("auto_search_online_art_switch")
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = coverSearchQuery,
                    onValueChange = { coverSearchQuery = it },
                    singleLine = true,
                    placeholder = { Text("Song & artist", fontSize = 13.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFFF2B44),
                        unfocusedBorderColor = Color(0xFF444450),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("online_cover_search_input")
                )

                Button(
                    onClick = {
                        MediaNotificationRepository.searchOnlineCovers(
                            customQuery = coverSearchQuery,
                            autoApplyBest = false
                        )
                    },
                    enabled = !isSearchingCovers && coverSearchQuery.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2B44)),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                    modifier = Modifier.testTag("search_online_cover_button")
                ) {
                    if (isSearchingCovers) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search HD covers",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Find", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (originalPlayerArt != null || onlineCovers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    originalPlayerArt?.let { orig ->
                        item(key = "player_art") {
                            CoverArtChoiceTile(
                                bitmap = orig,
                                title = "Player Art",
                                subtitle = "${orig.width}×${orig.height}",
                                isSelected = selectedCoverId == "player",
                                onClick = { MediaNotificationRepository.restoreOriginalPlayerCover() },
                                modifier = Modifier.testTag("cover_choice_player")
                            )
                        }
                    }

                    items(onlineCovers, key = { it.id }) { candidate ->
                        candidate.thumbnailBitmap?.let { thumb ->
                            CoverArtChoiceTile(
                                bitmap = thumb,
                                title = candidate.source,
                                subtitle = candidate.resolutionLabel,
                                isSelected = selectedCoverId == candidate.id,
                                onClick = { MediaNotificationRepository.selectOnlineCover(candidate) },
                                modifier = Modifier.testTag("cover_choice_${candidate.id}")
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 3B. ANIMATED SONG CANVAS & SPOTIFY ACCOUNT LOGIN
            Text(
                text = "Animated Song Canvas & Spotify Login",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.9f)
            )
            Text(
                text = "Log in to your Spotify account to automatically capture your token for animated Canvas loops, or smart-search HD music clips.",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF262630),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                        Text(
                            text = "Auto-scratch animated canvas",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Automatically finds exact looping HD canvas backgrounds for active songs",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = styleConfig.autoSearchAnimatedCanvas,
                        onCheckedChange = { enabled ->
                            onStyleChange(styleConfig.copy(autoSearchAnimatedCanvas = enabled))
                            if (enabled && canvasCandidates.isEmpty()) {
                                MediaNotificationRepository.searchAnimatedCanvases(
                                    customQuery = canvasSearchQuery,
                                    autoApplyBest = !currentTrack.isSample
                                )
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF1DB954)
                        ),
                        modifier = Modifier.testTag("auto_search_canvas_switch")
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Primary Spotify Account Login Card
            val isSpotifyConnected = styleConfig.spotifyAccessToken.isNotBlank()
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (isSpotifyConnected) Color(0xFF1A2A20) else Color(0xFF22242D),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = if (isSpotifyConnected) Color(0xFF1DB954).copy(alpha = 0.55f) else Color.White.copy(alpha = 0.10f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    if (isSpotifyConnected) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF1DB954))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Connected: ${styleConfig.spotifyAccountLabel.ifBlank { "Spotify Account" }}",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("spotify_connected_label")
                                    )
                                    Text(
                                        text = "Spotify Canvas token active & saved",
                                        color = Color(0xFF9BE7B3),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                onClick = { showSpotifyLoginDialog = true },
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF253A2C),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("spotify_reconnect_button")
                            ) {
                                Text(
                                    text = "Reconnect",
                                    color = Color(0xFF1DB954),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                )
                            }
                            Surface(
                                onClick = {
                                    MediaNotificationRepository.disconnectSpotifyAccount()
                                    Toast.makeText(context, "Logged out of Spotify", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF3A2429),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("spotify_logout_button")
                            ) {
                                Text(
                                    text = "Log Out",
                                    color = Color(0xFFFF8A96),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                )
                            }
                        }
                    } else {
                        Button(
                            onClick = { showSpotifyLoginDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1DB954),
                                contentColor = Color(0xFF0A0C0B)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("spotify_login_button")
                        ) {
                            Text(
                                text = "Login to Spotify",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Sign in once — EchoSnap automatically captures and saves your Spotify token.",
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 11.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Surface(
                        onClick = { showAdvancedSpotifyOptions = !showAdvancedSpotifyOptions },
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF2B2D38),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("toggle_advanced_spotify_button")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Advanced: Manual Token / PKCE Client ID",
                                color = Color.White.copy(alpha = 0.82f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (showAdvancedSpotifyOptions) "Hide" else "Show",
                                color = Color(0xFF1DB954),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (showAdvancedSpotifyOptions) {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = styleConfig.spotifyAccessToken,
                            onValueChange = { token ->
                                onStyleChange(styleConfig.copy(spotifyAccessToken = token))
                            },
                            singleLine = true,
                            label = { Text("Spotify Access Token (manual)") },
                            placeholder = { Text("Paste Bearer / OAuth token", fontSize = 12.sp) },
                            visualTransformation = if (revealSpotifyToken) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(end = 6.dp)
                                ) {
                                    Surface(
                                        onClick = { revealSpotifyToken = !revealSpotifyToken },
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFF2E2E3A),
                                        modifier = Modifier.testTag("toggle_spotify_token_visibility")
                                    ) {
                                        Text(
                                            text = if (revealSpotifyToken) "Hide" else "Show",
                                            color = Color.White.copy(alpha = 0.85f),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                        )
                                    }
                                    if (styleConfig.spotifyAccessToken.isNotEmpty()) {
                                        IconButton(
                                            onClick = { onStyleChange(styleConfig.copy(spotifyAccessToken = "")) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Clear token",
                                                tint = Color.White.copy(alpha = 0.65f),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF1DB954),
                                unfocusedBorderColor = Color(0xFF444450),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedLabelColor = Color(0xFF1DB954),
                                unfocusedLabelColor = Color.White.copy(alpha = 0.6f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("spotify_token_input")
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = styleConfig.spotifyClientId,
                                onValueChange = { cid ->
                                    onStyleChange(styleConfig.copy(spotifyClientId = cid))
                                },
                                singleLine = true,
                                label = { Text("Spotify Client ID (PKCE)", fontSize = 12.sp) },
                                placeholder = { Text("Optional App Client ID", fontSize = 11.sp) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF1DB954),
                                    unfocusedBorderColor = Color(0xFF444450),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedLabelColor = Color(0xFF1DB954),
                                    unfocusedLabelColor = Color.White.copy(alpha = 0.6f)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("spotify_client_id_input")
                            )

                            Button(
                                onClick = {
                                    val intent = SpotifyAuthManager.buildPkceAuthorizeIntent(
                                        context = context,
                                        clientId = styleConfig.spotifyClientId
                                    )
                                    if (intent != null) {
                                        context.startActivity(intent)
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Enter a Spotify Client ID or tap Login to Spotify above",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF2D303C),
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
                                modifier = Modifier.testTag("spotify_pkce_oauth_button")
                            ) {
                                Text("OAuth", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = canvasSearchQuery,
                    onValueChange = { canvasSearchQuery = it },
                    singleLine = true,
                    placeholder = { Text("Smart canvas query", fontSize = 13.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF1DB954),
                        unfocusedBorderColor = Color(0xFF444450),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("canvas_search_input")
                )

                Button(
                    onClick = {
                        MediaNotificationRepository.searchAnimatedCanvases(
                            customQuery = canvasSearchQuery,
                            autoApplyBest = false
                        )
                    },
                    enabled = !isSearchingCanvas && canvasSearchQuery.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1DB954),
                        contentColor = Color(0xFF0A0A0D)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                    modifier = Modifier.testTag("search_animated_canvas_button")
                ) {
                    if (isSearchingCanvas) {
                        CircularProgressIndicator(
                            color = Color(0xFF0A0A0D),
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Search animated canvas",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Canvas", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            canvasSearchStatus?.let { status ->
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF232530),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = status,
                        color = Color.White.copy(alpha = 0.82f),
                        fontSize = 11.sp,
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("canvas_search_status_text")
                    )
                }
            }

            if (canvasCandidates.isNotEmpty() || activeCanvasId != "none") {
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item(key = "canvas_static_none") {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .width(92.dp)
                                .height(118.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { MediaNotificationRepository.clearAnimatedCanvas() }
                                .background(if (activeCanvasId == "none") Color(0xFF233028) else Color(0xFF26262F))
                                .border(
                                    width = if (activeCanvasId == "none") 2.dp else 1.dp,
                                    color = if (activeCanvasId == "none") Color(0xFF1DB954) else Color.White.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(8.dp)
                                .testTag("canvas_choice_none")
                        ) {
                            Text(
                                text = "Static",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "Cover Art Only",
                                color = Color.White.copy(alpha = 0.62f),
                                fontSize = 9.sp,
                                maxLines = 2
                            )
                        }
                    }

                    items(canvasCandidates, key = { it.id }) { candidate ->
                        AnimatedCanvasChoiceTile(
                            candidate = candidate,
                            isSelected = activeCanvasId == candidate.id,
                            onClick = { MediaNotificationRepository.selectAnimatedCanvas(candidate) },
                            modifier = Modifier.testTag("canvas_choice_${candidate.id}")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 4. SINGLE CARD SIZE CONTROL
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Card Size",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.9f)
                )
                Text(
                    text = "${(styleConfig.cardScale * 100f).roundToInt()}%",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.78f)
                )
            }
            Slider(
                value = styleConfig.cardScale.coerceIn(0.60f, 1.20f),
                onValueChange = { newScale ->
                    onStyleChange(styleConfig.copy(cardScale = newScale.coerceIn(0.60f, 1.20f)))
                },
                valueRange = 0.60f..1.20f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFFF2B44),
                    activeTrackColor = Color(0xFFFF2B44),
                    inactiveTrackColor = Color(0xFF40404C)
                ),
                modifier = Modifier.testTag("card_size_slider")
            )

            Spacer(modifier = Modifier.height(18.dp))

            // 5. BACKGROUND STYLE & PALETTE GRADIENTS
            Text(
                text = "Background Style & Gradient",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.9f)
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    onClick = { onStyleChange(styleConfig.copy(useBlurredAlbumBackground = true)) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (styleConfig.useBlurredAlbumBackground) Color(0xFFFF2B44) else Color(0xFF2C2C34),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("bg_mode_blurred_album")
                ) {
                    Text(
                        text = "Blurred Album Cover",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = if (styleConfig.useBlurredAlbumBackground) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 12.dp)
                    )
                }

                Surface(
                    onClick = { onStyleChange(styleConfig.copy(useBlurredAlbumBackground = false)) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (!styleConfig.useBlurredAlbumBackground) Color(0xFFFF2B44) else Color(0xFF2C2C34),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("bg_mode_palette_gradient")
                ) {
                    Text(
                        text = "Palette Gradient",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = if (!styleConfig.useBlurredAlbumBackground) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 12.dp)
                    )
                }
            }

            if (styleConfig.useBlurredAlbumBackground) {
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Background Blur Intensity",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                    Text(
                        text = "${styleConfig.backgroundBlurRadius.roundToInt()}px",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.65f)
                    )
                }
                Slider(
                    value = styleConfig.backgroundBlurRadius.coerceIn(0f, 40f),
                    onValueChange = {
                        onStyleChange(
                            styleConfig.copy(
                                useBlurredAlbumBackground = true,
                                backgroundBlurRadius = it
                            )
                        )
                    },
                    valueRange = 0f..40f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFFF2B44),
                        activeTrackColor = Color(0xFFFF2B44),
                        inactiveTrackColor = Color(0xFF40404C)
                    ),
                    modifier = Modifier.testTag("sheet_background_blur_slider")
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Adaptive Palette Gradients",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GradientStyle.values().forEach { style ->
                    val isSelected = !styleConfig.useBlurredAlbumBackground && styleConfig.gradientStyle == style
                    Surface(
                        onClick = {
                            onStyleChange(
                                styleConfig.copy(
                                    useBlurredAlbumBackground = false,
                                    gradientStyle = style
                                )
                            )
                        },
                        shape = RoundedCornerShape(20.dp),
                        color = if (isSelected) Color(0xFFFF2B44) else Color(0xFF2C2C34),
                        modifier = Modifier.testTag("gradient_preset_${style.name}")
                    ) {
                        Text(
                            text = style.label,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 6. CARD LAYOUT STYLE
            Text(
                text = "Card Layout Style",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.9f)
            )
            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 2
            ) {
                CardLayoutType.values().forEach { layout ->
                    val isSelected = styleConfig.cardLayoutType == layout
                    Surface(
                        onClick = { onStyleChange(styleConfig.copy(cardLayoutType = layout)) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) Color(0xFFFF2B44) else Color(0xFF2C2C34),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("layout_type_${layout.name}")
                    ) {
                        Text(
                            text = layout.label,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 12.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (styleConfig.cardLayoutType == CardLayoutType.SPOTIFY_LYRICS_CARD) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Lyrics Card Background",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.92f)
                )
                Text(
                    text = "Switch between album-matched color card or full song cover art",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.58f),
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LyricsCardBackgroundMode.values().forEach { bgMode ->
                        val isBgSelected = styleConfig.lyricsCardBackgroundMode == bgMode
                        Surface(
                            onClick = {
                                onStyleChange(styleConfig.copy(lyricsCardBackgroundMode = bgMode))
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isBgSelected) Color(0xFFFF2B44) else Color(0xFF2C2C34),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("lyrics_bg_${bgMode.name}")
                        ) {
                            Text(
                                text = bgMode.label,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = if (isBgSelected) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(vertical = 11.dp, horizontal = 12.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Manual Lyrics",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.92f)
                        )
                        Text(
                            text = "Type or paste up to 6 lines for the Lyrics Card",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.58f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(
                            onClick = {
                                val sampleSnippet =
                                    "Turn the lights down low\n" +
                                        "Feel the bassline echo through the room\n" +
                                        "Every chord hits right on time"
                                onStyleChange(styleConfig.copy(customLyrics = sampleSnippet))
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF2C2C36),
                            modifier = Modifier.testTag("sample_lyrics_button")
                        ) {
                            Text(
                                text = "Sample",
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                        if (styleConfig.customLyrics.isNotEmpty()) {
                            Surface(
                                onClick = { onStyleChange(styleConfig.copy(customLyrics = "")) },
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF3A2429),
                                modifier = Modifier.testTag("clear_lyrics_button")
                            ) {
                                Text(
                                    text = "Clear",
                                    color = Color(0xFFFF8A96),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = styleConfig.customLyrics,
                    onValueChange = { newLyrics ->
                        onStyleChange(styleConfig.copy(customLyrics = newLyrics))
                    },
                    placeholder = {
                        Text(
                            text = "Enter song lyrics line by line...",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.45f)
                        )
                    },
                    minLines = 3,
                    maxLines = 6,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFFF2B44),
                        unfocusedBorderColor = Color(0xFF444450),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_lyrics_input")
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 7. SHADOW & DEPTH INTENSITY
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Card Shadow Depth",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.9f)
                )
                Text(
                    text = "${(styleConfig.glowIntensity * 100).toInt()}%",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
            Slider(
                value = styleConfig.glowIntensity,
                onValueChange = { onStyleChange(styleConfig.copy(glowIntensity = it)) },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFFF2B44),
                    activeTrackColor = Color(0xFFFF2B44),
                    inactiveTrackColor = Color(0xFF40404C)
                ),
                modifier = Modifier.testTag("glow_intensity_slider")
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 8. SAMPLE TRACK SELECTOR
            Text(
                text = "Preset Demo Tracks",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.9f)
            )
            Text(
                text = "Test instant palette extraction and background blur with sample songs:",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )

            val samples = MediaNotificationRepository.getSampleTracks()
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                samples.forEach { sample ->
                    val isSelected = currentTrack.title == sample.title
                    Surface(
                        onClick = { onTrackSelect(sample) },
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) Color(0xFFFF2B44) else Color(0xFF2C2C34),
                        modifier = Modifier.testTag("sample_track_${sample.title}")
                    ) {
                        Text(
                            text = "${sample.title} • ${sample.artist}",
                            color = Color.White,
                            fontSize = 12.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 9. EDIT TRACK METADATA
            Text(
                text = "Edit Track Information",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.9f)
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = editTitle,
                onValueChange = {
                    editTitle = it
                    MediaNotificationRepository.updateCustomTrackDetails(editTitle, editArtist, currentTrack.album)
                },
                label = { Text("Track Title") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFFF2B44),
                    unfocusedBorderColor = Color(0xFF444450),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedLabelColor = Color(0xFFFF2B44),
                    unfocusedLabelColor = Color.White.copy(alpha = 0.6f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("edit_track_title_input")
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = editArtist,
                onValueChange = {
                    editArtist = it
                    MediaNotificationRepository.updateCustomTrackDetails(editTitle, editArtist, currentTrack.album)
                },
                label = { Text("Artist Name / Album") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFFF2B44),
                    unfocusedBorderColor = Color(0xFF444450),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedLabelColor = Color(0xFFFF2B44),
                    unfocusedLabelColor = Color.White.copy(alpha = 0.6f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("edit_artist_name_input")
            )
        }
    }

    if (showSpotifyLoginDialog) {
        SpotifyWebLoginDialog(
            onDismiss = { showSpotifyLoginDialog = false },
            onTokenCaptured = { result ->
                showSpotifyLoginDialog = false
                MediaNotificationRepository.applySpotifyLoginResult(result)
                Toast.makeText(
                    context,
                    "Spotify connected (${result.accountLabel})",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }
}

@Composable
private fun CoverArtChoiceTile(
    bitmap: Bitmap,
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(92.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(if (isSelected) Color(0xFF2E222A) else Color(0xFF26262F))
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) Color(0xFFFF2B44) else Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
        ) {
            Image(
                bitmap = imageBitmap,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BrandModeChip(
    brandMode: CardBrandMode,
    previewBadgeColor: Color,
    previewLogoColorMode: LogoColorMode,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) Color(0xFF2D2D38) else Color(0xFF24242C),
        border = androidx.compose.foundation.BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) previewBadgeColor else Color.White.copy(alpha = 0.10f)
        ),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            BitChordBadge(
                brandMode = brandMode,
                badgeColor = previewBadgeColor,
                logoColorMode = previewLogoColorMode,
                size = 22.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = brandMode.label,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

@Composable
private fun LogoColorOption(
    label: String,
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(6.dp)
            .testTag("logo_color_option_$label")
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (isSelected) 3.dp else 1.dp,
                    color = if (isSelected) Color.White else Color.Transparent,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = if (color == Color.White) Color.Black else Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.65f),
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun AnimatedCanvasChoiceTile(
    candidate: AnimatedCanvasCandidate,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val imageBitmap = remember(candidate.thumbnailBitmap) {
        candidate.thumbnailBitmap?.asImageBitmap()
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(if (isSelected) Color(0xFF1F3025) else Color(0xFF26262F))
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) Color(0xFF1DB954) else Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF121216)),
            contentAlignment = Alignment.Center
        ) {
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = candidate.title,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (candidate.isLoopingVideo) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Black.copy(alpha = 0.72f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Animated video",
                        tint = Color(0xFF1DB954),
                        modifier = Modifier
                            .size(16.dp)
                            .padding(2.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = candidate.source,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = candidate.resolutionLabel,
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
