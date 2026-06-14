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

import java.io.ByteArrayOutputStream
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
    private val pendingEdits = mutableListOf<PdfEdit>()
    private val detectedTextBlocks = mutableListOf<TextBlock>()

    // Biến quản lý trạng thái kéo thả phần tử
    private var activeDragEdit: PdfEdit? = null
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    // Biến quản lý trạng thái co giãn (resize) chữ ký hoặc ảnh chèn
    private var isResizing = false
    private var activeResizeEdit: ResizableEdit? = null

    // Biến quản lý trạng thái xoay (rotate) chữ ký hoặc ảnh chèn
    private var isRotating = false
    private var activeRotateEdit: ResizableEdit? = null
    private var initialTouchAngle = 0.0
    private var initialEditRotation = 0f

    // Cache cho trang PDF sạch hiện tại để tăng hiệu năng di chuyển mượt mà
    private var basePageBitmap: Bitmap? = null
    private var displayBitmap: Bitmap? = null
    private var displayCanvas: android.graphics.Canvas? = null

    private var imageInsertX = 0f
    private var imageInsertY = 0f

    private fun getLocalPoint(pdfX: Float, pdfY: Float, edit: ResizableEdit): android.graphics.PointF {
        val cx = edit.x + edit.width / 2f
        val cy = edit.y + edit.height / 2f
        val angleRad = Math.toRadians((-edit.rotation).toDouble())
        val cos = Math.cos(angleRad)
        val sin = Math.sin(angleRad)
        val dx = pdfX - cx
        val dy = pdfY - cy
        val rx = dx * cos - dy * sin + cx
        val ry = dx * sin + dy * cos + cy
        return android.graphics.PointF(rx.toFloat(), ry.toFloat())
    }

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
                        pendingEdits.add(
                            PdfEdit.ImageEdit(
                                bitmap,
                                imageInsertX - w / 2,
                                imageInsertY - h / 2,
                                w,
                                h
                            )
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
                    val rect = targetReplaceRect
                    if (bitmap != null && rect != null) {
                        val minX = minOf(rect.left, rect.right)
                        val minY = minOf(rect.top, rect.bottom)
                        val w = Math.abs(rect.width())
                        val h = Math.abs(rect.height())

                        // 1. Vẽ đè hình chữ nhật trắng (Whiteout) che ảnh cũ
                        pendingEdits.add(
                            PdfEdit.WhiteoutTextEdit(
                                text = "", // chuỗi trống để chỉ vẽ ô trắng che
                                x = minX,
                                y = minY,
                                width = w,
                                fontSize = h
                            )
                        )

                        // 2. Vẽ hình ảnh mới đè lên đúng tọa độ và kích thước đó
                        pendingEdits.add(
                            PdfEdit.ImageEdit(
                                bitmap = bitmap,
                                x = minX,
                                y = minY,
                                width = w,
                                height = h
                            )
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
        targetReplaceRect = null
    }

    private var isHighlightMode = false
    private var highlightStartX = 0f
    private var highlightStartY = 0f
    private var activeHighlightEdit: PdfEdit.HighlightEdit? = null

    private var isLineEffectMode = false
    private var activeLineEffectType = 1 // 1: Underline, 2: Strikethrough
    private var selectionStartX = 0f
    private var selectionStartY = 0f
    private var selectionRect: RectF? = null

    private var isEditTextMode = false
    private var activeInlineEditText: EditText? = null
    private var activeInlineBlock: TextBlock? = null

    private var isReplaceImageMode = false
    private var isSignatureMode = false
    private val detectedImageRects = mutableListOf<RectF>()
    private var targetReplaceRect: RectF? = null
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
            if (pendingEdits.isEmpty()) {
                Toast.makeText(this, "Không có chỉnh sửa nào để hủy!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            AlertDialog.Builder(this)
                .setTitle("Xác nhận hủy")
                .setMessage("Bạn có chắc chắn muốn hủy tất cả các thay đổi chưa lưu?")
                .setPositiveButton("Hủy tất cả") { _, _ ->
                    pendingEdits.clear()
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
                            isEditTextMode = true
                            btnMenuTools.text = "Đang sửa"
                            btnMenuTools.backgroundTintList = android.content.res.ColorStateList.valueOf("#2563EB".toColorInt())
                            Toast.makeText(this, "Chế độ sửa chữ: Chọn khung nét đứt màu xanh để sửa trực tiếp", Toast.LENGTH_SHORT).show()
                        }
                        1 -> {
                            isHighlightMode = true
                            btnMenuTools.text = "Đang tô"
                            btnMenuTools.backgroundTintList = android.content.res.ColorStateList.valueOf("#F59E0B".toColorInt())
                            Toast.makeText(this, "Chế độ tô sáng: Vuốt ngón tay ngang để vẽ highlight", Toast.LENGTH_SHORT).show()
                        }
                        2 -> {
                            isLineEffectMode = true
                            activeLineEffectType = 1
                            btnMenuTools.text = "Gạch dưới"
                            btnMenuTools.backgroundTintList = android.content.res.ColorStateList.valueOf("#EF4444".toColorInt())
                            Toast.makeText(this, "Chế độ gạch dưới: Vuốt để quét vùng chọn text cần gạch dưới", Toast.LENGTH_SHORT).show()
                        }
                        3 -> {
                            isLineEffectMode = true
                            activeLineEffectType = 2
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
                            isReplaceImageMode = true
                            btnMenuTools.text = "Thay ảnh"
                            btnMenuTools.backgroundTintList = android.content.res.ColorStateList.valueOf("#F97316".toColorInt())

                            // Quét hình ảnh trên trang hiện tại ngay lập tức
                            detectedImageRects.clear()
                            pdfFilePath?.let { path ->
                                detectedImageRects.addAll(PdfEditorHelper.detectImageRects(path, currentPageIndex))
                            }
                            if (detectedImageRects.isNotEmpty()) {
                                Toast.makeText(this, "Đã quét và tìm thấy ${detectedImageRects.size} ảnh. Chạm vào ảnh khoanh màu cam để thay thế.", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this, "Không tìm thấy hình ảnh nào trên trang này! Chạm vào trang để chèn ảnh mới.", Toast.LENGTH_LONG).show()
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
            if (pendingEdits.isNotEmpty()) {
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
            if (pendingEdits.isNotEmpty()) {
                Toast.makeText(this, "Vui lòng lưu chỉnh sửa trước khi chuyển trang!", Toast.LENGTH_SHORT).show()
            } else {
                showPage(currentPageIndex - 1)
            }
        }

        btnNextPage.setOnClickListener {
            if (pendingEdits.isNotEmpty()) {
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
                val xTouch = e.x
                val yTouch = e.y

                val page = currentPage ?: return false
                val wView = ivPdfPage.width.toFloat()
                val hView = ivPdfPage.height.toFloat()
                if (wView <= 0 || hView <= 0) return false

                val wPdf = page.width.toFloat()
                val hPdf = page.height.toFloat()

                // Chuyển đổi điểm chạm sang tọa độ PDF
                val pdfX = xTouch * (wPdf / wView)
                val pdfY = hPdf - (yTouch * (hPdf / hView))

                if (isReplaceImageMode) {
                    var clickedImageRect: RectF? = null
                    for (rect in detectedImageRects) {
                        val left = minOf(rect.left, rect.right)
                        val right = maxOf(rect.left, rect.right)
                        val bottom = minOf(rect.top, rect.bottom)
                        val top = maxOf(rect.top, rect.bottom)
                        if (pdfX >= left && pdfX <= right && pdfY >= bottom && pdfY <= top) {
                            clickedImageRect = rect
                            break
                        }
                    }

                    if (clickedImageRect != null) {
                        val options = arrayOf("Thay thế hình ảnh này", "Chèn ảnh mới tại đây", "Hủy")
                        AlertDialog.Builder(this@PdfViewerActivity)
                            .setTitle("Tùy chọn hình ảnh")
                            .setItems(options) { _, which ->
                                when (which) {
                                    0 -> {
                                        targetReplaceRect = clickedImageRect
                                        selectReplaceImageLauncher.launch("image/*")
                                    }
                                    1 -> {
                                        imageInsertX = pdfX
                                        imageInsertY = pdfY
                                        selectImageLauncher.launch("image/*")
                                    }
                                }
                            }
                            .show()
                    }
                } else if (isEditTextMode) {
                    // Kiểm tra chạm trúng khối text
                    val block = findDetectedTextBlockAt(pdfX, pdfY)
                    if (block != null) {
                        // Sửa trực tiếp trên trang thay vì hiện Dialog!
                        showInlineEditText(block, pdfX, pdfY, wView, hView, wPdf, hPdf)
                    } else {
                        // Nếu gõ bên ngoài, đóng và lưu EditText đang mở
                        closeAndSaveInlineEditText()
                    }
                } else if (isSignatureMode) {
                    showSignatureDialogOrList(pdfX, pdfY)
                }
                return true
            }
        })

        ivPdfPage.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                // Nếu chạm vào trang, kiểm tra và đóng/lưu inline editText nếu có
                activeInlineEditText?.let { et ->
                    val location = IntArray(2)
                    et.getLocationOnScreen(location)
                    val x = event.rawX
                    val y = event.rawY
                    // Chỉ đóng nếu điểm chạm nằm ngoài vùng của EditText hiện tại
                    if (x < location[0] || x > location[0] + et.width || y < location[1] || y > location[1] + et.height) {
                        closeAndSaveInlineEditText()
                    }
                }
            }

            val page = currentPage
            if (page == null) return@setOnTouchListener false

            val xTouch = event.x
            val yTouch = event.y

            val wView = ivPdfPage.width.toFloat()
            val hView = ivPdfPage.height.toFloat()

            if (wView > 0 && hView > 0) {
                val wPdf = page.width.toFloat()
                val hPdf = page.height.toFloat()

                // Chuyển đổi điểm chạm Android -> PDF
                val pdfX = xTouch * (wPdf / wView)
                val pdfY = hPdf - (yTouch * (hPdf / hView))

                if (isHighlightMode) {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            highlightStartX = pdfX
                            highlightStartY = pdfY
                            // Tạo highlight mới với chiều cao mặc định 16f
                            val newHighlight = PdfEdit.HighlightEdit(pdfX, pdfY, 0f, 16f)
                            activeHighlightEdit = newHighlight
                            pendingEdits.add(newHighlight)
                            // Khóa cuộn để vẽ mượt mà
                            ivPdfPage.parent?.requestDisallowInterceptTouchEvent(true)
                            return@setOnTouchListener true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            activeHighlightEdit?.let { edit ->
                                val currentX = pdfX
                                // Luôn tính chiều rộng và tọa độ x dương để vẽ chuẩn xác
                                if (currentX >= highlightStartX) {
                                    edit.x = highlightStartX
                                    edit.width = currentX - highlightStartX
                                } else {
                                    edit.x = currentX
                                    edit.width = highlightStartX - currentX
                                }
                                renderPageWithEdits()
                            }
                            return@setOnTouchListener true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            activeHighlightEdit?.let { edit ->
                                // Nếu người dùng chỉ chạm nhẹ mà không vuốt, xóa highlight thừa
                                if (edit.width < 5f) {
                                    pendingEdits.remove(edit)
                                    renderPageWithEdits()
                                }
                            }
                            activeHighlightEdit = null
                            ivPdfPage.parent?.requestDisallowInterceptTouchEvent(false)
                            return@setOnTouchListener true
                        }
                    }
                } else if (isLineEffectMode) {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            selectionStartX = pdfX
                            selectionStartY = pdfY
                            selectionRect = RectF(pdfX, pdfY, pdfX, pdfY)
                            // Khóa cuộn màn hình
                            ivPdfPage.parent?.requestDisallowInterceptTouchEvent(true)
                            return@setOnTouchListener true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val currentX = pdfX
                            val currentY = pdfY
                            selectionRect = RectF(
                                minOf(selectionStartX, currentX),
                                minOf(selectionStartY, currentY),
                                maxOf(selectionStartX, currentX),
                                maxOf(selectionStartY, currentY)
                            )
                            renderPageWithEdits()
                            return@setOnTouchListener true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            selectionRect?.let { rect ->
                                if (rect.width() > 3f || rect.height() > 3f) {
                                    var count = 0
                                    for (block in detectedTextBlocks) {
                                        val blockLeft = block.x
                                        val blockRight = block.x + block.width
                                        val blockBottom = block.y - 4f
                                        val blockTop = block.y + block.height

                                        val isIntersect = !(blockRight < rect.left || blockLeft > rect.right ||
                                                            blockTop < rect.top || blockBottom > rect.bottom)
                                        if (isIntersect) {
                                            pendingEdits.add(
                                                PdfEdit.LineEffectEdit(
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
                                    if (count > 0) {
                                        Toast.makeText(this, "Đã kẻ chữ $count dòng văn bản", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            selectionRect = null
                            ivPdfPage.parent?.requestDisallowInterceptTouchEvent(false)
                            renderPageWithEdits()
                            return@setOnTouchListener true
                        }
                    }
                } else {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            // 1. Kiểm tra xem có chạm trúng handle của SignatureEdit hoặc ImageEdit nào không
                            var foundHandle = false
                            for (i in pendingEdits.indices.reversed()) {
                                val edit = pendingEdits[i]
                                if (edit is ResizableEdit) {
                                    val localPoint = getLocalPoint(pdfX, pdfY, edit)
                                    val localX = localPoint.x
                                    val localY = localPoint.y

                                    // a. Xóa handle: top-left (edit.x, edit.y + edit.height)
                                    val deleteX = edit.x
                                    val deleteY = edit.y + edit.height
                                    val distDelete = Math.hypot((localX - deleteX).toDouble(), (localY - deleteY).toDouble())
                                    if (distDelete < 20.0) { // Bán kính nhận diện 20 points
                                        pendingEdits.removeAt(i)
                                        renderPageWithEdits()
                                        foundHandle = true
                                        break
                                    }

                                    // b. Xoay handle: top-middle (edit.x + edit.width / 2, edit.y + edit.height + 15)
                                    val rotateX = edit.x + edit.width / 2f
                                    val rotateY = edit.y + edit.height + 15f
                                    val distRotate = Math.hypot((localX - rotateX).toDouble(), (localY - rotateY).toDouble())
                                    if (distRotate < 20.0) {
                                        isRotating = true
                                        activeRotateEdit = edit
                                        val cx = edit.x + edit.width / 2f
                                        val cy = edit.y + edit.height / 2f
                                        val dx = pdfX - cx
                                        val dy = pdfY - cy
                                        initialTouchAngle = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble()))
                                        initialEditRotation = edit.rotation
                                        foundHandle = true
                                        ivPdfPage.parent?.requestDisallowInterceptTouchEvent(true)
                                        break
                                    }

                                    // c. Co giãn handle: bottom-right (edit.x + edit.width, edit.y)
                                    val resizeX = edit.x + edit.width
                                    val resizeY = edit.y
                                    val distResize = Math.hypot((localX - resizeX).toDouble(), (localY - resizeY).toDouble())
                                    if (distResize < 20.0) {
                                        isResizing = true
                                        activeResizeEdit = edit
                                        foundHandle = true
                                        ivPdfPage.parent?.requestDisallowInterceptTouchEvent(true)
                                        break
                                    }
                                }
                            }

                            if (foundHandle) {
                                return@setOnTouchListener true
                            }

                            // 2. Nếu không chạm trúng handle, kiểm tra chạm trúng phần tử để kéo thả di chuyển
                            val edit = findEditAt(pdfX, pdfY)
                            if (edit != null) {
                                activeDragEdit = edit
                                dragOffsetX = pdfX - edit.x
                                dragOffsetY = pdfY - edit.y
                                // Khóa cuộn màn hình của ScrollView cha để tập trung kéo thả phần tử
                                ivPdfPage.parent?.requestDisallowInterceptTouchEvent(true)
                                return@setOnTouchListener true
                            }
                        }
                        MotionEvent.ACTION_MOVE -> {
                            // Xử lý xoay chữ ký
                            if (isRotating && activeRotateEdit != null) {
                                activeRotateEdit?.let { edit ->
                                    val cx = edit.x + edit.width / 2f
                                    val cy = edit.y + edit.height / 2f
                                    val dx = pdfX - cx
                                    val dy = pdfY - cy
                                    val currentAngle = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble()))
                                    val diff = (initialTouchAngle - currentAngle).toFloat()
                                    edit.rotation = (initialEditRotation + diff) % 360f
                                    renderPageWithEdits()
                                }
                                return@setOnTouchListener true
                            }

                            // Xử lý co giãn chữ ký
                            if (isResizing && activeResizeEdit != null) {
                                activeResizeEdit?.let { edit ->
                                    val localPoint = getLocalPoint(pdfX, pdfY, edit)
                                    val ratio = edit.bitmap.width.toFloat() / edit.bitmap.height.toFloat()
                                    val newWidth = (localPoint.x - edit.x).coerceAtLeast(30f) // Chiều rộng tối thiểu 30pt
                                    edit.width = newWidth
                                    edit.height = newWidth / ratio
                                    renderPageWithEdits()
                                }
                                return@setOnTouchListener true
                            }

                            // Xử lý kéo thả di chuyển
                            activeDragEdit?.let { edit ->
                                // Tính toán tọa độ kéo mới
                                var newX = pdfX - dragOffsetX
                                var newY = pdfY - dragOffsetY

                                // Giới hạn biên để không bị kéo ra ngoài trang PDF
                                newX = newX.coerceIn(0f, wPdf)
                                newY = newY.coerceIn(0f, hPdf)

                                edit.x = newX
                                edit.y = newY

                                // Cập nhật bản vẽ xem trước di chuyển theo ngón tay (real-time)
                                renderPageWithEdits()
                                return@setOnTouchListener true
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (isResizing || isRotating || activeDragEdit != null) {
                                isResizing = false
                                isRotating = false
                                activeResizeEdit = null
                                activeRotateEdit = null
                                activeDragEdit = null
                                // Mở khóa cuộn màn hình của ScrollView
                                ivPdfPage.parent?.requestDisallowInterceptTouchEvent(false)
                                return@setOnTouchListener true
                            }
                        }
                    }
                }
            }

            // Nếu không chạm trúng phần tử để kéo và không ở chế độ Highlight/LineEffect, truyền sự kiện cho GestureDetector xử lý tap nhẹ
            if (!isHighlightMode && !isLineEffectMode) {
                gestureDetector.onTouchEvent(event)
            } else {
                false
            }
        }
    }

    // Tìm xem điểm chạm của người dùng có trúng vào phần tử nào trong danh sách edits tạm hay không
    private fun findEditAt(pdfX: Float, pdfY: Float): PdfEdit? {
        // Duyệt từ cuối lên đầu để ưu tiên phần tử nằm đè lên trên (vừa tạo sau)
        for (i in pendingEdits.indices.reversed()) {
            when (val edit = pendingEdits[i]) {
                is PdfEdit.TextEdit -> {
                    val width = edit.text.length * 8f // ước lượng chiều rộng text
                    val height = 16f
                    if (pdfX >= edit.x && pdfX <= edit.x + width && pdfY >= edit.y - 12f && pdfY <= edit.y + 4f) {
                        return edit
                    }
                }
                is PdfEdit.WhiteoutTextEdit -> {
                    val width = edit.width
                    val height = 16f
                    if (pdfX >= edit.x && pdfX <= edit.x + width && pdfY >= edit.y - 12f && pdfY <= edit.y + 4f) {
                        return edit
                    }
                }
                is PdfEdit.SignatureEdit -> {
                    val localPoint = getLocalPoint(pdfX, pdfY, edit)
                    val localX = localPoint.x
                    val localY = localPoint.y
                    val padding = 20f
                    if (localX >= edit.x - padding && localX <= edit.x + edit.width + padding &&
                        localY >= edit.y - padding && localY <= edit.y + edit.height + padding) {
                        return edit
                    }
                }
                is PdfEdit.ImageEdit -> {
                    val localPoint = getLocalPoint(pdfX, pdfY, edit)
                    val localX = localPoint.x
                    val localY = localPoint.y
                    val padding = 20f
                    if (localX >= edit.x - padding && localX <= edit.x + edit.width + padding &&
                        localY >= edit.y - padding && localY <= edit.y + edit.height + padding) {
                        return edit
                    }
                }
                is PdfEdit.HighlightEdit -> {
                    val width = edit.width
                    val height = edit.height
                    if (pdfX >= edit.x && pdfX <= edit.x + width && pdfY >= edit.y - 12f && pdfY <= edit.y + 4f) {
                        return edit
                    }
                }
                is PdfEdit.LineEffectEdit -> {
                    val width = edit.width
                    val midY = if (edit.effectType == 1) edit.y - 2f else edit.y + edit.height / 2
                    if (pdfX >= edit.x && pdfX <= edit.x + width && pdfY >= midY - 6f && pdfY <= midY + 6f) {
                        return edit
                    }
                }
            }
        }
        return null
    }

    // Hiển thị Menu lựa chọn khi người dùng chạm vào PDF
    private fun showEditOptionsDialog(xTouch: Float, yTouch: Float) {
        val page = currentPage ?: return
        val wView = ivPdfPage.width.toFloat()
        val hView = ivPdfPage.height.toFloat()

        if (wView <= 0 || hView <= 0) return

        val wPdf = page.width.toFloat()
        val hPdf = page.height.toFloat()

        // Chuyển đổi tọa độ View của Android thành tọa độ PDF (points) thực tế
        val pdfX = xTouch * (wPdf / wView)
        val pdfY = hPdf - (yTouch * (hPdf / hView))

        val options = arrayOf("Thêm chữ mới tại đây", "Ký tên tại đây", "Xóa & Sửa chữ tại đây", "Tô sáng (Highlight) tại đây", "Chèn/Thay ảnh tại đây")
        AlertDialog.Builder(this)
            .setTitle("Tùy chọn chỉnh sửa")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showInsertTextDialog(pdfX, pdfY)
                    1 -> showSignatureDialog(pdfX, pdfY)
                    2 -> showWhiteoutEditDialog(pdfX, pdfY)
                    3 -> showHighlightDialog(pdfX, pdfY)
                    4 -> {
                        imageInsertX = pdfX
                        imageInsertY = pdfY
                        selectImageLauncher.launch("image/*")
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
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
                pendingEdits.add(PdfEdit.HighlightEdit(pdfX, pdfY, width, 16f))
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
                pendingEdits.add(PdfEdit.TextEdit(text, pdfX, pdfY))
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
                    val w = 140f
                    val h = 70f

                    // Nếu người dùng chọn lưu chữ ký
                    if (cbSaveSignature.isChecked) {
                        saveSignatureToStorage(bitmap)
                    }

                    // Căn giữa chữ ký tại điểm chạm (pdfX, pdfY)
                    pendingEdits.add(PdfEdit.SignatureEdit(bitmap, pdfX - w / 2, pdfY - h / 2, w, h))
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
        try {
            val dir = File(filesDir, "signatures")
            if (!dir.exists()) dir.mkdirs()
            val filename = "sig_${System.currentTimeMillis()}.png"
            val file = File(dir, filename)
            val out = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
            out.close()
            Toast.makeText(this, "Đã lưu chữ ký cục bộ!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Lỗi khi lưu chữ ký: ${e.message}", Toast.LENGTH_SHORT).show()
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
                        val w = 140f
                        val h = 70f
                        pendingEdits.add(PdfEdit.SignatureEdit(bmp, pdfX - w / 2, pdfY - h / 2, w, h))
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
                pendingEdits.add(PdfEdit.WhiteoutTextEdit(text, pdfX, pdfY, width, 14f, false, android.graphics.Color.BLACK))
                renderPageWithEdits()
                alertDialog.dismiss()
            } else {
                Toast.makeText(this, "Vui lòng nhập chữ thay thế!", Toast.LENGTH_SHORT).show()
            }
        }

        alertDialog.show()
    }

    // Render lại trang PDF kết hợp vẽ preview các edits tạm thời
    private fun renderPageWithEdits() {
        val page = currentPage ?: return
        val baseBmp = basePageBitmap ?: return
        val dispBmp = displayBitmap ?: return
        val canvas = displayCanvas ?: return

        try {
            val densityScale = resources.displayMetrics.density
            val scaleFactor = (densityScale * 1.5f).coerceAtLeast(1.0f)

            // Sao chép trang PDF sạch từ cache vào display bitmap (tránh việc cấp phát bộ nhớ mới gây lag GC)
            canvas.drawBitmap(baseBmp, 0f, 0f, null)

            // Cấu hình bút vẽ chữ mới (màu đỏ để dễ phân biệt)
            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.RED
                textSize = 14f * scaleFactor
                isAntiAlias = true
            }

            // Bút xóa (hình chữ nhật màu trắng che text cũ)
            val whitePaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                style = android.graphics.Paint.Style.FILL
            }

            // Bút vẽ chữ thay thế (màu đen cho giống text gốc)
            val blackTextPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = 14f * scaleFactor
                isAntiAlias = true
            }

            for (edit in pendingEdits) {
                when (edit) {
                    is PdfEdit.TextEdit -> {
                        val bmpX = edit.x * scaleFactor
                        val bmpY = (page.height - edit.y) * scaleFactor
                        canvas.drawText(edit.text, bmpX, bmpY, textPaint)
                    }
                    is PdfEdit.SignatureEdit -> {
                        drawResizableEdit(edit, canvas, scaleFactor, page.height)
                    }
                    is PdfEdit.ImageEdit -> {
                        drawResizableEdit(edit, canvas, scaleFactor, page.height)
                    }
                    is PdfEdit.WhiteoutTextEdit -> {
                        val bmpX = edit.x * scaleFactor
                        val bmpY = (page.height - edit.y) * scaleFactor

                        // Che đi vùng chữ cũ bằng ô màu trắng
                        val whiteWidth = edit.width * scaleFactor
                        val whiteHeight = edit.fontSize * 1.2f * scaleFactor

                        canvas.drawRect(
                            bmpX,
                            bmpY - whiteHeight + 2f * scaleFactor,
                            bmpX + whiteWidth,
                            bmpY + 4f * scaleFactor,
                            whitePaint
                        )

                        // Vẽ chữ thay thế đè lên ô trắng theo đúng màu sắc, cỡ chữ và kiểu chữ
                        val tempPaint = android.graphics.Paint(blackTextPaint).apply {
                            color = edit.textColor
                            textSize = edit.fontSize * scaleFactor
                            if (edit.isBold) {
                                isFakeBoldText = true
                            }
                        }
                        canvas.drawText(edit.text, bmpX, bmpY, tempPaint)
                    }
                    is PdfEdit.HighlightEdit -> {
                        val bmpX = edit.x * scaleFactor
                        val bmpY = (page.height - edit.y) * scaleFactor

                        val highlightWidth = edit.width * scaleFactor
                        val highlightHeight = edit.height * scaleFactor

                        val highlightPaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.argb(100, 255, 255, 0) // Màu vàng bán trong suốt
                            style = android.graphics.Paint.Style.FILL
                        }

                        canvas.drawRect(
                            bmpX,
                            bmpY - highlightHeight + 2f,
                            bmpX + highlightWidth,
                            bmpY + 4f,
                            highlightPaint
                        )
                    }
                    is PdfEdit.LineEffectEdit -> {
                        val bmpX = edit.x * scaleFactor
                        val bmpY = (page.height - edit.y) * scaleFactor
                        val bmpWidth = edit.width * scaleFactor
                        val bmpHeight = edit.height * scaleFactor

                        val linePaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.RED
                            strokeWidth = 1.5f * scaleFactor
                            style = android.graphics.Paint.Style.STROKE
                            isAntiAlias = true
                        }

                        if (edit.effectType == 1) {
                            // Underline: dưới baseline 2dp
                            val lineY = bmpY + 2f * scaleFactor
                            canvas.drawLine(bmpX, lineY, bmpX + bmpWidth, lineY, linePaint)
                        } else {
                            // Strikethrough: ở giữa chữ
                            val lineY = bmpY - bmpHeight / 2
                            canvas.drawLine(bmpX, lineY, bmpX + bmpWidth, lineY, linePaint)
                        }
                    }
                }
            }

            // Vẽ các khung văn bản detect được dưới dạng nét đứt mờ (chỉ vẽ khi bật chế độ Edit Mode)
            if (isEditTextMode) {
                val detectFramePaint = android.graphics.Paint().apply {
                    color = "#3B82F6".toColorInt() // màu xanh dương đẹp
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 1f * scaleFactor
                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f * scaleFactor, 4f * scaleFactor), 0f)
                }

                for (block in detectedTextBlocks) {
                    val bmpX = block.x * scaleFactor
                    val bmpY = (page.height - block.y) * scaleFactor
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

            // Vẽ khoanh vùng ảnh (khi bật chế độ thay ảnh)
            if (isReplaceImageMode) {
                val imagePaint = android.graphics.Paint().apply {
                    color = "#F97316".toColorInt() // màu cam
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 2f * scaleFactor
                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f * scaleFactor, 4f * scaleFactor), 0f)
                    isAntiAlias = true
                }
                val fillPaint = android.graphics.Paint().apply {
                    color = "#F97316".toColorInt()
                    alpha = 40 // trong suốt
                    style = android.graphics.Paint.Style.FILL
                }
                for (rect in detectedImageRects) {
                    val minX = minOf(rect.left, rect.right)
                    val maxX = maxOf(rect.left, rect.right)
                    val minY = minOf(rect.top, rect.bottom)
                    val maxY = maxOf(rect.top, rect.bottom)

                    val bmpX = minX * scaleFactor
                    val bmpY = (page.height - minY) * scaleFactor
                    val bmpRight = maxX * scaleFactor
                    val bmpTop = (page.height - maxY) * scaleFactor
                    val drawRect = RectF(bmpX, bmpTop, bmpRight, bmpY)
                    canvas.drawRect(drawRect, fillPaint)
                    canvas.drawRect(drawRect, imagePaint)
                }
            }

            // Vẽ khung quét nét đứt màu đỏ xem trước
            val currentSelectionRect = selectionRect
            if (isLineEffectMode && currentSelectionRect != null) {
                val selPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.RED
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 1.5f * scaleFactor
                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f * scaleFactor, 6f * scaleFactor), 0f)
                }
                val selFillPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(30, 239, 68, 68) // Đỏ nhạt mờ
                    style = android.graphics.Paint.Style.FILL
                }

                val left = currentSelectionRect.left * scaleFactor
                val right = currentSelectionRect.right * scaleFactor
                val bottom = (page.height - currentSelectionRect.bottom) * scaleFactor
                val top = (page.height - currentSelectionRect.top) * scaleFactor

                canvas.drawRect(left, top, right, bottom, selFillPaint)
                canvas.drawRect(left, top, right, bottom, selPaint)
            }

            // Ép buộc làm mới ImageView để hiển thị những thay đổi trên bitmap dùng lại
            if (ivPdfPage.drawable == null) {
                ivPdfPage.setImageBitmap(dispBmp)
            } else {
                ivPdfPage.invalidate()
            }

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Lỗi khi cập nhật bản xem trước: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun drawResizableEdit(
        edit: ResizableEdit,
        canvas: android.graphics.Canvas,
        scaleFactor: Float,
        pageHeight: Int
    ) {
        val bmpX = edit.x * scaleFactor
        val bmpY = (pageHeight - edit.y) * scaleFactor

        val sigWidth = edit.width * scaleFactor
        val sigHeight = edit.height * scaleFactor

        val destRect = RectF(
            bmpX,
            bmpY - sigHeight,
            bmpX + sigWidth,
            bmpY
        )

        val cx = bmpX + sigWidth / 2f
        val cy = bmpY - sigHeight / 2f

        canvas.save()
        canvas.rotate(edit.rotation, cx, cy)

        canvas.drawBitmap(edit.bitmap, null, destRect, null)

        // Vẽ khung viền nét đứt màu xanh dương
        val borderPaint = android.graphics.Paint().apply {
            color = "#3B82F6".toColorInt()
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1.5f * scaleFactor
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f * scaleFactor, 4f * scaleFactor), 0f)
            isAntiAlias = true
        }
        canvas.drawRect(destRect, borderPaint)

        // Bút vẽ viền handle màu trắng
        val handleStrokePaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1.5f * scaleFactor
            isAntiAlias = true
        }

        val handleRadius = 7f * scaleFactor

        // 1. Vẽ nút tròn xóa (Red) ở góc trên bên trái (bmpX, bmpY - sigHeight)
        val deletePaint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#EF4444")
            style = android.graphics.Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(bmpX, bmpY - sigHeight, handleRadius, deletePaint)
        canvas.drawCircle(bmpX, bmpY - sigHeight, handleRadius, handleStrokePaint)

        // Vẽ chữ 'X' nhỏ trong nút xóa
        val xPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            strokeWidth = 1.5f * scaleFactor
            style = android.graphics.Paint.Style.STROKE
            isAntiAlias = true
        }
        val xOffset = 2.5f * scaleFactor
        canvas.drawLine(bmpX - xOffset, bmpY - sigHeight - xOffset, bmpX + xOffset, bmpY - sigHeight + xOffset, xPaint)
        canvas.drawLine(bmpX + xOffset, bmpY - sigHeight - xOffset, bmpX - xOffset, bmpY - sigHeight + xOffset, xPaint)

        // 2. Vẽ nút tròn xoay (Green) ở giữa phía trên (cx, bmpY - sigHeight - 12f * scaleFactor)
        val rotateHandleY = bmpY - sigHeight - 12f * scaleFactor
        val rotateLinePaint = android.graphics.Paint().apply {
            color = "#3B82F6".toColorInt()
            strokeWidth = 1.2f * scaleFactor
            style = android.graphics.Paint.Style.STROKE
            isAntiAlias = true
        }
        canvas.drawLine(cx, bmpY - sigHeight, cx, rotateHandleY, rotateLinePaint)

        val rotatePaint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#10B981")
            style = android.graphics.Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(cx, rotateHandleY, handleRadius, rotatePaint)
        canvas.drawCircle(cx, rotateHandleY, handleRadius, handleStrokePaint)

        // 3. Vẽ nút tròn co giãn (Blue) ở góc dưới bên phải (bmpX + sigWidth, bmpY)
        val resizePaint = android.graphics.Paint().apply {
            color = "#3B82F6".toColorInt()
            style = android.graphics.Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(bmpX + sigWidth, bmpY, handleRadius, resizePaint)
        canvas.drawCircle(bmpX + sigWidth, bmpY, handleRadius, handleStrokePaint)

        canvas.restore()
    }

    private fun showPage(index: Int) {
        val renderer = pdfRenderer ?: return
        if (index < 0 || index >= pageCount) return

        try {
            // Đóng và dọn dẹp inline edit đang mở trước khi chuyển trang
            closeAndSaveInlineEditText()

            currentPage?.close()

            currentPageIndex = index
            val page = renderer.openPage(index)
            currentPage = page

            // Render trang sạch một lần duy nhất để lưu cache tối ưu hiệu năng
            val densityScale = resources.displayMetrics.density
            val scaleFactor = (densityScale * 1.5f).coerceAtLeast(1.0f)
            val w = (page.width * scaleFactor).toInt()
            val h = (page.height * scaleFactor).toInt()
            basePageBitmap?.recycle()
            val baseBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            page.render(baseBmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            basePageBitmap = baseBmp

            // Khởi tạo display bitmap và canvas dùng lại để tối ưu hóa hiệu năng vẽ, tránh cấp phát liên tục
            displayBitmap?.recycle()
            val dispBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            displayBitmap = dispBmp
            displayCanvas = android.graphics.Canvas(dispBmp)

            // Detect text blocks trên trang hiện tại để chuẩn bị cho việc chỉnh sửa
            detectTextBlocks(index)

            tvPageIndicator.text = "Trang ${index + 1} / $pageCount"
            btnPrevPage.isEnabled = index > 0
            btnNextPage.isEnabled = index < pageCount - 1

            // Vẽ lại trang kết hợp hiển thị các khung text nét đứt màu xanh mờ
            renderPageWithEdits()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Lỗi hiển thị trang: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Áp dụng các thay đổi thực sự vào tệp PDF
    private fun saveEditsToFile() {
        if (pendingEdits.isEmpty()) {
            Toast.makeText(this, "Không có thay đổi nào để lưu!", Toast.LENGTH_SHORT).show()
            return
        }

        val originalFile = File(pdfFilePath!!)
        if (!originalFile.exists()) {
            Toast.makeText(this, "Tệp PDF gốc không tồn tại!", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Đóng tất cả renderer để mở khóa file hệ thống hoàn toàn
            currentPage?.close()
            currentPage = null
            pdfRenderer?.close()
            pdfRenderer = null
            fileDescriptor?.close()
            fileDescriptor = null

            val success = PdfEditorHelper.saveEdits(
                this,
                pdfFilePath!!,
                pdfFileUri,
                currentPageIndex,
                pendingEdits
            )

            if (success) {
                pendingEdits.clear()
                setupPdfRenderer()
                Toast.makeText(this, "Đã lưu chỉnh sửa vào PDF thành công!", Toast.LENGTH_SHORT).show()
            } else {
                setupPdfRenderer()
                Toast.makeText(this, "Lỗi khi lưu PDF!", Toast.LENGTH_LONG).show()
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            Toast.makeText(this, "Lỗi khi lưu PDF: ${e.message}", Toast.LENGTH_LONG).show()
            setupPdfRenderer()
        }
    }

    override fun onDestroy() {
        try {
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

        activeInlineBlock = block

        // Chuyển đổi tọa độ PDF sang tọa độ Pixel thực tế của View
        val xView = block.x * (wView / wPdf)
        val yView = (hPdf - block.y) * (hView / hPdf)
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
                leftMargin = xView.toInt()
                topMargin = (yView - viewHeight).toInt()
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
        val block = activeInlineBlock ?: return

        // Giải phóng biến trạng thái ngay lập tức để tránh các cuộc gọi đệ quy đè lên nhau (Focus Loss)
        activeInlineEditText = null
        activeInlineBlock = null

        val newText = et.text.toString().trim()
        val container = findViewById<FrameLayout>(R.id.pdfViewContainer)

        // Ẩn bàn phím ảo
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(et.windowToken, 0)

        // Ghi nhận thay đổi nếu nội dung thay đổi và không rỗng
        if (newText.isNotEmpty() && newText != block.text) {
            pendingEdits.add(PdfEdit.WhiteoutTextEdit(newText, block.x, block.y, block.width, block.fontSize, block.isBold, block.textColor))
            renderPageWithEdits()
        }

        container?.removeView(et)
    }

    // Tìm xem tọa độ chạm có trúng vào khối text được nhận diện nào không
    private fun findDetectedTextBlockAt(pdfX: Float, pdfY: Float): TextBlock? {
        val padding = 6f
        for (block in detectedTextBlocks) {
            if (pdfX >= block.x - padding && pdfX <= block.x + block.width + padding &&
                pdfY >= block.y - block.height - padding && pdfY <= block.y + padding) {
                return block
            }
        }
        return null
    }


    // Quét toàn bộ trang PDF để nhận diện văn bản và tọa độ của chúng
    private fun detectTextBlocks(pageIndex: Int) {
        detectedTextBlocks.clear()
        val filePath = pdfFilePath ?: return
        detectedTextBlocks.addAll(PdfEditorHelper.detectTextBlocks(filePath, pageIndex))
    }

    private fun deactivateAllModes() {
        isEditTextMode = false
        isHighlightMode = false
        isLineEffectMode = false
        isReplaceImageMode = false
        isSignatureMode = false

        closeAndSaveInlineEditText()
        detectedImageRects.clear()

        btnMenuTools.text = "Công cụ"
        btnMenuTools.backgroundTintList = android.content.res.ColorStateList.valueOf("#2563EB".toColorInt())
    }
}
