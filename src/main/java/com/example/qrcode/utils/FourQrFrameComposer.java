package com.example.qrcode.utils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Creates a 2x2 playback frame without changing the legacy QR payloads. */
public final class FourQrFrameComposer {
    public static final int CODE_SIZE = 500;
    public static final int FRAME_SIZE = 1080;
    public static final int MIN_DWELL_MILLIS = 200;
    private static final int OUTER_MARGIN = 20;
    private static final int GUTTER = 40;
    private static final String OUTPUT_PREFIX = "four_qr_frame_";
    private static final Pattern PAGE_NUMBER = Pattern.compile("qr_code_(\\d+)\\.(png|jpe?g)", Pattern.CASE_INSENSITIVE);

    public interface ProgressListener {
        void onProgress(int completedFrames, int totalFrames);
    }

    private FourQrFrameComposer() { }

    /** Encodes source bytes straight into four-QR frames without persisting single-QR pages. */
    public static Path composeFile(Path sourceFile, Path outputDir, ProgressListener listener) throws IOException {
        if (!Files.isRegularFile(sourceFile)) {
            throw new IOException("所选路径不是普通文件：" + sourceFile);
        }
        long fileSize = Files.size(sourceFile);
        if (fileSize > FileConvertUtil.MAX_LEGACY_FILE_BYTES) {
            throw new IOException("当前二维码页格式最多支持 7 MiB，文件大小为 " + fileSize + " 字节。");
        }
        String fileName = sourceFile.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String extension = dot >= 0 ? fileName.substring(dot + 1) : "";
        if (extension.length() > 10) {
            throw new IOException("文件扩展名超过协议支持长度（10 个字符）：" + extension);
        }
        byte[] bytes = Files.readAllBytes(sourceFile);
        String contents = String.format(Locale.ROOT, "%10s", extension) + Base64.getEncoder().encodeToString(bytes);
        int dataPageCount = (contents.length() + PageCollector.SEGMENT_DATA_SIZE - 1)
                / PageCollector.SEGMENT_DATA_SIZE;
        if (dataPageCount <= 0 || dataPageCount > 10000) {
            throw new IOException("二维码页数超出协议范围：" + dataPageCount);
        }
        TransferManifest manifest = new TransferManifest(dataPageCount, bytes.length,
                TransferManifest.sha256(bytes), fileName);

        Files.createDirectories(outputDir);
        clearOldFrames(outputDir);
        int frameCount = 1 + (dataPageCount + 3) / 4;
        int frameIndex = 0;
        BufferedImage control = QRCodeUtil.encodeToImage(manifest.toPayload());
        String firstPayload = pagePayload(contents, 0);
        BufferedImage first = QRCodeUtil.encodeToImage(firstPayload);
        writeFrame(outputDir, frameIndex++, composeFrame(java.util.Arrays.asList(control, first, first, first)));
        if (listener != null) listener.onProgress(frameIndex, frameCount);

        for (int firstPage = 0; firstPage < dataPageCount; firstPage += 4) {
            List<BufferedImage> codes = new ArrayList<>(4);
            int end = Math.min(firstPage + 4, dataPageCount);
            for (int page = firstPage; page < end; page++) {
                codes.add(QRCodeUtil.encodeToImage(pagePayload(contents, page)));
            }
            writeFrame(outputDir, frameIndex++, composeFrame(codes));
            if (listener != null) listener.onProgress(frameIndex, frameCount);
        }
        return outputDir;
    }

    private static String pagePayload(String contents, int page) {
        int start = page * PageCollector.SEGMENT_DATA_SIZE;
        int end = Math.min(start + PageCollector.SEGMENT_DATA_SIZE, contents.length());
        return String.format(Locale.ROOT, "%4d", page) + contents.substring(start, end);
    }

