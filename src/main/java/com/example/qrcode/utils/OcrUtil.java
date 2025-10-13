/*
package com.example.qrcode.utils;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

import java.io.File;

public class OcrUtil {
   */
/* static {
        // 从 OpenCV 官方网站下载对应平台的本地库文件（例如 opencv_javaXXX.dll 在 Windows 上，libopencv_javaXXX.so 在 Linux 上，libopencv_javaXXX.dylib 在 macOS 上）。
        //将本地库文件放置在一个目录中，例如 src/main/resources/native。

        // Windows
        //设置 PATH 环境变量：
        //找到 OpenCV 的 bin 目录，例如 C:\opencv\build\x64\vc15\bin。
        //将该目录添加到系统的 PATH 环境变量中。
        //右键点击“此电脑” -> “属性” -> “高级系统设置” -> “环境变量”。
        //在“系统变量”部分找到 Path，点击“编辑”，然后添加 OpenCV 的 bin 路径。

        //从 OpenCV 官方发布页面下载：
        //访问 OpenCV Releases 页面。
        //下载适合你版本的 opencv-<version>-java.zip 文件。例如，opencv-4.5.1-java.zip。
        //解压文件：
        //解压下载的 ZIP 文件，找到 opencv-<version>/bin 目录下的 libopencv_javaXXX.so 文件。
        // my-project/
        //├── src/
        //│   └── main/
        //│       ├── java/
        //│       │   └── com/
        //│       │       └── example/
        //│       │           └── AdaptiveThresholdingExample.java
        //│       └── resources/
        //│           └── native/
        //│               ├── windows/
        //│               │   └── x64/
        //│               │       └── opencv_javaXXX.dll
        //│               ├── linux/
        //│               │   └── libopencv_javaXXX.so


        // 加载 OpenCV 的本地库
        loadOpenCVNativeLibrary();
    }

    private static void loadOpenCVNativeLibrary() {
        String osName = System.getProperty("os.name").toLowerCase();

        if (osName.contains("win")) {
                loadLibraryFromResources("/native/windows/", "opencv_java4100.dll");
        } else if (osName.contains("mac")) {
            loadLibraryFromResources("/native/mac/", "libopencv_javaXXX.dylib");
        } else if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) {
            loadLibraryFromResources("/native/linux/", "libopencv_javaXXX.so");
        } else {
            throw new UnsupportedOperationException("Unsupported OS: " + osName);
        }
    }

    private static void loadLibraryFromResources(String resourcePath, String libraryFileName) {
        try {
            Path tempDir = Files.createTempDirectory("opencv-native-lib");
            File tempFile = new File(tempDir.toFile(), libraryFileName);

            // 复制资源文件到临时目录
            Files.copy(OcrUtil.class.getResourceAsStream(resourcePath + libraryFileName),
                    tempFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);

            // 加载库文件
            System.load(tempFile.getAbsolutePath());
        } catch (Exception e) {
            throw new RuntimeException("Failed to load OpenCV native library", e);
        }
    }*//*



    */
/**
     * 图片识别
     * @param tempQrcodepath
     * @return
     * @throws Exception
     *//*

    public static String ocr(String tempQrcodepath) throws Exception {
        ITesseract instance = new Tesseract();
        // 设置 Tesseract 数据文件路径（通常是 tessdata 目录）
        // 确保你已经下载了相应的语言数据文件（例如 eng.traineddata）
        String tessDataPath = "C:/soft/tesseract-ocr/tessdata/";
        instance.setDatapath(tessDataPath);

        // 设置要使用的语言（例如英语）
        // 语言	ISO 639-3 代码
        //英语	eng
        //西班牙语	spa
        //法语	fra
        //德语	deu
        // 简体中文：chi_sim
        // 繁体中文：chi_tra
        //日语	jpn
        //韩语	kor
        //俄语	rus
        //意大利语	ita
        //葡萄牙语	por
        instance.setLanguage("chi_sim");
        try {
            // 提取图像中的文本
            File imageFile = new File(tempQrcodepath);
            String result = instance.doOCR(imageFile);
            // 输出提取的文本
            System.out.println("正在OCR处理:" + tempQrcodepath +" 识别结果" + result);
            return result;
        } catch (TesseractException e) {
            System.err.println(e.getMessage());
            return null;
        }

    }



    public static void main(String[] args) throws Exception {

        String tempQrcodepath ="C:\\soft\\tesseract-ocr\\text\\ocr\\25.png";
        ocr(tempQrcodepath);
        //
        System.out.println("///////////////////////////////////////");
       // String imgPreprocess = OpencvUtil.imgPreprocess(tempQrcodepath);
       // ocr(imgPreprocess);


    }
}
*/
