package com.example.qrcode.utils;

import com.google.zxing.NotFoundException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.awt.Rectangle;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Paths;

public class QRCodeUtil {

    /** 一页二维码的有效内容长度下限 */
    private static final int MIN_VALID_LENGTH = 20;

    public static BufferedImage encodeToImage(String contents) throws IOException {
        try {
            return QRCodeUtilForZxing.encodeToImage(contents);
        } catch (Exception zxingError) {
            System.err.println("ZXing 内存二维码生成失败，改用 BoofCV，原因：" + zxingError.getMessage());
            try {
                return QRCodeUtilForBoofcv.encodeToImage(contents);
            } catch (Exception boofcvError) {
                boofcvError.addSuppressed(zxingError);
                throw new IOException("二维码图像生成失败", boofcvError);
            }
        }
    }

    /**
     * 文本生成二维码文件
     * @param contents
     * @param filePath  需要生成二维码图片完整地址  默认是png图片
     * @throws IOException
     */
    public static void encode(String contents,String filePath) throws IOException {
        java.nio.file.Path output = Paths.get(filePath).toAbsolutePath();
        java.nio.file.Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try {
            QRCodeUtilForZxing.encode(contents,filePath);
        } catch (Exception zxingError) {
            System.err.println("ZXing 生成二维码失败，改用 BoofCV：" + filePath + "，原因：" + zxingError.getMessage());
            try {
                QRCodeUtilForBoofcv.encode(contents,filePath);
            } catch (Exception boofcvError) {
                boofcvError.addSuppressed(zxingError);
                if (boofcvError instanceof IOException) {
                    throw (IOException) boofcvError;
                }
                throw new IOException("二维码图片生成失败：" + filePath, boofcvError);
            }
        }
    }

    /**
     * 解码一帧中的一个或多个二维码。
     * 单码图仍使用旧解码逻辑；检测到多个二维码时先按检测区域裁剪，再逐张调用旧解码器。
     */
    public static List<String> decodeAll(BufferedImage image) {
        if (image == null) {
            return java.util.Collections.emptyList();
        }
        List<QRCodeUtilForBoofcv.QrRegion> regions = QRCodeUtilForBoofcv.findQrRegions(image);
        if (regions.size() <= 1) {
            String text = decode(image);
            if ((text == null || text.isEmpty()) && !regions.isEmpty()) {
                text = regions.get(0).text;
            }
            if (text == null || text.isEmpty()) {
                return java.util.Collections.emptyList();
            }
            return java.util.Collections.singletonList(text);
        }

        List<String> results = new ArrayList<>(regions.size());
        List<QRCodeUtilForBoofcv.QrRegion> ordered = new ArrayList<>(regions);
        ordered.sort(Comparator.comparingInt((QRCodeUtilForBoofcv.QrRegion r) -> r.bounds.y)
                .thenComparingInt(r -> r.bounds.x));
        for (QRCodeUtilForBoofcv.QrRegion region : ordered) {
            Rectangle bounds = expandAndClip(region.bounds, image.getWidth(), image.getHeight());
            String decoded = null;
            if (bounds.width > 0 && bounds.height > 0) {
                BufferedImage crop = copyCrop(image, bounds);
                decoded = decode(crop);
            }
            // BoofCV 已逐个定位并解出内容时，保留该结果作为单码解码器的兜底。
            if ((decoded == null || decoded.isEmpty()) && region.text != null && !region.text.isEmpty()) {
                decoded = region.text;
            }
            if (decoded != null && !decoded.isEmpty()) {
                results.add(decoded);
            }
        }
        return results;
    }

