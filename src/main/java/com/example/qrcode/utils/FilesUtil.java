package com.example.qrcode.utils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class FilesUtil {

    /**
     * 检查文件夹是否存在 如果存在可以清理文件夹里面固定前缀的文件
     * @param folder
     * @param searchString
     */
    public static void checkAndClear(String folder,String searchString){
        // 创建输出目录
        File outputDir = new File(folder);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
            System.out.println("创建输出目录" + folder);
        }else {
            // 清理一下 按帧输出图片目录路径里面 旧文件
            FilesUtil.deleteFilesContainingString(new File(folder),searchString);
        }
    }

    /**
     * 删除指定文件夹里面 包含特殊字符的文件
     * @param folder
     * @param searchString
     */
    public static void deleteFilesContainingString(File folder, String searchString) {

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

    /**
     * 遍历文件夹获取png图片
     * @param folderPath
     * @return
     */
    public static List<File> getImageFiles(String folderPath) {
        List<File> imageFiles = new ArrayList<>();
        File folder = new File(folderPath);

        if (!folder.exists() || !folder.isDirectory()) {
            System.out.println("The specified directory does not exist or is not a directory.");
            return imageFiles;
        }

        File[] files = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".png"));
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    imageFiles.add(file);
                }
            }
        }

        return imageFiles;
    }
}
