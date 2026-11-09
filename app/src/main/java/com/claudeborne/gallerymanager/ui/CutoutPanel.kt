package com.claudeborne.gallerymanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.gallerymanager.edit.Backdrop
import com.claudeborne.gallerymanager.edit.EditState
import kotlin.math.roundToInt

/** Background tools: once the subject is found, keep, blur, recolour or remove everything behind it. */
@Composable
internal fun CutoutPanel(
    st: EditState, found: Boolean, tried: Boolean, onFind: () -> Unit, onPick: (Backdrop) -> Unit,
    onBlur: (Float) -> Unit, onBlurDone: () -> Unit, onColor: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
        when {
            !found && !tried -> Text("FINDING THE SUBJECT… THE FIRST TIME DOWNLOADS A MODEL.", Modifier.padding(horizontal = 16.dp), style = monoStyle(DarkCb.mint, 2.sp))
            !found -> {
                Text(
                    "COULDN'T FIND A SUBJECT. THE FIRST USE NEEDS INTERNET TO DOWNLOAD GOOGLE'S MODEL. SOME PHOTOS HAVE NO CLEAR SUBJECT.",
                    Modifier.padding(horizontal = 16.dp), style = monoStyle(DarkCb.inkDim, 0.6.sp),
                )
                LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) { items(listOf("TRY AGAIN")) { Chip(it, color = DarkCb.mint, onClick = onFind) } }
            }
            else -> {
                LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) {
                    items(Backdrop.entries) { b -> Chip(b.label, st.backdrop == b) { onPick(b) } }
                    item { Chip("FIND AGAIN", color = DarkCb.inkDim, onClick = onFind) }
                }
                when (st.backdrop) {
                    Backdrop.BLUR -> EditSlider("BLUR", st.backdropBlur, 0f..1f, "${(st.backdropBlur * 100).roundToInt()}", onBlur, onBlurDone) {}
                    Backdrop.COLOR -> LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        items(MarkPalette + 0xFF0A1020.toInt()) { c ->
                            Column(
                                Modifier.size(32.dp).clip(CircleShape).background(Color(c))
                                    .border(if (c == st.backdropColor) 2.dp else 1.dp, if (c == st.backdropColor) DarkCb.mint else Color(0x66FFFFFF), CircleShape)
                                    .clickable { onColor(c) },
                            ) {}
                        }
                    }
                    Backdrop.TRANSPARENT -> Text("SAVES AS A PNG COPY SO THE BACKGROUND STAYS SEE-THROUGH.", Modifier.padding(horizontal = 16.dp), style = monoStyle(DarkCb.inkDim, 0.6.sp))
                    Backdrop.KEEP -> Text("PICK WHAT GOES BEHIND THE SUBJECT.", Modifier.padding(horizontal = 16.dp), style = monoStyle(DarkCb.inkDim, 0.6.sp))
                }
            }
        }
    }
}
