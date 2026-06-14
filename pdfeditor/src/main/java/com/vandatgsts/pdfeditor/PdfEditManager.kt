package com.vandatgsts.pdfeditor

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF

/**
 * Quản lý danh sách chỉnh sửa tạm thời và trạng thái tương tác (kéo, co giãn, xoay).
 * Không phụ thuộc vào View.
 */
class PdfEditManager {

    val pendingEdits = mutableListOf<PdfViewerActivity.PdfEdit>()
    val detectedTextBlocks = mutableListOf<TextBlock>()
    val detectedImageRects = mutableListOf<RectF>()

    // ---- Trạng thái kéo thả ----
    var activeDragEdit: PdfViewerActivity.PdfEdit? = null
        private set
    var dragOffsetX = 0f
        private set
    var dragOffsetY = 0f
        private set

    // ---- Trạng thái co giãn ----
    var isResizing = false
        private set
    var activeResizeEdit: PdfViewerActivity.ResizableEdit? = null
        private set

    // ---- Trạng thái xoay ----
    var isRotating = false
        private set
    var activeRotateEdit: PdfViewerActivity.ResizableEdit? = null
        private set
    var initialTouchAngle = 0.0
        private set
    var initialEditRotation = 0f
        private set

    // ---- Trạng thái highlight ----
    var activeHighlightEdit: PdfViewerActivity.PdfEdit.HighlightEdit? = null
    var highlightStartX = 0f
    var highlightStartY = 0f

    // ---- Trạng thái line effect ----
    var selectionRect: RectF? = null
    var selectionStartX = 0f
    var selectionStartY = 0f

    // ---- Trạng thái các chế độ ----
    var isEditTextMode = false
    var isHighlightMode = false
    var isLineEffectMode = false
    var isReplaceImageMode = false
    var isSignatureMode = false
    var activeLineEffectType = 1 // 1: Underline, 2: Strikethrough

    // ---- Trạng thái chèn ảnh ----
    var imageInsertX = 0f
    var imageInsertY = 0f
    var targetReplaceRect: RectF? = null

    // ---- Trạng thái inline edit ----
    var activeInlineBlock: TextBlock? = null

    // ---- Quản lý kéo thả ----
    fun startDrag(edit: PdfViewerActivity.PdfEdit, pdfX: Float, pdfY: Float) {
        activeDragEdit = edit
        dragOffsetX = pdfX - edit.x
        dragOffsetY = pdfY - edit.y
    }

    fun updateDrag(pdfX: Float, pdfY: Float, pageW: Float, pageH: Float) {
        activeDragEdit?.let { edit ->
            edit.x = (pdfX - dragOffsetX).coerceIn(0f, pageW)
            edit.y = (pdfY - dragOffsetY).coerceIn(0f, pageH)
        }
    }

    // ---- Quản lý co giãn ----
    fun startResize(edit: PdfViewerActivity.ResizableEdit) {
        isResizing = true
        activeResizeEdit = edit
    }

    fun updateResize(pdfX: Float, pdfY: Float, touchHelper: TouchInteractionHelper) {
        activeResizeEdit?.let { edit ->
            val localPoint = touchHelper.getLocalPoint(pdfX, pdfY, edit)
            val ratio = edit.bitmap.width.toFloat() / edit.bitmap.height.toFloat()
            val newWidth = (localPoint.x - edit.x).coerceAtLeast(30f)
            edit.width = newWidth
            edit.height = newWidth / ratio
        }
    }

