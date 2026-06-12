package com.vandatgsts.demopdf

import android.graphics.Bitmap
import android.net.Uri
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
import android.widget.FrameLayout
import android.view.inputmethod.EditorInfo
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.graphics.toColorInt
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
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

    // Danh sách các chỉnh sửa tạm thời (chưa lưu vào file) để vẽ preview
    sealed class PdfEdit {
        abstract var x: Float
        abstract var y: Float
        
        data class TextEdit(val text: String, override var x: Float, override var y: Float) : PdfEdit()
        data class SignatureEdit(val bitmap: Bitmap, override var x: Float, override var y: Float, var width: Float = 140f, var height: Float = 70f) : PdfEdit()
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

    // Biến quản lý trạng thái co giãn (resize) chữ ký
    private var isResizing = false
    private var activeResizeEdit: PdfEdit.SignatureEdit? = null

    private var isHighlightMode = false
    private lateinit var btnToggleHighlight: Button
    private var highlightStartX = 0f
    private var highlightStartY = 0f
    private var activeHighlightEdit: PdfEdit.HighlightEdit? = null

    private var isLineEffectMode = false
    private var activeLineEffectType = 1 // 1: Underline, 2: Strikethrough
    private lateinit var btnToggleLineEffect: Button
    private var selectionStartX = 0f
    private var selectionStartY = 0f
    private var selectionRect: android.graphics.RectF? = null

    private var isEditTextMode = false
    private lateinit var btnToggleEdit: Button
    private var activeInlineEditText: EditText? = null
    private var activeInlineBlock: TextBlock? = null

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
        btnToggleHighlight = findViewById(R.id.btnToggleHighlight)
        btnToggleEdit = findViewById(R.id.btnToggleEdit)
        btnToggleLineEffect = findViewById(R.id.btnToggleLineEffect)

        btnToggleHighlight.setOnClickListener {
            isHighlightMode = !isHighlightMode
            if (isHighlightMode) {
                // Tắt chế độ sửa chữ nếu đang bật
                if (isEditTextMode) {
                    isEditTextMode = false
                    closeAndSaveInlineEditText()
                    btnToggleEdit.text = "Sửa chữ"
                    btnToggleEdit.backgroundTintList = android.content.res.ColorStateList.valueOf("#6B7280".toColorInt())
                }
                // Tắt chế độ kẻ chữ nếu đang bật
                if (isLineEffectMode) {
                    isLineEffectMode = false
                    btnToggleLineEffect.text = "Kẻ chữ"
                    btnToggleLineEffect.backgroundTintList = android.content.res.ColorStateList.valueOf("#6B7280".toColorInt())
                }
                btnToggleHighlight.text = "Đang tô"
                btnToggleHighlight.backgroundTintList = android.content.res.ColorStateList.valueOf("#F59E0B".toColorInt()) // màu cam/vàng highlight
                Toast.makeText(this, "Chế độ tô sáng: Vuốt ngón tay ngang để vẽ highlight", Toast.LENGTH_SHORT).show()
            } else {
                btnToggleHighlight.text = "Bút dạ"
                btnToggleHighlight.backgroundTintList = android.content.res.ColorStateList.valueOf("#6B7280".toColorInt()) // xám
            }
            renderPageWithEdits()
        }

        btnToggleEdit.setOnClickListener {
            isEditTextMode = !isEditTextMode
            if (isEditTextMode) {
                // Tắt chế độ highlight nếu đang bật
                if (isHighlightMode) {
                    isHighlightMode = false
                    btnToggleHighlight.text = "Bút dạ"
                    btnToggleHighlight.backgroundTintList = android.content.res.ColorStateList.valueOf("#6B7280".toColorInt())
                }
                // Tắt chế độ kẻ chữ nếu đang bật
                if (isLineEffectMode) {
                    isLineEffectMode = false
                    btnToggleLineEffect.text = "Kẻ chữ"
                    btnToggleLineEffect.backgroundTintList = android.content.res.ColorStateList.valueOf("#6B7280".toColorInt())
                }
                btnToggleEdit.text = "Đang sửa"
                btnToggleEdit.backgroundTintList = android.content.res.ColorStateList.valueOf("#2563EB".toColorInt()) // màu xanh lam
                Toast.makeText(this, "Chế độ sửa chữ: Chọn khung nét đứt màu xanh để sửa trực tiếp", Toast.LENGTH_SHORT).show()
            } else {
                closeAndSaveInlineEditText()
                btnToggleEdit.text = "Sửa chữ"
                btnToggleEdit.backgroundTintList = android.content.res.ColorStateList.valueOf("#6B7280".toColorInt()) // màu xám
            }
            renderPageWithEdits()
        }

        btnToggleLineEffect.setOnClickListener {
            if (!isLineEffectMode) {
                val options = arrayOf("Gạch dưới (Underline)", "Gạch ngang (Strikethrough)")
                AlertDialog.Builder(this)
                    .setTitle("Chọn kiểu kẻ chữ")
                    .setItems(options) { _, which ->
                        activeLineEffectType = if (which == 0) 1 else 2
                        isLineEffectMode = true

                        // Tắt chế độ highlight nếu đang bật
                        if (isHighlightMode) {
                            isHighlightMode = false
                            btnToggleHighlight.text = "Bút dạ"
                            btnToggleHighlight.backgroundTintList = android.content.res.ColorStateList.valueOf("#6B7280".toColorInt())
                        }
                        // Tắt chế độ sửa chữ nếu đang bật
                        if (isEditTextMode) {
                            isEditTextMode = false
                            closeAndSaveInlineEditText()
                            btnToggleEdit.text = "Sửa chữ"
                            btnToggleEdit.backgroundTintList = android.content.res.ColorStateList.valueOf("#6B7280".toColorInt())
                        }

                        val modeText = if (activeLineEffectType == 1) "Kẻ: Gạch dưới" else "Kẻ: Gạch ngang"
                        btnToggleLineEffect.text = modeText
                        btnToggleLineEffect.backgroundTintList = android.content.res.ColorStateList.valueOf("#EF4444".toColorInt()) // màu đỏ
                        Toast.makeText(this, "Chế độ kẻ chữ: Vuốt để quét vùng chọn text cần kẻ chữ", Toast.LENGTH_SHORT).show()
                        renderPageWithEdits()
                    }
                    .setNegativeButton("Hủy", null)
                    .show()
            } else {
                isLineEffectMode = false
                btnToggleLineEffect.text = "Kẻ chữ"
                btnToggleLineEffect.backgroundTintList = android.content.res.ColorStateList.valueOf("#6B7280".toColorInt())
                renderPageWithEdits()
            }
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

                if (isEditTextMode) {
                    // Kiểm tra chạm trúng khối text
                    val block = findDetectedTextBlockAt(pdfX, pdfY)
                    if (block != null) {
                        // Sửa trực tiếp trên trang thay vì hiện Dialog!
                        showInlineEditText(block, pdfX, pdfY, wView, hView, wPdf, hPdf)
                    } else {
                        // Nếu gõ bên ngoài, đóng và lưu EditText đang mở
                        closeAndSaveInlineEditText()
                    }
                } else {
                    // Chế độ thường: hiện menu Options
                    showEditOptionsDialog(xTouch, yTouch)
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
                            selectionRect = android.graphics.RectF(pdfX, pdfY, pdfX, pdfY)
                            // Khóa cuộn màn hình
                            ivPdfPage.parent?.requestDisallowInterceptTouchEvent(true)
                            return@setOnTouchListener true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val currentX = pdfX
                            val currentY = pdfY
                            selectionRect = android.graphics.RectF(
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
                            // 1. Kiểm tra xem có chạm trúng resize handle của SignatureEdit nào không (góc dưới bên phải)
                            var foundResize = false
                            for (i in pendingEdits.indices.reversed()) {
                                val edit = pendingEdits[i]
                                if (edit is PdfEdit.SignatureEdit) {
                                    val handleX = edit.x + edit.width
                                    val handleY = edit.y
                                    val dist = Math.hypot((pdfX - handleX).toDouble(), (pdfY - handleY).toDouble())
                                    if (dist < 20.0) { // Bán kính nhận diện 20 points
                                        isResizing = true
                                        activeResizeEdit = edit
                                        foundResize = true
                                        ivPdfPage.parent?.requestDisallowInterceptTouchEvent(true)
                                        break
                                    }
                                }
                            }

                            if (foundResize) {
                                return@setOnTouchListener true
                            }

                            // 2. Nếu không co giãn, kiểm tra chạm trúng phần tử để kéo thả di chuyển
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
                            // Xử lý co giãn chữ ký
                            if (isResizing && activeResizeEdit != null) {
                                activeResizeEdit?.let { edit ->
                                    val ratio = edit.bitmap.width.toFloat() / edit.bitmap.height.toFloat()
                                    val newWidth = (pdfX - edit.x).coerceAtLeast(30f) // Chiều rộng tối thiểu 30pt
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
                            if (isResizing || activeDragEdit != null) {
                                isResizing = false
                                activeResizeEdit = null
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
            val edit = pendingEdits[i]
            when (edit) {
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
                    val width = edit.width
                    val height = edit.height
                    if (pdfX >= edit.x && pdfX <= edit.x + width && pdfY >= edit.y && pdfY <= edit.y + height) {
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

        val options = arrayOf("Thêm chữ mới tại đây", "Ký tên tại đây", "Xóa & Sửa chữ tại đây", "Tô sáng (Highlight) tại đây")
        AlertDialog.Builder(this)
            .setTitle("Tùy chọn chỉnh sửa")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showInsertTextDialog(pdfX, pdfY)
                    1 -> showSignatureDialog(pdfX, pdfY)
                    2 -> showWhiteoutEditDialog(pdfX, pdfY)
                    3 -> showHighlightDialog(pdfX, pdfY)
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
        val renderer = pdfRenderer ?: return
        if (currentPageIndex !in 0..<pageCount) return

        try {
            currentPage?.close()
            val page = renderer.openPage(currentPageIndex)
            currentPage = page

            val densityScale = resources.displayMetrics.density
            val scaleFactor = (densityScale * 1.5f).coerceAtLeast(1.0f)

            val width = (page.width * scaleFactor).toInt()
            val height = (page.height * scaleFactor).toInt()

            val displayBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            page.render(displayBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            // Vẽ Canvas đè lên ảnh hiển thị các edit tạm thời (preview)
            val canvas = android.graphics.Canvas(displayBitmap)
            
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
                        val bmpX = edit.x * scaleFactor
                        val bmpY = (page.height - edit.y) * scaleFactor
                        
                        val sigWidth = edit.width * scaleFactor
                        val sigHeight = edit.height * scaleFactor
                        
                        val destRect = android.graphics.RectF(
                            bmpX,
                            bmpY - sigHeight,
                            bmpX + sigWidth,
                            bmpY
                        )
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

                        // Vẽ nút tròn resize handle ở góc dưới bên phải (bmpX + sigWidth, bmpY)
                        val handlePaint = android.graphics.Paint().apply {
                            color = "#3B82F6".toColorInt()
                            style = android.graphics.Paint.Style.FILL
                            isAntiAlias = true
                        }
                        val handleStrokePaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.WHITE
                            style = android.graphics.Paint.Style.STROKE
                            strokeWidth = 1.5f * scaleFactor
                            isAntiAlias = true
                        }
                        
                        val handleRadius = 7f * scaleFactor
                        canvas.drawCircle(bmpX + sigWidth, bmpY, handleRadius, handlePaint)
                        canvas.drawCircle(bmpX + sigWidth, bmpY, handleRadius, handleStrokePaint)
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

            ivPdfPage.setImageBitmap(displayBitmap)

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Lỗi khi cập nhật bản xem trước: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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

            // 1. Tạo tệp tạm đọc nội dung cũ
            val tempFile = File(cacheDir, "temp_process.pdf")
            originalFile.copyTo(tempFile, overwrite = true)

            // 2. Tạo tệp tạm đầu ra để ghi kết quả chỉnh sửa, tránh lock trực tiếp trên originalFile
            val tempOutputFile = File(cacheDir, "temp_output.pdf")
            
            val document = PDDocument.load(tempFile)
            val page = document.getPage(currentPageIndex)

            val fontPath = "/system/fonts/Roboto-Regular.ttf"
            val font = if (File(fontPath).exists()) {
                PDType0Font.load(document, File(fontPath))
            } else {
                PDType1Font.HELVETICA
            }

            // Mở content stream ở chế độ APPEND, cấu hình resetContext=true và compress=true
            val contentStream = PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)

            for (edit in pendingEdits) {
                when (edit) {
                    is PdfEdit.TextEdit -> {
                        contentStream.beginText()
                        contentStream.setFont(font, 14f)
                        contentStream.setNonStrokingColor(1f, 0f, 0f) // Chèn chữ mới màu đỏ
                        contentStream.newLineAtOffset(edit.x, edit.y)
                        contentStream.showText(edit.text)
                        contentStream.endText()
                    }
                    is PdfEdit.SignatureEdit -> {
                        val pdImage = LosslessFactory.createFromImage(document, edit.bitmap)
                        val sigWidth = edit.width
                        val sigHeight = edit.height
                        contentStream.drawImage(pdImage, edit.x, edit.y, sigWidth, sigHeight)
                    }
                    is PdfEdit.WhiteoutTextEdit -> {
                        // 1. Vẽ hình chữ nhật trắng che chữ cũ
                        contentStream.setNonStrokingColor(1f, 1f, 1f)
                        contentStream.addRect(edit.x, edit.y - 4f, edit.width, edit.fontSize * 1.2f)
                        contentStream.fill()

                        // 2. Viết chữ thay thế mới màu với đúng cỡ chữ, kiểu font và màu sắc gốc
                        val selectedFont = if (edit.isBold) {
                            val boldFontPath = "/system/fonts/Roboto-Bold.ttf"
                            if (File(boldFontPath).exists()) {
                                PDType0Font.load(document, File(boldFontPath))
                            } else {
                                font
                            }
                        } else {
                            font
                        }

                        contentStream.beginText()
                        contentStream.setFont(selectedFont, edit.fontSize)
                        
                        val r = android.graphics.Color.red(edit.textColor) / 255f
                        val g = android.graphics.Color.green(edit.textColor) / 255f
                        val b = android.graphics.Color.blue(edit.textColor) / 255f
                        contentStream.setNonStrokingColor(r, g, b)
                        
                        contentStream.newLineAtOffset(edit.x, edit.y)
                        contentStream.showText(edit.text)
                        contentStream.endText()
                    }
                    is PdfEdit.HighlightEdit -> {
                        // 1. Đặt thuộc tính bán trong suốt (opacity 0.4) cho bút vẽ
                        val gState = PDExtendedGraphicsState()
                        gState.nonStrokingAlphaConstant = 0.4f
                        contentStream.setGraphicsStateParameters(gState)
                        
                        // 2. Vẽ hình chữ nhật màu vàng làm nổi bật chữ
                        contentStream.setNonStrokingColor(1f, 1f, 0f)
                        contentStream.addRect(edit.x, edit.y - 4f, edit.width, edit.height)
                        contentStream.fill()
                        
                        // 3. Reset lại opacity về 1.0f cho các phần vẽ sau
                        val resetState = PDExtendedGraphicsState()
                        resetState.nonStrokingAlphaConstant = 1.0f
                        contentStream.setGraphicsStateParameters(resetState)
                    }
                    is PdfEdit.LineEffectEdit -> {
                        contentStream.setStrokingColor(1f, 0f, 0f)
                        contentStream.setLineWidth(1.5f)
                        if (edit.effectType == 1) {
                            // Underline: dưới baseline 2pt
                            val lineY = edit.y - 2f
                            contentStream.moveTo(edit.x, lineY)
                            contentStream.lineTo(edit.x + edit.width, lineY)
                        } else {
                            // Strikethrough: ở giữa chữ
                            val lineY = edit.y + edit.height / 2
                            contentStream.moveTo(edit.x, lineY)
                            contentStream.lineTo(edit.x + edit.width, lineY)
                        }
                        contentStream.stroke()
                    }
                }
            }

            contentStream.close()
            document.save(tempOutputFile)
            document.close()

            // 3. Sao chép kết quả từ tệp tạm đè lên tệp PDF gốc
            if (tempOutputFile.exists()) {
                tempOutputFile.copyTo(originalFile, overwrite = true)
                tempOutputFile.delete()
            }

            // Ghi ngược lại Uri gốc bên ngoài thiết bị (nếu có) để đồng bộ thay đổi thực tế
            pdfFileUri?.let { uriString ->
                try {
                    val uri = Uri.parse(uriString)
                    contentResolver.openOutputStream(uri, "w")?.use { outputStream ->
                        originalFile.inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Xóa tệp tạm đọc
            tempFile.delete()
            pendingEdits.clear()

            // Khởi tạo lại PdfRenderer hiển thị tệp mới
            setupPdfRenderer()

            Toast.makeText(this, "Đã lưu chỉnh sửa vào PDF thành công!", Toast.LENGTH_SHORT).show()

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
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
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
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
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

    // Hiển thị hộp thoại chỉnh sửa văn bản được nhận diện
    private fun showEditDetectedTextDialog(block: TextBlock) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_whiteout_edit, null)
        val etNewText = dialogView.findViewById<EditText>(R.id.etNewText)
        val etWhiteoutWidth = dialogView.findViewById<EditText>(R.id.etWhiteoutWidth)
        val btnWhiteoutCancel = dialogView.findViewById<Button>(R.id.btnWhiteoutCancel)
        val btnWhiteoutConfirm = dialogView.findViewById<Button>(R.id.btnWhiteoutConfirm)

        // Điền sẵn nội dung chữ cũ và chiều rộng cũ của khối text
        etNewText.setText(block.text)
        etWhiteoutWidth.setText(block.width.toString())

        val alertDialog = AlertDialog.Builder(this)
            .setTitle("Sửa văn bản trong tài liệu")
            .setView(dialogView)
            .create()

        btnWhiteoutCancel.setOnClickListener {
            alertDialog.dismiss()
        }

        btnWhiteoutConfirm.setOnClickListener {
            val newText = etNewText.text.toString().trim()
            val widthStr = etWhiteoutWidth.text.toString().trim()
            val width = widthStr.toFloatOrNull() ?: block.width

            if (newText.isNotEmpty()) {
                // Thêm chỉnh sửa WhiteoutTextEdit đè lên đúng tọa độ của text block cũ
                pendingEdits.add(PdfEdit.WhiteoutTextEdit(newText, block.x, block.y, width, block.fontSize, block.isBold, block.textColor))
                renderPageWithEdits()
                alertDialog.dismiss()
            } else {
                Toast.makeText(this, "Vui lòng nhập chữ thay thế!", Toast.LENGTH_SHORT).show()
            }
        }

        alertDialog.show()
    }

    // Quét toàn bộ trang PDF để nhận diện văn bản và tọa độ của chúng
    private fun detectTextBlocks(pageIndex: Int) {
        detectedTextBlocks.clear()
        val filePath = pdfFilePath ?: return
        try {
            val file = File(filePath)
            val document = PDDocument.load(file)
            val page = document.getPage(pageIndex)
            val pageHeight = page.cropBox.height

            val stripper = AndroidTextStripper(pageHeight)
            stripper.startPage = pageIndex + 1
            stripper.endPage = pageIndex + 1

            val writer = java.io.StringWriter()
            stripper.writeText(document, writer)

            detectedTextBlocks.addAll(stripper.textBlocks)
            document.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

// Lớp lưu trữ thông tin khối văn bản nhận diện được
class TextBlock(
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val fontSize: Float,
    val isBold: Boolean,
    val textColor: Int
)

// Trình trích xuất văn bản của PDFBox-Android gom cụm các ký tự trên cùng dòng
class AndroidTextStripper(private val pageHeight: Float) : PDFTextStripper() {
    val textBlocks = mutableListOf<TextBlock>()

    init {
        sortByPosition = true
    }

    @Throws(java.io.IOException::class)
    override fun writeString(text: String, textPositions: List<TextPosition>) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val first = textPositions.first()
        val last = textPositions.last()

        val x = first.xDirAdj
        val y = pageHeight - first.yDirAdj
        val width = (last.xDirAdj + last.widthDirAdj) - first.xDirAdj
        val height = if (first.heightDir > 1f) first.heightDir else 14f
        
        val fontSize = if (first.fontSizeInPt > 0f) first.fontSizeInPt else 14f
        val fontName = first.font?.name?.lowercase() ?: ""
        val isBold = fontName.contains("bold") || fontName.contains("black") || fontName.contains("w700")

        val textColor = try {
            val color = graphicsState.nonStrokingColor
            val rgbValues = color.colorSpace.toRGB(color.components)
            val r = (rgbValues[0] * 255).toInt().coerceIn(0, 255)
            val g = (rgbValues[1] * 255).toInt().coerceIn(0, 255)
            val b = (rgbValues[2] * 255).toInt().coerceIn(0, 255)
            android.graphics.Color.rgb(r, g, b)
        } catch (e: Exception) {
            android.graphics.Color.BLACK
        }

        textBlocks.add(TextBlock(trimmed, x, y, width, height, fontSize, isBold, textColor))
    }
}
