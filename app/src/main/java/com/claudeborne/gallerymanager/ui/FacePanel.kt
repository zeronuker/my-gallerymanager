package com.claudeborne.gallerymanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.gallerymanager.edit.EditState
import kotlin.math.roundToInt

internal enum class FaceFix(val label: String) { SKIN("SKIN"), TEETH("TEETH"), EYES("EYES") }

internal fun EditState.strength(f: FaceFix) = when (f) { FaceFix.SKIN -> skin; FaceFix.TEETH -> teeth; FaceFix.EYES -> eyes }
internal fun EditState.withStrength(f: FaceFix, v: Float) = when (f) { FaceFix.SKIN -> copy(skin = v); FaceFix.TEETH -> copy(teeth = v); FaceFix.EYES -> copy(eyes = v) }

/** Portrait retouch: smooth skin, whiten teeth, brighten eyes on the faces found in the photo. */
@Composable
internal fun FacePanel(
    st: EditState, found: Boolean, tried: Boolean, which: FaceFix, onWhich: (FaceFix) -> Unit,
    onChange: (Float) -> Unit, onDone: () -> Unit, onHeal: () -> Unit, onClone: () -> Unit, onFind: () -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
        LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) {
            item { Chip("HEAL", onClick = onHeal) }
            item { Chip("CLONE", onClick = onClone) }
            item { Chip("FACE", true) {} }
            if (found) FaceFix.entries.forEach { f -> item { Chip(f.label, which == f) { onWhich(f) } } }
        }
        when {
            !found && !tried -> WorkingLine("LOOKING FOR FACES…", null)
            !found -> {
                Text("NO FACE FOUND. TRY A PHOTO WHERE THE FACE IS LARGE AND FACING THE CAMERA.", Modifier.padding(horizontal = 16.dp), style = monoStyle(DarkCb.inkDim, 0.6.sp))
                LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) { item { Chip("TRY AGAIN", color = DarkCb.mint, onClick = onFind) } }
            }
            else -> {
                val v = st.strength(which)
                EditSlider(which.label, v, 0f..1f, "${(v * 100).roundToInt()}", onChange, onDone) { onChange(0f); onDone() }
            }
        }
    }
}
