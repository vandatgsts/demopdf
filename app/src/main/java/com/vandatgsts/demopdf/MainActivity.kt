package com.vandatgsts.demopdf

import android.content.Intent
import android.net.Uri
import com.vandatgsts.pdfeditor.PdfViewerActivity
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.vandatgsts.mylibrary.pdfbox.android.PDFBoxResourceLoader
import com.vandatgsts.mylibrary.pdfbox.pdmodel.PDDocument
import com.vandatgsts.mylibrary.pdfbox.pdmodel.PDPage
import com.vandatgsts.mylibrary.pdfbox.pdmodel.PDPageContentStream
import com.vandatgsts.mylibrary.pdfbox.pdmodel.font.PDType0Font
import com.vandatgsts.mylibrary.pdfbox.pdmodel.font.PDType1Font
import com.vandatgsts.mylibrary.pdfbox.pdmodel.common.PDRectangle
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var btnOpenDocument: Button
    private lateinit var btnOpenSample: Button

    private var sourcePdfFile: File? = null
    private var lastSelectedFileName: String? = null
    
    // Cờ để tự động kích hoạt bộ chọn file PDF ngay khi khởi chạy app lần đầu tiên
    private var isFirstLaunch = true

    // Bộ chọn file PDF từ thiết bị
    private val selectPdfLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            copyUriToLocalFile(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        
        // Khởi tạo PDFBox Resource Loader cho Android
        PDFBoxResourceLoader.init(applicationContext)
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        btnOpenDocument = findViewById(R.id.btnOpenDocument)
        btnOpenSample = findViewById(R.id.btnOpenSample)

        sourcePdfFile = File(filesDir, "input.pdf")

        // Xử lý sự kiện click thủ công
        btnOpenDocument.setOnClickListener {
            selectPdfLauncher.launch(arrayOf("application/pdf"))
        }

        btnOpenSample.setOnClickListener {
            generateSamplePdf()
        }
    }

    override fun onResume() {
        super.onResume()
        // Tự động mở trình chọn file PDF ngay lập tức khi ứng dụng khởi chạy lần đầu tiên
        if (isFirstLaunch) {
            isFirstLaunch = false
            selectPdfLauncher.launch(arrayOf("application/pdf"))
        }
    }

    // Sao chép tệp được chọn vào lưu trữ nội bộ của app và mở màn hình chỉnh sửa PDF
    private fun copyUriToLocalFile(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val destination = sourcePdfFile ?: File(filesDir, "input.pdf")
                FileOutputStream(destination).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
                
                lastSelectedFileName = getFileName(uri)
                
                // Mở màn hình xem và ký PDF trực tiếp
                navigateToPdfViewer(destination.absolutePath, lastSelectedFileName ?: "document.pdf", uri.toString())
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Lỗi khi nạp file PDF: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Lấy tên tệp tin thực tế từ Uri
    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = it.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "document.pdf"
    }

    // Tạo file mẫu hợp đồng nếu người dùng muốn trải nghiệm nhanh
    private fun generateSamplePdf() {
        try {
            val file = sourcePdfFile ?: File(filesDir, "input.pdf")
            val document = PDDocument()
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)

            val fontPath = "/system/fonts/Roboto-Regular.ttf"
            val font = if (File(fontPath).exists()) {
                PDType0Font.load(document, File(fontPath))
            } else {
                PDType1Font.HELVETICA
            }

            val contentStream = PDPageContentStream(document, page)
            
            // Vẽ tiêu đề
            contentStream.beginText()
            contentStream.setFont(font, 22f)
            val title = "HỢP ĐỒNG CUNG CẤP DỊCH VỤ"
            val titleWidth = if (font is PDType0Font) font.getStringWidth(title) / 1000f * 22f else 300f
            val titleX = (PDRectangle.A4.width - titleWidth) / 2f
            contentStream.newLineAtOffset(titleX, 750f)
            contentStream.showText(title)
            contentStream.endText()

            // Các dòng nội dung
            val lines = listOf(
                "Bên A: Nguyễn Văn A",
                "Bên B: Công ty TNHH Demo PDF",
                "",
                "Điều 1: Bên A đồng ý ký hợp đồng sử dụng dịch vụ ký số trực tuyến do Bên B cung cấp.",
                "Điều 2: Chữ ký trên tài liệu này có đầy đủ giá trị pháp lý giữa các bên.",
                "",
                "Đại diện hai bên ký xác nhận bên dưới:",
                "", "", "", "", "", "", "",
                "Họ tên người ký: ......................................."
            )

            var currentY = 700f
            for (line in lines) {
                if (line.isNotEmpty()) {
                    contentStream.beginText()
                    contentStream.setFont(font, 14f)
                    contentStream.newLineAtOffset(50f, currentY)
                    contentStream.showText(line)
                    contentStream.endText()
                }
                currentY -= 25f
            }

            contentStream.close()
            document.save(file)
            document.close()
            
            // Mở màn hình xem và ký PDF trực tiếp
            navigateToPdfViewer(file.absolutePath, "sample.pdf (tự tạo)")
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Lỗi tạo PDF mẫu: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun navigateToPdfViewer(filePath: String, fileName: String, fileUri: String? = null) {
        val intent = Intent(this, PdfViewer3rdActivity::class.java).apply {
            putExtra("PDF_FILE_PATH", filePath)
            putExtra("PDF_FILE_NAME", fileName)
            putExtra("PDF_FILE_URI", fileUri)
        }
        startActivity(intent)
    }
}