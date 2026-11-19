package com.example.qrcode.utils;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
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

    // 二维码生成名称前缀
    private static final String QRCODE_NAME_PREFIX ="qr_code_";

    // 每页二维码实际承载的数据长度（前4位是页码，所以每页数据是1020）
    private static final int SEGMENT_DATA_SIZE = PageCollector.SEGMENT_DATA_SIZE;
    /** Legacy page numbers have four decimal digits (0 through 9999). */
    public static final long MAX_LEGACY_FILE_BYTES = 7L * 1024L * 1024L;
    private static final int MAX_LEGACY_PAGES = 10000;
    private static final String MANIFEST_REQUIRED_FILE = "requires_transfer_manifest.txt";

    public interface ProgressListener {
        void onProgress(int completedPages, int totalPages);
    }

    // 识别是CPU密集型操作，线程数按CPU核数分配即可；开100~200个线程反而会因线程切换和内存争用变慢
    static ExecutorService executorService = new ThreadPoolExecutor(
            Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors())),
            Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors())),
            60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1000));



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
        return fileToQrCode(filePath, null);
    }

    public static String fileToQrCode(String filePath, ProgressListener listener) throws Exception {
        Path sourceFile = Paths.get(filePath).toAbsolutePath();
        return fileToQrCode(filePath, sourceFile.getParent().resolve("temp_qr_codes").toString(), listener);
    }

    public static String fileToQrCode(String filePath, String outputDirectory, ProgressListener listener) throws Exception {
        String res = null;
        System.out.println("开始将文件转成 二维码文件" + filePath );
        Path sourceFile = Paths.get(filePath).toAbsolutePath();
        if (!Files.isRegularFile(sourceFile)) {
            throw new IOException("所选路径不是普通文件：" + sourceFile);
        }
        long sourceSize = Files.size(sourceFile);
        if (sourceSize > MAX_LEGACY_FILE_BYTES) {
            throw new IOException("旧协议最多支持 7 MiB 文件，当前文件大小为 " + sourceSize + " 字节。请拆分文件或使用后续扩展协议。");
        }
        // 将文件转成Base64 字符串  1MB 的数据在经过 Base64 编码后大约会变成 1.33 倍大小，即约 1.33MB。
        byte[] sourceBytes = Files.readAllBytes(sourceFile);
        String base64String = Base64.getEncoder().encodeToString(sourceBytes);
        // 文件后缀
        String fileExtension = sourceFile.getFileName().toString().substring(sourceFile.getFileName().toString().lastIndexOf(".") + 1);
        // 10位长度的文件后缀
        String fileExtensionForLength10 = String.format("%10s",fileExtension);
        // 前面10位是文件后缀
        base64String = fileExtensionForLength10 + base64String;
        // 将文件拆分成小文本
        String[] segments = splitTextIntoSegments(base64String, SEGMENT_DATA_SIZE); //1024  留4位在首位增加当前4位
        if (segments.length > MAX_LEGACY_PAGES) {
            throw new IOException("旧协议页码最多支持 10000 页，当前需要 " + segments.length + " 页。");
        }

        // 获取 Path 对象
        // 获取父路径
        Path outputPath = Paths.get(outputDirectory).toAbsolutePath();
        Files.createDirectories(outputPath);
        res = outputPath.toString() + File.separator;
        // 清理一下 存储二维码图片路径里面 旧文件
        FilesUtil.deleteFilesContainingString(new File(res),QRCODE_NAME_PREFIX);
        Files.deleteIfExists(Paths.get(res, "transfer_manifest.txt"));

        List<String> todoEncodeList= new ArrayList<>();
        Map<String,String> map = new HashMap<>();
        java.util.Queue<Exception> encodeErrors = new java.util.concurrent.ConcurrentLinkedQueue<>();
        java.util.concurrent.atomic.AtomicInteger completedPages = new java.util.concurrent.atomic.AtomicInteger();
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
                        System.out.println("正在生成二维码页：" + tempQrcodepath);
                    } catch (Exception e) {
                        encodeErrors.add(e);
                        System.out.println("使用多线程处理异常"+e.toString());
                    }finally {
                        int completed = completedPages.incrementAndGet();
                        if (listener != null) {
                            listener.onProgress(completed, segments.length);
                        }
                        // 计数器加一
                        countDownLatch.countDown();
                    }

                });
            }
            // 等待所有线程处理完成
            try {
                countDownLatch.await();
            }catch (InterruptedException e){
                Thread.currentThread().interrupt();
                throw e;
            }

        }
        if (!encodeErrors.isEmpty()) {
            throw new IOException("有 " + encodeErrors.size() + " 张二维码图片生成失败，未将本次转换报告为成功。", encodeErrors.peek());
        }
        for (String imagePath : todoEncodeList) {
            Path image = Paths.get(imagePath);
            if (!Files.isRegularFile(image) || Files.size(image) == 0) {
                throw new IOException("二维码图片没有成功生成：" + imagePath);
            }
        }
        TransferManifest manifest = new TransferManifest(segments.length, sourceBytes.length,
                TransferManifest.sha256(sourceBytes), sourceFile.getFileName().toString());
        Files.write(Paths.get(res, "transfer_manifest.txt"), manifest.toPayload().getBytes(StandardCharsets.UTF_8));
        return res;
    }

    /**
     * 图片二维码 解析并生成文件
     * @param qrcodeFilePath   图片二维码文件夹路径，里面有多个图片
     */
    public static String qrCode4ToFile(String qrcodeFilePath) throws IOException {
        return qrCode4ToFile(qrcodeFilePath, qrcodeFilePath);
    }

    public static String qrCode4ToFile(String qrcodeFilePath, String outputDirectory) throws IOException {
        System.out.println("开始图片二维码解析并生成文件  " + qrcodeFilePath);
        PageCollector collector = new PageCollector();
        if (Files.isRegularFile(Paths.get(qrcodeFilePath, MANIFEST_REQUIRED_FILE))) {
            collector.markMultiQrFrame();
        }
        Path manifestFile = Paths.get(qrcodeFilePath, "transfer_manifest.txt");
        if (Files.isRegularFile(manifestFile)) {
            try {
                String payload = new String(Files.readAllBytes(manifestFile), StandardCharsets.UTF_8).trim();
                if (!payload.startsWith(TransferManifest.PREFIX)) {
                    throw new IllegalArgumentException("清单标识无效");
                }
                TransferManifest.parse(payload);
                collector.accept(payload, manifestFile.toString());
            } catch (IllegalArgumentException ex) {
                throw new IOException("传输清单无效：" + manifestFile + "，" + ex.getMessage(), ex);
            }
        }

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
                        List<String> decodedPages = QRCodeUtil.decodeAll(filepath);
                        List<Integer> accepted = collector.acceptFrame(decodedPages, filepath);
                        boolean isControlFrame = decodedPages.stream()
                                .anyMatch(text -> text != null && text.startsWith(TransferManifest.PREFIX));
                        if (collector.hasMultiQrFrame() && accepted.isEmpty() && !isControlFrame) {
                            List<String> heavyPages = QRCodeUtil.decodeAllHeavy(ImageIO.read(new File(filepath)));
                            collector.acceptFrame(heavyPages, filepath + "(四码强解)");
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

        return buildFileFromPages(outputDirectory, collector, null);
    }

    /**
     * 保存视频中成功识别到的页为标准二维码图片，并生成缺页清单。
     * 输出图片可与后续补拍图片放在同一目录，直接交给 qrCode4ToFile 合并解析。
     */
    public static RecoveryInfo saveVideoRecoveryFiles(String outputDirPath,
                                                       Map<Integer, String> pageContentMap) throws IOException {
        return saveVideoRecoveryFiles(outputDirPath, pageContentMap, null, false);
    }

    public static RecoveryInfo saveVideoRecoveryFiles(String outputDirPath, PageCollector collector) throws IOException {
        return saveVideoRecoveryFiles(outputDirPath, collector, null);
    }

    public interface RecoverySaveProgressListener {
        void onProgress(int completedPages, int totalPages, long estimatedRemainingMillis);
    }

    public static RecoveryInfo saveVideoRecoveryFiles(String outputDirPath, PageCollector collector,
                                                       RecoverySaveProgressListener listener) throws IOException {
        if (collector == null) {
            return saveVideoRecoveryFiles(outputDirPath, Collections.<Integer, String>emptyMap(), null, false);
        }
        return saveVideoRecoveryFiles(outputDirPath, collector.getPageContentMap(), collector.getManifest(),
                collector.hasMultiQrFrame(), listener);
    }

    private static RecoveryInfo saveVideoRecoveryFiles(String outputDirPath,
                                                        Map<Integer, String> pageContentMap,
                                                        TransferManifest manifest,
                                                        boolean multiQrFrame) throws IOException {
        return saveVideoRecoveryFiles(outputDirPath, pageContentMap, manifest, multiQrFrame, null);
    }

    private static RecoveryInfo saveVideoRecoveryFiles(String outputDirPath,
                                                        Map<Integer, String> pageContentMap,
                                                        TransferManifest manifest,
                                                        boolean multiQrFrame,
                                                        RecoverySaveProgressListener listener) throws IOException {
        Path recoveryDir = Paths.get(outputDirPath, "recognized_qr");
        Files.createDirectories(recoveryDir);
        List<Integer> pages = new ArrayList<>(pageContentMap.keySet());
        Collections.sort(pages);
        List<Integer> missingPages = new ArrayList<>();
        int expectedTotalPages = manifest == null
                ? (pages.isEmpty() ? 0 : pages.get(pages.size() - 1) + 1)
                : manifest.totalPages;
        if (expectedTotalPages > 0) {
            for (int i = 0; i < expectedTotalPages; i++) {
                if (!pageContentMap.containsKey(i)) {
                    missingPages.add(i);
                }
            }
        }

        long saveStarted = System.currentTimeMillis();
        int completed = 0;
        for (Integer page : pages) {
            String payload = pageContentMap.get(page);
            String pagePrefix = String.format(Locale.ROOT, "%4d", page);
            QRCodeUtil.encode(pagePrefix + payload,
                    recoveryDir.resolve(QRCODE_NAME_PREFIX + page + ".png").toString());
            completed++;
            if (listener != null && (completed == pages.size() || completed % 5 == 0)) {
                long elapsed = System.currentTimeMillis() - saveStarted;
                long eta = completed < pages.size() && elapsed > 0
                        ? Math.round((double) elapsed * (pages.size() - completed) / completed) : 0L;
                listener.onProgress(completed, pages.size(), eta);
            }
        }
        if (manifest != null) {
            QRCodeUtil.encode(manifest.toPayload(), recoveryDir.resolve("transfer_manifest.png").toString());
        } else if (multiQrFrame) {
            Files.write(recoveryDir.resolve(MANIFEST_REQUIRED_FILE),
                    "该多码传输未识别到文件清单；请重新生成四码帧并完整录制。".getBytes(StandardCharsets.UTF_8));
        }

        String legacyTailWarning = "";
        if (manifest == null && !multiQrFrame && !pages.isEmpty()) {
            StringBuilder receivedPayload = new StringBuilder();
            boolean contiguous = true;
            for (int i = 0; i < pages.size(); i++) {
                if (pages.get(i) != i) {
                    contiguous = false;
                    break;
                }
                receivedPayload.append(pageContentMap.get(i));
            }
            if (contiguous && receivedPayload.length() >= 10) {
                int base64Length = receivedPayload.length() - 10;
                if (base64Length % 4 != 0) {
                    String lastPage = pageContentMap.get(pages.get(pages.size() - 1));
                    legacyTailWarning = lastPage != null && lastPage.length() == SEGMENT_DATA_SIZE
                            ? "注意：已识别的页码连续且最后一页也是满页，但 Base64 长度 " + base64Length
                                + " 余数为 " + (base64Length % 4) + "；数据尾部仍不完整，可能还缺最大已识别页之后的后续页。"
                                + "旧协议没有总页数清单，不能仅凭当前已识别页数判断传输完成。\n"
                            : "注意：Base64 长度 " + base64Length + " 不是 4 的倍数，已识别末页可能只解出部分内容。\n";
                }
            }
        }

        StringBuilder report = new StringBuilder();
        report.append("视频二维码识别恢复清单\n")
                .append("已识别页数：").append(pages.size()).append("\n")
                .append("文件总页数：").append(manifest == null
                        ? (multiQrFrame ? "未知（多码帧缺少文件清单）" : "未知（旧协议）")
                        : manifest.totalPages).append("\n")
                .append("已识别页码：").append(pages.isEmpty() ? "无" : joinPages(pages)).append("\n")
                .append("缺失页：")
                .append(missingPages.isEmpty() ? "无" : joinPages(missingPages)).append("\n")
                .append(legacyTailWarning)
                .append(manifest == null
                        ? (multiQrFrame
                            ? "注意：多码帧缺少文件清单，无法可靠判断完整性；系统会阻止据此恢复，请重新生成并完整录制。\n"
                            : "注意：旧协议视频不提供总页数，无法判断最大已识别页之后是否还缺尾页。\n")
                        : "本视频携带页数和 SHA-256 清单，尾页也会纳入完整性校验。\n")
                .append("请将补拍的二维码照片放入本目录，然后使用“二维码图片==》文件”选择本目录解析。\n")
                .append("目录：").append(recoveryDir.toAbsolutePath()).append("\n");
        Path reportPath = Paths.get(outputDirPath, "missing_pages.txt");
        Files.write(reportPath, report.toString().getBytes(StandardCharsets.UTF_8));
        return new RecoveryInfo(recoveryDir.toString(), reportPath.toString(), missingPages, pages.size());
    }

    public static final class RecoveryInfo {
        public final String recoveryDir;
        public final String reportPath;
        public final List<Integer> missingPages;
        public final int recognizedPageCount;

        RecoveryInfo(String recoveryDir, String reportPath, List<Integer> missingPages, int recognizedPageCount) {
            this.recoveryDir = recoveryDir;
            this.reportPath = reportPath;
            this.missingPages = Collections.unmodifiableList(new ArrayList<>(missingPages));
            this.recognizedPageCount = recognizedPageCount;
        }
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
        return buildFileFromPages(outputDirPath, pageContentMap, conflictCount, null, null, false);
    }

    /**
     * 生成文件前的完整性检查 + 按页码顺序拼接 + 写出文件
     *
     * @param unresolvedPages 视频定向补采后仍未识别到的页；该结果不证明页面没有入镜，可为 null
     */
    public static String buildFileFromPages(String outputDirPath,
                                            Map<Integer, String> pageContentMap,
                                            int conflictCount,
                                            List<Integer> unresolvedPages) throws IOException {
        return buildFileFromPages(outputDirPath, pageContentMap, conflictCount,
                unresolvedPages, null, false);
    }

    public static String buildFileFromPages(String outputDirPath, PageCollector collector,
                                            List<Integer> unresolvedPages) throws IOException {
        if (collector == null) {
            throw new DataIncompleteException("没有二维码识别结果");
        }
        if (collector.hasMultiQrFrame() && collector.getManifest() == null) {
            throw new DataIncompleteException("检测到一帧包含多个二维码，但没有收到文件页数和 SHA-256 清单；为避免静默生成不完整文件，已停止恢复。"
                    + "请使用新版发送端重新生成四码图片并完整录制。");
        }
        return buildFileFromPages(outputDirPath, collector.getPageContentMap(), collector.getConflictCount(),
                unresolvedPages, collector.getManifest(), collector.hasMultiQrFrame());
    }

    private static String buildFileFromPages(String outputDirPath,
                                             Map<Integer, String> pageContentMap,
                                             int conflictCount,
                                             List<Integer> unresolvedPages,
                                             TransferManifest manifest,
                                             boolean multiQrFrame) throws IOException {
        if (pageContentMap == null || pageContentMap.isEmpty()) {
            if (manifest != null) {
                throw new DataIncompleteException("文件清单要求 " + manifest.totalPages + " 页，但没有识别到有效数据页。");
            }
            throw new DataIncompleteException("未识别到任何有效二维码！请确认视频拍摄清晰、二维码完整出现在画面中。");
        }

        int observedMaxPage = Collections.max(pageContentMap.keySet());
        int maxPage = manifest == null ? observedMaxPage : manifest.totalPages - 1;
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
        if (manifest != null) {
            for (Integer page : pageContentMap.keySet()) {
                if (page > maxPage) {
                    brokenPages.add(page);
                }
            }
            if (conflictCount > 0) {
                throw new DataIncompleteException("数据不完整：同一二维码页或文件清单存在冲突（" + conflictCount
                        + " 次），为避免写出错误文件已停止。");
            }
        }
        if (!missingPages.isEmpty() || !brokenPages.isEmpty()) {
            throw new DataIncompleteException(buildIncompleteMessage(missingPages, brokenPages, maxPage,
                    pageContentMap.size(), conflictCount, unresolvedPages, multiQrFrame));
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

        // Base64 encoded data must have a length divisible by four. This can mean
        // the final page was absent or a decoder only returned a truncated payload.
        if (base64string.length() % 4 != 0) {
            throw new DataIncompleteException("数据不完整，已停止生成文件！\n已识别的最大页码是 " + maxPage
                    + " 页，但拼接后的 Base64 长度 " + base64string.length() + " 不是 4 的倍数。"
                    + "可能是最后一页未录全，或视频解码曾只返回该页的部分内容。\n"
                    + "请确认最后一页清晰完整，并在末页多停留 1~2 秒后重试；若仍失败，请保留识别结果供进一步检查。");
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
        if (manifest != null && !manifest.matches(fileBytes)) {
            throw new DataIncompleteException("文件完整性校验失败：文件长度或 SHA-256 与发送端清单不一致，未写出恢复文件。");
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date());
        System.out.println("文件后缀=" + ext + " 共 " + pageContentMap.size() + " 页，数据校验通过，开始生成文件");
        //  BASE64文本 ==》  文件
        Path outputDir = Paths.get(outputDirPath);
        if (!Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
        }
        String originalFileName = manifest == null ? null : manifest.originalFileName;
        String prefix = originalFileName == null ? "recovered" : baseNameWithoutExtension(originalFileName);
        if (prefix.isEmpty()) {
            prefix = "recovered";
        }
        Path outputFile = outputDir.resolve(prefix + "_" + timestamp + "." + ext);
        Files.write(outputFile, fileBytes);
        String outputFilePath = outputFile.toString();
        System.out.println("已生成文件：" + outputFilePath + " 大小=" + fileBytes.length + " 字节");
        return outputFilePath;
    }

    private static String baseNameWithoutExtension(String fileName) {
        String safe = fileName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        int dot = safe.lastIndexOf('.');
        return dot > 0 ? safe.substring(0, dot) : safe;
    }

    /**
     * 拼装"数据不完整"的提示信息，明确告诉用户缺了哪些页
     */
    private static String buildIncompleteMessage(List<Integer> missingPages, List<Integer> brokenPages,
                                                 int maxPage, int decodedCount, int conflictCount,
                                                 List<Integer> unresolvedPages, boolean multiQrFrame) {
        StringBuilder sb = new StringBuilder("数据不完整，已停止生成文件（避免生成内容错位的损坏文件）。\n");
        sb.append("共识别到 ").append(decodedCount).append(" 页，最大页码 ").append(maxPage).append("。");
        if (!missingPages.isEmpty()) {
            sb.append("\n缺少二维码页：").append(joinPages(missingPages))
              .append("（共 ").append(missingPages.size()).append(" 页）");
            sb.append("\n对应拍摄位置：").append(describeCaptureLocations(missingPages, multiQrFrame));
        }
        if (unresolvedPages != null && !unresolvedPages.isEmpty()) {
            sb.append("\n其中第 ").append(joinPages(unresolvedPages))
              .append(" 页在本次定向补采中仍未识别到。仅凭识别失败不能判断二维码没有入镜；")
              .append("四码画面可能只有对应象限被遮挡、裁切或模糊，也可能受视频压缩影响。");
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

    private static String describeCaptureLocations(List<Integer> missingPages, boolean multiQrFrame) {
        StringBuilder result = new StringBuilder();
        int limit = Math.min(missingPages.size(), 20);
        for (int i = 0; i < limit; i++) {
            int page = missingPages.get(i);
            if (i > 0) {
                result.append("；");
            }
            if (multiQrFrame) {
                int dataImage = page / 4 + 1;
                String[] positions = {"左上", "右上", "左下", "右下"};
                result.append("第 ").append(page).append(" 页 → 第 ").append(dataImage)
                        .append(" 张数据图片的").append(positions[page % 4])
                        .append("二维码（含开头清单控制画面时为第 ").append(dataImage + 1).append(" 个画面）");
            } else {
                result.append("第 ").append(page).append(" 页 → 单码顺序下第 ")
                        .append(page + 1).append(" 张二维码图片");
            }
        }
        if (missingPages.size() > limit) {
            result.append("；其余页码位置请按同一规则换算");
        }
        if (multiQrFrame) {
            result.append("。四码位置按每张图片左上、右上、左下、右下排列；数据页码从 0 开始。");
        } else {
            result.append("。页码从 0 开始；若实际视频是四码画面，请以识别日志中的多码协议判断位置。");
        }
        return result.toString();
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
