package com.example.qrcode.demo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/** Persistent directory preferences for the desktop transfer tool. */
public final class TransferAppSettings {
    private static final String CONFIG_DIR = ".qrcode-forwin";
    private static final String CONFIG_FILE = "settings.properties";

    private String qrOutputDirectory;
    private String recoveryDirectory;
    private String videoWorkDirectory;
    private int playbackDwellMillis = 200;

    private TransferAppSettings() {
        String home = System.getProperty("user.home", ".");
        qrOutputDirectory = Paths.get(home, "QRCodeTransfer", "qr_codes").toString();
        recoveryDirectory = Paths.get(home, "QRCodeTransfer", "recovered").toString();
        videoWorkDirectory = Paths.get(home, "QRCodeTransfer", "video_work").toString();
    }

    public static TransferAppSettings load() {
        TransferAppSettings settings = new TransferAppSettings();
        Path file = settingsFile();
        if (Files.isRegularFile(file)) {
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(file)) {
                properties.load(input);
                settings.qrOutputDirectory = properties.getProperty("qrOutputDirectory", settings.qrOutputDirectory);
                settings.recoveryDirectory = properties.getProperty("recoveryDirectory", settings.recoveryDirectory);
                settings.videoWorkDirectory = properties.getProperty("videoWorkDirectory", settings.videoWorkDirectory);
                settings.playbackDwellMillis = parseDwellMillis(
                        properties.getProperty("playbackDwellMillis"), settings.playbackDwellMillis);
            } catch (IOException ignored) {
                // Keep defaults if the preference file is unreadable.
            }
        }
        return settings;
    }

    public void save() throws IOException {
        Path file = settingsFile();
        Files.createDirectories(file.getParent());
        Properties properties = new Properties();
        properties.setProperty("qrOutputDirectory", qrOutputDirectory);
        properties.setProperty("recoveryDirectory", recoveryDirectory);
        properties.setProperty("videoWorkDirectory", videoWorkDirectory);
        properties.setProperty("playbackDwellMillis", Integer.toString(playbackDwellMillis));
        try (OutputStream output = Files.newOutputStream(file)) {
            properties.store(output, "QR Code File Transfer settings");
        }
    }

    private static Path settingsFile() {
        return Paths.get(System.getProperty("user.home", "."), CONFIG_DIR, CONFIG_FILE);
    }

    public String getQrOutputDirectory() { return qrOutputDirectory; }
    public void setQrOutputDirectory(String value) { qrOutputDirectory = value; }
    public String getRecoveryDirectory() { return recoveryDirectory; }
    public void setRecoveryDirectory(String value) { recoveryDirectory = value; }
    public String getVideoWorkDirectory() { return videoWorkDirectory; }
    public void setVideoWorkDirectory(String value) { videoWorkDirectory = value; }
    public int getPlaybackDwellMillis() { return playbackDwellMillis; }
    public void setPlaybackDwellMillis(int value) {
        if (value < 200 || value > 10000) {
            throw new IllegalArgumentException("每张图片停留时间需在 200 到 10000 毫秒之间。");
        }
        playbackDwellMillis = value;
    }

    private static int parseDwellMillis(String value, int fallback) {
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed >= 200 && parsed <= 10000 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
