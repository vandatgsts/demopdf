package com.vandatgsts.demopdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class SignatureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.BLUE // Màu chữ ký mặc định (thường ký mực xanh)
        isAntiAlias = true
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                path.moveTo(x, y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                path.lineTo(x, y)
            }
            MotionEvent.ACTION_UP -> {
                // Hoàn thành nét vẽ
                performClick()
            }
            else -> return false
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun clear() {
        path.reset()
        invalidate()
    }

    fun getSignatureBitmap(): Bitmap? {
        if (width <= 0 || height <= 0) return null
        // Tạo bitmap trong suốt để vẽ chữ ký lên PDF không bị che nền văn bản cũ
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        draw(canvas)
        return bitmap
    }
    
    fun isSignatureEmpty(): Boolean {
        return path.isEmpty
    }
}