    /**
     * 多码画面的强解补充：对已定位出的每个二维码区域分别运行完整解码链。
     * 普通 decodeAll 即使读出同一画面的其它二维码，也可能有一个区域没解出；
     * 因此补采时不能只在整张图完全没有结果时才重试。
     */
    public static List<String> decodeAllHeavy(BufferedImage image) {
        if (image == null) {
            return java.util.Collections.emptyList();
        }
        List<QRCodeUtilForBoofcv.QrRegion> regions = QRCodeUtilForBoofcv.findQrRegions(image);
        if (regions.size() <= 1) {
            Outcome outcome = decodeDetailed(image, true, true);
            if (outcome.text != null && !outcome.text.isEmpty()) {
                return java.util.Collections.singletonList(outcome.text);
            }
            if (!regions.isEmpty() && regions.get(0).text != null && !regions.get(0).text.isEmpty()) {
                return java.util.Collections.singletonList(regions.get(0).text);
            }
            return java.util.Collections.emptyList();
        }

        List<QRCodeUtilForBoofcv.QrRegion> ordered = new ArrayList<>(regions);
        ordered.sort(Comparator.comparingInt((QRCodeUtilForBoofcv.QrRegion r) -> r.bounds.y)
                .thenComparingInt(r -> r.bounds.x));
        List<String> results = new ArrayList<>(ordered.size());
        for (QRCodeUtilForBoofcv.QrRegion region : ordered) {
            Rectangle bounds = expandAndClip(region.bounds, image.getWidth(), image.getHeight());
            String decoded = null;
            if (bounds.width > 0 && bounds.height > 0) {
                decoded = decodeDetailed(copyCrop(image, bounds), true, true).text;
            }
            if ((decoded == null || decoded.isEmpty()) && region.text != null && !region.text.isEmpty()) {
                decoded = region.text;
            }
            if (decoded != null && !decoded.isEmpty() && !results.contains(decoded)) {
                results.add(decoded);
            }
        }
        return results;
    }

