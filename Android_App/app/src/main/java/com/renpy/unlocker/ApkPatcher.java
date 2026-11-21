package com.renpy.unlocker;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.android.apksig.ApkSigner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Quét script Ren'Py bên trong file .apk (assets/x-game/ hoặc assets/game/),
 * chèn mod và ký lại APK bằng Google apksig.
 */
public class ApkPatcher {

    public interface ScriptCallback {
        void onScript(String logicalName, String text, boolean isRpyc);
        void onWarning(String msg);
    }

    public interface PatchProgressCallback {
        void onProgress(int percent, String stage);
        void onLog(String line);
    }

    public static class ApkScanInfo {
        public boolean foundGameAssets = false;
        public boolean usesXPrefix = false;
        public boolean hasVietnamese = false;
        public boolean patchExists = false;
        public int rpaCount = 0;
        public int rpyCount = 0;
        public int rpycCount = 0;
    }

    public static ApkScanInfo scanApk(ContentResolver cr, Uri apkUri, File cacheDir, ScriptCallback cb) throws IOException {
        ApkScanInfo info = new ApkScanInfo();
        try (InputStream rawIn = cr.openInputStream(apkUri)) {
            if (rawIn == null) throw new IOException("Không mở được file APK.");
            try (ZipInputStream zis = new ZipInputStream(rawIn)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        zis.closeEntry();
                        continue;
                    }
                    String name = entry.getName().replace('\\', '/');
                    String logical = extractLogicalGamePath(name);
                    if (logical == null) {
                        zis.closeEntry();
                        continue;
                    }

                    info.foundGameAssets = true;
                    if (name.startsWith("assets/x-game/")) {
                        info.usesXPrefix = true;
                    }

                    String low = logical.toLowerCase();
                    if (low.startsWith("tl/vietnamese/") || low.contains("vi_patch") || low.contains("ui_patch")) {
                        info.hasVietnamese = true;
                    }
                    if (low.equals("zzzz_unlock_mod.rpy")) {
                        info.patchExists = true;
                        zis.closeEntry();
                        continue;
                    }
                    if (low.equals("zzzz_unlock_mod.rpyc") || low.startsWith("tl/")
                            || low.startsWith("cache/") || low.startsWith("saves/")) {
                        zis.closeEntry();
                        continue;
                    }

                    if (low.endsWith(".rpy")) {
                        info.rpyCount++;
                        byte[] bytes = readEntryBytes(zis);
                        String text = new String(bytes, StandardCharsets.UTF_8);
                        cb.onScript(logical, text, false);
                    } else if (low.endsWith(".rpyc")) {
                        byte[] bytes = readEntryBytes(zis);
                        String text = RpaReader.extractRpycStrings(bytes);
                        if (text != null && !text.isEmpty()) {
                            info.rpycCount++;
                            cb.onScript(logical, text, true);
                        }
                    } else if (low.endsWith(".rpa")) {
                        info.rpaCount++;
                        File tmpRpa = File.createTempFile("scan_rpa_", ".rpa", cacheDir);
                        try {
                            try (FileOutputStream fos = new FileOutputStream(tmpRpa)) {
                                copyStream(zis, fos);
                            }
                            try (FileInputStream fis = new FileInputStream(tmpRpa)) {
                                RpaReader.readScriptEntries(fis.getChannel(), (entryName, text, isRpyc) -> {
                                    if (isRpyc) {
                                        info.rpycCount++;
                                    } else {
                                        info.rpyCount++;
                                    }
                                    cb.onScript(entryName, text, isRpyc);
                                });
                            }
                        } catch (Exception e) {
                            cb.onWarning("Không đọc được " + logical + " trong APK: " + e.getMessage());
                        } finally {
                            //noinspection ResultOfMethodCallIgnored
                            tmpRpa.delete();
                        }
                    }
                    zis.closeEntry();
                }
            }
        }
        return info;
    }

    public static void patchAndSignApk(
            Context ctx,
            Uri srcApkUri,
            Uri destApkUri,
            String modCode,
            boolean usesXPrefix,
            PatchProgressCallback progressCb
    ) throws Exception {
        File cacheDir = ctx.getCacheDir();
        File unsignedApk = new File(cacheDir, "renpy_mod_unsigned.apk");
        File signedApk = new File(cacheDir, "renpy_mod_signed.apk");

        String modEntryPath = usesXPrefix
                ? "assets/x-game/x-zzzz_unlock_mod.rpy"
                : "assets/game/zzzz_unlock_mod.rpy";

        long totalSrcBytes = queryUriSize(ctx.getContentResolver(), srcApkUri);
        long processedBytes = 0L;
        int entryCount = 0;
        long lastUiMs = 0L;

        try {
            if (progressCb != null) {
                progressCb.onProgress(2, "Bước 1/3: Đang đọc và đóng gói APK...");
                progressCb.onLog("[APK] Bắt đầu đóng gói lại APK"
                        + (totalSrcBytes > 0 ? " (" + formatMb(totalSrcBytes) + " MB)" : "") + "...");
            }
            try (InputStream rawIn = ctx.getContentResolver().openInputStream(srcApkUri)) {
                if (rawIn == null) throw new IOException("Không mở được APK nguồn.");
                try (ZipInputStream zis = new ZipInputStream(rawIn);
                     ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(unsignedApk))) {
                    zos.setLevel(1);

                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        String name = entry.getName().replace('\\', '/');
                        if (isSignatureEntry(name) || isOldModEntry(name)) {
                            zis.closeEntry();
                            continue;
                        }

                        entryCount++;
                        if (entry.isDirectory()) {
                            ZipEntry dirEntry = new ZipEntry(name);
                            zos.putNextEntry(dirEntry);
                            zos.closeEntry();
                            zis.closeEntry();
                            continue;
                        }

                        long entryBytes;
                        if (entry.getMethod() == ZipEntry.STORED
                                && entry.getSize() >= 0
                                && entry.getCrc() >= 0) {
                            ZipEntry outEntry = new ZipEntry(name);
                            outEntry.setMethod(ZipEntry.STORED);
                            outEntry.setSize(entry.getSize());
                            outEntry.setCompressedSize(entry.getSize());
                            outEntry.setCrc(entry.getCrc());
                            zos.putNextEntry(outEntry);
                            entryBytes = copyStreamCount(zis, zos);
                            zos.closeEntry();
                        } else {
                            ZipEntry outEntry = new ZipEntry(name);
                            outEntry.setMethod(ZipEntry.DEFLATED);
                            zos.putNextEntry(outEntry);
                            entryBytes = copyStreamCount(zis, zos);
                            zos.closeEntry();
                        }
                        zis.closeEntry();

                        long compSize = entry.getCompressedSize();
                        processedBytes += (compSize > 0 ? compSize : entryBytes);

                        long now = System.currentTimeMillis();
                        boolean isLarge = entryBytes >= 5 * 1024 * 1024L;
                        if (progressCb != null && (isLarge || now - lastUiMs >= 350)) {
                            lastUiMs = now;
                            int pct = totalSrcBytes > 0
                                    ? Math.min(68, 2 + (int) ((processedBytes * 66L) / totalSrcBytes))
                                    : Math.min(68, 5 + (entryCount / 40));
                            String stage = "Bước 1/3: Đóng gói APK • " + pct + "% ("
                                    + entryCount + " file • " + formatMb(processedBytes)
                                    + (totalSrcBytes > 0 ? "/" + formatMb(totalSrcBytes) + " MB)" : " MB)");
                            progressCb.onProgress(pct, stage);
                            if (isLarge) {
                                progressCb.onLog("[APK] Đã xử lý file lớn: " + name + " (" + formatMb(entryBytes) + " MB)");
                            } else if (entryCount % 250 == 0) {
                                progressCb.onLog("[APK] Đã đóng gói " + entryCount + " file (" + formatMb(processedBytes) + " MB)...");
                            }
                        }
                    }

                    ZipEntry modEntry = new ZipEntry(modEntryPath);
                    modEntry.setMethod(ZipEntry.DEFLATED);
                    zos.putNextEntry(modEntry);
                    zos.write(modCode.getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                    if (progressCb != null) {
                        progressCb.onProgress(70, "Bước 1/3 hoàn tất (" + entryCount + " file).");
                        progressCb.onLog("[APK] Đã chèn mod: " + modEntryPath);
                    }
                }
            }

            if (progressCb != null) {
                progressCb.onProgress(74, "Bước 2/3: Đang ký chữ ký số APK (v1 + v2)...");
                progressCb.onLog("[APK] Đang ký lại APK bằng apksig...");
            }

            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream ksIn = ctx.getAssets().open("mod_key.p12")) {
                ks.load(ksIn, "renpymod".toCharArray());
            }
            PrivateKey privateKey = (PrivateKey) ks.getKey("modkey", "renpymod".toCharArray());
            X509Certificate cert = (X509Certificate) ks.getCertificate("modkey");

            ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                    "modkey",
                    privateKey,
                    Collections.singletonList(cert)
            ).build();

            ApkSigner signer = new ApkSigner.Builder(Collections.singletonList(signerConfig))
                    .setInputApk(unsignedApk)
                    .setOutputApk(signedApk)
                    .setV1SigningEnabled(true)
                    .setV2SigningEnabled(true)
                    .setMinSdkVersion(21)
                    .build();
            signer.sign();

            long signedTotal = signedApk.length();
            if (progressCb != null) {
                progressCb.onProgress(90, "Bước 3/3: Đang ghi file ra bộ nhớ (" + formatMb(signedTotal) + " MB)...");
                progressCb.onLog("[APK] Ký thành công! Đang xuất " + formatMb(signedTotal) + " MB ra file đích...");
            }

            try (FileInputStream fis = new FileInputStream(signedApk);
                 OutputStream out = ctx.getContentResolver().openOutputStream(destApkUri, "wt")) {
                if (out == null) throw new IOException("Không ghi được file APK đầu ra.");
                byte[] buf = new byte[65536];
                long written = 0L;
                int n;
                lastUiMs = 0L;
                while ((n = fis.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    written += n;
                    long now = System.currentTimeMillis();
                    if (progressCb != null && signedTotal > 0 && now - lastUiMs >= 250) {
                        lastUiMs = now;
                        int pct = 90 + (int) ((written * 10L) / signedTotal);
                        progressCb.onProgress(Math.min(99, pct),
                                "Bước 3/3: Đang ghi file (" + formatMb(written) + "/" + formatMb(signedTotal) + " MB)...");
                    }
                }
                out.flush();
            }
            if (progressCb != null) {
                progressCb.onProgress(100, "Hoàn tất xuất APK (" + formatMb(signedTotal) + " MB)!");
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            unsignedApk.delete();
            //noinspection ResultOfMethodCallIgnored
            signedApk.delete();
        }
    }

    private static long queryUriSize(ContentResolver cr, Uri uri) {
        try (android.database.Cursor c = cr.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getLong(0);
            }
        } catch (Exception ignored) {
        }
        try (AssetFileDescriptor afd = cr.openAssetFileDescriptor(uri, "r")) {
            if (afd != null && afd.getLength() > 0) {
                return afd.getLength();
            }
        } catch (Exception ignored) {
        }
        return -1L;
    }

    private static String formatMb(long bytes) {
        return String.format(java.util.Locale.US, "%.1f", bytes / (1024.0 * 1024.0));
    }

    private static long copyStreamCount(InputStream is, OutputStream os) throws IOException {
        byte[] buf = new byte[16384];
        long total = 0L;
        int n;
        while ((n = is.read(buf)) > 0) {
            os.write(buf, 0, n);
            total += n;
        }
        return total;
    }

    private static String extractLogicalGamePath(String zipPath) {
        if (zipPath.startsWith("assets/x-game/")) {
            return stripXPrefix(zipPath.substring("assets/x-game/".length()));
        }
        if (zipPath.startsWith("assets/game/")) {
            return zipPath.substring("assets/game/".length());
        }
        return null;
    }

    private static String stripXPrefix(String relPath) {
        String[] parts = relPath.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.startsWith("x-") && p.length() > 2) {
                p = p.substring(2);
            }
            if (i > 0) sb.append('/');
            sb.append(p);
        }
        return sb.toString();
    }

    private static boolean isSignatureEntry(String name) {
        String up = name.toUpperCase();
        return up.startsWith("META-INF/") && (up.endsWith(".SF") || up.endsWith(".RSA")
                || up.endsWith(".DSA") || up.endsWith(".EC") || up.equals("META-INF/MANIFEST.MF"));
    }

    private static boolean isOldModEntry(String name) {
        String low = name.toLowerCase();
        return low.equals("assets/x-game/x-zzzz_unlock_mod.rpy")
                || low.equals("assets/x-game/x-zzzz_unlock_mod.rpyc")
                || low.equals("assets/game/zzzz_unlock_mod.rpy")
                || low.equals("assets/game/zzzz_unlock_mod.rpyc");
    }

    private static byte[] readEntryBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        copyStream(is, baos);
        return baos.toByteArray();
    }

    private static void copyStream(InputStream is, OutputStream os) throws IOException {
        byte[] buf = new byte[16384];
        int n;
        while ((n = is.read(buf)) > 0) {
            os.write(buf, 0, n);
        }
    }
}
