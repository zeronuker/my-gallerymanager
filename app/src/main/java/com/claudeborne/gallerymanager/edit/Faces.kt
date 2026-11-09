package com.claudeborne.gallerymanager.edit

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Finds the faces in [bmp] on the device and returns a map for the shader: red is skin (the face without eyes, brows and lips),
 * green is the open mouth (teeth) and blue the eyes, all feathered. Returns null when no face was found.
 */
suspend fun findFaces(bmp: Bitmap): Bitmap? {
    val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .build(),
    )
    val faces = try {
        suspendCancellableCoroutine<List<Face>> { cont ->
            detector.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(emptyList()) }
        }
    } finally {
        detector.close()
    }
    if (faces.isEmpty()) return null
    return withContext(Dispatchers.Default) { buildMask(bmp.width, bmp.height, faces) }
}

private fun Face.points(type: Int): List<PointF> = getContour(type)?.points.orEmpty()

private fun path(vararg rings: List<PointF>): Path = Path().apply {
    val pts = rings.flatMap { it }
    pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
    close()
}

private fun buildMask(w: Int, h: Int, faces: List<Face>): Bitmap {
    val skin = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
    val teeth = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
    val eyes = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
    val cs = Canvas(skin)
    val ct = Canvas(teeth)
    val ce = Canvas(eyes)

    for (f in faces) {
        val size = f.boundingBox.width().toFloat()
        fun brush(feather: Float, clear: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            maskFilter = BlurMaskFilter(maxOf(1f, size * feather), BlurMaskFilter.Blur.NORMAL)
            if (clear) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }

        val oval = f.points(FaceContour.FACE)
        if (oval.size < 3) continue
        cs.drawPath(path(oval), brush(0.03f))

        // everything on the face that is not skin keeps its own look
        val leftEye = f.points(FaceContour.LEFT_EYE)
        val rightEye = f.points(FaceContour.RIGHT_EYE)
        val brows = listOf(
            f.points(FaceContour.LEFT_EYEBROW_TOP) + f.points(FaceContour.LEFT_EYEBROW_BOTTOM).reversed(),
            f.points(FaceContour.RIGHT_EYEBROW_TOP) + f.points(FaceContour.RIGHT_EYEBROW_BOTTOM).reversed(),
        )
        val lips = f.points(FaceContour.UPPER_LIP_TOP) + f.points(FaceContour.LOWER_LIP_BOTTOM).reversed()
        val clear = brush(0.012f, clear = true)
        for (ring in listOf(leftEye, rightEye, lips) + brows) if (ring.size >= 3) cs.drawPath(path(ring), clear)

        for (eye in listOf(leftEye, rightEye)) if (eye.size >= 3) ce.drawPath(path(eye), brush(0.004f))

        // the dark gap between the lips only exists when the mouth is open
        val mouth = f.points(FaceContour.UPPER_LIP_BOTTOM) + f.points(FaceContour.LOWER_LIP_TOP).reversed()
        if (mouth.size >= 3) ct.drawPath(path(mouth), brush(0.006f))
    }

    val a = IntArray(w * h).also { skin.getPixels(it, 0, w, 0, 0, w, h) }
    val b = IntArray(w * h).also { teeth.getPixels(it, 0, w, 0, 0, w, h) }
    val c = IntArray(w * h).also { eyes.getPixels(it, 0, w, 0, 0, w, h) }
    for (i in a.indices) a[i] = (255 shl 24) or ((a[i] ushr 24) shl 16) or ((b[i] ushr 24) shl 8) or (c[i] ushr 24)
    return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
}
