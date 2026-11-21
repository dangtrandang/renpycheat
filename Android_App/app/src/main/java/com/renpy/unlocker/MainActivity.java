package com.renpy.unlocker;

import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static final String PATCH_FILENAME = "zzzz_unlock_mod.rpy";
    private static final String PATCH_RPYC = "zzzz_unlock_mod.rpyc";
    private static final String PREFS_NAME = "renpy_unlocker_prefs";
    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai";
    private static final String DEFAULT_MODEL = "gemini-2.5-flash";

    private static final Pattern PERSISTENT_PATTERN = Pattern.compile(
            "^\\s*(?:default|define|\\$)?\\s*(persistent\\.([a-zA-Z0-9_]*(?:gallery|unlock|replay|cg|scene)[a-zA-Z0-9_]*))\\s*=\\s*(False|None|0)\\b",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PERSISTENT_REF_PATTERN = Pattern.compile(
            "persistent\\.([a-zA-Z0-9_]*(?:gallery|unlock|replay|cg|scene|ending|bonus|extra)[a-zA-Z0-9_]*)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern RPYC_UNLOCK_ATTR_PATTERN = Pattern.compile(
            "^[a-zA-Z_][a-zA-Z0-9_]*(?:gallery|unlock|replay|cg|scene|bonus|extra)[a-zA-Z0-9_]*$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern UNLOCK_FUNC_PATTERN = Pattern.compile(
            "\\b(IsPictureUnlocked|is_picture_unlocked|is_scene_unlocked|is_gallery_unlocked|is_cg_unlocked|check_gallery_unlock)\\s*\\("
    );

    private static final Pattern BAD_VI_FONT_PATTERN = Pattern.compile(
            "(adeb|ade1|ade2|ade3|poiretone|tales5|sketchzone|balgruf|alwayslovely|rockybilly)\\.ttf",
            Pattern.CASE_INSENSITIVE
    );

    private enum UnlockVerdict {
        HIGH,
        MEDIUM,
        LOW,
        UNREADABLE
    }

    private ScrollView svRoot;
    private View llExportProgress;
    private TextView tvFolderStatus;
    private TextView tvUnlockVerdict;
    private TextView tvExportStage;
    private LinearProgressIndicator pbExport;
    private TextView tvLog;
    private MaterialSwitch cbGallery, cbFixFont, cbSeenImage, cbConsole, cbSkip;
    private Button btnInject, btnExport, btnRestore, btnAiAnalyze;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private DocumentFile gameDir;
    private Uri selectedApkUri;
    private String selectedApkName = "game";
    private boolean apkUsesXPrefix = false;
    private ScanResult lastScan;
    private String aiExtraCode = "";

    private static class ScanResult {
        final List<String> vars = new ArrayList<>();
        final Set<String> rpycPersistentHints = new LinkedHashSet<>();
        final Set<String> unlockFuncs = new LinkedHashSet<>();
        final Set<String> suspectFonts = new LinkedHashSet<>();
        final StringBuilder priorityAiContext = new StringBuilder();
        final StringBuilder aiContext = new StringBuilder();
        boolean usesSeenImage = false;
        boolean usesSeenLabel = false;
        boolean usesGalleryClass = false;
        boolean hasGalleryScreen = false;
        boolean hasCustomUnlockFunc = false;
        boolean hasVietnamese = false;
        int rpaCount = 0;
        int rpyCount = 0;
        int rpycCount = 0;
        boolean patchExists = false;
        UnlockVerdict verdict = UnlockVerdict.LOW;
        String verdictTitle = "";
        String verdictReason = "";

        String getCombinedAiContext() {
            StringBuilder out = new StringBuilder();
            if (priorityAiContext.length() > 0) {
                out.append(priorityAiContext);
            }
            if (aiContext.length() > 0) {
                if (out.length() > 0) out.append('\n');
                out.append(aiContext);
            }
            return out.toString();
        }
    }

    private final ActivityResultLauncher<Uri> pickFolderLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri != null) {
                    onFolderPicked(uri);
                }
            });

    private final ActivityResultLauncher<String[]> pickApkLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    onApkPicked(uri);
                }
            });

    private final ActivityResultLauncher<String> exportFileLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/octet-stream"), uri -> {
                if (uri != null) {
                    writePatchToUri(uri);
                }
            });

    private final ActivityResultLauncher<String> exportApkLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/vnd.android.package-archive"), uri -> {
                if (uri != null) {
                    performExportPatchedApk(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        svRoot = findViewById(R.id.svRoot);
        llExportProgress = findViewById(R.id.llExportProgress);
        tvFolderStatus = findViewById(R.id.tvFolderStatus);
        tvUnlockVerdict = findViewById(R.id.tvUnlockVerdict);
        tvExportStage = findViewById(R.id.tvExportStage);
        pbExport = findViewById(R.id.pbExport);
        tvLog = findViewById(R.id.tvLog);
        cbGallery = findViewById(R.id.cbGallery);
        cbFixFont = findViewById(R.id.cbFixFont);
        cbSeenImage = findViewById(R.id.cbSeenImage);
        cbConsole = findViewById(R.id.cbConsole);
        cbSkip = findViewById(R.id.cbSkip);

        Button btnPickFolder = findViewById(R.id.btnPickFolder);
        Button btnPickApk = findViewById(R.id.btnPickApk);
        Button btnAiConfig = findViewById(R.id.btnAiConfig);
        btnAiAnalyze = findViewById(R.id.btnAiAnalyze);
        btnInject = findViewById(R.id.btnInject);
        btnExport = findViewById(R.id.btnExport);
        btnRestore = findViewById(R.id.btnRestore);

        btnPickFolder.setOnClickListener(v -> pickFolderLauncher.launch(null));
        btnPickApk.setOnClickListener(v -> pickApkLauncher.launch(new String[]{
                "application/vnd.android.package-archive",
                "application/octet-stream",
                "*/*"
        }));
        btnAiConfig.setOnClickListener(v -> showAiConfigDialog());
        btnAiAnalyze.setOnClickListener(v -> runAiAnalysis());
        btnInject.setOnClickListener(v -> injectPatchIntoGame());
        btnExport.setOnClickListener(v -> exportFileLauncher.launch(PATCH_FILENAME));
        btnRestore.setOnClickListener(v -> restoreMod());
    }

    private void onFolderPicked(Uri treeUri) {
        DocumentFile picked = DocumentFile.fromTreeUri(this, treeUri);
        if (picked == null || !picked.isDirectory()) {
            tvFolderStatus.setText("Không đọc được thư mục đã chọn.");
            return;
        }

        selectedApkUri = null;
        tvLog.setText("Đang tìm thư mục game/ và quét dữ liệu...\n");
        tvUnlockVerdict.setVisibility(View.GONE);
        aiExtraCode = "";
        btnInject.setText("Chép thẳng vào thư mục game");
        btnInject.setEnabled(false);
        btnExport.setEnabled(false);
        btnRestore.setEnabled(false);
        btnAiAnalyze.setEnabled(false);

        executor.execute(() -> {
            DocumentFile foundGame = findGameDir(picked);
            if (foundGame == null) {
                runOnUiThread(() -> {
                    gameDir = null;
                    lastScan = null;
                    tvFolderStatus.setText("Không tìm thấy thư mục game/ trong thư mục đã chọn.");
                    tvUnlockVerdict.setVisibility(View.VISIBLE);
                    tvUnlockVerdict.setText("KHÔNG HỢP LỆ: Không thấy thư mục game/");
                    tvUnlockVerdict.setTextColor(Color.parseColor("#F87171"));
                    appendLog("[LỖI] Hãy chọn thư mục gốc của game (chứa thư mục con 'game/').");
                });
                return;
            }

            ScanResult res = scanGameDir(foundGame);
            runOnUiThread(() -> {
                gameDir = foundGame;
                lastScan = res;
                tvFolderStatus.setText("Đã nhận diện: " + picked.getName() + "/game");
                renderScanResult(res, false);
            });
        });
    }

    private void onApkPicked(Uri apkUri) {
        String displayName = queryDisplayName(apkUri);
        if (displayName == null || displayName.isEmpty()) {
            displayName = "game.apk";
        }
        String finalName = displayName;
        gameDir = null;
        selectedApkUri = apkUri;
        selectedApkName = finalName.replaceAll("(?i)\\.apk$", "");
        apkUsesXPrefix = false;

        tvFolderStatus.setText("Đã chọn APK: " + finalName);
        tvLog.setText("Đang quét assets/ bên trong " + finalName + "...\n");
        tvUnlockVerdict.setVisibility(View.GONE);
        aiExtraCode = "";
        btnInject.setText("Xuất APK đã Mod (đã ký)");
        btnInject.setEnabled(false);
        btnExport.setEnabled(false);
        btnRestore.setEnabled(false);
        btnAiAnalyze.setEnabled(false);

        executor.execute(() -> {
            try {
                ScanResult res = new ScanResult();
                Set<String> seenVars = new LinkedHashSet<>();
                ApkPatcher.ApkScanInfo info = ApkPatcher.scanApk(
                        getContentResolver(),
                        apkUri,
                        getCacheDir(),
                        new ApkPatcher.ScriptCallback() {
                            @Override
                            public void onScript(String logicalName, String text, boolean isRpyc) {
                                scanText(logicalName, text, isRpyc, res, seenVars);
                            }

                            @Override
                            public void onWarning(String msg) {
                                runOnUiThread(() -> appendLog("[CẢNH BÁO] " + msg));
                            }
                        }
                );
                res.rpaCount = info.rpaCount;
                res.rpyCount = info.rpyCount;
                res.rpycCount = info.rpycCount;
                res.hasVietnamese = res.hasVietnamese || info.hasVietnamese;
                res.patchExists = info.patchExists;
                res.vars.addAll(seenVars);
                res.rpycPersistentHints.removeAll(seenVars);
                evaluateVerdict(res);

                runOnUiThread(() -> {
                    if (!info.foundGameAssets) {
                        selectedApkUri = null;
                        lastScan = null;
                        tvUnlockVerdict.setVisibility(View.VISIBLE);
                        tvUnlockVerdict.setText("KHÔNG PHẢI GAME REN'PY: Không thấy assets/game hoặc assets/x-game");
                        tvUnlockVerdict.setTextColor(Color.parseColor("#F87171"));
                        appendLog("[LỖI] Không tìm thấy dữ liệu Ren'Py (assets/x-game/ hoặc assets/game/) trong APK.");
                        return;
                    }
                    apkUsesXPrefix = info.usesXPrefix;
                    lastScan = res;
                    appendLog("[OK] Đã đọc APK (" + (info.usesXPrefix ? "assets/x-game/" : "assets/game/") + ")");
                    renderScanResult(res, true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    selectedApkUri = null;
                    lastScan = null;
                    tvUnlockVerdict.setVisibility(View.VISIBLE);
                    tvUnlockVerdict.setText("LỖI ĐỌC APK");
                    tvUnlockVerdict.setTextColor(Color.parseColor("#F87171"));
                    appendLog("[LỖI] Không đọc được APK: " + e.getMessage());
                });
            }
        });
    }

    private void renderScanResult(ScanResult res, boolean isApkMode) {
        applyVerdictBanner(res);
        if (!isApkMode) {
            appendLog("[OK] Tìm thấy thư mục game/");
        }
        appendLog("• File .rpa: " + res.rpaCount + " | .rpy: " + res.rpyCount + " | .rpyc: " + res.rpycCount);
        if (!res.vars.isEmpty()) {
            appendLog("• Biến unlock (.rpy): " + String.join(", ", res.vars));
        }
        if (!res.rpycPersistentHints.isEmpty()) {
            appendLog("• Biến nghi ngờ (.rpyc/ref): " + String.join(", ", res.rpycPersistentHints));
        }
        if (!res.unlockFuncs.isEmpty()) {
            appendLog("• Hàm kiểm tra unlock: " + String.join(", ", res.unlockFuncs));
        }
        if (res.vars.isEmpty() && res.rpycPersistentHints.isEmpty() && res.unlockFuncs.isEmpty()) {
            appendLog("• Không thấy tên biến persistent.*gallery/unlock cụ thể.");
        }
        if (res.usesSeenImage || res.usesSeenLabel) {
            String seenInfo = (res.usesSeenImage ? "seen_image() " : "")
                    + (res.usesSeenLabel ? "seen_label()" : "");
            appendLog("• Game có dùng: " + seenInfo.trim() + " -> Tự động bật Universal Bypass.");
            cbSeenImage.setChecked(true);
        }
        if (res.usesGalleryClass) {
            appendLog("• Phát hiện hệ thống Ren'Py Gallery().");
        }
        if (res.hasGalleryScreen) {
            appendLog("• Phát hiện màn hình Gallery / Replay.");
        }
        if (res.hasCustomUnlockFunc) {
            appendLog("• Phát hiện hàm/cấu trúc unlock tùy chỉnh.");
        }
        if (res.hasVietnamese && !res.suspectFonts.isEmpty()) {
            appendLog("[FONT] Phát hiện bản Việt hóa dùng font dễ thiếu dấu: "
                    + String.join(", ", res.suspectFonts));
            appendLog("-> Tự động bật 'Sửa lỗi Font tiếng Việt (DejaVuSans)'.");
            cbFixFont.setChecked(true);
        } else if (res.hasVietnamese) {
            appendLog("• Phát hiện bản Việt hóa.");
        }
        appendLog("────────────────────────");
        appendLog("[ĐÁNH GIÁ] " + res.verdictTitle);
        appendLog("→ " + res.verdictReason);
        appendLog(res.patchExists
                ? "• Trạng thái mod: ĐÃ CÀI MOD (" + PATCH_FILENAME + ")"
                : "• Trạng thái mod: Chưa cài mod");

        boolean hasScripts = (res.rpyCount + res.rpycCount) > 0;
        btnInject.setEnabled(true);
        btnExport.setEnabled(true);
        btnAiAnalyze.setEnabled(hasScripts && (res.priorityAiContext.length() > 0 || res.aiContext.length() > 0));
        btnRestore.setEnabled(!isApkMode && res.patchExists);
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private DocumentFile findGameDir(DocumentFile picked) {
        String name = picked.getName();
        if (name != null && name.equalsIgnoreCase("game")) {
            return picked;
        }
        DocumentFile direct = picked.findFile("game");
        if (direct != null && direct.isDirectory()) {
            return direct;
        }
        for (DocumentFile child : picked.listFiles()) {
            if (child.isDirectory()) {
                DocumentFile nested = child.findFile("game");
                if (nested != null && nested.isDirectory()) {
                    return nested;
                }
            }
        }
        return null;
    }

    private ScanResult scanGameDir(DocumentFile dir) {
        ScanResult res = new ScanResult();
        Set<String> seenVars = new LinkedHashSet<>();

        DocumentFile tl = dir.findFile("tl");
        if (tl != null && tl.isDirectory() && tl.findFile("vietnamese") != null) {
            res.hasVietnamese = true;
        }

        scanRecursive(dir, res, seenVars);
        res.vars.addAll(seenVars);
        res.rpycPersistentHints.removeAll(seenVars);
        DocumentFile existing = dir.findFile(PATCH_FILENAME);
        res.patchExists = existing != null && existing.exists();
        evaluateVerdict(res);
        return res;
    }

    private void evaluateVerdict(ScanResult res) {
        if (res.rpyCount == 0 && res.rpycCount == 0) {
            res.verdict = UnlockVerdict.UNREADABLE;
            res.verdictTitle = "KHÔNG UNLOCK ĐƯỢC (Không đọc được script)";
            res.verdictReason = res.rpaCount > 0
                    ? "File .rpa có thể bị mã hóa riêng hoặc không phải chuẩn RPA-2.0/3.0."
                    : "Không tìm thấy file .rpy hay .rpyc nào trong thư mục game/.";
            return;
        }

        if (!res.vars.isEmpty() || !res.unlockFuncs.isEmpty() || res.usesSeenImage || res.usesSeenLabel || res.usesGalleryClass) {
            res.verdict = UnlockVerdict.HIGH;
            res.verdictTitle = "CÓ THỂ UNLOCK (Độ tin cậy cao)";
            List<String> reasons = new ArrayList<>();
            if (!res.vars.isEmpty()) reasons.add("tìm thấy " + res.vars.size() + " biến unlock");
            if (!res.unlockFuncs.isEmpty()) reasons.add("phát hiện hàm " + String.join(", ", res.unlockFuncs));
            if (res.usesSeenImage || res.usesSeenLabel) reasons.add("dùng seen_image/seen_label");
            if (res.usesGalleryClass) reasons.add("dùng chuẩn Gallery()");
            res.verdictReason = "Phát hiện cơ chế rõ ràng: " + String.join(", ", reasons) + ".";
            return;
        }

        if (res.hasGalleryScreen || res.hasCustomUnlockFunc || !res.rpycPersistentHints.isEmpty()) {
            res.verdict = UnlockVerdict.MEDIUM;
            res.verdictTitle = "CÓ THỂ UNLOCK (Mức trung bình - Nên dùng AI)";
            if (res.rpyCount == 0 && res.rpycCount > 0) {
                res.verdictReason = "Game chỉ có file biên dịch (.rpyc), đã thấy dấu hiệu Gallery/Unlock nhưng logic ẩn trong AST. Nên bấm 'Phân tích sâu bằng AI' hoặc thử Inject.";
            } else {
                res.verdictReason = "Thấy màn hình Gallery hoặc hàm unlock riêng, nhưng không theo mẫu biến phổ biến. Nên dùng AI để tạo patch chuẩn hơn.";
            }
            return;
        }

        res.verdict = UnlockVerdict.LOW;
        res.verdictTitle = "KHÔNG CHẮC CHẮN / CÓ THỂ KHÔNG CÓ GALLERY";
        res.verdictReason = "Đã quét " + (res.rpyCount + res.rpycCount)
                + " file script nhưng không thấy dấu hiệu Gallery/Replay/Unlock. Game có thể không có Gallery hoặc khóa theo cốt truyện.";
    }

    private void applyVerdictBanner(ScanResult res) {
        tvUnlockVerdict.setVisibility(View.VISIBLE);
        tvUnlockVerdict.setText(res.verdictTitle);
        switch (res.verdict) {
            case HIGH:
                tvUnlockVerdict.setTextColor(Color.parseColor("#34D399"));
                break;
            case MEDIUM:
                tvUnlockVerdict.setTextColor(Color.parseColor("#FBBF24"));
                break;
            case LOW:
                tvUnlockVerdict.setTextColor(Color.parseColor("#FB923C"));
                break;
            case UNREADABLE:
                tvUnlockVerdict.setTextColor(Color.parseColor("#F87171"));
                break;
        }
    }

    private void scanRecursive(DocumentFile folder, ScanResult res, Set<String> seenVars) {
        for (DocumentFile f : folder.listFiles()) {
            String name = f.getName();
            if (name == null) continue;
            String low = name.toLowerCase();

            if (f.isDirectory()) {
                if (low.equals("tl") || low.equals("cache") || low.equals("saves")) continue;
                scanRecursive(f, res, seenVars);
            } else if (low.endsWith(".rpy") && !name.equals(PATCH_FILENAME)) {
                res.rpyCount++;
                if (low.contains("vi_patch") || low.contains("ui_patch")) {
                    res.hasVietnamese = true;
                }
                String text = readDocumentText(f.getUri());
                if (text != null) {
                    scanText(name, text, false, res, seenVars);
                }
            } else if (low.endsWith(".rpyc") && !name.equals(PATCH_RPYC)) {
                String text = readRpycText(f.getUri());
                if (text != null && !text.isEmpty()) {
                    res.rpycCount++;
                    scanText(name, text, true, res, seenVars);
                }
            } else if (low.endsWith(".rpa")) {
                res.rpaCount++;
                try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(f.getUri(), "r")) {
                    if (pfd != null) {
                        try (FileInputStream fis = new FileInputStream(pfd.getFileDescriptor())) {
                            RpaReader.readScriptEntries(fis.getChannel(), (entryName, text, isRpyc) -> {
                                if (isRpyc) {
                                    res.rpycCount++;
                                } else {
                                    res.rpyCount++;
                                }
                                scanText(entryName, text, isRpyc, res, seenVars);
                            });
                        }
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> appendLog("[CẢNH BÁO] Không đọc được " + name + ": " + e.getMessage()));
                }
            }
        }
    }

    private void scanText(String filename, String text, boolean isRpyc, ScanResult res, Set<String> seenVars) {
        if (text.contains("renpy.seen_image") || text.contains("seen_image")) {
            res.usesSeenImage = true;
        }
        if (text.contains("renpy.seen_label") || text.contains("seen_label")) {
            res.usesSeenLabel = true;
        }
        if (text.contains("vietnamese")) {
            res.hasVietnamese = true;
        }
        String low = filename.toLowerCase();
        String textLow = text.toLowerCase();
        if (textLow.contains("screen gallery") || textLow.contains("cg_gallery")
                || textLow.contains("scene_gallery") || textLow.contains("replay_gallery")
                || low.contains("gallery")) {
            res.hasGalleryScreen = true;
        }
        if (text.contains("Gallery(") || (low.contains("gallery")
                && (text.contains(".button(") || text.contains(".unlock(") || text.contains(".condition(")))) {
            res.usesGalleryClass = true;
        }
        if (textLow.contains("is_unlocked") || textLow.contains("unlock_all")
                || textLow.contains("unlocked_scenes") || textLow.contains("unlocked_cg")
                || textLow.contains("photos_gallery") || textLow.contains("videos_gallery")) {
            res.hasCustomUnlockFunc = true;
        }

        Matcher m = PERSISTENT_PATTERN.matcher(text);
        while (m.find()) {
            seenVars.add(m.group(1));
        }
        Matcher refM = PERSISTENT_REF_PATTERN.matcher(text);
        while (refM.find()) {
            res.rpycPersistentHints.add("persistent." + refM.group(1));
        }
        Matcher fm = BAD_VI_FONT_PATTERN.matcher(text);
        while (fm.find()) {
            res.suspectFonts.add(fm.group(0));
        }
        Matcher ufm = UNLOCK_FUNC_PATTERN.matcher(text);
        while (ufm.find()) {
            res.unlockFuncs.add(ufm.group(1));
            res.hasCustomUnlockFunc = true;
        }
        if (text.contains("photos_gallery") || text.contains("videos_gallery")) {
            seenVars.add("persistent.EmilyApa");
            seenVars.add("persistent.HarperApa");
            seenVars.add("persistent.AmaraApa");
        }

        String[] lines = text.split("\n");
        if (isRpyc) {
            for (int i = 0; i + 1 < lines.length; i++) {
                if ("persistent".equals(lines[i].trim())) {
                    String attr = lines[i + 1].trim();
                    if (RPYC_UNLOCK_ATTR_PATTERN.matcher(attr).matches()) {
                        res.rpycPersistentHints.add("persistent." + attr);
                    }
                }
            }
        }

        boolean isPriorityFile = low.contains("gallery") || low.contains("replay")
                || low.contains("unlock") || low.contains("cheat")
                || low.contains("ui_patch") || low.contains("vi_patch");
        StringBuilder targetBuf = isPriorityFile ? res.priorityAiContext : res.aiContext;
        int maxCap = isPriorityFile ? 18000 : 8000;

        if (targetBuf.length() < maxCap) {
            StringBuilder hits = new StringBuilder();
            int lastEnd = -1;
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i].toLowerCase();
                if (l.contains("gallery") || l.contains("is_unlocked") || l.contains("unlock")
                        || l.contains("replay") || l.contains("seen_image") || l.contains("seen_label")
                        || l.contains("text_font") || l.contains("languages[")) {
                    int start = Math.max(0, i - 3);
                    int end = Math.min(lines.length - 1, i + 10);
                    if (start <= lastEnd) {
                        start = lastEnd + 1;
                    }
                    for (int j = start; j <= end; j++) {
                        hits.append(lines[j]).append('\n');
                    }
                    lastEnd = end;
                    if (hits.length() > 4000) break;
                }
            }
            if (hits.length() > 0) {
                targetBuf.append("\n--- ").append(filename).append(" ---\n");
                int avail = maxCap - targetBuf.length();
                if (avail > 0) {
                    targetBuf.append(hits, 0, Math.min(hits.length(), avail)).append("\n");
                }
            } else if (isPriorityFile) {
                targetBuf.append("\n--- ").append(filename).append(" ---\n");
                int avail = Math.min(maxCap - targetBuf.length(), 2500);
                if (avail > 0) {
                    targetBuf.append(text, 0, Math.min(text.length(), avail)).append("\n");
                }
            }
        }
    }

    private String readRpycText(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                rawOut.write(buf, 0, n);
            }
            return RpaReader.extractRpycStrings(rawOut.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    private String readDocumentText(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String generatePatchCode() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Generated by RenPy Unlock Mod (Android)\n");
        sb.append("init 999 python:\n");
        boolean hasBody = false;

        if (cbGallery.isChecked()) {
            if (lastScan != null && !lastScan.vars.isEmpty()) {
                for (String v : lastScan.vars) {
                    sb.append("    ").append(v).append(" = True\n");
                }
            } else {
                sb.append("    persistent.gallery_unlocked = True\n");
            }
            if (lastScan != null && !lastScan.rpycPersistentHints.isEmpty()) {
                for (String hint : lastScan.rpycPersistentHints) {
                    String attr = hint.startsWith("persistent.") ? hint.substring("persistent.".length()) : hint;
                    sb.append("    try:\n");
                    sb.append("        if not isinstance(getattr(persistent, '").append(attr).append("', None), (dict, list, set)):\n");
                    sb.append("            setattr(persistent, '").append(attr).append("', True)\n");
                    sb.append("    except Exception:\n");
                    sb.append("        pass\n");
                }
            }
            if (lastScan != null && !lastScan.unlockFuncs.isEmpty()) {
                for (String fn : lastScan.unlockFuncs) {
                    sb.append("    if '").append(fn).append("' in globals():\n");
                    sb.append("        ").append(fn).append(" = lambda *args, **kwargs: True\n");
                }
            }
            sb.append("    try:\n");
            sb.append("        if 'sex_scene' in globals():\n");
            sb.append("            sex_scene.is_unlocked = lambda self: True\n");
            sb.append("        for _i in range(100):\n");
            sb.append("            setattr(persistent, f'item_unlocked_{_i}', True)\n");
            sb.append("    except Exception:\n");
            sb.append("        pass\n");
            sb.append("    try:\n");
            sb.append("        for _g in (getattr(persistent, 'photos_gallery', None), getattr(persistent, 'videos_gallery', None)):\n");
            sb.append("            if isinstance(_g, dict):\n");
            sb.append("                for _c, _items in _g.items():\n");
            sb.append("                    for _it in _items:\n");
            sb.append("                        if len(_it) >= 3:\n");
            sb.append("                            setattr(persistent, f'{_it[2]}{_it[1]}', True)\n");
            sb.append("    except Exception:\n");
            sb.append("        pass\n");
            hasBody = true;
        }

        if (cbSeenImage.isChecked()) {
            sb.append("    try:\n");
            sb.append("        renpy.seen_image = lambda *args, **kwargs: True\n");
            sb.append("        renpy.seen_label = lambda *args, **kwargs: True\n");
            sb.append("    except Exception:\n");
            sb.append("        pass\n");
            hasBody = true;
        }

        if (cbConsole.isChecked()) {
            sb.append("    config.developer = True\n");
            sb.append("    config.console = True\n");
            hasBody = true;
        }

        if (cbSkip.isChecked()) {
            sb.append("    config.allow_skipping = True\n");
            sb.append("    preferences.skip_unseen = True\n");
            hasBody = true;
        }

        if (!hasBody) {
            sb.append("    pass\n");
        }

        if (cbFixFont.isChecked()) {
            sb.append("\ninit 1005 python:\n");
            sb.append("    try:\n");
            sb.append("        if 'languages' in globals() and 'vietnamese' in languages:\n");
            sb.append("            languages['vietnamese']['dialogue'] = 'DejaVuSans.ttf'\n");
            sb.append("            languages['vietnamese']['fallback'] = 'DejaVuSans.ttf'\n");
            sb.append("            if 'swap' in languages['vietnamese']:\n");
            sb.append("                for _k in list(languages['vietnamese']['swap'].keys()):\n");
            sb.append("                    languages['vietnamese']['swap'][_k] = 'DejaVuSans.ttf'\n");
            sb.append("            if 'convert' in languages['vietnamese']:\n");
            sb.append("                for _k in list(languages['vietnamese']['convert'].keys()):\n");
            sb.append("                    languages['vietnamese']['convert'][_k] = 'DejaVuSans.ttf'\n");
            sb.append("    except Exception:\n");
            sb.append("        pass\n");
            sb.append("\ntranslate vietnamese python:\n");
            sb.append("    gui.text_font = \"DejaVuSans.ttf\"\n");
            sb.append("    gui.interface_text_font = \"DejaVuSans.ttf\"\n");
            sb.append("    gui.button_text_font = \"DejaVuSans.ttf\"\n");
            sb.append("    gui.choice_button_text_font = \"DejaVuSans.ttf\"\n");
        }

        if (!aiExtraCode.isEmpty()) {
            sb.append("\n# --- AI Suggested Code ---\n");
            sb.append(aiExtraCode).append("\n");
        }

        return sb.toString();
    }

    private static String normalizeBaseUrl(String raw) {
        String u = raw == null ? "" : raw.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        if (u.endsWith("/chat/completions")) {
            u = u.substring(0, u.length() - "/chat/completions".length());
        }
        return u;
    }

    private void showAiConfigDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_ai_config, null);

        TextInputEditText etUrl = view.findViewById(R.id.etApiUrl);
        TextInputEditText etKey = view.findViewById(R.id.etApiKey);
        AutoCompleteTextView actvModel = view.findViewById(R.id.actvModel);
        Button btnFetchModels = view.findViewById(R.id.btnFetchModels);
        TextView tvModelStatus = view.findViewById(R.id.tvModelStatus);

        etUrl.setText(prefs.getString("api_url", DEFAULT_BASE_URL));
        etKey.setText(prefs.getString("api_key", ""));
        actvModel.setText(prefs.getString("api_model", DEFAULT_MODEL), false);

        String savedModels = prefs.getString("cached_models", "");
        if (!savedModels.isEmpty()) {
            String[] items = savedModels.split("\n");
            actvModel.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, items));
        }

        btnFetchModels.setOnClickListener(v -> {
            String baseUrl = normalizeBaseUrl(etUrl.getText() != null ? etUrl.getText().toString() : "");
            String apiKey = etKey.getText() != null ? etKey.getText().toString().trim() : "";
            if (baseUrl.isEmpty() || apiKey.isEmpty()) {
                tvModelStatus.setText("Vui lòng nhập đủ Base URL và API Key.");
                return;
            }
            btnFetchModels.setEnabled(false);
            tvModelStatus.setText("Đang tải danh sách model từ " + baseUrl + "/models ...");

            executor.execute(() -> {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + "/models").openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(12000);
                    conn.setReadTimeout(15000);
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey);

                    int code = conn.getResponseCode();
                    if (code == 404 && !baseUrl.endsWith("/v1") && !baseUrl.endsWith("/openai")) {
                        conn.disconnect();
                        String altUrl = baseUrl + "/v1";
                        conn = (HttpURLConnection) new URL(altUrl + "/models").openConnection();
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(12000);
                        conn.setReadTimeout(15000);
                        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                        code = conn.getResponseCode();
                    }
                    InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                    StringBuilder resp = new StringBuilder();
                    if (is != null) {
                        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = br.readLine()) != null) resp.append(line);
                        }
                    }
                    if (code < 200 || code >= 300) {
                        throw new RuntimeException("HTTP " + code + ": " + resp);
                    }

                    JSONObject json = new JSONObject(resp.toString());
                    JSONArray data = json.optJSONArray("data");
                    List<String> models = new ArrayList<>();
                    if (data != null) {
                        for (int i = 0; i < data.length(); i++) {
                            String id = data.getJSONObject(i).optString("id", "");
                            if (!id.isEmpty()) {
                                // Gemini trả về dạng "models/gemini-2.5-flash" -> giữ gọn
                                if (id.startsWith("models/")) {
                                    id = id.substring("models/".length());
                                }
                                models.add(id);
                            }
                        }
                    }
                    Collections.sort(models);
                    if (models.isEmpty()) {
                        throw new RuntimeException("API không trả về model nào.");
                    }

                    prefs.edit().putString("cached_models", String.join("\n", models)).apply();

                    runOnUiThread(() -> {
                        btnFetchModels.setEnabled(true);
                        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                                this, android.R.layout.simple_dropdown_item_1line, models);
                        actvModel.setAdapter(adapter);
                        String current = actvModel.getText().toString().trim();
                        if (current.isEmpty() || !models.contains(current)) {
                            actvModel.setText(models.get(0), false);
                        }
                        actvModel.showDropDown();
                        tvModelStatus.setText("Đã tải thành công " + models.size() + " model!");
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        btnFetchModels.setEnabled(true);
                        tvModelStatus.setText("Lỗi tải model: " + e.getMessage());
                    });
                }
            });
        });

        new MaterialAlertDialogBuilder(this)
                .setTitle("Cài đặt LLM API")
                .setView(view)
                .setPositiveButton("Lưu", (d, w) -> {
                    String url = normalizeBaseUrl(etUrl.getText() != null ? etUrl.getText().toString() : DEFAULT_BASE_URL);
                    String key = etKey.getText() != null ? etKey.getText().toString().trim() : "";
                    String model = actvModel.getText().toString().trim();
                    if (model.isEmpty()) model = DEFAULT_MODEL;
                    prefs.edit()
                            .putString("api_url", url)
                            .putString("api_model", model)
                            .putString("api_key", key)
                            .apply();
                    Toast.makeText(this, "Đã lưu cấu hình AI (" + model + ")", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    private void runAiAnalysis() {
        if (lastScan == null) return;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String baseUrl = normalizeBaseUrl(prefs.getString("api_url", DEFAULT_BASE_URL));
        String model = prefs.getString("api_model", DEFAULT_MODEL);
        String apiKey = prefs.getString("api_key", "");

        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Hãy nhập API Key trong 'Cài đặt AI' trước!", Toast.LENGTH_LONG).show();
            showAiConfigDialog();
            return;
        }

        appendLog("\n[AI] Đang gửi đoạn script liên quan lên " + model + "...");
        btnAiAnalyze.setEnabled(false);

        executor.execute(() -> {
            try {
                String prompt = "You are a Ren'Py mod expert. Analyze these game script excerpts (including .rpyc AST strings) and return ONLY valid JSON with keys:\n"
                        + "- \"can_unlock\": boolean (true if a gallery/replay/CG unlock mechanism exists and can be unlocked or bypassed, false otherwise).\n"
                        + "- \"summary\": short Vietnamese explanation of how gallery unlock works in this game, whether it can be unlocked, and any Vietnamese font issues.\n"
                        + "- \"extra_rpy\": valid Ren'Py script block (or empty string) to unlock the gallery/replays or fix fonts. "
                        + "If you override Python functions (e.g. `IsPictureUnlocked = lambda *args, **kwargs: True`) or set `persistent.*` variables, "
                        + "MUST wrap them in `init 999 python:` with 4-space indentation so Ren'Py loads it cleanly.\n\n"
                        + "Detected vars: " + lastScan.vars + "\n"
                        + "Detected unlock funcs: " + lastScan.unlockFuncs + "\n"
                        + "RPYC persistent hints: " + lastScan.rpycPersistentHints + "\n"
                        + "Suspect fonts: " + lastScan.suspectFonts + "\n"
                        + "Excerpts:\n" + lastScan.getCombinedAiContext();

                JSONObject body = new JSONObject();
                body.put("model", model);
                JSONArray msgs = new JSONArray();
                msgs.put(new JSONObject().put("role", "user").put("content", prompt));
                body.put("messages", msgs);

                HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + "/chat/completions").openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                StringBuilder resp = new StringBuilder();
                if (is != null) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) resp.append(line);
                    }
                }

                if (code < 200 || code >= 300) {
                    throw new RuntimeException("HTTP " + code + ": " + resp);
                }

                JSONObject json = new JSONObject(resp.toString());
                String content = json.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim();

                if (content.startsWith("```")) {
                    int nl = content.indexOf('\n');
                    if (nl > 0) content = content.substring(nl + 1);
                    if (content.endsWith("```")) content = content.substring(0, content.length() - 3);
                    content = content.trim();
                }

                int firstBrace = content.indexOf('{');
                int lastBrace = content.lastIndexOf('}');
                if (firstBrace >= 0 && lastBrace > firstBrace) {
                    content = content.substring(firstBrace, lastBrace + 1);
                }

                JSONObject parsed = new JSONObject(content);
                boolean canUnlock = parsed.optBoolean("can_unlock", true);
                String summary = parsed.optString("summary", content);
                String extra = normalizeAiRpy(parsed.optString("extra_rpy", "").trim());

                runOnUiThread(() -> {
                    btnAiAnalyze.setEnabled(true);
                    appendLog("[AI KẾT QUẢ] (" + (canUnlock ? "CÓ THỂ UNLOCK" : "KHÔNG KHẢ DỤNG") + ")\n" + summary);
                    if (!extra.isEmpty()) {
                        aiExtraCode = extra;
                        appendLog("[AI] Đã nạp code patch tự động:\n" + extra);
                        appendLog("-> Hãy bấm 'Chép thẳng vào thư mục game' để áp dụng.");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnAiAnalyze.setEnabled(true);
                    appendLog("[AI LỖI] " + e.getMessage());
                });
            }
        });
    }

    private static String normalizeAiRpy(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String trimmed = raw.trim();
        String firstLine = trimmed.split("\n", 2)[0].trim().toLowerCase();
        if (firstLine.startsWith("init ") || firstLine.startsWith("translate ")
                || firstLine.startsWith("define ") || firstLine.startsWith("default ")
                || firstLine.startsWith("python ") || firstLine.startsWith("label ")
                || firstLine.startsWith("screen ")) {
            return trimmed;
        }
        StringBuilder wrapped = new StringBuilder("init 999 python:\n");
        for (String line : trimmed.split("\n")) {
            if (line.trim().isEmpty()) continue;
            wrapped.append("    ").append(line).append('\n');
        }
        return wrapped.toString().trim();
    }

    private void injectPatchIntoGame() {
        if (gameDir == null && selectedApkUri == null) return;
        if (lastScan != null
                && (lastScan.verdict == UnlockVerdict.LOW || lastScan.verdict == UnlockVerdict.UNREADABLE)
                && cbGallery.isChecked()
                && aiExtraCode.isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Cảnh báo khả năng Unlock")
                    .setMessage(lastScan.verdictTitle + "\n\n" + lastScan.verdictReason
                            + "\n\nBạn vẫn muốn tiếp tục áp dụng mod?")
                    .setPositiveButton("Vẫn tiếp tục", (d, w) -> proceedInjectOrExportApk())
                    .setNegativeButton("Hủy", null)
                    .show();
            return;
        }
        proceedInjectOrExportApk();
    }

    private void proceedInjectOrExportApk() {
        if (selectedApkUri != null) {
            exportApkLauncher.launch(selectedApkName + "_unlocked.apk");
        } else {
            performInjectPatch();
        }
    }

    private void performExportPatchedApk(Uri destApkUri) {
        if (selectedApkUri == null) return;
        String code = generatePatchCode();
        llExportProgress.setVisibility(View.VISIBLE);
        pbExport.setProgressCompat(0, false);
        tvExportStage.setText("Đang chuẩn bị xuất APK...");
        tvExportStage.setTextColor(Color.parseColor("#34D399"));
        appendLog("\n[APK] Bắt đầu chèn mod và ký lại APK...");
        btnInject.setEnabled(false);

        executor.execute(() -> {
            try {
                ApkPatcher.patchAndSignApk(
                        this,
                        selectedApkUri,
                        destApkUri,
                        code,
                        apkUsesXPrefix,
                        new ApkPatcher.PatchProgressCallback() {
                            @Override
                            public void onProgress(int percent, String stage) {
                                runOnUiThread(() -> {
                                    pbExport.setProgressCompat(percent, true);
                                    tvExportStage.setText(stage);
                                });
                            }

                            @Override
                            public void onLog(String line) {
                                runOnUiThread(() -> appendLog(line));
                            }
                        }
                );
                runOnUiThread(() -> {
                    btnInject.setEnabled(true);
                    pbExport.setProgressCompat(100, true);
                    tvExportStage.setText("Hoàn tất xuất APK: " + selectedApkName + "_unlocked.apk");
                    appendLog("[THÀNH CÔNG] Đã xuất APK mod đã ký: " + selectedApkName + "_unlocked.apk");
                    appendLog("Lưu ý: Gỡ bản APK gốc (nếu đã cài) rồi cài file APK mod mới này.");
                    Toast.makeText(this, "Xuất APK mod thành công!", Toast.LENGTH_LONG).show();
                });
            } catch (Throwable t) {
                Log.e("RenPyUnlocker", "patchAndSignApk failed", t);
                runOnUiThread(() -> {
                    btnInject.setEnabled(true);
                    tvExportStage.setText("Lỗi xuất APK!");
                    tvExportStage.setTextColor(Color.parseColor("#F87171"));
                    appendLog("[LỖI APK] " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    Toast.makeText(this, "Lỗi patch APK: " + t.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void performInjectPatch() {
        if (gameDir == null) return;
        String code = generatePatchCode();

        executor.execute(() -> {
            try {
                DocumentFile old = gameDir.findFile(PATCH_FILENAME);
                if (old != null && old.exists()) {
                    old.delete();
                }
                DocumentFile created = gameDir.createFile("application/octet-stream", PATCH_FILENAME);
                if (created == null) {
                    runOnUiThread(() -> Toast.makeText(this, "Không tạo được file trong game/", Toast.LENGTH_LONG).show());
                    return;
                }
                if (!PATCH_FILENAME.equals(created.getName())) {
                    created.renameTo(PATCH_FILENAME);
                }
                try (OutputStream os = getContentResolver().openOutputStream(created.getUri(), "wt")) {
                    if (os != null) {
                        os.write(code.getBytes(StandardCharsets.UTF_8));
                    }
                }
                runOnUiThread(() -> {
                    btnRestore.setEnabled(true);
                    appendLog("\n[THÀNH CÔNG] Đã chép " + PATCH_FILENAME + " thẳng vào game/!");
                    appendLog("Hãy mở JoiPlay và vào game để kiểm tra.");
                    Toast.makeText(this, "Đã áp dụng xong!", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    appendLog("[LỖI] " + e.getMessage());
                    Toast.makeText(this, "Lỗi ghi file: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void writePatchToUri(Uri uri) {
        String code = generatePatchCode();
        executor.execute(() -> {
            try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                if (os != null) {
                    os.write(code.getBytes(StandardCharsets.UTF_8));
                }
                runOnUiThread(() -> {
                    appendLog("\n[XUẤT FILE] Đã lưu " + PATCH_FILENAME + " thành công!");
                    Toast.makeText(this, "Đã xuất file unlock!", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> appendLog("[LỖI XUẤT FILE] " + e.getMessage()));
            }
        });
    }

    private void restoreMod() {
        if (gameDir == null) return;
        executor.execute(() -> {
            int deleted = 0;
            for (String fn : new String[]{PATCH_FILENAME, PATCH_RPYC}) {
                DocumentFile f = gameDir.findFile(fn);
                if (f != null && f.exists() && f.delete()) {
                    deleted++;
                }
            }
            int count = deleted;
            runOnUiThread(() -> {
                btnRestore.setEnabled(false);
                appendLog(count > 0
                        ? "\n[ĐÃ GỠ MOD] Đã xóa " + count + " file mod."
                        : "\n[THÔNG BÁO] Không có file mod nào để xóa.");
            });
        });
    }

    private void appendLog(String msg) {
        tvLog.append(msg + "\n");
        if (svRoot != null) {
            svRoot.post(() -> svRoot.fullScroll(View.FOCUS_DOWN));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
