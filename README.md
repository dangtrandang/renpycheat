# Ren'Py Unlock Mod (Android / JoiPlay)

Ứng dụng Android hỗ trợ quét, đánh giá khả năng mở khóa và tạo mod mở khóa Gallery / Replay / Console / Skip / sửa lỗi font tiếng Việt cho các game Ren'Py chạy trên JoiPlay mà **không cần sửa file gốc** của game.

## Tính năng chính

- **Quét đa định dạng không cần thư viện game có sẵn**:
  - Đọc trực tiếp file `.rpy`, `.rpyc` (giải nén AST `RENPY RPC2`) và các file `.rpy`/`.rpyc` nằm bên trong gói `.rpa` (hỗ trợ `RPA-2.0` và `RPA-3.0` qua `RpaReader` mà không cần giải nén toàn bộ archive).
  - Tự động nhận diện biến `persistent.*`, hàm kiểm tra unlock (`IsPictureUnlocked`, `is_picture_unlocked`, `is_scene_unlocked`, `is_gallery_unlocked`, `is_cg_unlocked`, `check_gallery_unlock`), `Gallery()`, `renpy.seen_image()`, `renpy.seen_label()`.
- **Đánh giá rõ khả năng Unlock ngay sau khi quét**:
  - Phân loại theo 4 mức: **Độ tin cậy cao**, **Mức trung bình (Nên dùng AI)**, **Không chắc chắn / Có thể không có Gallery**, và **Không đọc được script**.
  - Cảnh báo trước khi chép mod nếu không phát hiện cơ chế Gallery/Unlock.
- **Phân tích sâu bằng AI (LLM API)**:
  - Hỗ trợ cấu hình Base URL, API Key và tải danh sách model theo chuẩn OpenAI API (mặc định trỏ tới Gemini OpenAI-compatible endpoint).
  - Ưu tiên trích ngữ cảnh từ các file liên quan tới `gallery`, `replay`, `unlock`, `cheat`, `ui_patch`, `vi_patch` để phân tích logic khóa và tự sinh đoạn patch `init 999 python:`.
- **Sửa lỗi Font tiếng Việt & Tiện ích**:
  - Tự phát hiện bản Việt hóa dùng font dễ thiếu dấu và bật sửa sang `DejaVuSans.ttf`.
  - Tùy chọn bật Developer Mode & Console (`config.developer`, `config.console`) và Skip thoại chưa đọc.
- **An toàn, dễ gỡ**:
  - Chỉ tạo file riêng `zzzz_unlock_mod.rpy` trong thư mục `game/`. Bấm **Gỡ Mod** để xóa `zzzz_unlock_mod.rpy` và `zzzz_unlock_mod.rpyc` về nguyên bản.

## Cấu trúc dự án

- `Android_App/`: Mã nguồn ứng dụng Android (Java, `minSdk 26`, `targetSdk 35`).
  - `app/src/main/java/com/renpy/unlocker/MainActivity.java`: Giao diện chính, bộ quét script, đánh giá khả năng unlock, gọi LLM API và sinh/chép file mod.
  - `app/src/main/java/com/renpy/unlocker/RpaReader.java`: Bộ đọc index `RPA-2.0`/`RPA-3.0`, giải mã `MiniPickle` và trích chuỗi từ `.rpyc`.
- `RenPyUnlockMod.apk`: File APK debug build sẵn.

## Hướng dẫn sử dụng

1. Cài đặt `RenPyUnlockMod.apk` lên thiết bị Android.
2. Bấm **Chọn thư mục game** và trỏ tới thư mục gốc của game Ren'Py (thư mục chứa `game/`).
3. Xem kết quả quét & đánh giá khả năng unlock:
   - Nếu ở mức **Độ tin cậy cao**, bấm **Chép thẳng vào thư mục game**.
   - Nếu game dùng cơ chế lạ hoặc chỉ có `.rpyc`, vào **Cài đặt AI** nhập API Key rồi bấm **Phân tích sâu bằng AI (LLM)** trước khi chép mod.
4. Mở JoiPlay và vào lại game để kiểm tra.

## Build từ mã nguồn

Yêu cầu JDK 17 và Android SDK (API 35):

```bash
gradle -p Android_App assembleDebug
```

File APK đầu ra nằm tại `Android_App/app/build/outputs/apk/debug/app-debug.apk`.
