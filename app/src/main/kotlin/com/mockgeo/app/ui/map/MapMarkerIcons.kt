package com.mockgeo.app.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface

/** Original code-drawn markers; no vendor or reference-app artwork. */
internal object MapMarkerIcons {
    private const val BLUE = 0xff246bfd.toInt()
    fun pin(context: Context, simulation: Boolean): Bitmap {
        val scale = context.resources.displayMetrics.density
        val bitmap = Bitmap.createBitmap((32 * scale).toInt(), (42 * scale).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawCircle(16f, 15f, 14.5f, paint)
        paint.color = if (simulation) 0xff14866d.toInt() else BLUE
        val path = Path().apply {
            moveTo(16f, 41f); lineTo(5.5f, 23f)
            cubicTo(-8f, 2f, 36f, -8f, 27f, 23f)
            close()
        }
        canvas.drawPath(path, paint)
        canvas.drawCircle(16f, 14f, 12f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(16f, 14f, 4.5f, paint)
        return bitmap
    }

    fun routePoint(context: Context, number: Int, isFirst: Boolean, isLast: Boolean): Bitmap {
        val scale = context.resources.displayMetrics.density
        val bitmap = Bitmap.createBitmap((34 * scale).toInt(), (34 * scale).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawCircle(17f, 17f, 16f, paint)
        paint.color = when { isFirst -> 0xff14866d.toInt(); isLast -> 0xffd94b53.toInt(); else -> BLUE }
        canvas.drawCircle(17f, 17f, 13.5f, paint)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = if (number < 100) 13f else 10f
        canvas.drawText(number.toString(), 17f, 17f - (paint.ascent() + paint.descent()) / 2, paint)
        return bitmap
    }
}
