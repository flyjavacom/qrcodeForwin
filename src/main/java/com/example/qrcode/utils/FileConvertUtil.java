package com.example.qrcode.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 *
 * 文件解析成二维码图片
 *
 * 将二维码图片识别成文本并转成文件
 *
 */
public class FileConvertUtil {
    private static final int MAX_SEGMENT_SIZE = 1024; // Maximum size of each segment in bytes
    private static final String TEMP_QRCODE_DIR = "/temp_qr_codes/";



    public static void main(String[] args) throws IOException {
     /* // xls文件解析成二维码图片
        String xlsFilePath = "C:\\soft\\tesseract-ocr\\text\\2025-权益服务兑换限制汇总.xlsx";
        XlsToQrCode(xlsFilePath);

        // 将二维码图片识别成文本并转成xls文件
        String qrcodeFilePath = "C:\\soft\\tesseract-ocr\\text\\" + TEMP_QRCODE_DIR;
        QrCode4ToXls(qrcodeFilePath);
*/

        // 将二维码图片识别成文本并转成xls文件
        //String qrcodeFilePath = "C:\\soft\\tesseract-ocr\\text\\wechat-qr\\";
        //String qrcodeFilePath = "C:\\soft\\tesseract-ocr\\text\\44mp4\\";
        String qrcodeFilePath = "C:\\soft\\tesseract-ocr\\text\\buqingxi\\";
        qrCode4ToFile(qrcodeFilePath);
    }

    /**
     * 将文件转成 二维码文件  【限制大小在7m 以内，这样生成的二维码个数在9999以内 】
     * 1.文件转成Base64 字符串  【Base64 编码后大约会变成 1.33 倍大小】  前面10位是文件后缀
     * 2.将Base64 字符串 按照1024长度取拆分成小字符串  1020原字符串 + 4位当前页码
     * 3.小字符串转成二维码图片 ，图片路径在当前文件路径 temp_qr_codes 文件夹下面
     * @param filePath
     */
    public static String fileToQrCode(String filePath) throws Exception {
        String res = null;
        System.out.println("开始将文件转成 二维码文件" + filePath );
        // 将 XLS文件转成Base64 字符串  1MB 的数据在经过 Base64 编码后大约会变成 1.33 倍大小，即约 1.33MB。
        String base64String = ExcelBase64Converter.convertXlsToBase64(filePath);
        // 文件后缀
        String fileExtension = filePath.substring(filePath.lastIndexOf(".") + 1);
        // 10位长度的文件后缀
        String fileExtensionForLength10 = String.format("%10s",fileExtension);
        // 前面10位是文件后缀
        base64String = fileExtensionForLength10 + base64String;
        // 将文件拆分成小文本
        String[] segments = splitTextIntoSegments(base64String, 1020); //1024  留4位在首位增加当前4位

        // 获取 Path 对象
        Path pathToFile = Paths.get(filePath);
        // 获取父路径
        Path parentPath = pathToFile.getParent();
        // 存储二维码图片路径
        res = parentPath + TEMP_QRCODE_DIR;
        // 清理一下 存储二维码图片路径里面 旧文件
        deleteFilesContainingString(new File(res),"qr_code_");

        // 将文本 ==》 生成二维码图片
        for (int i = 0; i < segments.length; i++) {

            String tempQrcodepath = res + "qr_code_" + i + ".png";

            String segment = segments[i];
            // 页码从0开始
            String page = String.format("%4d",i);
            // 文件前面4位是当前页数
            segment = page + segment;
            System.out.println("正在生成QR二维码: 文件路径=" + tempQrcodepath +" 4位页码+1020长度文件内容=" + segment);
            QRCodeUtil.encode(segment,tempQrcodepath);
        }
        return res;
    }

