package com.example.qrcode.utils;


import org.jcodec.api.FrameGrab;
import org.jcodec.api.JCodecException;
import org.jcodec.common.SeekableDemuxerTrack;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.AWTUtil;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;


public class JCodecUtill {

    /**
     * 重复帧判定阈值：32x32 缩略图平均灰度差小于该值视为同一页二维码，直接跳过不再保存/识别
     * 视频里同一页二维码会停留多帧，跳过重复帧可以大幅减少后续二维码识别量
     */
    private static final double DUPLICATE_FRAME_THRESHOLD = 3.0;

    public static void main(String[] args) {
        // 视频文件路径
        String videoFilePath = "C:\\soft\\tesseract-ocr\\text/buqingxi.mp4";
        // 输出目录
        String outputDirPath = "C:\\soft\\tesseract-ocr\\text\\buqingxi\\";
        videoToPic(videoFilePath,outputDirPath);
    }

    /**
     * 将视频按照一帧一帧的输出成图片
     // 如果视频的帧率为 24 FPS，那么一帧的时间是 1/24≈0.0417 秒。
     // 如果视频的帧率为 30 FPS，那么一帧的时间是 1/30≈0.0333 秒。
     // 如果视频的帧率为 60 FPS，那么一帧的时间是 1/60≈0.0167 秒。
     * @param videoFilePath  视频文件路径
     * @param outputDirPath  按帧输出图片目录
     */
    public static void videoToPic(String videoFilePath, String outputDirPath) {
        System.out.println("视频文件路径" + videoFilePath );
        // 创建输出目录
        File outputDir = new File(outputDirPath);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
            System.out.println("创建输出目录" + outputDirPath);
        }else {
            // 清理一下 按帧输出图片目录路径里面 旧文件
            deleteFilesContainingString(new File(outputDirPath),"frame_");
        }

        try (SeekableByteChannel seekableByteChannel = NIOUtils.readableChannel(new File(videoFilePath))) {
            FrameGrab grab = FrameGrab.createFrameGrab(seekableByteChannel);
            SeekableDemuxerTrack videoTrack1 = grab.getVideoTrack();
            if (videoTrack1 == null) {
                System.out.println("No video track found in the file.");
                return;
            }

            Picture picture;
            int frameNumber = 0;

            // 总时长
            double totalDuration = videoTrack1.getMeta().getTotalDuration();
            System.out.println(" 总时长 getTotalDuration：" + totalDuration);
            // 获取总帧数
            int totalFrames = videoTrack1.getMeta().getTotalFrames();
            System.out.println(" 获取总帧数getTotalFrames：" + totalFrames);
            // 获取视频的帧率
            double frameRate = totalFrames / totalDuration ;
            System.out.println("获取视频的帧率: " +  frameRate);

            // 计算一帧的时间长度  以秒为单位
            // 如果视频的帧率为 24 FPS，那么一帧的时间是 1/24≈0.0417 秒。
            // 如果视频的帧率为 30 FPS，那么一帧的时间是 1/30≈0.0333 秒。
            // 如果视频的帧率为 60 FPS，那么一帧的时间是 1/60≈0.0167 秒。
            double frameDuration = 1.0 / frameRate;
            System.out.println("计算一帧的时间长度  " + frameDuration + " seconds");
            // grab.getNativeFrame() 方法用于从视频中提取每一帧。这个方法返回一个 Picture 对象，表示当前帧的图像数据
            int i = 0 ;
            // 按帧率动态计算采样间隔：目标是每秒采样约15张，与FFmpeg路径的 fps=15 保持一致
            // 例如30fps视频 每2帧保存一张；60fps视频 每4帧保存一张
            int sampleInterval = frameRate >= 10 ? Math.max(1, (int) Math.round(frameRate / 15.0)) : 1;
            // 上一张已保存帧的缩略灰度特征，用于重复帧判定
            int[] lastGrayFeature = null;
            while ((picture = grab.getNativeFrame()) != null) {
                i++;

                // 按采样间隔保存图片，减少识别量
                if(i != sampleInterval){
                    continue;
                }
                i = 0;

                // 将 Picture 转换为 BufferedImage（只有候选帧才转换，减少无效开销）
                BufferedImage bufferedImage = AWTUtil.toBufferedImage(picture);

                // 重复帧跳过：同一页二维码在视频里停留多帧，只保留第一帧，后续相同页不再保存和识别
                int[] grayFeature = downscaleGrayFeature(bufferedImage);
                if (lastGrayFeature != null && isDuplicateFrame(grayFeature, lastGrayFeature)) {
                    continue;
                }
                lastGrayFeature = grayFeature;

                // 构建输出文件路径
                String outputPath = outputDirPath + "frame_" + String.format("%04d", frameNumber++) + ".png";
                // 保存图像
                ImageIO.write(bufferedImage, "png", new File(outputPath));
                System.out.println("Extracted frame: " + outputPath);
            }
        } catch (IOException | JCodecException e) {
            System.err.println("Error extracting frames from video: " + e.getMessage());
        }
    }


    /**
     * 将图片缩放成 32x32 灰度缩略图并提取像素特征，用于快速判断两帧是否为同一页二维码
     * 计算开销在毫秒级，远小于一次二维码识别
     */
    private static int[] downscaleGrayFeature(BufferedImage image) {
        int size = 32;
        BufferedImage small = new BufferedImage(size, size, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = small.createGraphics();
        try {
            g.drawImage(image, 0, 0, size, size, null);
        } finally {
            g.dispose();
        }
        int[] gray = new int[size * size];
        small.getRaster().getPixels(0, 0, size, size, gray);
        return gray;
    }

    /**
     * 判断当前帧是否与上一帧重复（平均灰度差小于阈值）
     */
    private static boolean isDuplicateFrame(int[] current, int[] last) {
        if (current.length != last.length) {
            return false;
        }
        long diff = 0;
        for (int k = 0; k < current.length; k++) {
            diff += Math.abs(current[k] - last[k]);
        }
        double meanDiff = (double) diff / current.length;
        return meanDiff < DUPLICATE_FRAME_THRESHOLD;
    }


    /**
     * 删除指定文件夹里面 包含特殊字符的文件
     * @param folder
     * @param searchString
     */
    private static void deleteFilesContainingString(File folder, String searchString) {
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

