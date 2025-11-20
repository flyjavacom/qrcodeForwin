package com.example.qrcode.utils;



import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

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
    // 保存合成图片以及视频文件夹
    private static final String TEMP_CONCATENATED_FILE_DIR = "/temp_concatenated/";

    // 视频按帧截图的图片名称前缀
    private static final String VIDEOTOPIC_NAME_PREFIX ="frame_";
    // 将4个图片合成一个 图片名称前缀
    private static final String CONCATENATEDIMAGE_NAME_PREFIX ="concatenated_";
    // 生成视频名称前缀
    private static final String VIDEO_NAME_PREFIX ="video_";
    // 生成视频需要将图片路径写入txt文本里面
    private static final String CONCATENATED_FILE_LIST_TXT_NAME ="concatenated_file_list.txt";

    // 将1个图片拆分4个 图片名称前缀
    private static final String SPILT_NAME_PREFIX ="split_";

    // 保存合成图片以及视频文件夹
    private static final String TEMP_SPILT_FILE_DIR = "/temp_split/";

    //创建一个动态大小为200的线程池
    static ExecutorService executorService = new ThreadPoolExecutor(100,200,60L, TimeUnit.SECONDS,new ArrayBlockingQueue<>(10000));


    private static File tempFFmpeg = initFFmpeg();

    /**
     * 初始化FFmpeg
     * @return
     */
    private static  File  initFFmpeg() {
        // 将ffmpeg 直接放在Resources 下面，这样不需要本地安装了。
        // 从 JAR 中提取 ffmpeg.exe 到临时文件：由于 JAR 文件是只读的，无法直接从中执行可执行文件，因此需要先将 ffmpeg.exe 提取到一个临时文件中，然后再执行该文件

        // 资源路径中的 ffmpeg.exe
        String resourcePath = "/ffmpeg-8.0-essentials_build/bin/ffmpeg.exe";
        // 提取 ffmpeg.exe 到临时文件
        File tempFile = extractResourceToFile(resourcePath);
        System.out.println("获取ffmpeg.exe临时路径 ==" +  tempFile.getAbsolutePath());
        return  tempFile;

    }

    /**
     * 清理临时文件
     */
    public static  void  clernFFmpeg() {
        // 删除临时文件
        if ( tempFFmpeg!=null && !tempFFmpeg.delete()) {
            System.err.println("Failed to delete temporary file: " + tempFFmpeg.getAbsolutePath());
        }
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
        // 检查目录 并清理一下 按帧输出图片目录路径里面 旧文件
        FilesUtil.checkAndClear(outputDirPath,VIDEOTOPIC_NAME_PREFIX);

        try {
            if (tempFFmpeg == null) {
                tempFFmpeg = initFFmpeg();
            }
            // 构建FFmpeg命令
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "\"" +  tempFFmpeg.getAbsolutePath() + "\"",
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
     * 将4个二维码拼接成一个图片 然后分别在4个拐角
     */
    public static void concatenatedImage(String imagePath1,
                                         String imagePath2,
                                         String imagePath3,
                                         String imagePath4,
                                         String outImagePath) {
        System.out.println("合成图片文件路径" + outImagePath );


        try {
            if (tempFFmpeg == null) {
                tempFFmpeg = initFFmpeg();
            }
            // 构建FFmpeg命令
            String[] command = {
                    "\"" +  tempFFmpeg.getAbsolutePath() + "\"",
                    "-f", "lavfi",
                    "-i", "color=c=black:s=1980x1280:d=1:r=1",
                    "-i", imagePath1,
                    "-i", imagePath2,
                    "-i", imagePath3,
                    "-i", imagePath4,
                    "-filter_complex", "[0:v][1:v]overlay=shortest=1:x=0:y=0[top_left_overlay];[top_left_overlay][2:v]overlay=shortest=1:x=1480:y=0[top_right_overlay];[top_right_overlay][3:v]overlay=shortest=1:x=0:y=780[bottom_left_overlay];[bottom_left_overlay][4:v]overlay=shortest=1:x=1480:y=780",
                    outImagePath
            };

            System.out.println("合成图片FFmpeg命令 =ffmpeg " + String.join(" ", command));
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            Process process = processBuilder.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);  // 打印FFmpeg输出信息
            }

            int exitCode = process.waitFor();
            System.out.println("合成图片FFmpeg exited with code: " + exitCode);
            System.out.println(" 将4个二维码拼接成一个图片成功 " + outImagePath);
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * 将一个图片拆分成4个图片
     */
    public static String splitImage( String inImageDir){
        System.out.println("开始拆分图片   原图片文件夹路径 " + inImageDir);

        // 获取 Path 对象
        Path pathToFile = Paths.get(inImageDir);
        // 获取父父路径
        Path parentPath = pathToFile.getParent();
        String splitImageDirPath = parentPath +TEMP_SPILT_FILE_DIR;
        System.out.println("开始拆分图片    " + splitImageDirPath);
        // 检查目录 并清理一下  旧文件
        FilesUtil.checkAndClear(splitImageDirPath,SPILT_NAME_PREFIX);

        File directory = new File(inImageDir);
        File[] files = directory.listFiles();
        // 遍历文件夹 得到要处理的图片路径
        List<String> todoSplitImageList = new ArrayList<>();
        for (File file : files) {
            if(file.isFile() &&
                    (file.getPath().endsWith("png")
                            ||file.getPath().endsWith("PNG")
                            ||file.getPath().endsWith("jpg")
                            ||file.getPath().endsWith("JPG")
                    )){
                File absoluteFile = file.getAbsoluteFile();
                todoSplitImageList.add(absoluteFile.getPath());
            }
        }
        // 使用多线程处理
        // 分批处理（当待处理突破集合大于线程池最大队列容量之后会报RejectedExecutionException）
        int batchSize = 1000; // 每次提交的任务数量


        int page = 1;
        for (int i = 0; i < todoSplitImageList.size(); i += batchSize) {
            int end = Math.min(i + batchSize, todoSplitImageList.size());
            List<String> batch = todoSplitImageList.subList(i, end);
            CountDownLatch countDownLatch = new CountDownLatch(batch.size());
            for (int j = 0; j < batch.size(); j++) {
                //图片合并
                // 页码从0开始
                String pageString1 = String.format("%05d",page++);
                String splitImageName1 =   splitImageDirPath + SPILT_NAME_PREFIX + pageString1 + ".png";

                String pageString2 = String.format("%05d",page++);
                String splitImageName2 =   splitImageDirPath + SPILT_NAME_PREFIX + pageString2 + ".png";

                String pageString3 = String.format("%05d",page++);
                String splitImageName3 =   splitImageDirPath + SPILT_NAME_PREFIX + pageString3 + ".png";

                String pageString4 = String.format("%05d",page++);
                String splitImageName4 =   splitImageDirPath + SPILT_NAME_PREFIX + pageString4 + ".png";

                String inImagePath = batch.get(j);
                executorService.execute(()->{
                    try {
                        splitImage(inImagePath, splitImageName1, splitImageName2, splitImageName3,splitImageName4); ;
                    } catch (Exception e) {
                        System.out.println("使用多线程处理异常"+e.toString());
                        e.printStackTrace();
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


        return splitImageDirPath;

    }


    /**
     * 将一个图片拆分成4个图片
     */
    public static void splitImage( String inImagePath,
                                   String spltImagePath1,
                                   String spltImagePath2,
                                   String spltImagePath3,
                                   String spltImagePath4) {
        System.out.println("拆分图片文件路径" + inImagePath );


        try {
            if (tempFFmpeg == null) {
                tempFFmpeg = initFFmpeg();
            }
            // 计算图片分辨率

            BufferedImage image = ImageIO.read(new File(inImagePath));
            if (image == null) {
                System.out.println("Could not decode image");
                return;
            }
            int width = image.getWidth();
            int height = image.getHeight();

            System.out.println("图片宽度"+ width + "图片高度"+ height );
            // 需要裁剪的图片大小是
            int newWidth =width/2;
            int newHeight = height/2;
            String format =String.format("[0:v]crop=w=%d:h=%d:x=0:y=0[top_left];" + //上左 x=0:y=0
                            "[0:v]crop=w=%d:h=%d:x=%d:y=0[top_right];" +               //上右 x=宽度/2:y=0
                            "[0:v]crop=w=%d:h=%d:x=0:y=%d[bottom_left];" +              //下左 x=0:y=高度/2
                            "[0:v]crop=w=%d:h=%d:x=%d:y=%d[bottom_right]" ,           //下右 x=宽度/2:y=高度/2
                    newWidth,newHeight,
                    newWidth,newHeight,newWidth,
                    newWidth,newHeight,newHeight,
                    newWidth,newHeight,newWidth,newHeight
            ) ;
            // 构建FFmpeg命令
            //ffmpeg -i 1111.jpg -filter_complex "[0:v]crop=w=1000:h=600:x=0:y=0[top_left];[0:v]crop=w=1000:h=600:x=1480:y=0[top_right];[0:v]crop=w=1000:h=600:x=0:y=780[bottom_left];[0:v]crop=w=1000:h=600:x=1480:y=780[bottom_right]" -map "[top_left]" top_left_output.jpg -map "[top_right]" top_right_output.jpg -map "[bottom_left]" bottom_left_output.jpg -map "[bottom_right]" bottom_right_output.jpg
            String[] command = {
                    "\"" +  tempFFmpeg.getAbsolutePath() + "\"",
                    "-i", inImagePath,
                    "-filter_complex", format,
                    "-map", "[top_left]", spltImagePath1,
                    "-map", "[top_right]", spltImagePath2,
                    "-map", "[bottom_left]", spltImagePath3,
                    "-map", "[bottom_right]", spltImagePath4
            };

            System.out.println("拆分图片FFmpeg命令 =ffmpeg " + String.join(" ", command));

            ProcessBuilder processBuilder = new ProcessBuilder(command);


            Process process = processBuilder.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);  // 打印FFmpeg输出信息
            }

            int exitCode = process.waitFor();
            System.out.println("拆分图片 FFmpeg exited with code: " + exitCode);

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * 根据图片文件夹生成视频
     */
    public static void geneteVideoFromImageDir(String concatenatedImageDirPath){
        System.out.println("根据图片文件夹生成视频文件路径" + concatenatedImageDirPath );
        try {
            if (tempFFmpeg == null) {
                tempFFmpeg = initFFmpeg();
            }
            //  读取图片列表并生成txt
            String outputFile = concatenatedImageDirPath + CONCATENATED_FILE_LIST_TXT_NAME;

            // 获取文件夹中的所有 PNG 图片文件
            List<File> imageFiles = FilesUtil.getImageFiles(concatenatedImageDirPath);
            if (imageFiles.isEmpty()) {
                System.out.println("No PNG files found in the specified directory.");
                return;
            }
            // 按文件名排序
            Collections.sort(imageFiles);
            // 写入 concatenated_file_list.txt
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
                for (File file : imageFiles) {
                    writer.write("file '" + file.getAbsolutePath().replace("\\", "\\\\") + "'");
                    writer.newLine();
                }
                System.out.println("生成 concatenated_file_list.txt  成功.");
            } catch (IOException e) {
                e.printStackTrace();
            }


            String timestamp = new SimpleDateFormat("yyyy-MM-dd-HHmmss").format(new Date());
            String videoPath =concatenatedImageDirPath+ VIDEO_NAME_PREFIX +timestamp+ ".mp4";
            // 定义FFmpeg命令
            String[] command = {
                    "\"" +  tempFFmpeg.getAbsolutePath() + "\"",
                    "-r", "3", // 一秒 3帧
                    "-f", "concat",
                    "-safe", "0", //-f concat 和 -safe 0 选项来读取文件列表
                    "-i", outputFile,
                    "-c:v", "libx264",//使用H.264编码器。
                    "-pix_fmt", "yuv420p",videoPath//-pix_fmt yuv420p: 设置像素格式为yuv420p。
            };

            System.out.println("根据图片文件夹生成视频FFmpeg命令 =ffmpeg " + String.join(" ", command));
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            Process process = processBuilder.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);  // 打印FFmpeg输出信息
            }
            int exitCode = process.waitFor();
            System.out.println("生成视频文件 FFmpeg exited with code: " + exitCode);
            System.out.println("根据图片文件夹生成视频文件完成 " + videoPath);

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }



    /**
     * 将生成二维码文件夹 里面的图片全成 一个图片4个二维码，然后生成1秒张的视频
     *  跟图片的文件夹同一级 生成 /temp_concatenated/ 文件夹保存 合成图片和视频
     * @param qrcodeFilePath 生成二维码文件夹
     * @return  qrcodeFilePath 合成图片和视频文件夹路径
     */
    public static  String  geneteConcatenatedImageAndVideo(String qrcodeFilePath ){

        System.out.println("开始解析图片二维码合成和生成视频  原图片路径 " + qrcodeFilePath);
        // 获取 Path 对象
        Path pathToFile = Paths.get(qrcodeFilePath);
        // 获取父父路径
        Path parentPath = pathToFile.getParent();
        String concatenatedImageDirPath = parentPath +TEMP_CONCATENATED_FILE_DIR;
        System.out.println("开始解析图片二维码合成和生成视频  合成图片以及视频路径 " + concatenatedImageDirPath);


        // 检查目录 并清理一下  旧文件
        FilesUtil.checkAndClear(concatenatedImageDirPath,CONCATENATEDIMAGE_NAME_PREFIX);

        File directory = new File(qrcodeFilePath);
        File[] files = directory.listFiles();
        // 遍历文件夹 得到要处理的图片路径
        List<String> todoConcatenatedImageList = new ArrayList<>();
        for (File file : files) {
            if(file.isFile() &&
                    (file.getPath().endsWith("png")
                            ||file.getPath().endsWith("PNG")
                            ||file.getPath().endsWith("jpg")
                            ||file.getPath().endsWith("JPG")
                    )){
                File absoluteFile = file.getAbsoluteFile();
                todoConcatenatedImageList.add(absoluteFile.getPath());
            }
        }
        // 使用多线程处理
        // 分批处理（当待处理突破集合大于线程池最大队列容量之后会报RejectedExecutionException）
        int batchSize = 4; // 每次提交的任务数量
        CountDownLatch countDownLatch = new CountDownLatch( (todoConcatenatedImageList.size() + batchSize - 1) / batchSize);

        int page = 1;
        for (int i = 0; i < todoConcatenatedImageList.size(); i += batchSize) {
            int end = Math.min(i + batchSize, todoConcatenatedImageList.size());
            List<String> batch = todoConcatenatedImageList.subList(i, end);

            String imagePath1 = null;
            String imagePath2 = null;
            String imagePath3 = null;
            String imagePath4 = null;
            for (int j = 0; j < batch.size(); j++) {
                if(j==0){
                    imagePath1 = batch.get(0);
                }
                if(j==1){
                    imagePath2 = batch.get(1);
                }
                if(j==2){
                    imagePath3 = batch.get(2);
                }
                if(j==3){
                    imagePath4 = batch.get(3);
                }
                // 如果有不足4张，其他位置图片默认跟第一张一样
                if(imagePath2 == null){
                    imagePath2 = imagePath1;
                }
                if(imagePath3 == null){
                    imagePath3 = imagePath1;
                }
                if(imagePath4 == null){
                    imagePath4 = imagePath1;
                }
            }
            //图片合并
            // 页码从0开始
            String pageString = String.format("%05d",page++);
            String concatenatedImageName =   concatenatedImageDirPath + CONCATENATEDIMAGE_NAME_PREFIX + pageString + ".png";

            String finalImagePath = imagePath1;
            String finalImagePath1 = imagePath2;
            String finalImagePath2 = imagePath3;
            String finalImagePath3 = imagePath4;
            executorService.execute(()->{
                try {
                    concatenatedImage(finalImagePath, finalImagePath1, finalImagePath2, finalImagePath3,concatenatedImageName) ;
                } catch (Exception e) {
                    System.out.println("使用多线程处理异常"+e.toString());
                    e.printStackTrace();
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
        // 将图片合成视频
        geneteVideoFromImageDir(concatenatedImageDirPath);
        return concatenatedImageDirPath;
    }








    public static void main(String[] args) {
        String qrcodeFilePath =  "C:\\soft\\二维码文件传输工具\\测试1\\temp_concatenated";
        // 将生成二维码文件夹 里面的图片全成 一个图片4个二维码，然后生成1秒张的视频
        splitImage( qrcodeFilePath );
        clernFFmpeg();
    }

    public static void main4(String[] args) {
        String qrcodeFilePath =  "C:\\soft\\二维码文件传输工具\\测试\\合并测试\\in\\";
        // 将生成二维码文件夹 里面的图片全成 一个图片4个二维码，然后生成1秒张的视频
        geneteConcatenatedImageAndVideo( qrcodeFilePath );
        clernFFmpeg();
    }



    public static void main3(String[] args) {
        //图片拆分
        String inImagePath ="C:\\soft\\二维码文件传输工具\\测试\\111\\1111.jpg";
        String spltImagePath1 ="C:\\soft\\二维码文件传输工具\\测试\\111\\spltImagePath1.png";
        String spltImagePath2 ="C:\\soft\\二维码文件传输工具\\测试\\111\\spltImagePath2.png";
        String spltImagePath3 ="C:\\soft\\二维码文件传输工具\\测试\\111\\spltImagePath3.png";
        String spltImagePath4 = "C:\\soft\\二维码文件传输工具\\测试\\111\\spltImagePath4.png";

        splitImage(  inImagePath, spltImagePath1,spltImagePath2,spltImagePath3, spltImagePath4);

        clernFFmpeg();
    }
    public static void main2(String[] args) {
        //图片合并
        String imagePath1 ="C:\\soft\\二维码文件传输工具\\测试\\111\\qr_code_0.png";
        String imagePath2 ="C:\\soft\\二维码文件传输工具\\测试\\111\\qr_code_1.png";
        String imagePath3 ="C:\\soft\\二维码文件传输工具\\测试\\111\\qr_code_2.png";
        String imagePath4 ="C:\\soft\\二维码文件传输工具\\测试\\111\\qr_code_3.png";
        String outImagePath = "C:\\soft\\二维码文件传输工具\\测试\\111\\out_qr_code_0111.png";
        concatenatedImage(imagePath1,imagePath2,imagePath3,imagePath4,outImagePath) ;
        clernFFmpeg();
    }

    public static void main1(String[] args) {
        // 视频文件路径
        String videoFilePath = "C:\\soft\\tesseract-ocr\\text/buqingxi.mp4";
        // 输出目录
        String outputDirPath = "C:\\soft\\tesseract-ocr\\text\\ffmpegPath1\\";
        videoToPic(videoFilePath,outputDirPath);
    }
}