    /**
     * 图片二维码 解析并生成文件
     * @param qrcodeFilePath   图片二维码文件夹路径，里面有多个图片
     */
    public static String qrCode4ToFile(String qrcodeFilePath) throws IOException {
        System.out.println("开始图片二维码解析并生成文件  " + qrcodeFilePath);
        String res = null;
        Map<Integer,String> pageContentMap = new HashMap<>();

        File directory = new File(qrcodeFilePath);
        File[] files = directory.listFiles();
        for (File file : files) {
            if(file.isFile() &&
                    (file.getPath().endsWith("png")
                       ||file.getPath().endsWith("PNG")
                       ||file.getPath().endsWith("jpg")
                       ||file.getPath().endsWith("JPG")
                    )){
                File absoluteFile = file.getAbsoluteFile();
                String decode = QRCodeUtil.decode(absoluteFile.getPath());
                System.out.println("正在二维码识别:" + absoluteFile.getPath() +" 识别结果=" + decode);
                if(decode == null || decode.length() < 4){
                    System.out.println("二维码识别不满足跳过！" + absoluteFile.getPath() +" 识别结果=" + decode);
                    continue;
                }
                String page = decode.substring(0,4).replace(" ","");
                String content = decode.substring(4);
                // map 不存在就插入 存在就跳过
                if(!pageContentMap.containsKey(Integer.valueOf(page))){
                    pageContentMap.put(Integer.valueOf(page),content);
                }
            }

        }
        // 将map 里面记录页码对应的小字符串 按照页码升序转成大字符串
        System.out.println("将map 里面记录页码对应的小字符串 按照页码升序转成大字符串");
        StringBuilder bigContent = new StringBuilder();
        for (int i = 0; i < pageContentMap.size(); i++) {
            bigContent.append(pageContentMap.get(i));
        }

        String content =bigContent.toString();
        // 获取文件后缀 并将空格去除
        String ext = content.substring(0, 10).replace(" ","");
        System.out.println("文件后缀="+ext);
        // 原base64文本
        String base64string = content.substring(10);

        String timestamp = new SimpleDateFormat("yyyy-MM-dd-HHmmss").format(new Date());
        //  BASE64文本 ==》 文件
        System.out.println("BASE64文本 ==》  文件");
        String outputXlsFilePath = qrcodeFilePath +"/" + timestamp+ "." + ext;
        ExcelBase64Converter.convertBase64ToXls(base64string, outputXlsFilePath);
        res = outputXlsFilePath;
        return  res;
    }


    /**
     * 将大文本拆分成小文本
     * @param text  大文本
     * @param maxSegmentSize  单个文本的长度
     * @return
     */
    private static String[] splitTextIntoSegments(String text, int maxSegmentSize) {
        int numSegments = (int) Math.ceil((double) text.length() / maxSegmentSize);
        String[] segments = new String[numSegments];

        for (int i = 0; i < numSegments; i++) {
            int start = i * maxSegmentSize;
            int end = Math.min(start + maxSegmentSize, text.length());
            segments[i] = text.substring(start, end);
        }

        return segments;
    }


    /**
     * 删除指定文件夹里面 包含特殊字符的文件
     * @param folder
     * @param searchString
     */
    private static void deleteFilesContainingString(File folder, String searchString) {

        if (!folder.exists()) {
            return;
        }


        if (!folder.isDirectory()) {
            throw new IllegalArgumentException("指定路径不是一个文件夹");
        }

        File[] files = folder.listFiles();
        if (files == null || files.length == 0) {
            System.out.println("文件夹为空");
            return;
        }

        for (File file : files) {
            if (file.isFile() && file.getName().contains(searchString)) {
                try {
                    if (file.delete()) {
                        System.out.println("已删除文件: " + file.getName());
                    } else {
                        System.out.println("删除文件失败: " + file.getName());
                    }
                } catch (SecurityException se) {
                    System.out.println("删除文件时发生安全异常: " + file.getName() + " - " + se.getMessage());
                }
            }
        }
    }


}
