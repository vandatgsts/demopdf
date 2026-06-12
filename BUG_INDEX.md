# Bug Index - PDF E-Sign Demo

Tài liệu này lưu trữ lịch sử các bug đã phát hiện, nguyên nhân gốc rễ, giải pháp đã áp dụng và trạng thái sửa lỗi trong dự án.

---

## BUG-0001 - NoClassDefFoundError: org/openpdf/text/pdf/ExtendedColor (java.awt.Color)

**Trạng thái:** FIXED

**Tính năng:** Khởi tạo tài liệu PDF (PDF Generation)

**Tệp ảnh hưởng:**
- [MainActivity.kt](file:///E:/test/demopdf/app/src/main/java/com/vandatgsts/demopdf/MainActivity.kt)

**Triệu chứng:**
Crash ngay khi khởi động app hoặc khi nhấn "Tạo PDF mẫu" với lỗi `NoClassDefFoundError` do thiếu `java.awt.Color` trên Android.

**Nguyên nhân:**
Thư viện OpenPDF 3.x phụ thuộc vào Java AWT (`java.awt.Color`) vốn không tồn tại trên hệ điều hành Android.

**Giải pháp:**
- Ban đầu: Tạo tệp stub `java.awt.Color` giả lập trong thư mục mã nguồn của app.
- Cuối cùng: Chuyển toàn bộ dự án sang thư viện **`iTextG:5.5.10`** (bản iText được tối ưu và loại bỏ hoàn toàn các gói `java.awt` dành riêng cho Android) và dọn dẹp tệp stub.

---

## BUG-0002 - NoSuchMethodError: MappedByteBuffer.duplicate()

**Trạng thái:** FIXED

**Tính năng:** Nạp Font chữ hệ thống (Font Loading)

**Tệp ảnh hưởng:**
- [MainActivity.kt](file:///E:/test/demopdf/app/src/main/java/com/vandatgsts/demopdf/MainActivity.kt)
- [PdfViewerActivity.kt](file:///E:/test/demopdf/app/src/main/java/com/vandatgsts/demopdf/PdfViewerActivity.kt)

**Triệu chứng:**
Crash khi cố gắng nạp font TrueType `/system/fonts/Roboto-Regular.ttf` với lỗi `NoSuchMethodError` cho phương thức `duplicate()Ljava/nio/MappedByteBuffer;`.

**Nguyên nhân:**
OpenPDF 3.x sử dụng Memory Mapped File để đọc tệp font. Phương thức `MappedByteBuffer.duplicate()` trên Java 9+ trả về `MappedByteBuffer` nhưng trên Android runtime chỉ trả về `ByteBuffer`, gây lệch kiểu dữ liệu khi gọi.

**Giải pháp:**
- Ban đầu: Đọc tệp font thành mảng byte `ByteArray` trong bộ nhớ rồi truyền vào `BaseFont.createFont` để bỏ qua cơ chế Memory Mapping.
- Cuối cùng: Chuyển sang thư viện **`iTextG`** tương thích Java 7/8 nguyên bản nên không gặp lỗi lệch kiểu trả về này nữa.

---

## BUG-0003 - NoSuchMethodError: Stream.toList()

**Trạng thái:** FIXED

**Tính năng:** Chèn hình ảnh vào PDF (Image Parsing)

**Tệp ảnh hưởng:**
- [PdfViewerActivity.kt](file:///E:/test/demopdf/app/src/main/java/com/vandatgsts/demopdf/PdfViewerActivity.kt)

**Triệu chứng:**
Crash khi lưu tệp PDF có chứa chữ ký với lỗi `NoSuchMethodError` liên quan đến `Stream.toList()`.

**Nguyên nhân:**
OpenPDF 3.0.5 gọi `Stream.toList()` (Java 16+) để phân tích định dạng ảnh, phương thức này không được hỗ trợ mặc định trên runtime của nhiều thiết bị Android cũ.

**Giải pháp:**
- Ban đầu: Cấu hình `coreLibraryDesugaring` trong `build.gradle.kts` để dịch ngược API.
- Cuối cùng: Chuyển đổi sang thư viện **`iTextG`** (Java 8 target) hoàn toàn không sử dụng các API Java 16+ này.

---

## BUG-0004 - NoClassDefFoundError: javax/imageio/ImageIO

**Trạng thái:** FIXED

**Tính năng:** Giải mã hình ảnh chữ ký PNG (Image I/O)

**Tệp ảnh hưởng:**
- [PdfViewerActivity.kt](file:///E:/test/demopdf/app/src/main/java/com/vandatgsts/demopdf/PdfViewerActivity.kt)

**Triệu chứng:**
Crash khi cố gắng chèn chữ ký PNG vào PDF thông qua `Image.getInstance(byte[])` với lỗi thiếu class `javax.imageio.ImageIO`.

**Nguyên nhân:**
OpenPDF 1.x sử dụng `javax.imageio.ImageIO` của Java SE Desktop để đọc ảnh PNG, gói này không khả dụng trên Android SDK.

**Giải pháp:**
- Ban đầu: Chuyển sang dùng bộ giải mã PNG nội bộ `com.lowagie.text.pdf.codec.PngImage.getImage()`.
- Cuối cùng: Chuyển sang thư viện **`iTextG`** (đã thay thế ImageIO bằng BitmapFactory của Android bên dưới lớp `Image.getInstance()`).

---

## BUG-0005 - Crash khi nhấn nút Lưu trên màn hình xem PDF (iTextG)

**Trạng thái:** FIXED

**Tính năng:** Lưu file PDF đã chỉnh sửa (PDF Saving)

**Tệp ảnh hưởng:**
- [PdfViewerActivity.kt](file:///E:/test/demopdf/app/src/main/java/com/vandatgsts/demopdf/PdfViewerActivity.kt)

**Triệu chứng:**
Khi nhấn nút **"Lưu"** ở màn hình `PdfViewerActivity`, ứng dụng bị crash đột ngột trên một số thiết bị.

**Nguyên nhân:**
1. Tranh chấp tài nguyên và khóa tệp tin (File Lock): `originalFile` (file gốc) vẫn đang bị khóa bởi luồng native của `PdfRenderer` / `ParcelFileDescriptor`. Dù đã gọi close ở tầng Java nhưng GC chưa thu hồi hoặc thiết bị chưa kịp giải phóng, việc mở `FileOutputStream` trực tiếp lên file gốc để ghi đè gây xung đột và sập ứng dụng.
2. Khối `try-catch` trong hàm lưu trước đây chỉ bắt `Exception` mà bỏ sót `Error` (lỗi hệ thống JVM runtime), khiến ứng dụng sập đột ngột nếu phát sinh lỗi lớp hoặc phương thức không tìm thấy thay vì hiện thông báo lỗi.

**Giải pháp:**
1. Chuyển sang cơ chế lưu gián tiếp thông qua tệp tạm trung gian:
   - Đọc từ tệp tạm 1 (`temp_process.pdf` - bản copy của file gốc).
   - Ghi kết quả chỉnh sửa qua `PdfStamper` vào tệp tạm 2 (`temp_output.pdf`).
   - Đóng toàn bộ stamper và reader của iText để đảm bảo giải phóng hoàn toàn các luồng.
   - Thực hiện copy đè tệp tạm 2 vào tệp gốc `originalFile` và xóa các file tạm.
2. Thay đổi kiểu bắt lỗi từ `Exception` sang `Throwable` trong hàm `saveEditsToFile()` để bắt được mọi lỗi runtime (kể cả `Error` hay `NoClassDefFoundError`).

---

## BUG-0006 - Lưu chỉnh sửa PDF thành công nhưng tệp tin gốc ngoài thiết bị không thay đổi

**Trạng thái:** FIXED

**Tính năng:** Đồng bộ tệp PDF đã lưu (PDF Sync & SAF)

**Tệp ảnh hưởng:**
- [MainActivity.kt](file:///E:/test/demopdf/app/src/main/java/com/vandatgsts/demopdf/MainActivity.kt)
- [PdfViewerActivity.kt](file:///E:/test/demopdf/app/src/main/java/com/vandatgsts/demopdf/PdfViewerActivity.kt)

**Triệu chứng:**
Khi người dùng chỉnh sửa tệp PDF và nhấn "Lưu", ứng dụng hiển thị thông báo toast "Đã lưu chỉnh sửa vào PDF thành công!". Tuy nhiên, khi kiểm tra lại tệp tin PDF gốc ở bộ nhớ thiết bị (ví dụ: thư mục Download/Documents) thì không thấy tệp tin thay đổi.

**Nguyên nhân:**
Khi người dùng chọn file PDF thông qua bộ chọn file (`OpenDocument`), `MainActivity` thực hiện sao chép file đó vào thư mục lưu trữ nội bộ của app dưới dạng tệp `filesDir/input.pdf` và chỉ truyền đường dẫn cục bộ này sang `PdfViewerActivity`. Do đó, `PdfViewerActivity` chỉ lưu đè lên tệp cục bộ này mà không đồng bộ/ghi đè ngược lại Uri gốc đã được người dùng chọn.

**Giải pháp:**
1. Truyền thêm Uri gốc (`PDF_FILE_URI`) từ `MainActivity` sang `PdfViewerActivity` thông qua Intent extras.
2. Trong `PdfViewerActivity.kt`, sau khi lưu thành công tệp cục bộ, sử dụng `contentResolver.openOutputStream(uri, "w")` để ghi đè ngược nội dung tệp đã chỉnh sửa sang Uri gốc bên ngoài thiết bị.

**Fixed Files:**
- [MainActivity.kt](file:///E:/test/demopdf/app/src/main/java/com/vandatgsts/demopdf/MainActivity.kt)
- [PdfViewerActivity.kt](file:///E:/test/demopdf/app/src/main/java/com/vandatgsts/demopdf/PdfViewerActivity.kt)

**Verification:**
Thực hiện chỉnh sửa và lưu PDF, tệp gốc bên ngoài thiết bị được cập nhật chính xác các thay đổi.

**Regression Risk:**
Low

---

## BUG-0007 - Crash NullPointerException khi đóng/lưu inline EditText do lỗi đệ quy/race condition

**Trạng thái:** FIXED

**Tính năng:** Chỉnh sửa văn bản trực tiếp (Inline Text Editing)

**Tệp ảnh hưởng:**
- [PdfViewerActivity.kt](file:///E:/test/demopdf/app/src/main/java/com/vandatgsts/demopdf/PdfViewerActivity.kt)

**Triệu chứng:**
Khi người dùng chạm ra ngoài vùng soạn thảo hoặc hoàn tất việc chỉnh sửa chữ trực tiếp trên trang (inline edit), ứng dụng bị crash đột ngột. Logcat báo lỗi: `java.lang.NullPointerException: Attempt to write to field 'android.view.ViewParent android.view.View.mParent' on a null object reference` tại `ViewGroup.removeFromArray`.

**Nguyên nhân:**
Khi người dùng chạm ra ngoài để đóng `EditText`, luồng xử lý touch gọi `closeAndSaveInlineEditText()`. Trong phương thức này, ta gọi `container?.removeView(et)`. Việc xóa `et` khỏi container ngay lập tức kích hoạt sự kiện mất tiêu điểm `OnFocusChangeListener` của chính `et`. Sự kiện này lại đệ quy gọi tiếp `closeAndSaveInlineEditText()` lần thứ hai. Do ở lần gọi thứ nhất, biến trạng thái `activeInlineEditText` vẫn chưa được gán thành `null` (đang chạy dở dòng `removeView(et)`), lần gọi thứ hai vẫn vượt qua dòng check null và thực hiện `container?.removeView(et)` lần nữa trên cùng một View đang bị xóa, dẫn đến crash NullPointerException trong nội bộ ViewGroup của Android.

**Giải pháp:**
Chuyển hai dòng gán null `activeInlineEditText = null` và `activeInlineBlock = null` lên ngay đầu phương thức `closeAndSaveInlineEditText()` (trước khi gọi `removeView(et)` hay ẩn bàn phím ảo). Khi đó, nếu sự kiện mất focus kích hoạt gọi lại đệ quy, lần gọi thứ hai sẽ lập tức thoát (return) vì `activeInlineEditText` đã được gán thành null từ đầu lần gọi thứ nhất.

**Fixed Files:**
- [PdfViewerActivity.kt](file:///E:/test/demopdf/app/src/main/java/com/vandatgsts/demopdf/PdfViewerActivity.kt)

**Verification:**
Thực hiện chỉnh sửa inline và click ra ngoài hoặc bấm Done nhiều lần, ứng dụng hoạt động ổn định và không còn bị crash.

**Regression Risk:**
Low

