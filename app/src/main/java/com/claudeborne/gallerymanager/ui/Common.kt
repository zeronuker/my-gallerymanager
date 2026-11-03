package com.claudeborne.gallerymanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val DangerRed = Color(0xFFFF6B6B)

/** Mono label style used by every button, chip and eyebrow in the app. */
fun mono(color: Color, size: Int = 11, spacing: Float = 2.2f, bold: Boolean = false) = TextStyle(
    fontFamily = JetBrainsMono, fontSize = size.sp, letterSpacing = spacing.sp, color = color,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
)

@Composable
fun ActionLabel(label: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        Modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        style = mono(if (enabled) color else color.copy(alpha = 0.35f)),
    )
}

/** Small outlined toggle chip; mint fill when [on]. */
@Composable
fun Chip(label: String, on: Boolean = false, onClick: () -> Unit) {
    val cb = LocalCb.current
    Text(
        label,
        Modifier.then(if (on) Modifier.background(cb.mint) else Modifier.border(1.dp, cb.lineStrong))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        style = mono(if (on) DarkCb.surface0 else cb.ink2, 10, 1.6f, bold = on),
    )
}

/** Brand-styled modal: mono title, free [content], and CANCEL / [confirm] buttons. Pass [confirm] = "" for a close-only dialog. */
@Composable
fun CbDialog(title: String, onDismiss: () -> Unit, confirm: String = "OK", danger: Boolean = false, onConfirm: () -> Unit = onDismiss, content: @Composable () -> Unit) {
    val cb = LocalCb.current
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().background(cb.surface2).border(1.dp, cb.lineStrong).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = mono(cb.mint, 11, 2.6f))
            content()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ActionLabel(if (confirm.isEmpty()) "CLOSE" else "CANCEL", cb.inkDim, onClick = onDismiss)
                if (confirm.isNotEmpty()) ActionLabel(confirm, if (danger) DangerRed else cb.mint, onClick = onConfirm)
            }
        }
    }
}

@Composable
fun DialogText(text: String) {
    Text(text, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = LocalCb.current.ink2))
}

@Composable
fun CbInput(value: String, onChange: (String) -> Unit, placeholder: String = "", password: Boolean = false) {
    val cb = LocalCb.current
    Box(Modifier.fillMaxWidth().border(1.dp, cb.lineStrong).padding(12.dp)) {
        if (value.isEmpty()) Text(placeholder, style = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = cb.inkDim))
        BasicTextField(
            value, onChange, singleLine = true, cursorBrush = SolidColor(cb.mint),
            visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            textStyle = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = cb.ink),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun EmptyNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
        Text(text, style = mono(LocalCb.current.inkDim, 12, 2f))
    }
}

fun sizeText(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble() / 1024
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (v >= 100) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
}

private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy")
fun dateText(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dateFmt)
