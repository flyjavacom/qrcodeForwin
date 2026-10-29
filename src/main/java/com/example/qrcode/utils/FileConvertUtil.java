package com.example.qrcode.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
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

    private static final String TEMP_QRCODE_DIR = "/temp_qr_codes/";
    // 二维码生成名称前缀
    private static final String QRCODE_NAME_PREFIX ="qr_code_";

    // 每页二维码实际承载的数据长度（前4位是页码，所以每页数据是1020）
    private static final int SEGMENT_DATA_SIZE = PageCollector.SEGMENT_DATA_SIZE;

    // 识别是CPU密集型操作，线程数按CPU核数分配即可；开100~200个线程反而会因线程切换和内存争用变慢
    static ExecutorService executorService = new ThreadPoolExecutor(
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(10000));



    public static void main(String[] args) throws IOException {
        // 将二维码图片识别成文本并转成文件
        String qrcodeFilePath = "C:\\soft\\二维码文件传输工具\\20251117\\videoToPic";
        qrCode4ToFile(qrcodeFilePath);
    }

    /**
     * 将文件转成 二维码文件  【限制大小在7m 以内，这样生成的二维码个数在9999以内 】
     * 1.文件转成Base64 字符串  【Base64 编码后大约会变成 1.33 倍大小】  前面10位是文件后缀
     * 2.将Base64 字符串 按照1024长度取拆分成小字符串  1020原字符串 + 4位当前页码
     * 3.小字符串转成二维码图片 ，图片路径在当前文件路径 temp_qr_codes 文件夹下面
     * @param filePath
     * @return  存储二维码图片路径
     */
    public static String fileToQrCode(String filePath) throws Exception {
        String res = null;
        System.out.println("开始将文件转成 二维码文件" + filePath );
        // 将文件转成Base64 字符串  1MB 的数据在经过 Base64 编码后大约会变成 1.33 倍大小，即约 1.33MB。
        String base64String = ExcelBase64Converter.convertXlsToBase64(filePath);
        // 文件后缀
        String fileExtension = filePath.substring(filePath.lastIndexOf(".") + 1);
        // 10位长度的文件后缀
        String fileExtensionForLength10 = String.format("%10s",fileExtension);
        // 前面10位是文件后缀
        base64String = fileExtensionForLength10 + base64String;
        // 将文件拆分成小文本
        String[] segments = splitTextIntoSegments(base64String, SEGMENT_DATA_SIZE); //1024  留4位在首位增加当前4位

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
            String page = String.format("%4d",i);
            // 文件前面4位是当前页数
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
                        System.out.println("正在生成QR二维码: 文件路径=" + tempQrcodepath +" 4位页码+1020长度文件内容=" + segment);
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
        PageCollector collector = new PageCollector();

        File directory = new File(qrcodeFilePath);
        File[] files = directory.listFiles();
        if (files == null || files.length == 0) {
            throw new IOException("目录中没有图片文件：" + qrcodeFilePath);
        }
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

        // 分批处理（当待处理突破集合大于线程池最大队列容量之后会报RejectedExecutionException）
        int batchSize = 1000; // 每次提交的任务数量
        for (int i = 0; i < todoDecodeFilePathList.size(); i += batchSize) {
            int end = Math.min(i + batchSize, todoDecodeFilePathList.size());
            List<String> batch = todoDecodeFilePathList.subList(i, end);

            CountDownLatch countDownLatch = new CountDownLatch(batch.size());
            for (String filepath : batch) {
                executorService.execute(()->{
                    try {
                        String decode = QRCodeUtil.decode(filepath);
                        if (decode == null) {
                            return;
                        }
                        collector.accept(decode, filepath);
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

        return buildFileFromPages(qrcodeFilePath, collector.getPageContentMap(), collector.getConflictCount());
    }

    /**
     * 数据不完整异常：属于"识别结果不可用"的业务结果，不是流程故障，调用方不要退回其它处理方式
     */
    public static class DataIncompleteException extends IOException {
        public DataIncompleteException(String message) {
            super(message);
        }
    }

    /**
     * 生成文件前的完整性检查 + 按页码顺序拼接 + 写出文件
     *
     * 文件转二维码是按页顺序生成的，视频录制时若某一页翻得太快/模糊，就会整页识别不到。
     * 这里必须拦下来，否则按页码顺序拼接时缺页会取到 null，生成一个内容错位的损坏文件。
     *
     * @param outputDirPath 输出目录
     * @param pageContentMap 页码 → 该页数据
     * @param conflictCount 同一页识别冲突次数
     * @return 生成的文件路径
     */
    public static String buildFileFromPages(String outputDirPath,
                                            Map<Integer, String> pageContentMap,
                                            int conflictCount) throws IOException {
        return buildFileFromPages(outputDirPath, pageContentMap, conflictCount, null);
    }

    /**
     * 生成文件前的完整性检查 + 按页码顺序拼接 + 写出文件
     *
     * @param notRecordedPages 已确认"视频里根本没录到"的页（区分于识别失败），可为 null
     */
    public static String buildFileFromPages(String outputDirPath,
                                            Map<Integer, String> pageContentMap,
                                            int conflictCount,
                                            List<Integer> notRecordedPages) throws IOException {
        if (pageContentMap == null || pageContentMap.isEmpty()) {
            throw new DataIncompleteException("未识别到任何有效二维码！请确认视频拍摄清晰、二维码完整出现在画面中。");
        }

        int maxPage = Collections.max(pageContentMap.keySet());
        List<Integer> missingPages = new ArrayList<>();
        List<Integer> brokenPages = new ArrayList<>();
        for (int i = 0; i <= maxPage; i++) {
            String segment = pageContentMap.get(i);
            if (segment == null) {
                missingPages.add(i);
            } else if (i < maxPage && segment.length() != SEGMENT_DATA_SIZE) {
                // 非最后一页的长度必须等于单页容量，否则说明该页识别不完整（内容错位）
                brokenPages.add(i);
            }
        }
        if (!missingPages.isEmpty() || !brokenPages.isEmpty()) {
            throw new DataIncompleteException(buildIncompleteMessage(missingPages, brokenPages, maxPage,
                    pageContentMap.size(), conflictCount, notRecordedPages));
        }

        // 已校验 0~maxPage 全部存在，按页码顺序拼接
        System.out.println("完整性检查通过，共 " + (maxPage + 1) + " 页，开始按页码顺序拼接");
        StringBuilder bigContent = new StringBuilder((maxPage + 1) * SEGMENT_DATA_SIZE);
        for (int i = 0; i <= maxPage; i++) {
            bigContent.append(pageContentMap.get(i));
        }

        String content = bigContent.toString();
        if (content.length() < 10) {
            throw new DataIncompleteException("识别内容过短，无法解析文件头，数据不完整！");
        }
        // 获取文件后缀 并将空格去除
        String ext = content.substring(0, 10).replace(" ", "");
        // 原base64文本
        String base64string = content.substring(10);

        // Base64编码后的总长度必定是4的倍数，不是4的倍数说明最后一页二维码没有被录进视频（尾部缺失）
        if (base64string.length() % 4 != 0) {
            throw new DataIncompleteException("数据不完整，已停止生成文件！\n已识别到第 " + maxPage
                    + " 页，但 Base64 长度不是 4 的倍数，最后一页二维码很可能没有被录制到。\n建议：录制时在最后一页多停留 1~2 秒后重新录制。");
        }
        if (pageContentMap.get(maxPage).length() == SEGMENT_DATA_SIZE) {
            System.out.println("提示：最后一页内容为满页，若原文件较大请确认视频末尾没有漏录");
        }

        // 先解码校验，校验通过后再写文件，避免留下损坏文件
        byte[] fileBytes;
        try {
            fileBytes = Base64.getDecoder().decode(base64string);
        } catch (IllegalArgumentException e) {
            throw new DataIncompleteException("数据不完整，已停止生成文件！\nBase64 内容校验失败，可能存在识别错误的二维码页（识别到 "
                    + pageContentMap.size() + " 页，最大页码 " + maxPage + "），请重新录制视频后重试。");
        }

        String timestamp = new SimpleDateFormat("yyyy-MM-dd-HHmmss").format(new Date());
        System.out.println("文件后缀=" + ext + " 共 " + pageContentMap.size() + " 页，数据校验通过，开始生成文件");
        //  BASE64文本 ==》  文件
        Path outputDir = Paths.get(outputDirPath);
        if (!Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
        }
        String outputFilePath = outputDirPath + "/" + timestamp + "." + ext;
        Files.write(Paths.get(outputFilePath), fileBytes);
        System.out.println("已生成文件：" + outputFilePath + " 大小=" + fileBytes.length + " 字节");
        return outputFilePath;
    }

    /**
     * 拼装"数据不完整"的提示信息，明确告诉用户缺了哪些页
     */
    private static String buildIncompleteMessage(List<Integer> missingPages, List<Integer> brokenPages,
                                                 int maxPage, int decodedCount, int conflictCount,
                                                 List<Integer> notRecordedPages) {
        StringBuilder sb = new StringBuilder("数据不完整，已停止生成文件（避免生成内容错位的损坏文件）。\n");
        sb.append("共识别到 ").append(decodedCount).append(" 页，最大页码 ").append(maxPage).append("。");
        if (!missingPages.isEmpty()) {
            sb.append("\n缺少二维码页：").append(joinPages(missingPages))
              .append("（共 ").append(missingPages.size()).append(" 页）");
        }
        if (notRecordedPages != null && !notRecordedPages.isEmpty()) {
            // 这些页已把相邻两页之间的每一帧都用最强引擎强解过，仍解不出来
            sb.append("\n其中第 ").append(joinPages(notRecordedPages))
              .append(" 页在视频里始终读不出来：已把左右相邻两页之间的每一帧都单独强解过，没有一帧能解出这一页。");
            sb.append("\n常见原因有两个：① 录制时这一页几乎没停住，甚至没被拍到；")
              .append("② 视频被聊天工具压缩过，二维码糊掉了（压缩版的码点只剩几个像素，无法还原）。");
        }
        if (!brokenPages.isEmpty()) {
            sb.append("\n识别不完整的页：").append(joinPages(brokenPages))
              .append("（共 ").append(brokenPages.size()).append(" 页）");
        }
        if (conflictCount > 0) {
            sb.append("\n另有 ").append(conflictCount).append(" 页多次识别结果不一致。");
        }
        sb.append("\n建议：优先用手机原片（不要用微信/QQ 转发后的压缩版）；录制时放慢翻页速度，每页停留 0.5 秒以上，保持对焦清晰，重新录制后再试。");
        return sb.toString();
    }

    /**
     * 页码列表转成简短文本（最多列30个）
     */
    private static String joinPages(List<Integer> pages) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(pages.size(), 30);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(pages.get(i));
        }
        if (pages.size() > limit) {
            sb.append(" ...");
        }
        return sb.toString();
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