    /**
     * Four-code playback fallback: split a 16:9 video frame into overlapping quadrants,
     * then run the legacy single-QR decoder on each crop. The overlap protects codes that
     * sit near the split lines and duplicate results are removed by their full payload.
     */
    public static List<String> decodeFourQuadrants(BufferedImage image) {
        if (image == null) return java.util.Collections.emptyList();
        int midX = image.getWidth() / 2;
        int midY = image.getHeight() / 2;
        int marginX = Math.max(8, image.getWidth() / 24);
        int marginY = Math.max(8, image.getHeight() / 24);
        int[] xs = {0, Math.max(0, midX - marginX), 0, Math.max(0, midX - marginX)};
        int[] ys = {0, 0, Math.max(0, midY - marginY), Math.max(0, midY - marginY)};
        int[] x2s = {Math.min(image.getWidth(), midX + marginX), image.getWidth(),
                Math.min(image.getWidth(), midX + marginX), image.getWidth()};
        int[] y2s = {Math.min(image.getHeight(), midY + marginY), Math.min(image.getHeight(), midY + marginY),
                image.getHeight(), image.getHeight()};
        List<String> results = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            Rectangle bounds = new Rectangle(xs[i], ys[i], x2s[i] - xs[i], y2s[i] - ys[i]);
            String text = decode(copyCrop(image, bounds));
            if (text != null && !text.isEmpty() && !results.contains(text)) {
                results.add(text);
            }
        }
        return results;
    }

    private static BufferedImage copyCrop(BufferedImage source, Rectangle bounds) {
        int type = source.getType() == BufferedImage.TYPE_BYTE_GRAY
                ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_3BYTE_BGR;
        BufferedImage copy = new BufferedImage(bounds.width, bounds.height, type);
        Graphics2D graphics = copy.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, bounds.width, bounds.height,
                    bounds.x, bounds.y, bounds.x + bounds.width, bounds.y + bounds.height, null);
        } finally {
            graphics.dispose();
        }
        return copy;
    }

    public static List<String> decodeAll(String filePath) throws IOException {
        BufferedImage image = ImageIO.read(new File(filePath));
        return decodeAll(image);
    }

    private static Rectangle expandAndClip(Rectangle source, int width, int height) {
        int margin = Math.max(8, (int) Math.ceil(Math.max(source.width, source.height) * 0.04));
        int x1 = Math.max(0, source.x - margin);
        int y1 = Math.max(0, source.y - margin);
        int x2 = Math.min(width, source.x + source.width + margin);
        int y2 = Math.min(height, source.y + source.height + margin);
        return new Rectangle(x1, y1, x2 - x1, y2 - y1);
    }

    /**
     * 解析QRCode二维码（文件方式）
     * @param filePath  需要解析二维码图片完整地址
     */
    public static String decode(String filePath ) throws IOException {
        BufferedImage image = ImageIO.read(new File(filePath));
        if (image == null) {
            return null;
        }
        return decode(image);
    }

    /**
     * 解析QRCode二维码（直接从内存图片解码，视频场景不再落盘读写）
     * 识别顺序按实测耗时/成功率排：Boofcv(51ms,80%) → Zxing(65ms,49%) → Opencv(249ms,78%)
     * 前两个都是毫秒级，Opencv 只在前两个都失败时才走到，避免每张废帧都跑最重的引擎
     */
    public static String decode(BufferedImage image) {
        return decode(image, true);
    }

    /**
     * 解析QRCode二维码（直接从内存图片解码，视频场景不再落盘读写）
     *
     * @param stablePage 该画面是否为"停留稳定"的页面。
     *                   视频里翻页过渡帧占了一半左右，这些帧跑完整识别链几乎都是失败，
     *                   而 OpenCV 微信引擎单帧要 650ms、Zxing 也要 200ms，代价很高。
     *                   因此非稳定画面只用 Boofcv（约 65ms）快速试一次，
     *                   除非确实检测到了二维码区域（说明是真正的二维码页，只是没解出来）。
     */
    public static String decode(BufferedImage image, boolean stablePage) {
        return decodeDetailed(image, stablePage, false).text;
    }

    /**
     * 识别结果明细：解出的文本 + Boofcv 是否在画面里找到二维码区域
     *
     * detections 大于 0 而 text 为空，说明"确实有二维码、但没解出来"，
     * 这种画面值得用更重的引擎（OpenCV）再试一次；detections 为 0 的多是翻页过渡帧，重试没有意义。
     */
    public static class Outcome {
        public final String text;
        public final int detections;

        Outcome(String text, int detections) {
            this.text = text;
            this.detections = detections;
        }
    }

    /**
     * 解析QRCode二维码（直接从内存图片解码，视频场景不再落盘读写）
     *
     * @param stablePage 该画面是否为"停留稳定"的页面。
     *                   视频里翻页过渡帧占了一半左右，这些帧跑完整识别链几乎都是失败，
     *                   而 OpenCV 微信引擎单帧要 650ms、Zxing 也要 200ms，代价很高。
     *                   因此非稳定画面只用 Boofcv（约 65ms）快速试一次，
     *                   除非确实检测到了二维码区域（说明是真正的二维码页，只是没解出来）。
     * @param forceHeavy 无条件使用最重的 OpenCV 引擎。用于"已确认是真正的二维码页、
     *                   但轻量引擎全都解不出"的兜底重试，以及缺页定向补采。
     */
    public static Outcome decodeDetailed(BufferedImage image, boolean stablePage, boolean forceHeavy) {
        if (image == null) {
            return new Outcome(null, 0);
        }
        int detectionCount = 0;
        String res = null;
        try {
            QRCodeUtilForBoofcv.DetectResult detectResult = QRCodeUtilForBoofcv.detect(image);
            detectionCount = detectResult.detectionCount;
            res = detectResult.message;
            if (isValid(res)) {
                return new Outcome(res, detectionCount);
            }
        } catch (Exception e) {
            // 单个引擎异常不影响后续降级识别
            System.err.println("Boofcv解析二维码异常: " + e.getMessage());
        }
        // 画面里没有二维码特征，且不是停留页：判定为翻页过渡帧，直接放弃
        if (detectionCount <= 0 && !stablePage && !forceHeavy) {
            return new Outcome(null, detectionCount);
        }
        try {
            res = QRCodeUtilForZxing.decode(image);
            if (isValid(res)) {
                return new Outcome(res, detectionCount);
            }
        } catch (Exception e) {
            System.err.println("Zxing解析二维码异常: " + e.getMessage());
        }
        // OpenCV 微信引擎最重，只有确实检测到二维码区域（或被要求强制）时才值得调用
        if (detectionCount <= 0 && !forceHeavy) {
            return new Outcome(null, detectionCount);
        }
        try {
            res = QRCodeUtilForOpencv.decode(image);
            if (isValid(res)) {
                return new Outcome(res, detectionCount);
            }
        } catch (Exception e) {
            System.err.println("Opencv解析二维码异常: " + e.getMessage());
        }
        return new Outcome(null, detectionCount);
    }

    private static boolean isValid(String res) {
        return res != null && res.length() >= MIN_VALID_LENGTH;
    }


    /**
     * 解析QRCode二维码
     *  先使用 Opencv 然后 Boofcv 然后Zxing
     * @param filePath  需要解析二维码图片完整地址
     */
    public static String decode1(String filePath ) throws IOException {
        String res = null;
        try {
            res = QRCodeUtilForOpencv.decode(filePath);
            if(res == null || res.length() < 20){
                System.err.println("Opencv解析二维码不符合预期，将使用Boofcv解析二维码" + filePath);
                res = QRCodeUtilForBoofcv.decode(filePath);
            }
            if(res == null || res.length() < 20){
                System.err.println("Boofcv解析二维码不符合预期，将使用Zxing解析二维码" + filePath);
                res = QRCodeUtilForZxing.decode(filePath);
            }
        }catch (Exception e){
            e.printStackTrace();
            try {
                System.err.println("解析二维码失败，将使用Boofcv解析二维码" + filePath);
                res = QRCodeUtilForBoofcv.decode(filePath);
                if(res == null || res.length() < 20){
                    System.err.println("Boofcv解析二维码不符合预期，将使用Zxing解析二维码" + filePath);
                    res = QRCodeUtilForZxing.decode(filePath);
                }
            }catch (Exception ee) {
                ee.printStackTrace();
                System.err.println("Boofcv解析二维码失败，将使用Zxing解析二维码" + filePath);
                try {
                    res = QRCodeUtilForZxing.decode(filePath);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    System.err.println("Zxing解析二维码失败 " + filePath);
                }
            }
        }
        return  res;
    }


    public static void main(String[] args) throws Exception {
        //String filePath = "C:\\soft\\tesseract-ocr\\text\\temp_qr_codes/qr_code_35.png";
       // String filePath = "C:\\soft\\tesseract-ocr\\text\\ocr/69.png";
        String filePath = "C:\\soft\\二维码文件传输工具\\测试识别\\样票\\ffmpeg44.jpg";
        String decode = decode(filePath);
        System.out.println(decode);
    }

    public static void main1(String[] args) throws Exception {
        //String filePath = "C:\\soft\\tesseract-ocr\\text\\temp_qr_codes/qr_code_35.png";
        String filePath = "C:\\soft\\tesseract-ocr\\text\\ocr/66.jpg";

        String decode1 = QRCodeUtilForOpencv.decode(filePath);
        System.out.println("Opencv长度"+ decode1.length());
        String decode2 = QRCodeUtilForBoofcv.decode(filePath);
        System.out.println("Boofcv长度"+ decode2.length());
        String decode3 = QRCodeUtilForZxing.decode(filePath);
        System.out.println("Zxing长度"+ decode3.length());

        if(decode1.equals(decode2)  && decode1.equals(decode3) ){
            System.out.println("识别都一样");
        }else {
            System.err.println("识别不一样");
        }

        if(decode1.equals(decode2) ){
            System.out.println("Opencv 和 Boofcv 识别都一样");
        }else {
            System.err.println("Opencv 和 Boofcv 识别不一样");
        }


        if(decode1.equals(decode3) ){
            System.out.println("Opencv 和 Zxing 识别都一样");
        }else {
            System.err.println("Opencv 和 Zxing 识别不一样");
        }

        if(decode2.equals(decode3) ){
            System.out.println("Boofcv 和 Zxing 识别都一样");
        }else {
            System.err.println("Boofcv 和 Zxing 识别不一样");
        }

    }
}
