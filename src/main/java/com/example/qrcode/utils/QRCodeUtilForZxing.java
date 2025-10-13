package com.example.qrcode.utils;


import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;


/**
 * 利用zxing开源工具生成二维码QRCode
 *
 */
public class QRCodeUtilForZxing {
    private static final int BLACK = 0xff000000;
    private static final int WHITE = 0xFFFFFFFF;


    /**
     * 字符串生成二维码文件 默认png格式
     * @param contents
     * @param filePath
     */
    public static void encode(String contents, String filePath) throws IOException, WriterException {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        File outputFile = new File(filePath);
        BitMatrix bitMatrix = new MultiFormatWriter().encode(contents, BarcodeFormat.QR_CODE, 500, 500, hints);
        MatrixToImageWriter.writeToPath(bitMatrix, "PNG", outputFile.toPath());
    }
    /**
     *  生成QRCode二维码<br>
     *  在编码时需要将com.google.zxing.qrcode.encoder.Encoder.java中的<br>
     *  static final String DEFAULT_BYTE_MODE_ENCODING = "ISO8859-1";<br>
     *  修改为UTF-8，否则中文编译后解析不了<br>
     * @param contents 二维码的内容
     * @param file 二维码保存的路径，如：C://test_QR_CODE.png
     * @param filePostfix 生成二维码图片的格式：png,jpeg,gif等格式
     * @param format qrcode码的生成格式
     * @param width 图片宽度
     * @param height 图片高度
     * @param hints
     */
    public static void encode(String contents, File file,String filePostfix, BarcodeFormat format, int width, int height, Map<EncodeHintType, ?> hints) {
        try {
            BitMatrix bitMatrix = new MultiFormatWriter().encode(contents, format, width, height);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    image.setRGB(x, y, bitMatrix.get(x, y) == true ? BLACK : WHITE);
                }
            }
            ImageIO.write(image, filePostfix, file);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }



    /**
     * 解析QRCode二维码
     */
    public static String decode(String filePath) throws IOException, NotFoundException {
        BufferedImage image;
        image = ImageIO.read(new File(filePath));
        if (image == null) {
            System.out.println("Could not decode image");
        }
        LuminanceSource source = new BufferedImageLuminanceSource(image);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
        Result result;
        @SuppressWarnings("rawtypes")
        Hashtable hints = new Hashtable();
        //解码设置编码方式为：utf-8
        hints.put(DecodeHintType.CHARACTER_SET, "utf-8");
        result = new MultiFormatReader().decode(bitmap, hints);
        String resultStr = result.getText();
        return  resultStr;
    }

    public static void main(String[] args) throws Exception {
        String filePath = "C:\\soft\\tesseract-ocr\\text\\temp_qr_codes/qr_code_35.png";
        String decode =decode(filePath);

        System.out.println("解析结果=" + decode);

    }
}