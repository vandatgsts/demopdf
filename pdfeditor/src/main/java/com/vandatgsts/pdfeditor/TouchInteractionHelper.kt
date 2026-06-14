package com.vandatgsts.pdfeditor

import android.graphics.PointF
import android.graphics.RectF

/**
 * Helper chuyên xử lý tọa độ chạm và hit-testing.
 * Không phụ thuộc vào View cụ thể.
 */
class TouchInteractionHelper {

    /** Chuyển tọa độ View (Android) sang tọa độ PDF (gốc trái-dưới). */
    fun viewToPdf(touchX: Float, touchY: Float, viewW: Float, viewH: Float, pdfW: Float, pdfH: Float): PointF {
        val pdfX = touchX * (pdfW / viewW)
        val pdfY = pdfH - (touchY * (pdfH / viewH))
        return PointF(pdfX, pdfY)
    }

    /** Chuyển tọa độ PDF sang tọa độ View. */
    fun pdfToView(pdfX: Float, pdfY: Float, viewW: Float, viewH: Float, pdfW: Float, pdfH: Float): PointF {
        val viewX = pdfX * (viewW / pdfW)
        val viewY = (pdfH - pdfY) * (viewH / pdfH)
        return PointF(viewX, viewY)
    }

    /**
     * Chuyển tọa độ PDF sang tọa độ cục bộ (local) của một edit đã xoay.
     * Dùng để kiểm tra điểm chạm trúng edit bất kể góc xoay.
     */
    fun getLocalPoint(pdfX: Float, pdfY: Float, edit: PdfViewerActivity.ResizableEdit): PointF {
        val cx = edit.x + edit.width / 2f
        val cy = edit.y + edit.height / 2f
        val angleRad = Math.toRadians((-edit.rotation).toDouble())
        val cos = Math.cos(angleRad)
        val sin = Math.sin(angleRad)
        val dx = pdfX - cx
        val dy = pdfY - cy
        val rx = dx * cos - dy * sin + cx
        val ry = dx * sin + dy * cos + cy
        return PointF(rx.toFloat(), ry.toFloat())
    }

    /**
     * Tìm phần tử edit tại tọa độ PDF cho trước.
     * Duyệt từ cuối lên đầu để ưu tiên phần tử nằm đè trên.
     */
    fun findEditAt(pdfX: Float, pdfY: Float, edits: List<PdfViewerActivity.PdfEdit>): PdfViewerActivity.PdfEdit? {
        for (i in edits.indices.reversed()) {
            when (val edit = edits[i]) {
                is PdfViewerActivity.PdfEdit.TextEdit -> {
                    val width = edit.text.length * 8f
                    if (pdfX >= edit.x && pdfX <= edit.x + width && pdfY >= edit.y - 12f && pdfY <= edit.y + 4f) {
                        return edit
                    }
                }
                is PdfViewerActivity.PdfEdit.WhiteoutTextEdit -> {
                    val width = edit.width
                    if (pdfX >= edit.x && pdfX <= edit.x + width && pdfY >= edit.y - 12f && pdfY <= edit.y + 4f) {
                        return edit
                    }
                }
                is PdfViewerActivity.PdfEdit.SignatureEdit -> {
                    val localPoint = getLocalPoint(pdfX, pdfY, edit)
                    val padding = 20f
                    if (localPoint.x >= edit.x - padding && localPoint.x <= edit.x + edit.width + padding &&
                        localPoint.y >= edit.y - padding && localPoint.y <= edit.y + edit.height + padding) {
                        return edit
                    }
                }
                is PdfViewerActivity.PdfEdit.ImageEdit -> {
                    val localPoint = getLocalPoint(pdfX, pdfY, edit)
                    val padding = 20f
                    if (localPoint.x >= edit.x - padding && localPoint.x <= edit.x + edit.width + padding &&
                        localPoint.y >= edit.y - padding && localPoint.y <= edit.y + edit.height + padding) {
                        return edit
                    }
                }
                is PdfViewerActivity.PdfEdit.HighlightEdit -> {
                    if (pdfX >= edit.x && pdfX <= edit.x + edit.width && pdfY >= edit.y - 12f && pdfY <= edit.y + 4f) {
                        return edit
                    }
                }
                is PdfViewerActivity.PdfEdit.LineEffectEdit -> {
                    val midY = if (edit.effectType == 1) edit.y - 2f else edit.y + edit.height / 2
                    if (pdfX >= edit.x && pdfX <= edit.x + edit.width && pdfY >= midY - 6f && pdfY <= midY + 6f) {
                        return edit
                    }
                }
            }
        }
        return null
    }

