package com.vandatgsts.demopdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.github.barteksc.pdfviewer.PDFView
import com.vandatgsts.pdfeditor.FloatingEditView
import com.vandatgsts.pdfeditor.PdfEditManager
import com.vandatgsts.pdfeditor.PdfEditRenderer
import com.vandatgsts.pdfeditor.PdfEditorHelper
import com.vandatgsts.pdfeditor.PdfViewerActivity.PdfEdit
import com.vandatgsts.pdfeditor.PdfViewerActivity.ResizableEdit
import com.vandatgsts.pdfeditor.SignatureView
import com.vandatgsts.pdfeditor.TextBlock
import com.vandatgsts.pdfeditor.TouchInteractionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PdfViewer3rdActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var tvTitle: TextView
    private lateinit var btnSaveEdits: Button
    private lateinit var pdfView3rd: PDFView
    private lateinit var pdfViewContainer: FrameLayout
    
    private lateinit var btnPrevPage: Button
    private lateinit var tvPageIndicator: TextView
    private lateinit var btnNextPage: Button
    private lateinit var btnMenuTools: Button
    private lateinit var btnCancelEdits: Button

    private var currentPageIndex = 0
    private var pageCount = 0
    private var pdfFilePath: String? = null
    private var pdfFileUri: String? = null

    // Ba helper classes tương thích view độc lập
    private val editManager = PdfEditManager()
    private val touchHelper = TouchInteractionHelper()
    private val renderer = PdfEditRenderer()

    private var activeInlineEditText: EditText? = null

    private val selectImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { imageUri ->
            try {
                contentResolver.openInputStream(imageUri)?.use { inputStream ->
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    if (bitmap != null) {
                        val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                        val w = 150f
                        val h = w / ratio
                        addFloatingOverlayView(
                            bitmap = bitmap,
                            pdfX = editManager.imageInsertX - w / 2,
                            pdfY = editManager.imageInsertY - h / 2,
                            pdfWidth = w,
                            pdfHeight = h,
                            rotationAngle = 0f,
                            isSignature = false
                        )
                        pdfView3rd.invalidate()
                    } else {
                        Toast.makeText(this, "Không thể đọc hình ảnh này!", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Lỗi đọc ảnh: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val selectReplaceImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { imageUri ->
            try {
                contentResolver.openInputStream(imageUri)?.use { inputStream ->
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    val rect = editManager.targetReplaceRect
                    if (bitmap != null && rect != null) {
                        val minX = minOf(rect.left, rect.right)
                        val minY = minOf(rect.top, rect.bottom)
                        val w = Math.abs(rect.width())
                        val h = Math.abs(rect.height())

                        // Che ảnh cũ bằng Whiteout
                        editManager.addWhiteoutEdit(
                            text = "",
                            x = minX,
                            y = minY,
                            width = w,
                            fontSize = h
                        )

                        // Thêm overlay ảnh mới
                        addFloatingOverlayView(
                            bitmap = bitmap,
                            pdfX = minX,
                            pdfY = minY,
                            pdfWidth = w,
                            pdfHeight = h,
                            rotationAngle = 0f,
                            isSignature = false
                        )
                        pdfView3rd.invalidate()
                        Toast.makeText(this, "Đã thay thế hình ảnh thành công!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Không thể đọc hình ảnh này!", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Lỗi thay ảnh: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        editManager.targetReplaceRect = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_pdf_viewer_3rd)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainViewer3rd)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Khởi tạo Views
        btnBack = findViewById(R.id.btnBack)
        tvTitle = findViewById(R.id.tvTitle)
        btnSaveEdits = findViewById(R.id.btnSaveEdits)
        pdfView3rd = findViewById(R.id.pdfView3rd)
        pdfViewContainer = findViewById(R.id.pdfViewContainer)
        btnPrevPage = findViewById(R.id.btnPrevPage)
        tvPageIndicator = findViewById(R.id.tvPageIndicator)
        btnNextPage = findViewById(R.id.btnNextPage)
        btnMenuTools = findViewById(R.id.btnMenuTools)
        btnCancelEdits = findViewById(R.id.btnCancelEdits)

        pdfFilePath = intent.getStringExtra("PDF_FILE_PATH")
        pdfFileUri = intent.getStringExtra("PDF_FILE_URI")
        val fileName = intent.getStringExtra("PDF_FILE_NAME") ?: "Tài liệu PDF"
        tvTitle.text = fileName

        if (pdfFilePath == null) {
            Toast.makeText(this, "Không tìm thấy đường dẫn tệp!", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupPdfView3rd()
        setupTouchInteraction()

        btnBack.setOnClickListener {
            val overlayEdits = collectOverlayEdits()
            if (editManager.pendingEdits.isNotEmpty() || overlayEdits.isNotEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("Cảnh báo")
                    .setMessage("Bạn có các chỉnh sửa chưa lưu. Bạn có chắc chắn muốn thoát?")
                    .setPositiveButton("Thoát") { _, _ -> finish() }
                    .setNegativeButton("Ở lại", null)
                    .show()
            } else {
                finish()
            }
        }

        btnSaveEdits.setOnClickListener {
            saveEditsToFile()
        }

        btnCancelEdits.setOnClickListener {
            val overlayEdits = collectOverlayEdits()
            if (editManager.pendingEdits.isEmpty() && overlayEdits.isEmpty()) {
                Toast.makeText(this, "Không có chỉnh sửa nào để hủy!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            AlertDialog.Builder(this)
                .setTitle("Xác nhận hủy")
                .setMessage("Bạn có chắc chắn muốn hủy tất cả các thay đổi chưa lưu?")
                .setPositiveButton("Hủy tất cả") { _, _ ->
                    editManager.clearEdits()
                    clearFloatingOverlayViews()
                    deactivateAllModes()
                    pdfView3rd.invalidate()
                    Toast.makeText(this, "Đã hủy tất cả chỉnh sửa!", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Giữ lại", null)
                .show()
        }

        btnMenuTools.setOnClickListener {
            val options = arrayOf(
                "Chế độ: Sửa chữ trực tiếp (Inline)",
                "Chế độ: Bút dạ (Highlight)",
                "Chế độ: Kẻ chữ: Gạch dưới",
                "Chế độ: Kẻ chữ: Gạch ngang",
                "Chế độ: Ký tên (Chèn chữ ký)",
                "Chế độ: Thay thế/Chèn ảnh (Quét ảnh)",
                "Tắt tất cả chế độ (Chỉ xem)"
            )
            AlertDialog.Builder(this)
                .setTitle("Chọn công cụ chỉnh sửa")
                .setItems(options) { _, which ->
                    deactivateAllModes()
                    when (which) {
                        0 -> {
                            editManager.isEditTextMode = true
                            btnMenuTools.text = "Đang sửa"
                            btnMenuTools.backgroundTintList = android.content.res.ColorStateList.valueOf("#2563EB".toColorInt())
                            Toast.makeText(this, "Chế độ sửa chữ: Chọn khung nét đứt màu xanh để sửa trực tiếp", Toast.LENGTH_SHORT).show()
                        }
                        1 -> {
                            editManager.isHighlightMode = true
                            btnMenuTools.text = "Đang tô"
                            btnMenuTools.backgroundTintList = android.content.res.ColorStateList.valueOf("#F59E0B".toColorInt())
                            Toast.makeText(this, "Chế độ tô sáng: Vuốt ngón tay ngang để vẽ highlight", Toast.LENGTH_SHORT).show()
                        }
                        2 -> {
                            editManager.isLineEffectMode = true
                            editManager.activeLineEffectType = 1
                            btnMenuTools.text = "Gạch dưới"
                            btnMenuTools.backgroundTintList = android.content.res.ColorStateList.valueOf("#EF4444".toColorInt())
                            Toast.makeText(this, "Chế độ gạch dưới: Vuốt để quét vùng chọn text cần gạch dưới", Toast.LENGTH_SHORT).show()
                        }
                        3 -> {
                            editManager.isLineEffectMode = true
                            editManager.activeLineEffectType = 2
                            btnMenuTools.text = "Gạch ngang"
                            btnMenuTools.backgroundTintList = android.content.res.ColorStateList.valueOf("#EF4444".toColorInt())
                            Toast.makeText(this, "Chế độ gạch ngang: Vuốt để quét vùng chọn text cần gạch ngang", Toast.LENGTH_SHORT).show()
                        }
                        4 -> {
                            val pdfSize = pdfView3rd.getPageSize(currentPageIndex)
                            val centerX = pdfSize.width / 2f
                            val centerY = pdfSize.height / 2f
                            showSignatureDialogOrList(centerX, centerY)
                        }
                        5 -> {
                            editManager.isReplaceImageMode = true
                            btnMenuTools.text = "Thay ảnh"
                            btnMenuTools.backgroundTintList = android.content.res.ColorStateList.valueOf("#F97316".toColorInt())

                            // Quét hình ảnh
                            lifecycleScope.launch {
                                editManager.detectedImageRects.clear()
                                val rects = withContext(Dispatchers.IO) {
                                    pdfFilePath?.let { path ->
                                        PdfEditorHelper.detectImageRects(path, currentPageIndex)
                                    } ?: emptyList()
                                }
                                editManager.detectedImageRects.addAll(rects)
                                if (editManager.detectedImageRects.isNotEmpty()) {
                                    Toast.makeText(this@PdfViewer3rdActivity, "Đã quét và tìm thấy ${editManager.detectedImageRects.size} ảnh. Chạm vào ảnh khoanh màu cam để thay thế.", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(this@PdfViewer3rdActivity, "Không tìm thấy hình ảnh nào trên trang này! Chạm vào trang để chèn ảnh mới.", Toast.LENGTH_LONG).show()
                                }
                                pdfView3rd.invalidate()
                            }
                        }
                        6 -> {
                            Toast.makeText(this, "Đã chuyển sang chế độ Chỉ xem", Toast.LENGTH_SHORT).show()
                        }
                    }
                    pdfView3rd.invalidate()
                }
                .setNegativeButton("Đóng", null)
                .show()
        }

        btnPrevPage.setOnClickListener {
            val overlayEdits = collectOverlayEdits()
            if (editManager.pendingEdits.isNotEmpty() || overlayEdits.isNotEmpty()) {
                Toast.makeText(this, "Vui lòng lưu chỉnh sửa trước khi chuyển trang!", Toast.LENGTH_SHORT).show()
            } else {
                pdfView3rd.jumpTo(currentPageIndex - 1)
            }
        }

        btnNextPage.setOnClickListener {
            val overlayEdits = collectOverlayEdits()
            if (editManager.pendingEdits.isNotEmpty() || overlayEdits.isNotEmpty()) {
                Toast.makeText(this, "Vui lòng lưu chỉnh sửa trước khi chuyển trang!", Toast.LENGTH_SHORT).show()
            } else {
                pdfView3rd.jumpTo(currentPageIndex + 1)
            }
        }
    }

    private fun setupPdfView3rd() {
        val file = File(pdfFilePath!!)
        if (!file.exists()) return

        pdfView3rd.fromFile(file)
            .enableSwipe(true) // swipe chuyển trang
            .swipeHorizontal(false) // cuộn dọc
            .enableDoubletap(false) // tắt double tap để tránh xung đột với chạm sửa text
            .defaultPage(currentPageIndex)
            .pageSnap(false) // tắt pageSnap để cuộn dọc liên tục mượt mà
            .autoSpacing(true)
            .pageFling(false) // tắt pageFling để cuộn liên tục tự do
            .onLoad { nbPages ->
                pageCount = nbPages
                updatePageIndicator()
            }
            .onPageChange { page, _ ->
                currentPageIndex = page
                updatePageIndicator()
                clearFloatingOverlayViews()
                closeAndSaveInlineEditText()
                loadTextBlocksForPage(page)
            }
            .onDraw { canvas, pageWidth, pageHeight, displayedPage ->
                // onDraw vẽ đè các nét vẽ tĩnh lên canvas của PDFView
                if (displayedPage == currentPageIndex) {
                    val pdfSize = pdfView3rd.getPageSize(displayedPage)
                    val scaleFactor = pageWidth / pdfSize.width

                    renderer.renderEdits(
                        canvas = canvas,
                        baseBitmap = null, // Không vẽ lại nền, chỉ vẽ nét overlay lên vector PDFView gốc
                        scaleFactor = scaleFactor,
                        pageHeight = pdfSize.height.toInt(),
                        edits = editManager.pendingEdits,
                        detectedTextBlocks = editManager.detectedTextBlocks,
                        isEditTextMode = editManager.isEditTextMode,
                        isReplaceImageMode = editManager.isReplaceImageMode,
                        detectedImageRects = editManager.detectedImageRects,
                        isLineEffectMode = editManager.isLineEffectMode,
                        selectionRect = editManager.selectionRect
                    )
                }
            }
            .onRender { _ ->
                syncOverlayContainer()
            }
            .load()
    }

    private fun updatePageIndicator() {
        tvPageIndicator.text = "Trang ${currentPageIndex + 1} / $pageCount"
        btnPrevPage.isEnabled = currentPageIndex > 0
        btnNextPage.isEnabled = currentPageIndex < pageCount - 1
    }

    private fun syncOverlayContainer() {
        val pdfSize = pdfView3rd.getPageSize(currentPageIndex)
        if (pdfSize.width > 0 && pdfSize.height > 0) {
            val viewW = pdfView3rd.width
            if (viewW > 0) {
                val aspectRatio = pdfSize.height / pdfSize.width
                val viewH = (viewW * aspectRatio).toInt()

                val lp = pdfViewContainer.layoutParams as FrameLayout.LayoutParams
                lp.width = viewW
                lp.height = viewH
                lp.gravity = android.view.Gravity.CENTER
                pdfViewContainer.layoutParams = lp
            }
        }
    }

    private fun loadTextBlocksForPage(pageIndex: Int) {
        lifecycleScope.launch {
            val blocks = withContext(Dispatchers.IO) {
                val filePath = pdfFilePath ?: return@withContext emptyList()
                PdfEditorHelper.detectTextBlocks(filePath, pageIndex)
            }
            editManager.detectedTextBlocks.clear()
            editManager.detectedTextBlocks.addAll(blocks)
            pdfView3rd.invalidate()
        }
    }

    private fun setupTouchInteraction() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val pdfSize = pdfView3rd.getPageSize(currentPageIndex)
                val wView = pdfViewContainer.width.toFloat()
                val hView = pdfViewContainer.height.toFloat()
                if (wView <= 0 || hView <= 0) return false

                val wPdf = pdfSize.width
                val hPdf = pdfSize.height

                val pdfPoint = touchHelper.viewToPdf(e.x, e.y, wView, hView, wPdf, hPdf)
                val pdfX = pdfPoint.x
                val pdfY = pdfPoint.y

                if (editManager.isReplaceImageMode) {
                    val clickedImageRect = touchHelper.findImageRectAt(pdfX, pdfY, editManager.detectedImageRects)
                    if (clickedImageRect != null) {
                        val options = arrayOf("Thay thế hình ảnh này", "Chèn ảnh mới tại đây", "Hủy")
                        AlertDialog.Builder(this@PdfViewer3rdActivity)
                            .setTitle("Tùy chọn hình ảnh")
                            .setItems(options) { _, which ->
                                when (which) {
                                    0 -> {
                                        editManager.targetReplaceRect = clickedImageRect
                                        selectReplaceImageLauncher.launch("image/*")
                                    }
                                    1 -> {
                                        editManager.imageInsertX = pdfX
                                        editManager.imageInsertY = pdfY
                                        selectImageLauncher.launch("image/*")
                                    }
                                }
                            }
                            .show()
                    }
                } else if (editManager.isEditTextMode) {
                    val block = touchHelper.findDetectedTextBlockAt(pdfX, pdfY, editManager.detectedTextBlocks)
                    if (block != null) {
                        showInlineEditText(block, pdfX, pdfY, wView, hView, wPdf, hPdf)
                    } else {
                        closeAndSaveInlineEditText()
                    }
                } else if (editManager.isSignatureMode) {
                    showSignatureDialogOrList(pdfX, pdfY)
                }
                return true
            }
        })

        // pdfViewContainer đóng vai trò vừa là bộ thu nhận kéo thả vừa khóa cử chỉ cuộn trang của PDFView khi đang vẽ
        pdfViewContainer.setOnTouchListener { _, event ->
            val isEditing = editManager.isHighlightMode || 
                            editManager.isLineEffectMode || 
                            editManager.isEditTextMode || 
                            editManager.isReplaceImageMode || 
                            editManager.isSignatureMode || 
                            editManager.pendingEdits.isNotEmpty() || 
                            collectOverlayEdits().isNotEmpty()

            if (event.action == MotionEvent.ACTION_DOWN) {
                activeInlineEditText?.let { et ->
                    val location = IntArray(2)
                    et.getLocationOnScreen(location)
                    val x = event.rawX
                    val y = event.rawY
                    if (x < location[0] || x > location[0] + et.width || y < location[1] || y > location[1] + et.height) {
                        closeAndSaveInlineEditText()
                    }
                }
            }

            if (!isEditing) {
                // Khi không chỉnh sửa, cho phép sự kiện chạm đi xuyên xuống PDFView để cuộn dọc
                return@setOnTouchListener false
            }

            val pdfSize = pdfView3rd.getPageSize(currentPageIndex)
            val wView = pdfViewContainer.width.toFloat()
            val hView = pdfViewContainer.height.toFloat()

            if (wView > 0 && hView > 0) {
                val wPdf = pdfSize.width
                val hPdf = pdfSize.height

                val pdfPoint = touchHelper.viewToPdf(event.x, event.y, wView, hView, wPdf, hPdf)
                val pdfX = pdfPoint.x
                val pdfY = pdfPoint.y

                if (editManager.isHighlightMode) {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            editManager.startHighlight(pdfX, pdfY)
                            return@setOnTouchListener true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            editManager.updateHighlight(pdfX)
                            pdfView3rd.invalidate()
                            return@setOnTouchListener true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            editManager.endHighlight()
                            pdfView3rd.invalidate()
                            return@setOnTouchListener true
                        }
                    }
                } else if (editManager.isLineEffectMode) {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            editManager.startSelection(pdfX, pdfY)
                            return@setOnTouchListener true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            editManager.updateSelection(pdfX, pdfY)
                            pdfView3rd.invalidate()
                            return@setOnTouchListener true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            val count = editManager.endSelectionAndApply()
                            if (count > 0) {
                                Toast.makeText(this, "Đã kẻ chữ $count dòng văn bản", Toast.LENGTH_SHORT).show()
                            }
                            pdfView3rd.invalidate()
                            return@setOnTouchListener true
                        }
                    }
                } else {
                    // Chế độ xem & Tap chạm nhẹ
                    gestureDetector.onTouchEvent(event)
                }
            }
            true // Consumes all touches inside the overlay container to avoid zooming/panning pdfView3rd while editing
        }
    }

    private fun addFloatingOverlayView(bitmap: Bitmap, pdfX: Float, pdfY: Float, pdfWidth: Float, pdfHeight: Float, rotationAngle: Float = 0f, isSignature: Boolean = true) {
        val pdfSize = pdfView3rd.getPageSize(currentPageIndex)
        val wView = pdfViewContainer.width.toFloat()
        val hView = pdfViewContainer.height.toFloat()
        val wPdf = pdfSize.width
        val hPdf = pdfSize.height

        if (wView <= 0 || hView <= 0) return

        // 1. Quy đổi kích thước PDF sang pixels
        val viewWidth = pdfWidth * (wView / wPdf)
        val viewHeight = pdfHeight * (hView / hPdf)

        // 2. Quy đổi toạ độ PDF (trái-dưới) sang toạ độ View (trái-trên)
        val viewPoint = touchHelper.pdfToView(pdfX, pdfY, wView, hView, wPdf, hPdf)
        val viewX_left = viewPoint.x
        val viewY_bottom = viewPoint.y
        val viewY_top = viewY_bottom - viewHeight

        // 3. Tính toán kích thước View bao gồm padding vẽ handle
        val padding = 40f
        val rotateLineLength = 30f
        
        val totalWidth = viewWidth + 2 * padding
        val totalHeight = viewHeight + 2 * padding + rotateLineLength

        val lp = FrameLayout.LayoutParams(totalWidth.toInt(), totalHeight.toInt()).apply {
            leftMargin = (viewX_left - padding).toInt()
            topMargin = (viewY_top - padding - rotateLineLength).toInt()
        }

        val floatingView = FloatingEditView(this, bitmap, isSignature).apply {
            layoutParams = lp
            rotation = rotationAngle
        }

        floatingView.onDeleteListener = {
            pdfViewContainer.removeView(floatingView)
        }

        pdfViewContainer.addView(floatingView)
    }

    private fun collectOverlayEdits(): List<PdfEdit> {
        val list = mutableListOf<PdfEdit>()
        val pdfSize = pdfView3rd.getPageSize(currentPageIndex) ?: return list
        
        val wView = pdfViewContainer.width.toFloat()
        val hView = pdfViewContainer.height.toFloat()
        val wPdf = pdfSize.width
        val hPdf = pdfSize.height

        if (wView <= 0 || hView <= 0) return list

        val padding = 40f
        val rotateLineLength = 30f

        for (i in 0 until pdfViewContainer.childCount) {
            val child = pdfViewContainer.getChildAt(i)
            if (child is FloatingEditView) {
                val viewX_left = child.x + padding
                val viewY_top = child.y + padding + rotateLineLength
                
                val viewWidth = child.width - 2 * padding
                val viewHeight = child.height - 2 * padding - rotateLineLength

                // Quy đổi ngược sang toạ độ PDF
                val pdfWidth = viewWidth * (wPdf / wView)
                val pdfHeight = viewHeight * (hPdf / hView)
                
                val pdfX = viewX_left * (wPdf / wView)
                val viewY_bottom = viewY_top + viewHeight
                val pdfY = hPdf - viewY_bottom * (hPdf / hView)

                val rotationVal = child.rotation

                if (child.isSignature) {
                    list.add(PdfEdit.SignatureEdit(child.bitmap, pdfX, pdfY, pdfWidth, pdfHeight, rotationVal))
                } else {
                    list.add(PdfEdit.ImageEdit(child.bitmap, pdfX, pdfY, pdfWidth, pdfHeight, rotationVal))
                }
            }
        }
        return list
    }

    private fun clearFloatingOverlayViews() {
        for (i in pdfViewContainer.childCount - 1 downTo 0) {
            val child = pdfViewContainer.getChildAt(i)
            if (child is FloatingEditView) {
                pdfViewContainer.removeViewAt(i)
            }
        }
    }

    private fun saveEditsToFile() {
        val overlayEdits = collectOverlayEdits()
        val allEdits = mutableListOf<PdfEdit>().apply {
            addAll(editManager.pendingEdits)
            addAll(overlayEdits)
        }

        if (allEdits.isEmpty()) {
            Toast.makeText(this, "Không có thay đổi nào để lưu!", Toast.LENGTH_SHORT).show()
            return
        }

        val originalFile = File(pdfFilePath!!)
        if (!originalFile.exists()) {
            Toast.makeText(this, "Tệp PDF gốc không tồn tại!", Toast.LENGTH_SHORT).show()
            return
        }

        btnSaveEdits.isEnabled = false
        btnSaveEdits.text = "Đang lưu..."

        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                PdfEditorHelper.saveEdits(
                    this@PdfViewer3rdActivity,
                    pdfFilePath!!,
                    pdfFileUri,
                    currentPageIndex,
                    allEdits
                )
            }

            btnSaveEdits.isEnabled = true
            btnSaveEdits.text = "Lưu"

            if (success) {
                editManager.clearEdits()
                clearFloatingOverlayViews()
                // Reload lại tài liệu
                setupPdfView3rd()
                Toast.makeText(this@PdfViewer3rdActivity, "Đã lưu chỉnh sửa vào PDF thành công!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@PdfViewer3rdActivity, "Lỗi khi lưu PDF!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showSignatureDialog(pdfX: Float, pdfY: Float) {
        val dialogView = layoutInflater.inflate(com.vandatgsts.pdfeditor.R.layout.dialog_signature, null)
        val dialogSignatureView = dialogView.findViewById<SignatureView>(com.vandatgsts.pdfeditor.R.id.dialogSignatureView)
        val btnDialogClear = dialogView.findViewById<Button>(com.vandatgsts.pdfeditor.R.id.btnDialogClear)
        val btnDialogCancel = dialogView.findViewById<Button>(com.vandatgsts.pdfeditor.R.id.btnDialogCancel)
        val btnDialogConfirm = dialogView.findViewById<Button>(com.vandatgsts.pdfeditor.R.id.btnDialogConfirm)
        val cbSaveSignature = dialogView.findViewById<CheckBox>(com.vandatgsts.pdfeditor.R.id.cbSaveSignature)

        val viewColorBlue = dialogView.findViewById<View>(com.vandatgsts.pdfeditor.R.id.viewColorBlue)
        val viewColorBlack = dialogView.findViewById<View>(com.vandatgsts.pdfeditor.R.id.viewColorBlack)
        val viewColorRed = dialogView.findViewById<View>(com.vandatgsts.pdfeditor.R.id.viewColorRed)

        var selectedColor = android.graphics.Color.BLUE

        fun updateSelectedColor() {
            setCircleBackground(viewColorBlue, android.graphics.Color.BLUE, selectedColor == android.graphics.Color.BLUE)
            setCircleBackground(viewColorBlack, android.graphics.Color.BLACK, selectedColor == android.graphics.Color.BLACK)
            setCircleBackground(viewColorRed, android.graphics.Color.RED, selectedColor == android.graphics.Color.RED)
            dialogSignatureView.setSignatureColor(selectedColor)
        }

        updateSelectedColor()

        viewColorBlue.setOnClickListener {
            selectedColor = android.graphics.Color.BLUE
            updateSelectedColor()
        }
        viewColorBlack.setOnClickListener {
            selectedColor = android.graphics.Color.BLACK
            updateSelectedColor()
        }
        viewColorRed.setOnClickListener {
            selectedColor = android.graphics.Color.RED
            updateSelectedColor()
        }

        val alertDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnDialogClear.setOnClickListener {
            dialogSignatureView.clear()
        }

        btnDialogCancel.setOnClickListener {
            alertDialog.dismiss()
        }

        btnDialogConfirm.setOnClickListener {
            if (!dialogSignatureView.isSignatureEmpty()) {
                val bitmap = dialogSignatureView.getSignatureBitmap()
                if (bitmap != null) {
                    if (cbSaveSignature.isChecked) {
                        saveSignatureToStorage(bitmap)
                    }
                    addFloatingOverlayView(bitmap, pdfX, pdfY, 140f, 70f, 0f, isSignature = true)
                    pdfView3rd.invalidate()
                    alertDialog.dismiss()
                }
            } else {
                Toast.makeText(this, "Vui lòng vẽ chữ ký trước!", Toast.LENGTH_SHORT).show()
            }
        }

        alertDialog.show()
    }

    private fun setCircleBackground(view: View, color: Int, selected: Boolean) {
        val drawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
            if (selected) {
                setStroke((3 * resources.displayMetrics.density).toInt(), android.graphics.Color.parseColor("#10B981"))
            } else {
                setStroke((1 * resources.displayMetrics.density).toInt(), android.graphics.Color.parseColor("#D1D5DB"))
            }
        }
        view.background = drawable
    }

    private fun saveSignatureToStorage(bitmap: Bitmap) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val dir = File(filesDir, "signatures")
                    if (!dir.exists()) dir.mkdirs()
                    val filename = "sig_${System.currentTimeMillis()}.png"
                    val file = File(dir, filename)
                    val out = FileOutputStream(file)
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.flush()
                    out.close()
                }
                Toast.makeText(this@PdfViewer3rdActivity, "Đã lưu chữ ký cục bộ!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@PdfViewer3rdActivity, "Lỗi khi lưu chữ ký: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showSignatureDialogOrList(pdfX: Float, pdfY: Float) {
        val dir = File(filesDir, "signatures")
        if (!dir.exists()) dir.mkdirs()
        val files = dir.listFiles { file -> file.extension.lowercase() == "png" } ?: emptyArray()

        if (files.isEmpty()) {
            showSignatureDialog(pdfX, pdfY)
            return
        }

        val paddingVal = (16 * resources.displayMetrics.density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(paddingVal, paddingVal, paddingVal, paddingVal)
        }

        val titleView = TextView(this).apply {
            text = "Chọn chữ ký đã lưu"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor("#1F2937".toColorInt())
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
        }
        container.addView(titleView)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (240 * resources.displayMetrics.density).toInt()
            )
        }

        val listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val alertDialogBuilder = AlertDialog.Builder(this)
        val dialog = alertDialogBuilder.setView(container).create()

        for (file in files) {
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#F3F4F6"))
                    cornerRadius = 8f * resources.displayMetrics.density
                }
                val margin = (4 * resources.displayMetrics.density).toInt()
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, margin, 0, margin)
                }
                layoutParams = lp
            }

            val imgView = ImageView(this).apply {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                setImageBitmap(bmp)
                layoutParams = LinearLayout.LayoutParams(
                    (120 * resources.displayMetrics.density).toInt(),
                    (60 * resources.displayMetrics.density).toInt()
                ).apply {
                    weight = 1f
                }
                adjustViewBounds = true
                setPadding((8 * resources.displayMetrics.density).toInt(), 0, 0, 0)
                setOnClickListener {
                    if (bmp != null) {
                        addFloatingOverlayView(bmp, pdfX, pdfY, 140f, 70f, 0f, isSignature = true)
                        pdfView3rd.invalidate()
                        Toast.makeText(this@PdfViewer3rdActivity, "Đã chèn chữ ký đã lưu!", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                }
            }

            val delButton = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = "Xóa"
                setTextColor(android.graphics.Color.parseColor("#EF4444"))
                textSize = 13f
                setOnClickListener {
                    file.delete()
                    Toast.makeText(this@PdfViewer3rdActivity, "Đã xóa chữ ký khỏi danh sách!", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    showSignatureDialogOrList(pdfX, pdfY)
                }
            }

            itemLayout.addView(imgView)
            itemLayout.addView(delButton)
            listLayout.addView(itemLayout)
        }

        scrollView.addView(listLayout)
        container.addView(scrollView)

        val btnDrawNew = Button(this).apply {
            text = "Vẽ chữ ký mới"
            backgroundTintList = android.content.res.ColorStateList.valueOf("#059669".toColorInt())
            setTextColor(android.graphics.Color.WHITE)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
            }
            layoutParams = lp
            setOnClickListener {
                dialog.dismiss()
                showSignatureDialog(pdfX, pdfY)
            }
        }
        container.addView(btnDrawNew)

        dialog.show()
    }

    private fun showWhiteoutEditDialog(pdfX: Float, pdfY: Float) {
        val dialogView = layoutInflater.inflate(com.vandatgsts.pdfeditor.R.layout.dialog_whiteout_edit, null)
        val etDialogText = dialogView.findViewById<EditText>(com.vandatgsts.pdfeditor.R.id.etNewText)
        val etDialogWidth = dialogView.findViewById<EditText>(com.vandatgsts.pdfeditor.R.id.etWhiteoutWidth)
        val btnTextCancel = dialogView.findViewById<Button>(com.vandatgsts.pdfeditor.R.id.btnWhiteoutCancel)
        val btnTextConfirm = dialogView.findViewById<Button>(com.vandatgsts.pdfeditor.R.id.btnWhiteoutConfirm)

        val alertDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnTextCancel.setOnClickListener {
            alertDialog.dismiss()
        }

        btnTextConfirm.setOnClickListener {
            val text = etDialogText.text.toString().trim()
            val widthStr = etDialogWidth.text.toString().trim()
            val width = widthStr.toFloatOrNull() ?: 150f

            if (text.isNotEmpty()) {
                editManager.addWhiteoutEdit(text, pdfX, pdfY, width, 14f, false, android.graphics.Color.BLACK)
                pdfView3rd.invalidate()
                alertDialog.dismiss()
            } else {
                Toast.makeText(this, "Vui lòng nhập chữ thay thế!", Toast.LENGTH_SHORT).show()
            }
        }

        alertDialog.show()
    }

    private fun showInlineEditText(
        block: TextBlock,
        pdfX: Float,
        pdfY: Float,
        wView: Float,
        hView: Float,
        wPdf: Float,
        hPdf: Float
    ) {
        closeAndSaveInlineEditText()
        editManager.activeInlineBlock = block

        val viewPoint = touchHelper.pdfToView(block.x, block.y, wView, hView, wPdf, hPdf)
        val viewWidth = block.width * (wView / wPdf)
        val viewHeight = block.height * (hView / hPdf)

        val etInline = EditText(this).apply {
            setText(block.text)
            setTextColor(block.textColor)
            setBackgroundColor("#E0F2FE".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, block.fontSize * (hView / hPdf))
            if (block.isBold) {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            setPadding(0, 0, 0, 0)
            gravity = android.view.Gravity.LEFT or android.view.Gravity.CENTER_VERTICAL
            setSingleLine(true)

            imeOptions = EditorInfo.IME_ACTION_DONE

            val params = FrameLayout.LayoutParams(
                viewWidth.toInt() + 40,
                viewHeight.toInt() + 10
            ).apply {
                leftMargin = viewPoint.x.toInt()
                topMargin = (viewPoint.y - viewHeight).toInt()
            }
            layoutParams = params
        }

        activeInlineEditText = etInline

        etInline.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                closeAndSaveInlineEditText()
                true
            } else {
                false
            }
        }

        etInline.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                closeAndSaveInlineEditText()
            }
        }

        pdfViewContainer.addView(etInline)
        etInline.requestFocus()

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(etInline, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeAndSaveInlineEditText() {
        val et = activeInlineEditText ?: return
        val block = editManager.activeInlineBlock ?: return

        activeInlineEditText = null
        editManager.activeInlineBlock = null

        val newText = et.text.toString().trim()

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(et.windowToken, 0)

        if (newText.isNotEmpty() && newText != block.text) {
            editManager.addWhiteoutEdit(newText, block.x, block.y, block.width, block.fontSize, block.isBold, block.textColor)
            pdfView3rd.invalidate()
        }

        pdfViewContainer.removeView(et)
    }

    private fun deactivateAllModes() {
        editManager.deactivateAllModes()
        closeAndSaveInlineEditText()

        btnMenuTools.text = "Công cụ"
        btnMenuTools.backgroundTintList = android.content.res.ColorStateList.valueOf("#2563EB".toColorInt())
    }
}
