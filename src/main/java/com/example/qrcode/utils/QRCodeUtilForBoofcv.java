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
import java.util.List;

/**
 *
 */
public class QRCodeUtilForBoofcv {

    /**
     * 字符串生成二维码文件 默认png格式
     * @param contents
     * @param filePath
     */
    public static void encode(String contents,String filePath) throws IOException {
        QrCode qr = new QrCodeEncoder().
                setError(QrCode.ErrorLevel.M).
                addAutomatic(contents).fixate();

        QrCodeGeneratorImage render = new QrCodeGeneratorImage(/* pixel per module */ 20);

        render.render(qr);

        // Convert it to a BufferedImage for display purposes
        BufferedImage image = ConvertBufferedImage.convertTo(render.getGray(), null);
        ImageIO.write(image, "png", Files.newOutputStream(Paths.get(filePath)));
    }



    /**
     * 解析QRCode二维码
     */
    public static String decode(String filePath ) throws IOException {
        BufferedImage image = ImageIO.read(new File(filePath));
        // 将BufferedImage转换为GrayU8图像
        GrayU8 input = ConvertBufferedImage.convertFrom(image, (GrayU8) null);

        // 创建QrCodeDetector实例
        QrCodeDetector<GrayU8> detector = FactoryFiducial.qrcode(null, GrayU8.class);

        // 检测二维码区域
        detector.process(input);

        List<QrCode> detections = detector.getDetections();
        StringBuilder sb = new StringBuilder();
        for (QrCode qr : detections)
            // The message encoded in the marker
            sb.append(qr.message);
        return sb.toString();
    }

    public static void main(String[] args) throws IOException {
        String filePath = "C:\\soft\\tesseract-ocr\\text\\temp_qr_codes/qr_code_35.png";
        String decode =decode(filePath);

        System.out.println("解析结果=" + decode);

    }
}

