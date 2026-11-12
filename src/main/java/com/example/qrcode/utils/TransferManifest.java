package com.example.qrcode.utils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Base64;

/** Optional v2 control record carried in a QR alongside unchanged legacy data pages. */
public final class TransferManifest {
    public static final String PREFIX = "QRF2|";

    public final int totalPages;
    public final long fileSize;
    public final String sha256;
    public final String originalFileName;

    public TransferManifest(int totalPages, long fileSize, String sha256) {
        this(totalPages, fileSize, sha256, null);
    }

    public TransferManifest(int totalPages, long fileSize, String sha256, String originalFileName) {
        if (totalPages <= 0 || totalPages > 10000 || fileSize < 0 || sha256 == null
                || !sha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("传输清单字段无效");
        }
        if (originalFileName != null && (originalFileName.trim().isEmpty() || originalFileName.length() > 255
                || originalFileName.contains("/") || originalFileName.contains("\\")
                || originalFileName.equals(".") || originalFileName.equals(".."))) {
            throw new IllegalArgumentException("传输清单文件名无效");
        }
        this.totalPages = totalPages;
        this.fileSize = fileSize;
        this.sha256 = sha256.toLowerCase(Locale.ROOT);
        this.originalFileName = originalFileName;
    }

    public String toPayload() {
        String payload = PREFIX + totalPages + "|" + fileSize + "|" + sha256;
        if (originalFileName != null) {
            payload += "|" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(originalFileName.getBytes(StandardCharsets.UTF_8));
        }
        return payload;
    }

    public static TransferManifest parse(String payload) {
        if (payload == null || !payload.startsWith(PREFIX)) {
            return null;
        }
        String[] fields = payload.split("\\|");
        if ((fields.length != 4 && fields.length != 5) || !"QRF2".equals(fields[0])) {
            throw new IllegalArgumentException("传输清单格式无效");
        }
        try {
            String originalFileName = fields.length == 5
                    ? new String(Base64.getUrlDecoder().decode(fields[4]), StandardCharsets.UTF_8) : null;
            return new TransferManifest(Integer.parseInt(fields[1]), Long.parseLong(fields[2]), fields[3], originalFileName);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("传输清单数字字段无效", ex);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("传输清单文件名编码无效", ex);
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 不支持 SHA-256", impossible);
        }
    }

    public boolean matches(byte[] bytes) {
        return bytes != null && bytes.length == fileSize && sha256.equals(sha256(bytes));
    }
}
