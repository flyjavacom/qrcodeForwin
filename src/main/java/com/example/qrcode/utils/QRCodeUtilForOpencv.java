package com.example.qrcode.utils;


import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.StringVector;
import org.bytedeco.opencv.opencv_wechat_qrcode.WeChatQRCode;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;


/**
 *  利用Opencv里面WeChatQRCode 开源工具生成二维码QRCode
 */
public class QRCodeUtilForOpencv {

    /**
     * WeChatQRCode 引擎初始化很重（需要加载模型），必须复用，不能每识别一张图就 new 一次。
     * 这里按线程各持有一个实例：既能并行识别，又避免多线程共享同一实例的线程安全问题。
     * （如果做成全局单例并加 synchronized，失败帧会全部串行排队，实测反而成为最大瓶颈）
     */
    private static final ThreadLocal<WeChatQRCode> ENGINE_HOLDER =
            ThreadLocal.withInitial(WeChatQRCode::new);

    private static WeChatQRCode getWeChatQRCode() {
        return ENGINE_HOLDER.get();
    }

    /**
     * 解析QRCode二维码（文件方式）
     * 注意：不能直接用 opencv 的 imread(filePath)，它不支持中文路径，
     * 本工具目录名常带中文（如「二维码文件传输工具」），会导致这个引擎静默失效。
     * 因此先用 ImageIO 读成内存图片再解码。
     */
    public static String decode(String filePath ) throws IOException {
        BufferedImage image = ImageIO.read(new File(filePath));
        if (image == null) {
            return "0";
        }
        return decode(image);
    }

    /**
     * 解析QRCode二维码（直接从内存图片解码，省掉读盘，且不受中文路径影响）
     */
    public static String decode(BufferedImage image) {
        if (image == null) {
            return "0";
        }
        // 灰度图直接按单通道 Mat 喂给引擎，省掉一次 6MB 级的彩色转换
        if (image.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            Mat gray = new Mat(image.getHeight(), image.getWidth(), opencv_core.CV_8UC1);
            try {
                byte[] data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
                gray.data().put(data, 0, data.length);
                return detectAndDecode(getWeChatQRCode(), gray);
            } finally {
                gray.close();
            }
        }
        BufferedImage bgrImage = image;
        // 转成 3通道BGR，便于直接拷贝到 Mat
        if (image.getType() != BufferedImage.TYPE_3BYTE_BGR) {
            bgrImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g = bgrImage.createGraphics();
            try {
                g.drawImage(image, 0, 0, null);
            } finally {
                g.dispose();
            }
        }

        Mat mat = new Mat(bgrImage.getHeight(), bgrImage.getWidth(), opencv_core.CV_8UC3);
        try {
            byte[] data = ((DataBufferByte) bgrImage.getRaster().getDataBuffer()).getData();
            BytePointer ptr = mat.data();
            ptr.put(data, 0, data.length);

            return detectAndDecode(getWeChatQRCode(), mat);
        } finally {
            // 及时释放 native 内存，防止长视频识别时内存持续增长
            mat.close();
        }
    }

    /**
     * 用微信二维码引擎解一张图（Mat 可以是单通道灰度，也可以是三通道 BGR）
     */
    private static String detectAndDecode(WeChatQRCode we, Mat mat) {
        // 微信二维码引擎解码，返回的valList中存放的是解码后的数据，points中Mat存放的是二维码4个角的坐标
        StringVector stringVector = we.detectAndDecode(mat);
        if (stringVector.empty()) {
            return "0";
        }
        String res = stringVector.get(0).getString(StandardCharsets.UTF_8);
        // 最后一位 是'\u0000' 所以需要过滤掉
        if (res != null && res.length() > 2) {
            res = res.substring(0, res.length() - 1);
        }
        return res;
    }

    public static void main(String[] args) throws Exception {
        String filePath = "C:\\soft\\二维码文件传输工具\\测试识别\\样票\\增值税普票二维码.png";
        String decode =decode(filePath);

        System.out.println("解析结果=" + decode);

    }
}
