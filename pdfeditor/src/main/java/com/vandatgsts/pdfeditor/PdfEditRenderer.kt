package com.vandatgsts.pdfeditor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.toColorInt

/**
 * Chịu trách nhiệm vẽ overlay các chỉnh sửa lên Canvas.
 * Tách biệt khỏi View layer để có thể tái sử dụng với bất kỳ surface nào.
 */
class PdfEditRenderer {

    // ---- Paint cache (tạo 1 lần, tái sử dụng) ----
    private val textPaint = Paint().apply {
        color = Color.RED
        isAntiAlias = true
    }
    private val whitePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val blackTextPaint = Paint().apply {
        color = Color.BLACK
        isAntiAlias = true
    }
    private val borderPaint = Paint().apply {
        color = "#3B82F6".toColorInt()
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val handleStrokePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
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
        isAntiAlias = true
    }
    private val resizePaint = Paint().apply {
        color = "#3B82F6".toColorInt()
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val highlightPaint = Paint().apply {
        color = Color.argb(100, 255, 255, 0)
        style = Paint.Style.FILL
    }
    private val linePaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val detectFramePaint = Paint().apply {
        color = "#3B82F6".toColorInt()
        style = Paint.Style.STROKE
    }
    private val imagePaint = Paint().apply {
        color = "#F97316".toColorInt()
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val imageFillPaint = Paint().apply {
        color = "#F97316".toColorInt()
        alpha = 40
        style = Paint.Style.FILL
    }
    private val selPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
    }
    private val selFillPaint = Paint().apply {
        color = Color.argb(30, 239, 68, 68)
        style = Paint.Style.FILL
    }
    // Reusable temp paint for WhiteoutTextEdit custom colors
    private val tempPaint = Paint()

    /**
     * Vẽ tất cả các phần tử chỉnh sửa lên [canvas].
     *
     * @param canvas Canvas đích
     * @param baseBitmap Bitmap trang PDF sạch (cache)
     * @param scaleFactor Hệ số tỉ lệ density
     * @param pageHeight Chiều cao trang PDF gốc (points)
     * @param edits Danh sách các chỉnh sửa tạm
     * @param detectedTextBlocks Khối text đã detect
     * @param isEditTextMode Có đang ở chế độ sửa text
     * @param isReplaceImageMode Có đang ở chế độ thay ảnh
     * @param detectedImageRects Danh sách hình chữ nhật ảnh đã detect
     * @param isLineEffectMode Có đang ở chế độ kẻ chữ
     * @param selectionRect Hình chữ nhật chọn vùng hiện tại
     */
    fun renderEdits(
        canvas: Canvas,
        baseBitmap: Bitmap,
        scaleFactor: Float,
        pageHeight: Int,
        edits: List<PdfViewerActivity.PdfEdit>,
        detectedTextBlocks: List<TextBlock>,
        isEditTextMode: Boolean,
        isReplaceImageMode: Boolean,
        detectedImageRects: List<RectF>,
        isLineEffectMode: Boolean,
        selectionRect: RectF?
    ) {
        // Cập nhật scale-dependent paint properties
        textPaint.textSize = 14f * scaleFactor
        blackTextPaint.textSize = 14f * scaleFactor
        borderPaint.strokeWidth = 1.5f * scaleFactor
        borderPaint.pathEffect = DashPathEffect(floatArrayOf(8f * scaleFactor, 4f * scaleFactor), 0f)
        handleStrokePaint.strokeWidth = 1.5f * scaleFactor
        xPaint.strokeWidth = 1.5f * scaleFactor
        linePaint.strokeWidth = 1.5f * scaleFactor
        rotateLinePaint.strokeWidth = 1.2f * scaleFactor
        detectFramePaint.strokeWidth = 1f * scaleFactor
        detectFramePaint.pathEffect = DashPathEffect(floatArrayOf(6f * scaleFactor, 4f * scaleFactor), 0f)
        imagePaint.strokeWidth = 2f * scaleFactor
        imagePaint.pathEffect = DashPathEffect(floatArrayOf(8f * scaleFactor, 4f * scaleFactor), 0f)
        selPaint.strokeWidth = 1.5f * scaleFactor
        selPaint.pathEffect = DashPathEffect(floatArrayOf(8f * scaleFactor, 6f * scaleFactor), 0f)

        // Vẽ nền trang PDF sạch
        canvas.drawBitmap(baseBitmap, 0f, 0f, null)

        // Vẽ từng edit
        for (edit in edits) {
            when (edit) {
                is PdfViewerActivity.PdfEdit.TextEdit -> {
                    val bmpX = edit.x * scaleFactor
                    val bmpY = (pageHeight - edit.y) * scaleFactor
                    canvas.drawText(edit.text, bmpX, bmpY, textPaint)
                }
                is PdfViewerActivity.PdfEdit.SignatureEdit -> {
                    // Đã hiển thị bằng FloatingEditView tăng tốc phần cứng, không vẽ lên canvas để tối ưu
                }
                is PdfViewerActivity.PdfEdit.ImageEdit -> {
                    // Đã hiển thị bằng FloatingEditView tăng tốc phần cứng, không vẽ lên canvas để tối ưu
                }
                is PdfViewerActivity.PdfEdit.WhiteoutTextEdit -> {
                    val bmpX = edit.x * scaleFactor
                    val bmpY = (pageHeight - edit.y) * scaleFactor
                    val whiteWidth = edit.width * scaleFactor
                    val whiteHeight = edit.fontSize * 1.2f * scaleFactor
                    canvas.drawRect(
                        bmpX,
                        bmpY - whiteHeight + 2f * scaleFactor,
                        bmpX + whiteWidth,
                        bmpY + 4f * scaleFactor,
                        whitePaint
                    )
                    tempPaint.set(blackTextPaint)
                    tempPaint.color = edit.textColor
                    tempPaint.textSize = edit.fontSize * scaleFactor
                    tempPaint.isFakeBoldText = edit.isBold
                    canvas.drawText(edit.text, bmpX, bmpY, tempPaint)
                }
                is PdfViewerActivity.PdfEdit.HighlightEdit -> {
                    val bmpX = edit.x * scaleFactor
                    val bmpY = (pageHeight - edit.y) * scaleFactor
                    val highlightWidth = edit.width * scaleFactor
                    canvas.drawRect(
                        bmpX,
                        bmpY - edit.height * scaleFactor + 2f,
                        bmpX + highlightWidth,
                        bmpY + 4f,
                        highlightPaint
                    )
                }
                is PdfViewerActivity.PdfEdit.LineEffectEdit -> {
                    val bmpX = edit.x * scaleFactor
                    val bmpY = (pageHeight - edit.y) * scaleFactor
                    val bmpWidth = edit.width * scaleFactor
                    val bmpHeight = edit.height * scaleFactor
                    if (edit.effectType == 1) {
                        val lineY = bmpY + 2f * scaleFactor
                        canvas.drawLine(bmpX, lineY, bmpX + bmpWidth, lineY, linePaint)
                    } else {
                        val lineY = bmpY - bmpHeight / 2
                        canvas.drawLine(bmpX, lineY, bmpX + bmpWidth, lineY, linePaint)
                    }
                }
            }
        }

        // Vẽ khung text blocks (chế độ Edit)
        if (isEditTextMode) {
            for (block in detectedTextBlocks) {
                val bmpX = block.x * scaleFactor
                val bmpY = (pageHeight - block.y) * scaleFactor
                val blockWidth = block.width * scaleFactor
                val blockHeight = block.height * scaleFactor
                canvas.drawRect(
                    bmpX - 2f * scaleFactor,
                    bmpY - blockHeight + 2f * scaleFactor,
                    bmpX + blockWidth + 2f * scaleFactor,
                    bmpY + 4f * scaleFactor,
                    detectFramePaint
                )
            }
        }

        // Vẽ khung ảnh detected (chế độ Replace Image)
        if (isReplaceImageMode) {
            for (rect in detectedImageRects) {
                val minX = minOf(rect.left, rect.right)
                val maxX = maxOf(rect.left, rect.right)
                val minY = minOf(rect.top, rect.bottom)
                val maxY = maxOf(rect.top, rect.bottom)
                val bmpX = minX * scaleFactor
                val bmpY = (pageHeight - minY) * scaleFactor
                val bmpRight = maxX * scaleFactor
                val bmpTop = (pageHeight - maxY) * scaleFactor
                val drawRect = RectF(bmpX, bmpTop, bmpRight, bmpY)
                canvas.drawRect(drawRect, imageFillPaint)
                canvas.drawRect(drawRect, imagePaint)
            }
        }

        // Vẽ selection rect (chế độ Line Effect)
        if (isLineEffectMode && selectionRect != null) {
            val left = selectionRect.left * scaleFactor
            val right = selectionRect.right * scaleFactor
            val bottom = (pageHeight - selectionRect.bottom) * scaleFactor
            val top = (pageHeight - selectionRect.top) * scaleFactor
            canvas.drawRect(left, top, right, bottom, selFillPaint)
            canvas.drawRect(left, top, right, bottom, selPaint)
        }
    }

    /**
     * Vẽ một phần tử ResizableEdit (SignatureEdit/ImageEdit) kèm các nút điều khiển.
     */
    fun drawResizableEdit(
        edit: PdfViewerActivity.ResizableEdit,
        canvas: Canvas,
        scaleFactor: Float,
        pageHeight: Int
    ) {
        val bmpX = edit.x * scaleFactor
        val bmpY = (pageHeight - edit.y) * scaleFactor
        val sigWidth = edit.width * scaleFactor
        val sigHeight = edit.height * scaleFactor

        val destRect = RectF(bmpX, bmpY - sigHeight, bmpX + sigWidth, bmpY)
        val cx = bmpX + sigWidth / 2f
        val cy = bmpY - sigHeight / 2f

        canvas.save()
        canvas.rotate(edit.rotation, cx, cy)

        canvas.drawBitmap(edit.bitmap, null, destRect, null)

        // Khung viền nét đứt
        canvas.drawRect(destRect, borderPaint)

        val handleRadius = 7f * scaleFactor

        // 1. Nút xóa (đỏ) - góc trên trái
        canvas.drawCircle(bmpX, bmpY - sigHeight, handleRadius, deletePaint)
        canvas.drawCircle(bmpX, bmpY - sigHeight, handleRadius, handleStrokePaint)
        val xOffset = 2.5f * scaleFactor
        canvas.drawLine(bmpX - xOffset, bmpY - sigHeight - xOffset, bmpX + xOffset, bmpY - sigHeight + xOffset, xPaint)
        canvas.drawLine(bmpX + xOffset, bmpY - sigHeight - xOffset, bmpX - xOffset, bmpY - sigHeight + xOffset, xPaint)

        // 2. Nút xoay (xanh lá) - giữa phía trên
        val rotateHandleY = bmpY - sigHeight - 12f * scaleFactor
        canvas.drawLine(cx, bmpY - sigHeight, cx, rotateHandleY, rotateLinePaint)
        canvas.drawCircle(cx, rotateHandleY, handleRadius, rotatePaint)
        canvas.drawCircle(cx, rotateHandleY, handleRadius, handleStrokePaint)

        // 3. Nút co giãn (xanh dương) - góc dưới phải
        canvas.drawCircle(bmpX + sigWidth, bmpY, handleRadius, resizePaint)
        canvas.drawCircle(bmpX + sigWidth, bmpY, handleRadius, handleStrokePaint)

        canvas.restore()
    }
}
