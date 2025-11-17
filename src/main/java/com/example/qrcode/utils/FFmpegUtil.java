package com.example.qrcode.utils;



import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 *
 *
 * 要将视频input.mp4分解成逐帧的PNG格式图片，并按照指定的命名格式保存在data目录下，可以使用以下FFmpeg命令：
 *
 * ffmpeg -i input.mp4 -vf "fps=1" data/%010d.png
 * 命令解释：
 *
 * -i input.mp4：指定输入文件。
 * -vf "fps=1"：视频过滤器，fps=1表示每秒提取一帧。如果要提取视频的每一帧，可以省略这个过滤器参数。
 * data/%010d.png：指定输出文件的路径和格式。data/是目标文件夹，%010d表示序列号将被格式化为十位数，前面用0填充。
 * 确保data目录已经存在，或者你可以在运行FFmpeg命令之前创建它，使用如下命令：
 *
 * mkdir -p data
 * 如果你想提取视频的每一帧，不限制于每秒一帧，可以使用以下命令：
 *
 * ffmpeg -i input.mp4 data/%010d.png
 * 这会将input.mp4中的每一帧都保存为PNG图片在data目录下，文件名从0000000000.png开始，依次递增。
 */
public class FFmpegUtil {
    public static void main(String[] args) {
        // 视频文件路径
        String videoFilePath = "C:\\soft\\tesseract-ocr\\text/buqingxi.mp4";
        // 输出目录
        String outputDirPath = "C:\\soft\\tesseract-ocr\\text\\ffmpegPath1\\";
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

        // 将ffmpeg 直接放在Resources 下面，这样不需要本地安装了。
        // 从 JAR 中提取 ffmpeg.exe 到临时文件：由于 JAR 文件是只读的，无法直接从中执行可执行文件，因此需要先将 ffmpeg.exe 提取到一个临时文件中，然后再执行该文件

        // 资源路径中的 ffmpeg.exe
        String resourcePath = "/ffmpeg-8.0-essentials_build/bin/ffmpeg.exe";

        // 提取 ffmpeg.exe 到临时文件
        File tempFile = extractResourceToFile(resourcePath);
        try {
            System.out.println("获取ffmpeg.exe临时路径 ==" +  tempFile.getAbsolutePath());
            if (tempFile == null) {
                System.err.println("Failed to extract ffmpeg.exe from resources.");
                return;
            }

            // 构建FFmpeg命令
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "\"" +  tempFile.getAbsolutePath() + "\"",
                    "-i", videoFilePath,
                    "-vf", "fps=6",  // 每秒提取6帧
                    outputDirPath + "frame_%04d.png"
            );
            Process process = processBuilder.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);  // 打印FFmpeg输出信息
            }

            int exitCode = process.waitFor();
            System.out.println("FFmpeg exited with code: " + exitCode);

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        } finally {
            // 删除临时文件
            if (!tempFile.delete()) {
                System.err.println("Failed to delete temporary file: " + tempFile.getAbsolutePath());
            }
        }
    }

    /**
     *  将resource 文件复制到临时文件夹里面
     * @param resourcePath
     * @return
     */
    private static File extractResourceToFile(String resourcePath) {
        try (InputStream inputStream = FFmpegUtil.class.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                System.err.println("Resource not found: " + resourcePath);
                return null;
            }

            Path tempFilePath = Files.createTempFile("ffmpeg", ".exe");
            Files.copy(inputStream, tempFilePath, StandardCopyOption.REPLACE_EXISTING);
            File tempFile = tempFilePath.toFile();

            // 设置文件权限为可执行
            if (!tempFile.setExecutable(true)) {
                System.err.println("Failed to set executable permission for: " + tempFile.getAbsolutePath());
            }

            return tempFile;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
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