package com.example.qrcode.utils;


import boofcv.abst.fiducial.QrCodeDetector;
import boofcv.alg.fiducial.qrcode.QrCode;
import boofcv.alg.fiducial.qrcode.QrCodeEncoder;
import boofcv.alg.fiducial.qrcode.QrCodeGeneratorImage;
import boofcv.factory.fiducial.FactoryFiducial;
import boofcv.io.image.ConvertBufferedImage;
import boofcv.struct.image.GrayU8;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 *
 */
public class QRCodeUtilForBoofcv {

    /** 单个二维码在整帧中的定位区域及 BoofCV 单码解码结果。 */
    public static class QrRegion {
        public final Rectangle bounds;
        public final String text;

        QrRegion(Rectangle bounds, String text) {
            this.bounds = bounds;
            this.text = text;
        }
    }

    /**
     * 字符串生成二维码文件 默认png格式
     * @param contents
     * @param filePath
     */
    public static void encode(String contents,String filePath) throws IOException {
        BufferedImage image = encodeToImage(contents);
        ImageIO.write(image, "png", Files.newOutputStream(Paths.get(filePath)));
    }

    public static BufferedImage encodeToImage(String contents) {
        QrCode qr = new QrCodeEncoder().
                setError(QrCode.ErrorLevel.M).
                addAutomatic(contents).fixate();

        QrCodeGeneratorImage render = new QrCodeGeneratorImage(/* pixel per module */ 20);

        render.render(qr);

        return ConvertBufferedImage.convertTo(render.getGray(), null);
    }



    /**
     * 解析QRCode二维码（文件方式，内部转成图片后解码）
     */
    public static String decode(String filePath ) throws IOException {
        BufferedImage image = ImageIO.read(new File(filePath));
        if (image == null) {
            return null;
        }
        return decode(image);
    }

    /**
     * 每个线程复用一个检测器：每次 new 检测器开销很大，实测 340ms → 51ms
     */
    private static final ThreadLocal<QrCodeDetector<GrayU8>> DETECTOR_HOLDER =
            ThreadLocal.withInitial(() -> FactoryFiducial.qrcode(null, GrayU8.class));

    /**
     * 识别结果：内容 + 检测到的二维码区域个数
     * detectionCount 大于 0 但内容解析失败，说明画面里确实有二维码，只是没解出来（值得用更重的引擎再试）
     */
    public static class DetectResult {
        public final String message;
        public final int detectionCount;

        public DetectResult(String message, int detectionCount) {
            this.message = message;
            this.detectionCount = detectionCount;
        }
    }

    /**
     * 解析QRCode二维码（直接从内存图片解码，省掉读盘）
     */
    public static String decode(BufferedImage image ) {
        return detect(image).message;
    }

    /**
     * 解析QRCode二维码，并返回检测到的二维码区域个数
     */
    public static DetectResult detect(BufferedImage image ) {
        if (image == null) {
            return new DetectResult(null, 0);
        }
        // 将BufferedImage转换为GrayU8图像
        GrayU8 input = ConvertBufferedImage.convertFrom(image, (GrayU8) null);

        // 复用当前线程的检测器
        QrCodeDetector<GrayU8> detector = DETECTOR_HOLDER.get();

        // 检测二维码区域
        detector.process(input);

        List<QrCode> detections = detector.getDetections();
        StringBuilder sb = new StringBuilder();
        for (QrCode qr : detections)
            // The message encoded in the marker
            sb.append(qr.message);
        return new DetectResult(sb.toString(), detections.size());
    }

    /**
     * 定位整帧里的二维码，并分别返回每个二维码的边界框。
     * 边界来自 QR 码自身的定位图形；四码画面另加的角标用于提示布局，裁剪时仍以实测码框为准。
     */
    public static List<QrRegion> findQrRegions(BufferedImage image) {
        if (image == null) {
            return Collections.emptyList();
        }
        GrayU8 input = ConvertBufferedImage.convertFrom(image, (GrayU8) null);
        QrCodeDetector<GrayU8> detector = DETECTOR_HOLDER.get();
        detector.process(input);
        List<QrCode> detections = detector.getDetections();
        List<QrRegion> regions = new ArrayList<>(detections.size());
        for (QrCode qr : detections) {
            if (qr.bounds == null || qr.bounds.size() < 4) {
                continue;
            }
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < qr.bounds.size(); i++) {
                minX = Math.min(minX, qr.bounds.get(i).x);
                minY = Math.min(minY, qr.bounds.get(i).y);
                maxX = Math.max(maxX, qr.bounds.get(i).x);
                maxY = Math.max(maxY, qr.bounds.get(i).y);
            }
            int x1 = Math.max(0, (int) Math.floor(minX));
            int y1 = Math.max(0, (int) Math.floor(minY));
            int x2 = Math.min(image.getWidth(), (int) Math.ceil(maxX) + 1);
            int y2 = Math.min(image.getHeight(), (int) Math.ceil(maxY) + 1);
            if (x2 > x1 && y2 > y1) {
                regions.add(new QrRegion(new Rectangle(x1, y1, x2 - x1, y2 - y1), qr.message));
            }
        }
        return regions;
    }

    public static void main(String[] args) throws IOException {
        String filePath = "C:\\soft\\tesseract-ocr\\text\\temp_qr_codes/qr_code_35.png";
        String decode =decode(filePath);

        System.out.println("解析结果=" + decode);

    }
}