    // ---- Quản lý xoay ----
    fun startRotate(edit: PdfViewerActivity.ResizableEdit, pdfX: Float, pdfY: Float) {
        isRotating = true
        activeRotateEdit = edit
        val cx = edit.x + edit.width / 2f
        val cy = edit.y + edit.height / 2f
        val dx = pdfX - cx
        val dy = pdfY - cy
        initialTouchAngle = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble()))
        initialEditRotation = edit.rotation
    }

    fun updateRotate(pdfX: Float, pdfY: Float) {
        activeRotateEdit?.let { edit ->
            val cx = edit.x + edit.width / 2f
            val cy = edit.y + edit.height / 2f
            val dx = pdfX - cx
            val dy = pdfY - cy
            val currentAngle = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble()))
            val diff = (initialTouchAngle - currentAngle).toFloat()
            edit.rotation = (initialEditRotation + diff) % 360f
        }
    }

    // ---- Quản lý highlight ----
    fun startHighlight(pdfX: Float, pdfY: Float): PdfViewerActivity.PdfEdit.HighlightEdit {
        highlightStartX = pdfX
        highlightStartY = pdfY
        val newHighlight = PdfViewerActivity.PdfEdit.HighlightEdit(pdfX, pdfY, 0f, 16f)
        activeHighlightEdit = newHighlight
        pendingEdits.add(newHighlight)
        return newHighlight
    }

    fun updateHighlight(pdfX: Float) {
        activeHighlightEdit?.let { edit ->
            if (pdfX >= highlightStartX) {
                edit.x = highlightStartX
                edit.width = pdfX - highlightStartX
            } else {
                edit.x = pdfX
                edit.width = highlightStartX - pdfX
            }
        }
    }

    fun endHighlight() {
        activeHighlightEdit?.let { edit ->
            if (edit.width < 5f) {
                pendingEdits.remove(edit)
            }
        }
        activeHighlightEdit = null
    }

    // ---- Quản lý line effect selection ----
    fun startSelection(pdfX: Float, pdfY: Float) {
        selectionStartX = pdfX
        selectionStartY = pdfY
        selectionRect = RectF(pdfX, pdfY, pdfX, pdfY)
    }

    fun updateSelection(pdfX: Float, pdfY: Float) {
        selectionRect = RectF(
            minOf(selectionStartX, pdfX),
            minOf(selectionStartY, pdfY),
            maxOf(selectionStartX, pdfX),
            maxOf(selectionStartY, pdfY)
        )
    }

    fun endSelectionAndApply(): Int {
        var count = 0
        selectionRect?.let { rect ->
            if (rect.width() > 3f || rect.height() > 3f) {
                for (block in detectedTextBlocks) {
                    val blockLeft = block.x
                    val blockRight = block.x + block.width
                    val blockBottom = block.y - 4f
                    val blockTop = block.y + block.height
                    val isIntersect = !(blockRight < rect.left || blockLeft > rect.right ||
                            blockTop < rect.top || blockBottom > rect.bottom)
                    if (isIntersect) {
                        pendingEdits.add(
                            PdfViewerActivity.PdfEdit.LineEffectEdit(
                                effectType = activeLineEffectType,
                                x = block.x,
                                y = block.y,
                                width = block.width,
                                height = block.height
                            )
                        )
                        count++
                    }
                }
            }
        }
        selectionRect = null
        return count
    }

    // ---- Giải phóng trạng thái tương tác ----
    fun releaseInteraction() {
        isResizing = false
        isRotating = false
        activeResizeEdit = null
        activeRotateEdit = null
        activeDragEdit = null
    }

    val isInteracting: Boolean
        get() = isResizing || isRotating || activeDragEdit != null

    // ---- Deactivate all modes ----
    fun deactivateAllModes() {
        isEditTextMode = false
        isHighlightMode = false
        isLineEffectMode = false
        isReplaceImageMode = false
        isSignatureMode = false
        detectedImageRects.clear()
    }

    // ---- Thêm edit ----
    fun addTextEdit(text: String, x: Float, y: Float) {
        pendingEdits.add(PdfViewerActivity.PdfEdit.TextEdit(text, x, y))
    }

    fun addSignatureEdit(bitmap: Bitmap, centerX: Float, centerY: Float, w: Float = 140f, h: Float = 70f) {
        pendingEdits.add(PdfViewerActivity.PdfEdit.SignatureEdit(bitmap, centerX - w / 2, centerY - h / 2, w, h))
    }

    fun addImageEdit(bitmap: Bitmap, x: Float, y: Float, w: Float, h: Float) {
        pendingEdits.add(PdfViewerActivity.PdfEdit.ImageEdit(bitmap, x, y, w, h))
    }

    fun addWhiteoutEdit(text: String, x: Float, y: Float, width: Float, fontSize: Float = 14f, isBold: Boolean = false, textColor: Int = android.graphics.Color.BLACK) {
        pendingEdits.add(PdfViewerActivity.PdfEdit.WhiteoutTextEdit(text, x, y, width, fontSize, isBold, textColor))
    }

    fun addHighlightEdit(x: Float, y: Float, width: Float, height: Float = 16f) {
        pendingEdits.add(PdfViewerActivity.PdfEdit.HighlightEdit(x, y, width, height))
    }

    fun deleteEditAt(index: Int) {
        if (index in pendingEdits.indices) {
            pendingEdits.removeAt(index)
        }
    }

    fun clearEdits() {
        pendingEdits.clear()
    }
}
