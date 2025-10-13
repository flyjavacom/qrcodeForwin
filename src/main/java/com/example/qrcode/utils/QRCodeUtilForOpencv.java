package com.example.qrcode.utils;


import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.StringVector;
import org.bytedeco.opencv.opencv_wechat_qrcode.WeChatQRCode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;


/**
 *  利用Opencv里面WeChatQRCode 开源工具生成二维码QRCode
 */
public class QRCodeUtilForOpencv {


    /**
     * 解析QRCode二维码
     */
    public static String decode(String filePath ) throws IOException {
        Mat img = opencv_imgcodecs.imread(filePath);
        WeChatQRCode we = new WeChatQRCode();
        // 微信二维码引擎解码，返回的valList中存放的是解码后的数据，points中Mat存放的是二维码4个角的坐标
        StringVector stringVector = we.detectAndDecode(img);
        if (stringVector.empty()) {
            return "0";
        }
        String res = stringVector.get(0).getString(StandardCharsets.UTF_8);
        // 最后一位 是'\u0000' 所以需要过滤掉
        if(res !=null && res.length() >2){
            res = res.substring(0,res.length()-1);
        }
        return res;
    }
    public static void main(String[] args) throws Exception {
        String filePath = "C:\\soft\\tesseract-ocr\\text\\temp_qr_codes/qr_code_35.png";
        String decode =decode(filePath);

        System.out.println("解析结果=" + decode);

    }
}
