package br.gov.rs.sapucaiadosul.sentinela

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import java.io.File
import java.io.FileOutputStream

/** Assinatura desenhada pelo responsável ou agente no próprio dispositivo. */
class SignatureView(context: Context) : View(context) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 40, 73); style = Paint.Style.STROKE
        strokeWidth = 5f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val paper = Paint().apply { color = Color.WHITE }
    private val stroke = Path()
    private var touched = false
    init { setBackgroundColor(Color.WHITE); minimumHeight = 220 }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        canvas.drawPath(stroke, ink)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { stroke.moveTo(event.x, event.y); touched = true; invalidate(); return true }
            MotionEvent.ACTION_MOVE -> { stroke.lineTo(event.x, event.y); invalidate(); return true }
            MotionEvent.ACTION_UP -> { stroke.lineTo(event.x, event.y); invalidate(); return true }
        }
        return true
    }
    fun clear() { stroke.reset(); touched = false; invalidate() }
    fun saveTo(file: File): Boolean {
        if (!touched || width <= 0 || height <= 0) return false
        file.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).also { onDraw(it) }
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return true
    }
}
