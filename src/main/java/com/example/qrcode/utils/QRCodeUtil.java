package com.example.qrcode.utils;

import com.google.zxing.NotFoundException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class QRCodeUtil {

    /**
     * 文本生成二维码文件
     * @param contents
     * @param filePath  需要生成二维码图片完整地址  默认是png图片
     * @throws IOException
     */
    public static void encode(String contents,String filePath) throws IOException {
        try {
            // 先保证文件夹是否存在
            if (!Files.exists(Paths.get(filePath))) {
                Files.createDirectories(Paths.get(filePath));
            }
            QRCodeUtilForZxing.encode(contents,filePath);
        }catch (Exception e){
            e.printStackTrace();
            System.err.println("zxing生成二维码异常，将使用Boofcv生成二维码" + filePath);
            QRCodeUtilForBoofcv.encode(contents,filePath);
        }
    }

    /**
     * 解析QRCode二维码
     *  先使用 Zxing 然后 Boofcv 然后 Opencv
     * @param filePath  需要解析二维码图片完整地址
     */
    public static String decode(String filePath ) throws IOException {
        String res = null;
        try {
            res = QRCodeUtilForZxing.decode(filePath);
            if(res == null || res.length() < 20){
                System.err.println("Zxing解析二维码不符合预期，将使用Boofcv解析二维码" + filePath);
                res = QRCodeUtilForBoofcv.decode(filePath);
            }
            if(res == null || res.length() < 20){
                System.err.println("Boofcv解析二维码不符合预期，将使用Opencv解析二维码" + filePath);
                res = QRCodeUtilForOpencv.decode(filePath);
            }
        }catch (Exception e){
            e.printStackTrace();
            try {
                System.err.println("解析二维码失败，将使用Boofcv解析二维码" + filePath);
                res = QRCodeUtilForBoofcv.decode(filePath);
                if(res == null || res.length() < 20){
                    System.err.println("Boofcv解析二维码不符合预期，将使用Opencv解析二维码" + filePath);
                    res = QRCodeUtilForOpencv.decode(filePath);
                }
            }catch (Exception ee) {
                ee.printStackTrace();
                System.err.println("Boofcv解析二维码失败，将使用Opencv解析二维码" + filePath);
                try {
                    res = QRCodeUtilForOpencv.decode(filePath);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    System.err.println("Opencv解析二维码失败 " + filePath);
                }
            }
        }
        return  res;
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
        String filePath = "C:\\soft\\tesseract-ocr\\text\\ocr/69.png";
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
