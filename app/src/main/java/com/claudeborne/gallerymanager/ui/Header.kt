package com.claudeborne.gallerymanager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Screen title block: mono eyebrow, gradient display title, mono count. A [onBack] turns the eyebrow into a back link. */
@Composable
fun ScreenHeader(title: String, count: String, onBack: (() -> Unit)? = null, backLabel: String = "") {
    val cb = LocalCb.current
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
        Text(
            if (onBack != null) "← $backLabel" else "CLAUDEBORNE",
            if (onBack != null) Modifier.clickable(onClick = onBack) else Modifier,
            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 2.6.sp, color = cb.mint),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                title,
                Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Tourney, fontWeight = FontWeight.Bold, fontSize = 28.sp, brush = cb.grad),
            )
            Text(count, Modifier.padding(start = 12.dp), style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 1.sp, color = cb.inkDim))
        }
    }
}
