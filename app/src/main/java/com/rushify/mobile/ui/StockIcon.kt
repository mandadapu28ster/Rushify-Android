package com.rushify.mobile.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rushify.mobile.data.IconBadge
import com.rushify.mobile.data.IconLoader

/** Provided once in MainActivity; null (previews, tests) means every icon is just the letter badge. */
val LocalIconLoader = staticCompositionLocalOf<IconLoader?> { null }

/** Stock logo on a white tile (so dark logos stay visible on the dark theme), or a letter badge when there is none. */
@Composable
fun StockIcon(symbol: String?, size: Dp = 28.dp, modifier: Modifier = Modifier) {
    val loader = LocalIconLoader.current
    val image: ImageBitmap? by produceState(initialValue = loader?.cached(symbol), symbol, loader) {
        value = if (loader == null || loader.isKnownMissing(symbol)) null else loader.image(symbol)
    }
    val shape = RoundedCornerShape(8.dp)
    val tag = "stock_icon_${symbol.orEmpty()}"
    val img = image
    if (img != null) {
        Box(modifier.size(size).clip(shape).background(Color.White).testTag(tag), contentAlignment = Alignment.Center) {
            Image(img, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(size).padding(2.dp))
        }
    } else {
        Box(
            modifier.size(size).clip(shape).background(Color.hsv(IconBadge.hue(symbol), 0.45f, 0.5f)).testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            Text(IconBadge.text(symbol), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.38f).coerceAtLeast(10f).sp)
        }
    }
}
