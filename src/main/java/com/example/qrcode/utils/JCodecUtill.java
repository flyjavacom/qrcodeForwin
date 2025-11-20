package com.example.qrcode.utils;


import org.jcodec.api.FrameGrab;
import org.jcodec.api.JCodecException;
import org.jcodec.common.SeekableDemuxerTrack;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.AWTUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;


public class JCodecUtill {

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
            while ((picture = grab.getNativeFrame()) != null) {
                i++;

                // 将 Picture 转换为 BufferedImage
                BufferedImage bufferedImage = AWTUtil.toBufferedImage(picture);

                // 构建输出文件路径
                String outputPath = outputDirPath + "frame_" + String.format("%04d", frameNumber) + ".png";
                if(frameRate >= 10){
                    // 每5帧 保存一张图片  如果30帧的视频 一秒保存6张图片，这样可以减少识别量
                    if( i== 5){
                        i = 0;
                        // 保存图像
                        ImageIO.write(bufferedImage, "png", new File(outputPath));
                        System.out.println("Extracted frame: " + outputPath);
                    }
                }else {
                    // 每一帧都都要保存图像
                    ImageIO.write(bufferedImage, "png", new File(outputPath));
                    System.out.println("Extracted frame: " + outputPath);
                }

                frameNumber++;
            }
        } catch (IOException | JCodecException e) {
            System.err.println("Error extracting frames from video: " + e.getMessage());
        }
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

