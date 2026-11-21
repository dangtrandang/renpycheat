# Ren'Py Unlock Mod (Android / JoiPlay & APK)

Ứng dụng Android `v2.0` hỗ trợ quét, đánh giá khả năng mở khóa và tạo mod mở khóa Gallery / Replay / Console / Skip / sửa lỗi font tiếng Việt cho cả **game Ren'Py chạy trên JoiPlay** lẫn **game đóng gói file `.apk`** ngay trên điện thoại.

## Tính năng chính

- **Hỗ trợ 2 chế độ nguồn game**:
  - **Thư mục (JoiPlay)**: Chọn thư mục gốc của game, chỉ tạo thêm `zzzz_unlock_mod.rpy` trong `game/` mà không sửa file gốc. Hỗ trợ **Gỡ Mod** sạch sẽ.
  - **File APK Game**: Chọn trực tiếp file `.apk` của game Ren'Py (`assets/x-game/` hoặc `assets/game/`), quét, chèn mod (`x-zzzz_unlock_mod.rpy` hoặc `zzzz_unlock_mod.rpy`) và **ký lại APK chuẩn v1 + v2** bằng `apksig` để cài đặt ngay không cần Root.
- **Thanh tiến trình & Nhật ký trực tiếp khi xuất APK**:
  - Hiển thị tiến độ 3 bước (Đóng gói → Ký số `apksig` → Ghi file) kèm phần trăm, số file và dung lượng MB đang xử lý, cùng log chi tiết theo thời gian thực ở phía dưới.
- **Quét đa định dạng không cần thư viện game có sẵn**:
  - Đọc trực tiếp file `.rpy`, `.rpyc` (giải nén AST `RENPY RPC2`) và các file `.rpy`/`.rpyc` nằm bên trong gói `.rpa` (`RPA-2.0` / `RPA-3.0`) qua `RpaReader` mà không cần giải nén toàn bộ archive.
  - Tự động nhận diện biến `persistent.*`, hàm kiểm tra unlock (`IsPictureUnlocked`, `is_picture_unlocked`, `is_scene_unlocked`, `is_gallery_unlocked`, `is_cg_unlocked`, `check_gallery_unlock`), `Gallery()`, `renpy.seen_image()`, `renpy.seen_label()`.
- **Đánh giá rõ khả năng Unlock ngay sau khi quét**:
  - Phân loại theo 4 mức: **Độ tin cậy cao**, **Mức trung bình (Nên dùng AI)**, **Không chắc chắn / Có thể không có Gallery**, và **Không đọc được script** kèm cảnh báo trước khi áp dụng mod.
- **Phân tích sâu bằng AI (LLM API)**:
  - Hỗ trợ cấu hình Base URL, API Key và tải danh sách model theo chuẩn OpenAI API (mặc định trỏ tới Gemini OpenAI-compatible endpoint).
  - Ưu tiên trích ngữ cảnh từ các file liên quan tới `gallery`, `replay`, `unlock`, `cheat`, `ui_patch`, `vi_patch` để phân tích logic khóa và tự sinh đoạn patch `init 999 python:`.
- **Sửa lỗi Font tiếng Việt & Tiện ích**:
  - Tự phát hiện bản Việt hóa dùng font dễ thiếu dấu và bật sửa sang `DejaVuSans.ttf`.
  - Tùy chọn bật Developer Mode & Console (`config.developer`, `config.console`) và Skip thoại chưa đọc.

## Cấu trúc dự án

- `Android_App/`: Mã nguồn ứng dụng Android (Java, `minSdk 26`, `targetSdk 35`).
  - `app/src/main/java/com/renpy/unlocker/MainActivity.java`: Giao diện chính, bộ quét script, đánh giá khả năng unlock, gọi LLM API và điều phối chép mod / xuất APK.
  - `app/src/main/java/com/renpy/unlocker/RpaReader.java`: Bộ đọc index `RPA-2.0`/`RPA-3.0`, giải mã `MiniPickle` và trích chuỗi từ `.rpyc`.
  - `app/src/main/java/com/renpy/unlocker/ApkPatcher.java`: Bộ quét `assets/` trong `.apk`, stream đóng gói lại không tràn RAM và ký APK bằng `com.android.tools.build:apksig`.
  - `app/src/main/assets/mod_key.p12`: Keystore PKCS#12 dùng để ký lại APK mod.
- `RenPyUnlockMod.apk`: File APK debug `v2.0` build sẵn.

## Hướng dẫn sử dụng

### Với game JoiPlay (thư mục)
1. Bấm **Thư mục (JoiPlay)** → chọn thư mục gốc của game (chứa `game/`).
2. Xem đánh giá (có thể bấm **Phân tích sâu bằng AI (LLM)** nếu game lạ).
3. Bấm **Chép thẳng vào thư mục game** → mở JoiPlay kiểm tra.

### Với game APK
1. Bấm **File APK Game** → chọn file `.apk` của game.
2. Xem đánh giá (có thể bấm **Phân tích sâu bằng AI (LLM)** nếu cần).
3. Bấm **Xuất APK đã Mod (đã ký)** và chờ thanh tiến trình chạy xong 100%.
4. Gỡ bản APK gốc (nếu đã cài) rồi cài file `*_unlocked.apk` vừa xuất.

## Build từ mã nguồn

Yêu cầu JDK 17 và Android SDK (API 35):

```bash
gradle -p Android_App assembleDebug
```

File APK đầu ra nằm tại `Android_App/app/build/outputs/apk/debug/app-debug.apk`.
