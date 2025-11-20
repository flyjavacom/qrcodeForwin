package com.example.qrcode.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

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
    // 二维码生成名称前缀
    private static final String QRCODE_NAME_PREFIX ="qr_code_";

    //创建一个动态大小为200的线程池
    static ExecutorService executorService = new ThreadPoolExecutor(100,200,60L, TimeUnit.SECONDS,new ArrayBlockingQueue<>(10000));



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
       // String qrcodeFilePath = "C:\\soft\\tesseract-ocr\\text\\buqingxi\\";
        String qrcodeFilePath = "C:\\soft\\二维码文件传输工具\\20251117\\videoToPic";
        qrCode4ToFile(qrcodeFilePath);
    }

    /**
     * 将文件转成 二维码文件  【限制大小在70m 以内，这样生成的二维码个数在99999以内 】
     * 1.文件转成Base64 字符串  【Base64 编码后大约会变成 1.33 倍大小】  前面10位是文件后缀
     * 2.将Base64 字符串 按照1024长度取拆分成小字符串  1020原字符串 + 5位当前页码
     * 3.小字符串转成二维码图片 ，图片路径在当前文件路径 temp_qr_codes 文件夹下面
     * @param filePath
     * @return  存储二维码图片路径
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
        String[] segments = splitTextIntoSegments(base64String, 1019); //1024  留4-5位在首位

        // 获取 Path 对象
        Path pathToFile = Paths.get(filePath);
        // 获取父路径
        Path parentPath = pathToFile.getParent();
        // 存储二维码图片路径
        res = parentPath + TEMP_QRCODE_DIR;
        // 清理一下 存储二维码图片路径里面 旧文件
        FilesUtil.deleteFilesContainingString(new File(res),QRCODE_NAME_PREFIX);

        List<String> todoEncodeList= new ArrayList<>();
        Map<String,String> map = new HashMap<>();
        // 将文本 ==》 生成二维码图片
        for (int i = 0; i < segments.length; i++) {

            String tempQrcodepath = res + QRCODE_NAME_PREFIX + i + ".png";
            String segment = segments[i];
            // 页码从0开始
            String page = String.format("%5d",i);
            // 文件前面5位是当前页数
            segment = page + segment;
            map.put(tempQrcodepath,segment);
            todoEncodeList.add(tempQrcodepath);
        }

        //遍历待处理图片集合
        // 分批处理（当待处理突破集合大于线程池最大队列容量之后会报RejectedExecutionException）
        int batchSize = 1000; // 每次提交的任务数量
        for (int i = 0; i < todoEncodeList.size(); i += batchSize) {
            int end = Math.min(i + batchSize, todoEncodeList.size());
            List<String> batch = todoEncodeList.subList(i, end);

            CountDownLatch countDownLatch = new CountDownLatch(batch.size());
            for (String tempQrcodepath : batch) {
                executorService.execute(()->{
                    try {
                        String segment =map.get(tempQrcodepath);
                        QRCodeUtil.encode(segment,tempQrcodepath);
                        System.out.println("正在生成QR二维码: 文件路径=" + tempQrcodepath +" 5位页码+1020长度文件内容=" + segment);
                    } catch (Exception e) {
                        System.out.println("使用多线程处理异常"+e.toString());
                    }finally {
                        // 计数器加一
                        countDownLatch.countDown();
                    }

                });
            }
            // 等待所有线程处理完成
            try {
                countDownLatch.await();
            }catch (Exception e){
                System.out.println("使用多线程处理超时异常"+e.toString());
            }

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
        Map<Integer,String> pageContentMap = new ConcurrentHashMap<>();

        File directory = new File(qrcodeFilePath);
        File[] files = directory.listFiles();
        // 遍历文件夹 得到要处理的图片路径
        List<String>  todoDecodeFilePathList = new ArrayList<>();
        for (File file : files) {
            if(file.isFile() &&
                    (file.getPath().endsWith("png")
                       ||file.getPath().endsWith("PNG")
                       ||file.getPath().endsWith("jpg")
                       ||file.getPath().endsWith("JPG")
                    )){
                File absoluteFile = file.getAbsoluteFile();
                todoDecodeFilePathList.add(absoluteFile.getPath());
            }
        }

        // 使用多线程处理


        //遍历待处理图片集合

        // 分批处理（当待处理突破集合大于线程池最大队列容量之后会报RejectedExecutionException）
        int batchSize = 1000; // 每次提交的任务数量
        for (int i = 0; i < todoDecodeFilePathList.size(); i += batchSize) {
            int end = Math.min(i + batchSize, todoDecodeFilePathList.size());
            List<String> batch = todoDecodeFilePathList.subList(i, end);

            CountDownLatch countDownLatch = new CountDownLatch(batch.size());
            for (String filepath : batch) {
                executorService.execute(()->{
                    try {
                        String  decode = QRCodeUtil.decode(filepath);
                        System.out.println("正在二维码识别:" + filepath +" 识别结果=" + decode);
                        // 异常识别结果跳过
                        if(decode == null || decode.length() < 5){
                            System.out.println("二维码识别不满足跳过！" + filepath +" 识别结果=" + decode);
                        }else {
                            // 前面5位作为页码
                            Integer page = Integer.valueOf(decode.substring(0,5).replace(" ",""));
                            String content  = decode.substring(5);
                            // map 不存在就插入 存在就跳过
                            if(!pageContentMap.containsKey(page)){
                                pageContentMap.put(page,content);
                            }
                        }
                    } catch (Exception e) {
                        System.out.println("使用多线程处理异常"+e.toString());
                    }finally {
                        // 计数器加一
                        countDownLatch.countDown();
                    }

                });
            }
            // 等待所有线程处理完成
            try {
                countDownLatch.await();
            }catch (Exception e){
                System.out.println("使用多线程处理超时异常"+e.toString());
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





}