    /** Tìm khối text đã detect tại tọa độ PDF. */
    fun findDetectedTextBlockAt(pdfX: Float, pdfY: Float, blocks: List<TextBlock>): TextBlock? {
        val padding = 6f
        for (block in blocks) {
            if (pdfX >= block.x - padding && pdfX <= block.x + block.width + padding &&
                pdfY >= block.y - block.height - padding && pdfY <= block.y + padding) {
                return block
            }
        }
        return null
    }

    /** Enum cho kết quả kiểm tra chạm trúng handle. */
    enum class HandleType {
        DELETE, ROTATE, RESIZE, NONE
    }

    /** Kết quả hit-test handle. */
    data class HandleHitResult(
        val type: HandleType,
        val edit: PdfViewerActivity.ResizableEdit?,
        val editIndex: Int = -1
    )

    /**
     * Kiểm tra xem chạm vào handle nào (xóa, xoay, co giãn) của ResizableEdit.
     * @return HandleHitResult với type là NONE nếu không trúng handle nào.
     */
    fun findHandleAt(pdfX: Float, pdfY: Float, edits: List<PdfViewerActivity.PdfEdit>): HandleHitResult {
        for (i in edits.indices.reversed()) {
            val edit = edits[i]
            if (edit !is PdfViewerActivity.ResizableEdit) continue

            val localPoint = getLocalPoint(pdfX, pdfY, edit)
            val localX = localPoint.x
            val localY = localPoint.y

            // a. Xóa: top-left (edit.x, edit.y + edit.height)
            val deleteX = edit.x
            val deleteY = edit.y + edit.height
            if (Math.hypot((localX - deleteX).toDouble(), (localY - deleteY).toDouble()) < 20.0) {
                return HandleHitResult(HandleType.DELETE, edit, i)
            }

            // b. Xoay: top-middle (edit.x + edit.width / 2, edit.y + edit.height + 15)
            val rotateX = edit.x + edit.width / 2f
            val rotateY = edit.y + edit.height + 15f
            if (Math.hypot((localX - rotateX).toDouble(), (localY - rotateY).toDouble()) < 20.0) {
                return HandleHitResult(HandleType.ROTATE, edit, i)
            }

            // c. Co giãn: bottom-right (edit.x + edit.width, edit.y)
            val resizeX = edit.x + edit.width
            val resizeY = edit.y
            if (Math.hypot((localX - resizeX).toDouble(), (localY - resizeY).toDouble()) < 20.0) {
                return HandleHitResult(HandleType.RESIZE, edit, i)
            }
        }
        return HandleHitResult(HandleType.NONE, null)
    }

    /** Kiểm tra chạm trúng RectF ảnh nào trong danh sách. */
    fun findImageRectAt(pdfX: Float, pdfY: Float, rects: List<RectF>): RectF? {
        for (rect in rects) {
            val left = minOf(rect.left, rect.right)
            val right = maxOf(rect.left, rect.right)
            val bottom = minOf(rect.top, rect.bottom)
            val top = maxOf(rect.top, rect.bottom)
            if (pdfX in left..right && pdfY in bottom..top) {
                return rect
            }
        }
        return null
    }
}
