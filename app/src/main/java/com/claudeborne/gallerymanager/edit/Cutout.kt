package com.claudeborne.gallerymanager.edit

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.resume
import kotlin.math.max

/** What goes behind the subject once the background is gone. */
enum class Backdrop(val label: String) { KEEP("ORIGINAL"), BLUR("BLUR"), COLOR("COLOUR"), TRANSPARENT("TRANSPARENT") }

/** Makes sure Google Play services has the subject model on the phone, asking it to download the model the first time (needs internet). */
private suspend fun ensureModel(context: Context): Boolean {
    val api = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
    return try {
        val client = ModuleInstall.getClient(context)
        suspendCancellableCoroutine<Boolean> { cont ->
            client.areModulesAvailable(api).addOnSuccessListener { have ->
                if (have.areModulesAvailable()) { cont.resume(true); return@addOnSuccessListener }
                val listener = InstallStatusListener { u ->
                    when (u.installState) {
                        ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> if (cont.isActive) cont.resume(true)
                        ModuleInstallStatusUpdate.InstallState.STATE_FAILED, ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> if (cont.isActive) cont.resume(false)
                        else -> Unit
                    }
                }
                client.installModules(ModuleInstallRequest.newBuilder().addApi(api).setListener(listener).build())
                    .addOnSuccessListener { if (it.areModulesAlreadyInstalled() && cont.isActive) cont.resume(true) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(false) }
            }.addOnFailureListener { if (cont.isActive) cont.resume(false) }
        }
    } finally {
        api.close()
    }
}

/**
 * Finds the main subject(s) of [bmp] on the device. Returns a mask the size of [bmp] whose alpha is how sure the model is that a pixel
 * belongs to the subject, or null if the model is not available yet or nothing was found.
 */
suspend fun findSubject(context: Context, bmp: Bitmap): Bitmap? {
    if (!ensureModel(context)) return null
    // the model works on a modest size; the mask is scaled back up afterwards
    val scale = 1024f / max(bmp.width, bmp.height)
    val input = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
    val segmenter = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
    val confidence = try {
        suspendCancellableCoroutine<FloatArray?> { cont ->
            segmenter.process(InputImage.fromBitmap(input, 0))
                .addOnSuccessListener { r -> cont.resume(r.foregroundConfidenceMask?.let { b -> FloatArray(b.remaining()).also { b.get(it) } }) }
                .addOnFailureListener { cont.resume(null) }
        }
    } finally {
        segmenter.close()
    }
    if (confidence == null || confidence.size != input.width * input.height) return null
    return withContext(Dispatchers.Default) {
        val px = IntArray(confidence.size) { (confidence[it].coerceIn(0f, 1f) * 255f).toInt() shl 24 }
        val mask = Bitmap.createBitmap(px, input.width, input.height, Bitmap.Config.ARGB_8888)
        if (mask.width == bmp.width) mask else Bitmap.createScaledBitmap(mask, bmp.width, bmp.height, true)
    }
}
