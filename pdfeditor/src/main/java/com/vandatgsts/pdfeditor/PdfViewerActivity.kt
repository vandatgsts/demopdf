package com.vandatgsts.pdfeditor

import android.graphics.Bitmap
import android.net.Uri
import com.vandatgsts.pdfeditor.R
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.util.TypedValue
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.view.inputmethod.EditorInfo
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.graphics.toColorInt
import android.graphics.BitmapFactory
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.io.File
import java.io.FileOutputStream

class PdfViewerActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var tvTitle: TextView
    private lateinit var btnSaveEdits: Button
    private lateinit var ivPdfPage: ImageView
    private lateinit var btnPrevPage: Button
    private lateinit var tvPageIndicator: TextView
    private lateinit var btnNextPage: Button

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var currentPage: PdfRenderer.Page? = null

    private var currentPageIndex = 0
    private var pageCount = 0
    private var pdfFilePath: String? = null
    private var pdfFileUri: String? = null

    interface ResizableEdit {
        val bitmap: Bitmap
        var x: Float
        var y: Float
        var width: Float
        var height: Float
        var rotation: Float
    }

    // Danh sách các chỉnh sửa tạm thời (chưa lưu vào file) để vẽ preview
    sealed class PdfEdit {
        abstract var x: Float
        abstract var y: Float

        data class TextEdit(val text: String, override var x: Float, override var y: Float) : PdfEdit()
        data class SignatureEdit(override val bitmap: Bitmap, override var x: Float, override var y: Float, override var width: Float = 140f, override var height: Float = 70f, override var rotation: Float = 0f) : PdfEdit(), ResizableEdit
        data class ImageEdit(override val bitmap: Bitmap, override var x: Float, override var y: Float, override var width: Float = 150f, override var height: Float = 150f, override var rotation: Float = 0f) : PdfEdit(), ResizableEdit
        // Whiteout & Overwrite: Che chữ cũ bằng hình chữ nhật trắng rộng width points, rồi đè chữ mới lên
        data class WhiteoutTextEdit(val text: String, override var x: Float, override var y: Float, val width: Float, val fontSize: Float = 14f, val isBold: Boolean = false, val textColor: Int = android.graphics.Color.BLACK) : PdfEdit()
        // Tô sáng (Highlight) chữ: vẽ hình chữ nhật vàng trong suốt
        data class HighlightEdit(override var x: Float, override var y: Float, var width: Float, var height: Float) : PdfEdit()
        // Kẻ chữ: Gạch dưới (Underline) hoặc gạch ngang (Strikethrough)
        data class LineEffectEdit(val effectType: Int, override var x: Float, override var y: Float, var width: Float, var height: Float) : PdfEdit()
    }

    // ---- Ba helper classes mới ----
    private val editManager = PdfEditManager()
    private val touchHelper = TouchInteractionHelper()
    private val renderer = PdfEditRenderer()

    // Cache cho trang PDF sạch hiện tại để tăng hiệu năng di chuyển mượt mà
    private var basePageBitmap: Bitmap? = null
    private var displayBitmap: Bitmap? = null
    private var displayCanvas: android.graphics.Canvas? = null

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
                        renderPageWithEdits()
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

                        // 1. Vẽ đè hình chữ nhật trắng (Whiteout) che ảnh cũ
                        editManager.addWhiteoutEdit(
                            text = "", // chuỗi trống để chỉ vẽ ô trắng che
                            x = minX,
                            y = minY,
                            width = w,
                            fontSize = h
                        )

                        // 2. Vẽ hình ảnh mới đè lên đúng tọa độ và kích thước đó
                        addFloatingOverlayView(
                            bitmap = bitmap,
                            pdfX = minX,
                            pdfY = minY,
                            pdfWidth = w,
                            pdfHeight = h,
                            rotationAngle = 0f,
                            isSignature = false
                        )
                        renderPageWithEdits()
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

    private lateinit var btnMenuTools: Button
    private lateinit var btnCancelEdits: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_pdf_viewer)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainViewer)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Khởi tạo views
        btnBack = findViewById(R.id.btnBack)
        tvTitle = findViewById(R.id.tvTitle)
        btnSaveEdits = findViewById(R.id.btnSaveEdits)
        ivPdfPage = findViewById(R.id.ivPdfPage)
        btnPrevPage = findViewById(R.id.btnPrevPage)
        tvPageIndicator = findViewById(R.id.tvPageIndicator)
        btnNextPage = findViewById(R.id.btnNextPage)
        btnMenuTools = findViewById(R.id.btnMenuTools)
        btnCancelEdits = findViewById(R.id.btnCancelEdits)

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
                    renderPageWithEdits()
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
                            val page = currentPage
                            if (page != null) {
                                val centerX = page.width.toFloat() / 2f
                                val centerY = page.height.toFloat() / 2f
                                showSignatureDialogOrList(centerX, centerY)
                            } else {
                                Toast.makeText(this, "Không thể xác định trang hiện tại!", Toast.LENGTH_SHORT).show()
                            }
                        }
                        5 -> {
                            editManager.isReplaceImageMode = true
                            btnMenuTools.text = "Thay ảnh"
                            btnMenuTools.backgroundTintList = android.content.res.ColorStateList.valueOf("#F97316".toColorInt())

                            // Quét hình ảnh trên trang hiện tại bất đồng bộ
                            lifecycleScope.launch {
                                editManager.detectedImageRects.clear()
                                val rects = withContext(Dispatchers.IO) {
                                    pdfFilePath?.let { path ->
                                        PdfEditorHelper.detectImageRects(path, currentPageIndex)
                                    } ?: emptyList()
                                }
                                editManager.detectedImageRects.addAll(rects)
                                if (editManager.detectedImageRects.isNotEmpty()) {
                                    Toast.makeText(this@PdfViewerActivity, "Đã quét và tìm thấy ${editManager.detectedImageRects.size} ảnh. Chạm vào ảnh khoanh màu cam để thay thế.", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(this@PdfViewerActivity, "Không tìm thấy hình ảnh nào trên trang này! Chạm vào trang để chèn ảnh mới.", Toast.LENGTH_LONG).show()
                                }
                                renderPageWithEdits()
                            }
                        }
                        6 -> {
                            Toast.makeText(this, "Đã chuyển sang chế độ Chỉ xem", Toast.LENGTH_SHORT).show()
                        }
                    }
                    renderPageWithEdits()
                }
                .setNegativeButton("Đóng", null)
                .show()
        }

        pdfFilePath = intent.getStringExtra("PDF_FILE_PATH")
        pdfFileUri = intent.getStringExtra("PDF_FILE_URI")
        val fileName = intent.getStringExtra("PDF_FILE_NAME") ?: "Tài liệu PDF"
        tvTitle.text = fileName

        if (pdfFilePath == null) {
            Toast.makeText(this, "Không tìm thấy đường dẫn tệp!", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupPdfRenderer()
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

        btnPrevPage.setOnClickListener {
            val overlayEdits = collectOverlayEdits()
            if (editManager.pendingEdits.isNotEmpty() || overlayEdits.isNotEmpty()) {
                Toast.makeText(this, "Vui lòng lưu chỉnh sửa trước khi chuyển trang!", Toast.LENGTH_SHORT).show()
            } else {
                showPage(currentPageIndex - 1)
            }
        }

        btnNextPage.setOnClickListener {
            val overlayEdits = collectOverlayEdits()
            if (editManager.pendingEdits.isNotEmpty() || overlayEdits.isNotEmpty()) {
                Toast.makeText(this, "Vui lòng lưu chỉnh sửa trước khi chuyển trang!", Toast.LENGTH_SHORT).show()
            } else {
                showPage(currentPageIndex + 1)
            }
        }
    }

    private fun setupPdfRenderer() {
        try {
            val file = File(pdfFilePath!!)
            if (!file.exists()) {
                Toast.makeText(this, "Tệp PDF không tồn tại!", Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            fileDescriptor?.let {
                pdfRenderer = PdfRenderer(it)
                pageCount = pdfRenderer?.pageCount ?: 0
                showPage(currentPageIndex)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Không thể tải tài liệu PDF: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // Lắng nghe điểm chạm nhẹ (Tap) để thêm phần tử, và kéo (Drag) để di chuyển phần tử
    private fun setupTouchInteraction() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val page = currentPage ?: return false
                val wView = ivPdfPage.width.toFloat()
                val hView = ivPdfPage.height.toFloat()
                if (wView <= 0 || hView <= 0) return false

                val wPdf = page.width.toFloat()
                val hPdf = page.height.toFloat()

                val pdfPoint = touchHelper.viewToPdf(e.x, e.y, wView, hView, wPdf, hPdf)
                val pdfX = pdfPoint.x
                val pdfY = pdfPoint.y

                if (editManager.isReplaceImageMode) {
                    val clickedImageRect = touchHelper.findImageRectAt(pdfX, pdfY, editManager.detectedImageRects)

                    if (clickedImageRect != null) {
                        val options = arrayOf("Thay thế hình ảnh này", "Chèn ảnh mới tại đây", "Hủy")
                        AlertDialog.Builder(this@PdfViewerActivity)
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

        ivPdfPage.setOnTouchListener { _, event ->
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

            val page = currentPage
            if (page == null) return@setOnTouchListener false

            val wView = ivPdfPage.width.toFloat()
            val hView = ivPdfPage.height.toFloat()

            if (wView > 0 && hView > 0) {
                val wPdf = page.width.toFloat()
                val hPdf = page.height.toFloat()

                val pdfPoint = touchHelper.viewToPdf(event.x, event.y, wView, hView, wPdf, hPdf)
                val pdfX = pdfPoint.x
                val pdfY = pdfPoint.y

                if (editManager.isHighlightMode) {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            editManager.startHighlight(pdfX, pdfY)
                            ivPdfPage.parent?.requestDisallowInterceptTouchEvent(true)
                            return@setOnTouchListener true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            editManager.updateHighlight(pdfX)
                            renderPageWithEdits()
                            return@setOnTouchListener true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            editManager.endHighlight()
                            renderPageWithEdits()
                            ivPdfPage.parent?.requestDisallowInterceptTouchEvent(false)
                            return@setOnTouchListener true
                        }
                    }
                } else if (editManager.isLineEffectMode) {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            editManager.startSelection(pdfX, pdfY)
                            ivPdfPage.parent?.requestDisallowInterceptTouchEvent(true)
                            return@setOnTouchListener true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            editManager.updateSelection(pdfX, pdfY)
                            renderPageWithEdits()
                            return@setOnTouchListener true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            val count = editManager.endSelectionAndApply()
                            if (count > 0) {
                                Toast.makeText(this, "Đã kẻ chữ $count dòng văn bản", Toast.LENGTH_SHORT).show()
                            }
                            ivPdfPage.parent?.requestDisallowInterceptTouchEvent(false)
                            renderPageWithEdits()
                            return@setOnTouchListener true
                        }
                    }
                } else {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            // 1. Kiểm tra chạm trúng handle
                            val handleResult = touchHelper.findHandleAt(pdfX, pdfY, editManager.pendingEdits)
                            when (handleResult.type) {
                                TouchInteractionHelper.HandleType.DELETE -> {
                                    editManager.deleteEditAt(handleResult.editIndex)
                                    renderPageWithEdits()
                                    return@setOnTouchListener true
                                }
                                TouchInteractionHelper.HandleType.ROTATE -> {
                                    editManager.startRotate(handleResult.edit!!, pdfX, pdfY)
                                    ivPdfPage.parent?.requestDisallowInterceptTouchEvent(true)
                                    return@setOnTouchListener true
                                }
                                TouchInteractionHelper.HandleType.RESIZE -> {
                                    editManager.startResize(handleResult.edit!!)
                                    ivPdfPage.parent?.requestDisallowInterceptTouchEvent(true)
                                    return@setOnTouchListener true
                                }
                                TouchInteractionHelper.HandleType.NONE -> { /* tiếp tục kiểm tra drag */ }
                            }

                            // 2. Kiểm tra chạm trúng phần tử để kéo thả
                            val edit = touchHelper.findEditAt(pdfX, pdfY, editManager.pendingEdits)
                            if (edit != null) {
                                editManager.startDrag(edit, pdfX, pdfY)
                                ivPdfPage.parent?.requestDisallowInterceptTouchEvent(true)
                                return@setOnTouchListener true
                            }
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (editManager.isRotating) {
                                editManager.updateRotate(pdfX, pdfY)
                                renderPageWithEdits()
                                return@setOnTouchListener true
                            }
                            if (editManager.isResizing) {
                                editManager.updateResize(pdfX, pdfY, touchHelper)
                                renderPageWithEdits()
                                return@setOnTouchListener true
                            }
                            if (editManager.activeDragEdit != null) {
                                editManager.updateDrag(pdfX, pdfY, wPdf, hPdf)
                                renderPageWithEdits()
                                return@setOnTouchListener true
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (editManager.isInteracting) {
                                editManager.releaseInteraction()
                                ivPdfPage.parent?.requestDisallowInterceptTouchEvent(false)
                                return@setOnTouchListener true
                            }
                        }
                    }
                }
            }

            // Nếu không chạm trúng phần tử để kéo và không ở chế độ Highlight/LineEffect, truyền sự kiện cho GestureDetector xử lý tap nhẹ
            if (!editManager.isHighlightMode && !editManager.isLineEffectMode) {
                gestureDetector.onTouchEvent(event)
            } else {
                false
            }
        }
    }


    // Hộp thoại tô sáng (Highlight) văn bản
    private fun showHighlightDialog(pdfX: Float, pdfY: Float) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("150") // mặc định là 150 points
            setSelection(3)
        }

        AlertDialog.Builder(this)
            .setTitle("Chiều rộng vùng tô sáng (Highlight)")
            .setView(input)
            .setPositiveButton("Xác nhận") { _, _ ->
                val widthStr = input.text.toString().trim()
                val width = widthStr.toFloatOrNull() ?: 150f
                editManager.addHighlightEdit(pdfX, pdfY, width)
                renderPageWithEdits()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // Hộp thoại chèn chữ mới
    private fun showInsertTextDialog(pdfX: Float, pdfY: Float) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_insert_text, null)
        val etDialogText = dialogView.findViewById<EditText>(R.id.etDialogText)
        val btnTextCancel = dialogView.findViewById<Button>(R.id.btnTextCancel)
        val btnTextConfirm = dialogView.findViewById<Button>(R.id.btnTextConfirm)

        val alertDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnTextCancel.setOnClickListener {
            alertDialog.dismiss()
        }

        btnTextConfirm.setOnClickListener {
            val text = etDialogText.text.toString().trim()
            if (text.isNotEmpty()) {
                editManager.addTextEdit(text, pdfX, pdfY)
                renderPageWithEdits()
                alertDialog.dismiss()
            } else {
                Toast.makeText(this, "Vui lòng nhập văn bản!", Toast.LENGTH_SHORT).show()
            }
        }

        alertDialog.show()
    }

    // Hộp thoại vẽ chữ ký tay
    private fun showSignatureDialog(pdfX: Float, pdfY: Float) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_signature, null)
        val dialogSignatureView = dialogView.findViewById<SignatureView>(R.id.dialogSignatureView)
        val btnDialogClear = dialogView.findViewById<Button>(R.id.btnDialogClear)
        val btnDialogCancel = dialogView.findViewById<Button>(R.id.btnDialogCancel)
        val btnDialogConfirm = dialogView.findViewById<Button>(R.id.btnDialogConfirm)
        val cbSaveSignature = dialogView.findViewById<CheckBox>(R.id.cbSaveSignature)

        val viewColorBlue = dialogView.findViewById<View>(R.id.viewColorBlue)
        val viewColorBlack = dialogView.findViewById<View>(R.id.viewColorBlack)
        val viewColorRed = dialogView.findViewById<View>(R.id.viewColorRed)

        var selectedColor = android.graphics.Color.BLUE // Màu mặc định là xanh lam

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
                    // Nếu người dùng chọn lưu chữ ký
                    if (cbSaveSignature.isChecked) {
                        saveSignatureToStorage(bitmap)
                    }

                    // Căn giữa chữ ký tại điểm chạm (pdfX, pdfY)
                    addFloatingOverlayView(bitmap, pdfX, pdfY, 140f, 70f, 0f, isSignature = true)
                    renderPageWithEdits()
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

    // Lưu chữ ký vào bộ nhớ trong dưới dạng tệp PNG
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
                Toast.makeText(this@PdfViewerActivity, "Đã lưu chữ ký cục bộ!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@PdfViewerActivity, "Lỗi khi lưu chữ ký: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Hiển thị danh sách chữ ký đã lưu trước, nếu rỗng thì cho phép vẽ mới
    private fun showSignatureDialogOrList(pdfX: Float, pdfY: Float) {
        val dir = File(filesDir, "signatures")
        if (!dir.exists()) dir.mkdirs()
        val files = dir.listFiles { file -> file.extension.lowercase() == "png" } ?: emptyArray()

        if (files.isEmpty()) {
            showSignatureDialog(pdfX, pdfY)
            return
        }

        // Tạo layout container cho Dialog bằng mã nguồn để đảm bảo tính tự hoạt độc lập
        val padding = (16 * resources.displayMetrics.density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val titleView = TextView(this).apply {
            text = "Chọn chữ ký đã lưu"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor("#1F2937".toColorInt())
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
        }
        container.addView(titleView)

        // Vùng cuộn chứa danh sách các chữ ký thumbnail
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (240 * resources.displayMetrics.density).toInt() // Chiều cao tối đa 240dp
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

            // Preview Thumbnail hình chữ ký
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
                        renderPageWithEdits()
                        Toast.makeText(this@PdfViewerActivity, "Đã chèn chữ ký đã lưu!", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                }
            }

            // Nút xóa chữ ký này
            val delButton = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = "Xóa"
                setTextColor(android.graphics.Color.parseColor("#EF4444"))
                textSize = 13f
                setOnClickListener {
                    file.delete()
                    Toast.makeText(this@PdfViewerActivity, "Đã xóa chữ ký khỏi danh sách!", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    showSignatureDialogOrList(pdfX, pdfY) // Reload lại danh sách
                }
            }

            itemLayout.addView(imgView)
            itemLayout.addView(delButton)
            listLayout.addView(itemLayout)
        }

        scrollView.addView(listLayout)
        container.addView(scrollView)

        // Nút vẽ chữ ký mới ở dưới cùng
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

    // Hộp thoại xóa chữ cũ & đè chữ mới lên
    private fun showWhiteoutEditDialog(pdfX: Float, pdfY: Float) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_whiteout_edit, null)
        val etNewText = dialogView.findViewById<EditText>(R.id.etNewText)
        val etWhiteoutWidth = dialogView.findViewById<EditText>(R.id.etWhiteoutWidth)
        val btnWhiteoutCancel = dialogView.findViewById<Button>(R.id.btnWhiteoutCancel)
        val btnWhiteoutConfirm = dialogView.findViewById<Button>(R.id.btnWhiteoutConfirm)

        val alertDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnWhiteoutCancel.setOnClickListener {
            alertDialog.dismiss()
        }

        btnWhiteoutConfirm.setOnClickListener {
            val text = etNewText.text.toString().trim()
            val widthStr = etWhiteoutWidth.text.toString().trim()
            val width = widthStr.toFloatOrNull() ?: 150f

            if (text.isNotEmpty()) {
                editManager.addWhiteoutEdit(text, pdfX, pdfY, width, 14f, false, android.graphics.Color.BLACK)
                renderPageWithEdits()
                alertDialog.dismiss()
            } else {
                Toast.makeText(this, "Vui lòng nhập chữ thay thế!", Toast.LENGTH_SHORT).show()
            }
        }

        alertDialog.show()
    }

    // Render lại trang PDF kết hợp vẽ preview các edits tạm thời (ủy quyền cho PdfEditRenderer)
    private fun renderPageWithEdits() {
        val page = currentPage ?: return
        val baseBmp = basePageBitmap ?: return
        val dispBmp = displayBitmap ?: return
        val canvas = displayCanvas ?: return

        try {
            val densityScale = resources.displayMetrics.density
            val scaleFactor = (densityScale * 1.5f).coerceAtLeast(1.0f)

            renderer.renderEdits(
                canvas = canvas,
                baseBitmap = baseBmp,
                scaleFactor = scaleFactor,
                pageHeight = page.height,
                edits = editManager.pendingEdits,
                detectedTextBlocks = editManager.detectedTextBlocks,
                isEditTextMode = editManager.isEditTextMode,
                isReplaceImageMode = editManager.isReplaceImageMode,
                detectedImageRects = editManager.detectedImageRects,
                isLineEffectMode = editManager.isLineEffectMode,
                selectionRect = editManager.selectionRect
            )

            // Ép buộc làm mới ImageView để hiển thị những thay đổi trên bitmap dùng lại
            val currentDrawable = ivPdfPage.drawable as? android.graphics.drawable.BitmapDrawable
            if (currentDrawable == null || currentDrawable.bitmap != dispBmp) {
                ivPdfPage.setImageBitmap(dispBmp)
            } else {
                ivPdfPage.invalidate()
            }

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Lỗi khi cập nhật bản xem trước: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPage(index: Int) {
        val pdfRend = pdfRenderer ?: return
        if (index < 0 || index >= pageCount) return

        try {
            // Đóng và dọn dẹp inline edit đang mở trước khi chuyển trang
            closeAndSaveInlineEditText()
            clearFloatingOverlayViews()

            currentPage?.close()

            currentPageIndex = index
            val page = pdfRend.openPage(index)
            currentPage = page

            // Render trang sạch một lần duy nhất để lưu cache tối ưu hiệu năng
            val densityScale = resources.displayMetrics.density
            val scaleFactor = (densityScale * 1.5f).coerceAtLeast(1.0f)
            val w = (page.width * scaleFactor).toInt()
            val h = (page.height * scaleFactor).toInt()
            // Tạo các bitmap mới trước
            val newBaseBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            page.render(newBaseBmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            val newDispBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val newCanvas = android.graphics.Canvas(newDispBmp)

            // Lưu trữ tạm thời các bitmap cũ để recycle sau
            val oldBaseBmp = basePageBitmap
            val oldDispBmp = displayBitmap

            // Gán các biến thành viên mới
            basePageBitmap = newBaseBmp
            displayBitmap = newDispBmp
            displayCanvas = newCanvas

            // Vẽ lại trang kết hợp hiển thị các khung text nét đứt màu xanh mờ
            renderPageWithEdits()

            // Sau khi renderPageWithEdits đã set newDispBmp vào ivPdfPage,
            // ta có thể an toàn giải phóng các bitmap cũ mà không sợ crash "trying to use a recycled bitmap"
            oldBaseBmp?.recycle()
            oldDispBmp?.recycle()

            // Detect text blocks trên trang hiện tại bất đồng bộ
            lifecycleScope.launch {
                val blocks = withContext(Dispatchers.IO) {
                    val filePath = pdfFilePath ?: return@withContext emptyList()
                    PdfEditorHelper.detectTextBlocks(filePath, index)
                }
                editManager.detectedTextBlocks.clear()
                editManager.detectedTextBlocks.addAll(blocks)
                renderPageWithEdits()
            }

            tvPageIndicator.text = "Trang ${index + 1} / $pageCount"
            btnPrevPage.isEnabled = index > 0
            btnNextPage.isEnabled = index < pageCount - 1

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Lỗi hiển thị trang: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Áp dụng các thay đổi thực sự vào tệp PDF (bất đồng bộ)
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

        // Hiển thị trạng thái đang lưu
        btnSaveEdits.isEnabled = false
        btnSaveEdits.text = "Đang lưu..."

        try {
            // Đóng tất cả renderer để mở khóa file hệ thống hoàn toàn
            currentPage?.close()
            currentPage = null
            pdfRenderer?.close()
            pdfRenderer = null
            fileDescriptor?.close()
            fileDescriptor = null

            lifecycleScope.launch {
                val success = withContext(Dispatchers.IO) {
                    PdfEditorHelper.saveEdits(
                        this@PdfViewerActivity,
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
                    setupPdfRenderer()
                    Toast.makeText(this@PdfViewerActivity, "Đã lưu chỉnh sửa vào PDF thành công!", Toast.LENGTH_SHORT).show()
                } else {
                    setupPdfRenderer()
                    Toast.makeText(this@PdfViewerActivity, "Lỗi khi lưu PDF!", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            btnSaveEdits.isEnabled = true
            btnSaveEdits.text = "Lưu"
            Toast.makeText(this, "Lỗi khi lưu PDF: ${e.message}", Toast.LENGTH_LONG).show()
            setupPdfRenderer()
        }
    }

    override fun onDestroy() {
        try {
            ivPdfPage.setImageBitmap(null)
            currentPage?.close()
            pdfRenderer?.close()
            fileDescriptor?.close()
            basePageBitmap?.recycle()
            basePageBitmap = null
            displayBitmap?.recycle()
            displayBitmap = null
            displayCanvas = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }

    // Hiển thị EditText trực tiếp đè lên trang PDF tại vị trí text block để sửa chữ inline
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

        // Chuyển đổi tọa độ PDF sang tọa độ Pixel thực tế của View
        val viewPoint = touchHelper.pdfToView(block.x, block.y, wView, hView, wPdf, hPdf)
        val viewWidth = block.width * (wView / wPdf)
        val viewHeight = block.height * (hView / hPdf)

        val container = findViewById<FrameLayout>(R.id.pdfViewContainer) ?: return

        val etInline = EditText(this).apply {
            setText(block.text)
            setTextColor(block.textColor)
            setBackgroundColor("#E0F2FE".toColorInt()) // Nền xanh nhạt
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

        // Lắng nghe sự kiện gõ nút Done trên bàn phím ảo
        etInline.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                closeAndSaveInlineEditText()
                true
            } else {
                false
            }
        }

        // Lắng nghe sự kiện mất tiêu điểm
        etInline.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                closeAndSaveInlineEditText()
            }
        }

        container.addView(etInline)
        etInline.requestFocus()

        // Tự động mở bàn phím ảo
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(etInline, InputMethodManager.SHOW_IMPLICIT)
    }

    // Đóng và lưu nội dung đã gõ trực tiếp
    private fun closeAndSaveInlineEditText() {
        val et = activeInlineEditText ?: return
        val block = editManager.activeInlineBlock ?: return

        // Giải phóng biến trạng thái ngay lập tức để tránh các cuộc gọi đệ quy đè lên nhau (Focus Loss)
        activeInlineEditText = null
        editManager.activeInlineBlock = null

        val newText = et.text.toString().trim()
        val container = findViewById<FrameLayout>(R.id.pdfViewContainer)

        // Ẩn bàn phím ảo
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(et.windowToken, 0)

        // Ghi nhận thay đổi nếu nội dung thay đổi và không rỗng
        if (newText.isNotEmpty() && newText != block.text) {
            editManager.addWhiteoutEdit(newText, block.x, block.y, block.width, block.fontSize, block.isBold, block.textColor)
            renderPageWithEdits()
        }

        container?.removeView(et)
    }

    private fun addFloatingOverlayView(bitmap: Bitmap, pdfX: Float, pdfY: Float, pdfWidth: Float, pdfHeight: Float, rotationAngle: Float = 0f, isSignature: Boolean = true) {
        val container = findViewById<FrameLayout>(R.id.pdfViewContainer) ?: return
        val page = currentPage ?: return
        
        val wView = ivPdfPage.width.toFloat()
        val hView = ivPdfPage.height.toFloat()
        val wPdf = page.width.toFloat()
        val hPdf = page.height.toFloat()

        if (wView <= 0 || hView <= 0) return

        // 1. Quy đổi kích thước PDF sang pixel màn hình
        val viewWidth = pdfWidth * (wView / wPdf)
        val viewHeight = pdfHeight * (hView / hPdf)

        // 2. Quy đổi toạ độ PDF (trái-dưới) sang toạ độ View (trái-trên)
        val viewPoint = touchHelper.pdfToView(pdfX, pdfY, wView, hView, wPdf, hPdf)
        val viewX_left = viewPoint.x
        val viewY_bottom = viewPoint.y
        val viewY_top = viewY_bottom - viewHeight

        // 3. Tính toán kích thước của toàn bộ FloatingEditView (bao gồm cả lề vẽ handle)
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

        // Đăng ký callback xoá View
        floatingView.onDeleteListener = {
            container.removeView(floatingView)
        }

        container.addView(floatingView)
    }

    private fun collectOverlayEdits(): List<PdfEdit> {
        val list = mutableListOf<PdfEdit>()
        val container = findViewById<FrameLayout>(R.id.pdfViewContainer) ?: return list
        val page = currentPage ?: return list
        
        val wView = ivPdfPage.width.toFloat()
        val hView = ivPdfPage.height.toFloat()
        val wPdf = page.width.toFloat()
        val hPdf = page.height.toFloat()

        if (wView <= 0 || hView <= 0) return list

        val padding = 40f
        val rotateLineLength = 30f

        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is FloatingEditView) {
                // 1. Lấy toạ độ thực tế của View (bao gồm cả translationX, translationY từ thao tác kéo thả)
                val viewX_left = child.x + padding
                val viewY_top = child.y + padding + rotateLineLength
                
                // 2. Lấy kích thước thực tế của View
                val viewWidth = child.width - 2 * padding
                val viewHeight = child.height - 2 * padding - rotateLineLength

                // 3. Quy đổi ngược sang toạ độ PDF
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
        val container = findViewById<FrameLayout>(R.id.pdfViewContainer) ?: return
        for (i in container.childCount - 1 downTo 0) {
            val child = container.getChildAt(i)
            if (child is FloatingEditView) {
                container.removeViewAt(i)
            }
        }
    }

    private fun deactivateAllModes() {
        editManager.deactivateAllModes()
        closeAndSaveInlineEditText()

        btnMenuTools.text = "Công cụ"
        btnMenuTools.backgroundTintList = android.content.res.ColorStateList.valueOf("#2563EB".toColorInt())
    }
}