    public static BufferedImage composeFrame(List<BufferedImage> codes) {
        if (codes == null || codes.isEmpty() || codes.size() > 4) {
            throw new IllegalArgumentException("每帧需要 1 到 4 张二维码图片");
        }
        BufferedImage frame = new BufferedImage(FRAME_SIZE, FRAME_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = frame.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, FRAME_SIZE, FRAME_SIZE);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            for (int i = 0; i < codes.size(); i++) {
                BufferedImage code = codes.get(i);
                if (code == null) {
                    continue;
                }
                int x = OUTER_MARGIN + (i % 2) * (CODE_SIZE + GUTTER);
                int y = OUTER_MARGIN + (i / 2) * (CODE_SIZE + GUTTER);
                g.drawImage(code, x, y, CODE_SIZE, CODE_SIZE, null);
            }
        } finally {
            g.dispose();
        }
        drawLocatorMarks(frame);
        return frame;
    }

    public static Path composeFolder(String inputDir, String outputDir, ProgressListener listener) throws IOException {
        File source = new File(inputDir);
        File[] listed = source.listFiles(file -> file.isFile() && isImage(file) && PAGE_NUMBER.matcher(file.getName()).matches());
        if (listed == null || listed.length == 0) {
            throw new IOException("二维码目录中没有 qr_code_数字.png 图片：" + inputDir);
        }
        List<File> images = new ArrayList<>();
        java.util.Collections.addAll(images, listed);
        images.sort(Comparator.comparingLong(FourQrFrameComposer::pageNumber));

        TransferManifest manifest = null;
        Path manifestPath = Paths.get(inputDir).resolve("transfer_manifest.txt");
        if (!Files.isRegularFile(manifestPath)) {
            throw new IOException("四码模式需要新版发送端生成的传输清单；请用当前版本重新生成二维码图片：" + manifestPath);
        }
        try {
            manifest = TransferManifest.parse(new String(Files.readAllBytes(manifestPath), java.nio.charset.StandardCharsets.UTF_8).trim());
        } catch (IllegalArgumentException ex) {
            throw new IOException("传输清单无效：" + manifestPath, ex);
        }
        if (manifest == null || manifest.totalPages != images.size()) {
            throw new IOException("传输清单页数与二维码图片数量不一致：" + manifestPath);
        }

        Path destination = Paths.get(outputDir);
        Files.createDirectories(destination);
        clearOldFrames(destination);

        int dataFrameCount = (images.size() + 3) / 4;
        int frameCount = dataFrameCount + (manifest == null ? 0 : 1);
        int frameIndex = 0;
        if (manifest != null) {
            Path manifestQr = Files.createTempFile("qrcode-transfer-manifest-", ".png");
            try {
                QRCodeUtil.encode(manifest.toPayload(), manifestQr.toString());
                BufferedImage control = ImageIO.read(manifestQr.toFile());
                BufferedImage first = ImageIO.read(images.get(0));
                if (control == null || first == null) {
                    throw new IOException("无法读取文件清单码或第 0 页二维码");
                }
                BufferedImage frame = composeFrame(java.util.Arrays.asList(control, first, first, first));
                writeFrame(destination, frameIndex, frame);
                frameIndex++;
                if (listener != null) {
                    listener.onProgress(frameIndex, frameCount);
                }
            } finally {
                Files.deleteIfExists(manifestQr);
            }
        }

        for (int dataFrame = 0; dataFrame < dataFrameCount; dataFrame++) {
            List<BufferedImage> codes = new ArrayList<>(4);
            int start = dataFrame * 4;
            int end = Math.min(start + 4, images.size());
            for (int i = start; i < end; i++) {
                BufferedImage code = ImageIO.read(images.get(i));
                if (code == null) {
                    throw new IOException("无法读取二维码图片：" + images.get(i).getAbsolutePath());
                }
                codes.add(code);
            }
            BufferedImage frame = composeFrame(codes);
            writeFrame(destination, frameIndex, frame);
            frameIndex++;
            if (listener != null) {
                listener.onProgress(frameIndex, frameCount);
            }
        }
        return destination;
    }

    private static void writeFrame(Path destination, int frameIndex, BufferedImage frame) throws IOException {
        Path output = destination.resolve(String.format(Locale.ROOT, OUTPUT_PREFIX + "%06d.png", frameIndex));
        if (!ImageIO.write(frame, "png", output.toFile())) {
            throw new IOException("无法写出四码帧：" + output);
        }
    }

    private static void clearOldFrames(Path destination) throws IOException {
        File[] oldFrames = destination.toFile().listFiles(file -> file.isFile()
                && file.getName().startsWith(OUTPUT_PREFIX)
                && file.getName().toLowerCase(Locale.ROOT).endsWith(".png"));
        if (oldFrames != null) {
            for (File old : oldFrames) Files.deleteIfExists(old.toPath());
        }
    }

    private static boolean isImage(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg");
    }

    private static long pageNumber(File file) {
        Matcher matcher = PAGE_NUMBER.matcher(file.getName());
        return matcher.matches() ? Long.parseLong(matcher.group(1)) : Long.MAX_VALUE;
    }

    /** High-contrast corner marks make the 2x2 layout visually obvious in captures. */
    private static void drawLocatorMarks(BufferedImage frame) {
        int size = 16;
        drawMark(frame, 2, 2, size, 1);
        drawMark(frame, FRAME_SIZE - size - 2, 2, size, 2);
        drawMark(frame, 2, FRAME_SIZE - size - 2, size, 3);
        drawMark(frame, FRAME_SIZE - size - 2, FRAME_SIZE - size - 2, size, 4);
    }

    private static void drawMark(BufferedImage frame, int x, int y, int size, int orientation) {
        Graphics2D g = frame.createGraphics();
        try {
            g.setColor(Color.BLACK);
            g.fillRect(x, y, size, size);
            g.setColor(Color.WHITE);
            g.fillRect(x + 3, y + 3, size - 6, size - 6);
            g.setColor(Color.BLACK);
            g.fillRect(x + 6, y + 6, size - 12, size - 12);
            int dotX = (orientation & 1) == 0 ? x + 2 : x + size - 5;
            int dotY = (orientation & 2) == 0 ? y + 2 : y + size - 5;
            g.fillRect(dotX, dotY, 3, 3);
        } finally {
            g.dispose();
        }
    }
}
