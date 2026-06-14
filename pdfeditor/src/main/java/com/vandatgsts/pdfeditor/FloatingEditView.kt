package com.vandatgsts.pdfeditor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.toColorInt
import kotlin.math.atan2
import kotlin.math.hypot

class FloatingEditView(
    context: Context,
    val bitmap: Bitmap,
    var isSignature: Boolean = true,
    var onDeleteListener: (() -> Unit)? = null,
    var onUpdateListener: (() -> Unit)? = null
) : View(context) {

    // Padding để không bị cắt khi vẽ handle ở viền
    private val padding = 40f
    private val rotateLineLength = 30f
    private val handleRadius = 25f

    // Aspect ratio của bitmap để giữ tỉ lệ khi co giãn
    private val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()

    // Chế độ tương tác
    private enum class TouchMode {
        NONE, DRAG, ROTATE, RESIZE, DELETE
    }

    private var currentMode = TouchMode.NONE

    // Lưu các biến tracking cử chỉ chạm
    private var initialX = 0f
    private var initialY = 0f
    private var initialTranslationX = 0f
    private var initialTranslationY = 0f

    private var initialCX = 0f
    private var initialCY = 0f
    private var initialTouchAngle = 0.0
    private var initialRotation = 0f

    private var initialTouchDist = 0.0
    private var initialWidth = 0
    private var initialHeight = 0

    // Paint cache
    private val borderPaint = Paint().apply {
        color = "#3B82F6".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
        isAntiAlias = true
    }

    private val handleStrokePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val deletePaint = Paint().apply {
        color = Color.parseColor("#EF4444")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val xPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val rotatePaint = Paint().apply {
        color = Color.parseColor("#10B981")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val rotateLinePaint = Paint().apply {
        color = "#3B82F6".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val resizePaint = Paint().apply {
        color = "#3B82F6".toColorInt()
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    init {
        // Đặt thuộc tính quan trọng để View có thể nhận sự kiện chạm và hoạt động đúng vị trí
        isClickable = true
        isFocusable = true
    }

    // Lấy Content Rect (phần ảnh vẽ)
    private fun getContentRect(): RectF {
        val w = width.toFloat()
        val h = height.toFloat()
        return RectF(
            padding,
            padding + rotateLineLength,
            w - padding,
            h - padding
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val contentRect = getContentRect()

        // 1. Vẽ hình ảnh bitmap của chữ ký/ảnh
        canvas.drawBitmap(bitmap, null, contentRect, null)

        // 2. Vẽ viền nét đứt màu xanh
        canvas.drawRect(contentRect, borderPaint)

        // 3. Vẽ nút xóa (Đỏ - Góc trên trái)
        val delX = contentRect.left
        val delY = contentRect.top
        canvas.drawCircle(delX, delY, handleRadius, deletePaint)
        canvas.drawCircle(delX, delY, handleRadius, handleStrokePaint)
        val xOffset = handleRadius * 0.4f
        canvas.drawLine(delX - xOffset, delY - xOffset, delX + xOffset, delY + xOffset, xPaint)
        canvas.drawLine(delX + xOffset, delY - xOffset, delX - xOffset, delY + xOffset, xPaint)

        // 4. Vẽ nút xoay (Xanh lá - Giữa phía trên)
        val cx = contentRect.centerX()
        val rotX = cx
        val rotY = contentRect.top - rotateLineLength
        canvas.drawLine(cx, contentRect.top, cx, rotY, rotateLinePaint)
        canvas.drawCircle(rotX, rotY, handleRadius, rotatePaint)
        canvas.drawCircle(rotX, rotY, handleRadius, handleStrokePaint)

        // 5. Vẽ nút co giãn (Xanh dương - Góc dưới phải)
        val resX = contentRect.right
        val resY = contentRect.bottom
        canvas.drawCircle(resX, resY, handleRadius, resizePaint)
        canvas.drawCircle(resX, resY, handleRadius, handleStrokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val localX = event.x
        val localY = event.y

        val contentRect = getContentRect()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Xác định chạm trúng vùng nào dựa trên tọa độ local (đã được Android un-rotate tự động)
                currentMode = when {
                    // a. Kiểm tra chạm trúng nút Xoá (Góc trên trái)
                    isNearPoint(localX, localY, contentRect.left, contentRect.top) -> TouchMode.DELETE

                    // b. Kiểm tra chạm trúng nút Xoay (Giữa trên)
                    isNearPoint(localX, localY, contentRect.centerX(), contentRect.top - rotateLineLength) -> TouchMode.ROTATE

                    // c. Kiểm tra chạm trúng nút Co giãn (Góc dưới phải)
                    isNearPoint(localX, localY, contentRect.right, contentRect.bottom) -> TouchMode.RESIZE

                    // d. Kiểm tra chạm trúng thân ảnh
                    contentRect.contains(localX, localY) -> TouchMode.DRAG

                    else -> TouchMode.NONE
                }

                if (currentMode != TouchMode.NONE) {
                    parent?.requestDisallowInterceptTouchEvent(true)

                    // Lưu các giá trị ban đầu để phục vụ di chuyển xoay zoom không bị feedback loop
                    initialX = event.rawX
                    initialY = event.rawY
                    initialTranslationX = translationX
                    initialTranslationY = translationY

                    // Tâm của View trong hệ tọa độ parent
                    initialCX = x + width / 2f
                    initialCY = y + height / 2f

                    // Dùng để xoay
                    val dx = event.rawX - initialCX
                    val dy = event.rawY - initialCY
                    initialTouchAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
                    initialRotation = rotation

                    // Dùng để resize
                    initialTouchDist = hypot(dx.toDouble(), dy.toDouble())
                    initialWidth = width
                    initialHeight = height
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when (currentMode) {
                    TouchMode.DRAG -> {
                        val dx = event.rawX - initialX
                        val dy = event.rawY - initialY
                        translationX = initialTranslationX + dx
                        translationY = initialTranslationY + dy
                        onUpdateListener?.invoke()
                    }

                    TouchMode.ROTATE -> {
                        val dx = event.rawX - initialCX
                        val dy = event.rawY - initialCY
                        val currentAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
                        val diff = (currentAngle - initialTouchAngle).toFloat()
                        rotation = (initialRotation + diff) % 360f
                        onUpdateListener?.invoke()
                    }

                    TouchMode.RESIZE -> {
                        val dx = event.rawX - initialCX
                        val dy = event.rawY - initialCY
                        val currentDist = hypot(dx.toDouble(), dy.toDouble())
                        
                        // Tỉ lệ scale so với vị trí chạm ban đầu
                        val scale = currentDist / initialTouchDist
                        
                        // Chiều rộng mới, giới hạn tối thiểu 150px và tối đa 1500px
                        val newWidth = (initialWidth * scale).toFloat().coerceIn(150f, 1500f)
                        val newHeight = newWidth / aspectRatio

                        val lp = layoutParams as? FrameLayout.LayoutParams
                        if (lp != null) {
                            lp.width = newWidth.toInt()
                            lp.height = newHeight.toInt()
                            
                            // Điều chỉnh lại lề để tâm View (initialCX, initialCY) hoàn toàn đứng im khi thay đổi kích thước
                            lp.leftMargin = (initialCX - newWidth / 2f).toInt()
                            lp.topMargin = (initialCY - newHeight / 2f).toInt()
                            
                            // Cập nhật lại các tham số translation về 0 vì margin đã dịch chuyển rồi
                            translationX = 0f
                            translationY = 0f
                            
                            layoutParams = lp
                            onUpdateListener?.invoke()
                        }
                    }
                    else -> {}
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (currentMode == TouchMode.DELETE) {
                    // Xác thực lại xem khi thả ra ngón tay vẫn nằm gần nút xóa
                    if (isNearPoint(localX, localY, contentRect.left, contentRect.top)) {
                        onDeleteListener?.invoke()
                    }
                }
                currentMode = TouchMode.NONE
                return true
            }
        }
        return false
    }

    private fun isNearPoint(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val touchRadiusWithPadding = handleRadius * 2f
        return hypot((x1 - x2).toDouble(), (y1 - y2).toDouble()) < touchRadiusWithPadding
    }
}
