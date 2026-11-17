package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.ui.theme.brandWordmarkResource
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun StartupSplashScreen(
    profileColorHex: String?,
    profileBackgroundUrl: String?,
    backgroundCacheKey: String? = null,
    skipGradient: Boolean = false,
    brandWordmarkRes: Int? = null,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize().background(Color(0xFF071633))) {
        if (!profileBackgroundUrl.isNullOrBlank()) {
            val imageData: Any = if (profileBackgroundUrl.startsWith("file:")) {
                java.io.File(java.net.URI(profileBackgroundUrl))
            } else {
                profileBackgroundUrl
            }
            val request = ImageRequest.Builder(LocalContext.current)
                .data(imageData)
                .crossfade(false)
            if (backgroundCacheKey != null) {
                request.memoryCacheKey(backgroundCacheKey)
                    .diskCacheKey(backgroundCacheKey)
                    .placeholderMemoryCacheKey(backgroundCacheKey)
            }
            AsyncImage(
                model = request.build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else if (!skipGradient) {
            // QR Time: the same navy as the TV banner, so launch flows from the home-screen tile.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(Color(0xFF071633), Color(0xFF040D21))
                        )
                    )
            )
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // QR Time: the logo artwork, then the wordmark and what the app does.
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(R.drawable.nuvio_one_icon),
                contentDescription = null,
                modifier = Modifier.size(132.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            BrandWordmark(
                modifier = Modifier.height(64.dp),
                contentDescription = com.nuvio.tv.livetv.branding.LiveTvBranding.APP_NAME,
                drawableOverride = com.nuvio.tv.livetv.branding.LiveTvBranding.iptvWordmark(
                    brandWordmarkRes ?: NuvioTheme.currentTheme.brandWordmarkResource
                )
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(14.dp))
            androidx.tv.material3.Text(
                text = com.nuvio.tv.livetv.branding.LiveTvBranding.TAGLINE,
                color = Color(0xFFC8CDD7).copy(alpha = 0.85f),
                fontSize = 16.sp
            )
            // Clear room between the tagline and the loading circle (they never overlap: they're
            // stacked, and this gap keeps them visually apart too).
            Spacer(modifier = Modifier.height(40.dp))
            LoadingIndicator(modifier = Modifier.size(NuvioTheme.spacing.xxxl))
        }
    }
}
