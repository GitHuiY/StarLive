package io.github.githuiy.starlive.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.createBitmap
import kotlin.math.roundToInt

internal const val WithinDoodlePenWidth = .008f
internal const val WithinDoodleEraserWidth = .065f
internal const val WithinDoodleInk = 0xFF0B1424.toInt()

internal data class WithinDoodleStroke(val points: List<Offset>, val erasing: Boolean) {
    val width: Float get() = if (erasing) WithinDoodleEraserWidth else WithinDoodlePenWidth
}

internal fun renderWithinDoodle(strokes: List<WithinDoodleStroke>, viewport: IntSize): Bitmap? {
    if (viewport.width <= 0 || viewport.height <= 0 || strokes.none { !it.erasing && it.points.isNotEmpty() }) {
        return null
    }
    val ratio = 1800f / maxOf(viewport.width, viewport.height)
    val width = (viewport.width * ratio).roundToInt().coerceAtLeast(1)
    val height = (viewport.height * ratio).roundToInt().coerceAtLeast(1)
    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    for (stroke in strokes) {
        if (stroke.points.isEmpty()) continue
        paint.color = if (stroke.erasing) Color.WHITE else WithinDoodleInk
        paint.strokeWidth = stroke.width * minOf(width, height)
        val first = stroke.points.first()
        if (stroke.points.size == 1) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(first.x * width, first.y * height, paint.strokeWidth / 2, paint)
        } else {
            paint.style = Paint.Style.STROKE
            val path = Path().apply {
                moveTo(first.x * width, first.y * height)
                stroke.points.drop(1).forEach { lineTo(it.x * width, it.y * height) }
            }
            canvas.drawPath(path, paint)
        }
    }
    val row = IntArray(width)
    for (y in 0 until height) {
        bitmap.getPixels(row, 0, width, 0, y, width, 1)
        if (row.any { it != Color.WHITE }) return bitmap
    }
    bitmap.recycle()
    return null
}
