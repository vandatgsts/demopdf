package com.vandatgsts.demopdf

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

object PdfEditorHelper {

    // Trích xuất các khối chữ trên một trang PDF
    fun detectTextBlocks(pdfFilePath: String, pageIndex: Int): List<TextBlock> {
        val detectedTextBlocks = mutableListOf<TextBlock>()
        try {
            val file = File(pdfFilePath)
            if (!file.exists()) return emptyList()
            
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
        return detectedTextBlocks
    }

    // Áp dụng các thay đổi và ghi đè vào tệp PDF
    fun saveEdits(
        context: Context,
        pdfFilePath: String,
        pdfFileUri: String?,
        pageIndex: Int,
        edits: List<PdfViewerActivity.PdfEdit>
    ): Boolean {
        val originalFile = File(pdfFilePath)
        if (!originalFile.exists()) return false

        try {
            // 1. Tạo tệp tạm đọc nội dung cũ
            val tempFile = File(context.cacheDir, "temp_process.pdf")
            originalFile.copyTo(tempFile, overwrite = true)

            // 2. Tạo tệp tạm đầu ra để ghi kết quả chỉnh sửa
            val tempOutputFile = File(context.cacheDir, "temp_output.pdf")
            
            val document = PDDocument.load(tempFile)
            val page = document.getPage(pageIndex)

            val fontPath = "/system/fonts/Roboto-Regular.ttf"
            val font = if (File(fontPath).exists()) {
                PDType0Font.load(document, File(fontPath))
            } else {
                PDType1Font.HELVETICA
            }

            val contentStream = PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)

            for (edit in edits) {
                when (edit) {
                    is PdfViewerActivity.PdfEdit.TextEdit -> {
                        contentStream.beginText()
                        contentStream.setFont(font, 14f)
                        contentStream.setNonStrokingColor(1f, 0f, 0f)
                        contentStream.newLineAtOffset(edit.x, edit.y)
                        contentStream.showText(edit.text)
                        contentStream.endText()
                    }
                    is PdfViewerActivity.PdfEdit.SignatureEdit -> {
                        val pdImage = LosslessFactory.createFromImage(document, edit.bitmap)
                        val sigWidth = edit.width
                        val sigHeight = edit.height
                        contentStream.drawImage(pdImage, edit.x, edit.y, sigWidth, sigHeight)
                    }
                    is PdfViewerActivity.PdfEdit.WhiteoutTextEdit -> {
                        // 1. Vẽ hình chữ nhật trắng che chữ cũ
                        contentStream.setNonStrokingColor(1f, 1f, 1f)
                        contentStream.addRect(edit.x, edit.y - 4f, edit.width, edit.fontSize * 1.2f)
                        contentStream.fill()

                        // 2. Viết chữ thay thế mới
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
                    is PdfViewerActivity.PdfEdit.HighlightEdit -> {
                        val gState = PDExtendedGraphicsState()
                        gState.nonStrokingAlphaConstant = 0.4f
                        contentStream.setGraphicsStateParameters(gState)
                        
                        contentStream.setNonStrokingColor(1f, 1f, 0f)
                        contentStream.addRect(edit.x, edit.y - 4f, edit.width, edit.height)
                        contentStream.fill()
                        
                        val resetState = PDExtendedGraphicsState()
                        resetState.nonStrokingAlphaConstant = 1.0f
                        contentStream.setGraphicsStateParameters(resetState)
                    }
                    is PdfViewerActivity.PdfEdit.LineEffectEdit -> {
                        contentStream.setStrokingColor(1f, 0f, 0f)
                        contentStream.setLineWidth(1.5f)
                        if (edit.effectType == 1) {
                            val lineY = edit.y - 2f
                            contentStream.moveTo(edit.x, lineY)
                            contentStream.lineTo(edit.x + edit.width, lineY)
                        } else {
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

            // 3. Sao chép kết quả đè lên tệp PDF gốc
            if (tempOutputFile.exists()) {
                tempOutputFile.copyTo(originalFile, overwrite = true)
                tempOutputFile.delete()
            }

            // Ghi ngược lại Uri nếu cần
            pdfFileUri?.let { uriString ->
                try {
                    val uri = Uri.parse(uriString)
                    context.contentResolver.openOutputStream(uri, "w")?.use { outputStream ->
                        originalFile.inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            tempFile.delete()
            return true
        } catch (e: Throwable) {
            e.printStackTrace()
            return false
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
