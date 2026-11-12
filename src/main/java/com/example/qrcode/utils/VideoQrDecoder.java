package com.example.qrcode.utils;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 视频 ==》 文件（流式识别）
 *
 * 老流程：视频 ==》 按帧落盘成几千张 PNG ==》 再逐张读盘识别
 *   中间图片的 PNG 编码、写盘、读盘、PNG 解码占了大量时间
 * 新流程：ffmpeg 直接把裸 RGB 帧通过管道送到内存 ==》 内存里去重 ==》 多线程识别
 *
 * 关键点：
 * 1. 不落盘中间帧
 * 2. 同一页二维码在视频里会连续停留多帧，一页只识别一次
 * 3. 翻页过渡帧只用轻量引擎试一次，停留页才走完整识别链
 * 4. 轻量引擎全失败、但画面里确实有二维码时，用最重的引擎补试一次
 * 5. 第一遍识别后若仍缺页，只回读"缺页所在的那一两秒"做定向补采，
 *    不再把整段视频重扫一遍（第一遍已按原生帧率采样，重扫拿到的还是同样的画面，纯属浪费）
 */
public class VideoQrDecoder {

    /**
     * 默认采样帧率：每秒取 30 帧。
     * 生成端是每秒翻 5 页（每页停留约 200ms），按 30fps 采样每页能拿到约 6 帧，
     * 才有机会避开液晶翻页的撕裂帧；15fps 时每页只有 2~3 帧，丢页率明显偏高。
     */
    public static final int DEFAULT_FPS = 30;

    /** 采样帧率上限：再高的帧率对二维码识别没有帮助，只会拖慢处理 */
    private static final int MAX_SAMPLE_FPS = 60;

    /** 重复帧判定阈值：32x32 灰度特征平均差小于该值视为同一页，跳过识别 */
    private static final double DUPLICATE_THRESHOLD = 3.0;

    /**
     * 稳定帧数阈值：同一画面连续出现这么多帧，才认为这是一个"停留的二维码页"。
     * 停留页识别成功率高，走完整识别链；一闪而过的画面多为翻页过渡，只做轻量识别。
     */
    private static final int STABLE_RUN_LENGTH = 2;

    /** 缺页少于这个数量才做逐页定向补采；超过说明视频质量整体很差，改走全片精修 */
    private static final int MAX_TARGETED_PAGES = 200;

    /**
     * 首轮识别时把画面缩到这个宽度。
     * 实测 1080p 素材：Boofcv 原始分辨率 97ms/帧（成功 35/40）→ 1440 宽 42ms（36/40）
     * → 1152 宽 17ms（36/40）；再降到 1024 宽成功率掉到 27/40，所以 1152 是速度与准确率的平衡点。
     * 缩放识别漏掉的少数页，由补采阶段用原始分辨率 + 重引擎逐帧强解找回。
     */
    private static final int FIRST_PASS_MAX_WIDTH = 1152;

    /** 补采时在窗口两端各留的余量（秒） */
    private static final double RESCUE_PADDING = 0.2;

    /** 单个补采窗口最多读多少秒，防止异常情况下长时间打转 */
    private static final double MAX_RESCUE_WINDOW = 3.0;

    /** 每个缺页窗口最多调用多少次最重的强解，控制少量缺页时的补采耗时。 */
    private static final int MAX_RESCUE_HEAVY_ATTEMPTS = 30;

    /** 进度回调 */
    public interface ProgressListener {
        /**
         * @param message 展示给用户的进度文本
         * @param percent 0-100
         */
        void onProgress(String message, int percent);
    }

    /**
     * 视频转文件（流式，不落盘中间帧）
     *
     * @param videoFilePath 视频文件
     * @param outputDirPath 生成文件所在目录
     * @param fps           采样帧率（内部会按视频原生帧率自动提升，保证每个原生帧都不漏）
     * @param listener      进度回调，可为 null
     * @return 生成的文件路径
     */
    public static String videoToFile(String videoFilePath, String outputDirPath,
                                     int fps, ProgressListener listener) throws Exception {
        long startTime = System.currentTimeMillis();
        final PageCollector collector = new PageCollector();

        FFmpegUtil.VideoInfo info = FFmpegUtil.probeVideo(videoFilePath);
        // 手机上录的是 1080p/4K；画面宽度不足 1280 基本就是聊天工具转发后的压缩版，
        // 二维码码点被压成几个像素，识别会大面积失败，提前提醒用户换原片，别白等十几分钟
        if (info.width > 0 && info.height > 0 && Math.min(info.width, info.height) < 720) {
            String hint = "提示：视频短边分辨率只有 " + Math.min(info.width, info.height) + " 像素（"
                    + info.width + "x" + info.height + "）"
                    + "，很可能是聊天工具转发后的压缩版。压缩会把二维码压糊，容易大量丢页，"
                    + "建议直接用手机原片。";
            System.out.println(hint);
            if (listener != null) {
                listener.onProgress(hint, 1);
            }
        }
        // 采样帧率不能低于视频原生帧率，否则等于主动扔掉画面（每页只停留 200ms，少采就是丢页）
        int sampleFps = resolveSampleFps(fps, info);
        report(listener, "视频信息：" + info.width + "x" + info.height + "，时长 "
                + formatDuration((long) (info.durationSeconds * 1000)) + "，采样帧率 " + sampleFps + " fps", 2);

        // 每页出现的时间点：页号 -> [首次时间, 末次时间]，缺页定向补采靠它定位
        final Map<Integer, double[]> pageTimes = new ConcurrentHashMap<>();

        // 第一遍：常规采样
        long firstPassStarted = System.currentTimeMillis();
        runPass(videoFilePath, sampleFps, info, collector, pageTimes, listener, "识别", 0, 70);
        long firstPassMillis = Math.max(1L, System.currentTimeMillis() - firstPassStarted);

        // 检查缺页，只对缺的那几页做定向补采
        List<Integer> missingPages = findMissingPages(collector);
        List<Integer> unresolvedPages = new ArrayList<>();
        if (!missingPages.isEmpty()) {
            System.out.println("第一遍识别后有 " + missingPages.size() + " 页缺失：" + missingPages);
            report(listener, "首轮识别发现 " + missingPages.size() + " 页缺失：" + missingPages, 70);
            boolean fullResolutionRefined = false;
            if (missingPages.size() > MAX_TARGETED_PAGES) {
                System.out.println("缺失页数过多（" + missingPages.size() + "），不做逐页定向补采。"
                        + "通常说明视频画质太差（例如经过聊天工具压缩）或录制漏拍严重。");
                if (info.width > FIRST_PASS_MAX_WIDTH) {
                    // 首轮做过缩放：再做一次全分辨率精修，排除"缩放导致识别失败"的可能
                    if (listener != null) {
                        listener.onProgress("缺失页较多，正在用原始分辨率精修一遍", 70);
                    }
                    System.out.println("首轮识别做过缩放，改用原始分辨率 " + info.width + " 宽精修一遍");
                    runPass(videoFilePath, sampleFps, info, collector, pageTimes,
                            listener, "精修", 70, 96, 0);
                    fullResolutionRefined = true;
                    missingPages = findMissingPages(collector);
                    System.out.println("精修后仍缺 " + missingPages.size() + " 页");
                    report(listener, "原始分辨率精修后仍缺 " + missingPages.size() + " 页：" + missingPages, 96);
                }
            }
            if (!missingPages.isEmpty() && missingPages.size() <= MAX_TARGETED_PAGES) {
                int rescuePercentFrom = fullResolutionRefined ? 88 : 70;
                report(listener, "剩余 " + missingPages.size() + " 页，按相邻已识别页的时间范围逐帧补采",
                        rescuePercentFrom);
                rescueMissingPages(videoFilePath, info, sampleFps, collector, pageTimes,
                        missingPages, unresolvedPages, listener, rescuePercentFrom, 96,
                        firstPassMillis, info.durationSeconds);
                missingPages = findMissingPages(collector);
            }
            if (!unresolvedPages.isEmpty()) {
                System.out.println("以下页在本次定向补采中仍未识别到（不能据此断定未入镜）：" + unresolvedPages);
                report(listener, "定向补采后仍未识别到以下页：" + unresolvedPages, 97);
            }
            if (!missingPages.isEmpty()) {
                System.out.println("补采后仍缺 " + missingPages.size() + " 页");
                report(listener, "视频解析后仍缺 " + missingPages.size() + " 页：" + missingPages, 97);
            }
            if (missingPages.isEmpty()) {
                report(listener, "视频内 0 至最大已识别页码之间没有缺页。视频无法推断最大页码之后的尾页是否缺失。", 97);
            }
        }

        if (listener != null) {
            listener.onProgress("识别完成，共 " + collector.size() + " 页，正在做完整性校验并生成文件", 98);
        }
        report(listener, "识别完成，共 " + collector.size() + " 页，正在保存恢复二维码图片", 97);
        FileConvertUtil.RecoveryInfo recovery = FileConvertUtil.saveVideoRecoveryFiles(
                outputDirPath, collector, (completed, total, eta) -> {
                    String message = "[保存结果] 已生成 " + completed + "/" + total + " 张恢复二维码图片"
                            + (eta > 0 ? "，预计本阶段剩余约 " + formatEta(eta) : "");
                    report(listener, message, total <= 0 ? 97
                            : 97 + (int) Math.min(1L, completed * 1L / total));
                });
        report(listener, "恢复二维码已保存 " + recovery.recognizedPageCount + " 页到：" + recovery.recoveryDir, 98);
        report(listener, "缺页清单已保存到：" + recovery.reportPath, 98);
        report(listener, "正在校验并生成最终文件", 99);
        if (listener != null) {
            listener.onProgress("已保存已识别二维码至 " + recovery.recoveryDir
                    + "，缺页清单：" + recovery.reportPath, 99);
        }
        String recoveredFile = FileConvertUtil.buildFileFromPages(outputDirPath, collector, unresolvedPages);
        System.out.println("任务总耗时 " + formatDuration(System.currentTimeMillis() - startTime)
                + "（含扫描、补采、保存恢复二维码和文件校验），共识别 " + collector.size() + " 页");
        report(listener, "处理完成", 100);
        return recoveredFile;
    }

    private static void report(ProgressListener listener, String message, int percent) {
        if (listener != null) {
            listener.onProgress(message, percent);
        }
    }

    /**
     * 采样帧率：不低于视频原生帧率（不能主动丢画面），但有上限
     */
    private static int resolveSampleFps(int requestedFps, FFmpegUtil.VideoInfo info) {
        int fps = Math.max(requestedFps, DEFAULT_FPS);
        if (info.fps > 0) {
            fps = Math.max(fps, (int) Math.ceil(info.fps));
        }
        return Math.min(fps, MAX_SAMPLE_FPS);
    }

    /**
     * 扫一遍视频并识别
     */
    private static int runPass(String videoFilePath, int fps, FFmpegUtil.VideoInfo info,
                               PageCollector collector, Map<Integer, double[]> pageTimes,
                               ProgressListener listener, String label,
                               int percentFrom, int percentTo) throws Exception {
        return runPass(videoFilePath, fps, info, collector, pageTimes, listener, label,
                percentFrom, percentTo, FIRST_PASS_MAX_WIDTH);
    }

    /**
     * 扫一遍视频并识别
     *
     * @param maxWidth 识别时允许的最大画面宽度，0 表示使用原始分辨率
     */
    private static int runPass(String videoFilePath, int fps, FFmpegUtil.VideoInfo info,
                               PageCollector collector, Map<Integer, double[]> pageTimes,
                               ProgressListener listener, String label,
                               int percentFrom, int percentTo, int maxWidth) throws Exception {
        // 留出 CPU 给 ffmpeg 解码，避免和识别线程互相抢占
        final int threads = Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
        System.out.println("[" + label + "] 识别线程数=" + threads + " 采样帧率=" + fps);

        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        // 限制在途帧数量，避免内存被大量大图撑爆
        final Semaphore gate = new Semaphore(threads * 2);
        final AtomicInteger skippedFrames = new AtomicInteger();
        final AtomicInteger decodedFrames = new AtomicInteger();
        final int[][] lastSignature = new int[1][];
        final long[] lastReport = new long[]{0};
        final int span = Math.max(1, percentTo - percentFrom);
        final double frameSeconds = 1.0 / fps;

        // 当前这一页的候选帧：优先用"这一页最后出现的帧"——页面刚翻过来时的 1~2 帧
        // 往往是液晶撕裂/半新半旧的画面，采到它必然识别失败。
        final BufferedImage[] firstImage = new BufferedImage[1];
        final BufferedImage[] midImage = new BufferedImage[1];
        final BufferedImage[] prevImage = new BufferedImage[1];
        final BufferedImage[] lastImage = new BufferedImage[1];
        final int[] runLength = new int[1];
        final int[] lastFrameIndex = new int[1];

        int frames;
        try {
            FFmpegUtil.RawFrameOptions options = new FFmpegUtil.RawFrameOptions(fps)
                    .maxWidth(maxWidth);
            frames = FFmpegUtil.streamRawFrames(videoFilePath, info, options, (data, w, h, idx) -> {
                int[] signature = signature(data, w, h);
                lastFrameIndex[0] = idx;
                if (lastSignature[0] != null && isDuplicate(signature, lastSignature[0])) {
                    // 同一画面的后续帧：滚动保留最近两帧 + 中间一帧，作为识别候选
                    runLength[0]++;
                    skippedFrames.incrementAndGet();
                    prevImage[0] = lastImage[0];
                    lastImage[0] = toImage(data, w, h);
                    if (runLength[0] == 4) {
                        midImage[0] = lastImage[0];
                    }
                    return;
                }
                lastSignature[0] = signature;

                // 上一页结束，提交它的候选帧（最后一帧优先）
                submitRun(pool, gate, collector, decodedFrames, pageTimes, frameSeconds,
                        buildCandidates(lastImage[0], prevImage[0], midImage[0], firstImage[0]),
                        runLength[0], idx - 1);

                firstImage[0] = toImage(data, w, h);
                midImage[0] = null;
                prevImage[0] = null;
                lastImage[0] = firstImage[0];
                runLength[0] = 1;
            }, (processed, total, eta) -> {
                long now = System.currentTimeMillis();
                if (now - lastReport[0] < 800 && processed < total) {
                    return;
                }
                lastReport[0] = now;
                int stagePercent = (int) Math.min(100L, (processed * 100L + total / 2L) / total);
                int percent = percentFrom + (int) Math.min(span,
                        Math.max(1L, processed * (long) span / total));
                // 前几帧主要是启动 FFmpeg、初始化识别引擎，样本太少时外推会严重高估；
                // 先积累至少 5% 且不少于 30 帧的速度数据，再展示 ETA。
                long etaWarmupFrames = Math.max(30L, (total + 19L) / 20L);
                boolean etaReady = processed >= etaWarmupFrames && eta > 0;
                String message = "[" + label + "] 已扫描 " + processed + "/" + total + " 帧（"
                        + stagePercent + "%），已识别 " + collector.size() + " 页，跳过重复帧 "
                        + skippedFrames.get() + " 张"
                        + (etaReady ? "，本阶段预计剩余约 " + formatEta(eta) : "，正在估算本阶段耗时");
                if (listener != null) {
                    listener.onProgress(message, percent);
                }
            });

            // 最后一段画面也要识别
            submitRun(pool, gate, collector, decodedFrames, pageTimes, frameSeconds,
                    buildCandidates(lastImage[0], prevImage[0], midImage[0], firstImage[0]),
                    runLength[0], lastFrameIndex[0]);
        } finally {
            pool.shutdown();
        }

        if (frames <= 0) {
            throw new IOException("视频帧读取失败！请确认视频文件可以正常播放。");
        }
        // 等剩余识别任务跑完
        try {
            while (!pool.awaitTermination(1, TimeUnit.SECONDS)) {
                if (listener != null) {
                    listener.onProgress("[" + label + "] 正在完成最后一批二维码识别，已识别 " + collector.size() + " 页",
                            percentFrom + span - 1);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("视频二维码识别被中断。", e);
        }
        System.out.println("[" + label + "] 完成：读取帧 " + frames + "，重复帧 " + skippedFrames.get()
                + "，唯一画面段 " + Math.max(0, frames - skippedFrames.get())
                + "，已完成识别任务 " + decodedFrames.get() + "，累计识别 " + collector.size() + " 页。");
        return frames;
    }

    /**
     * 定向补采：只回读缺页所在的那一小段时间窗口，用最重的引擎逐帧强解。
     *
     * 相比"整段视频用更高帧率再扫一遍"，这里只会读几秒画面：
     * 第一遍已经按原生帧率采样过，整段重扫拿到的还是同样的画面，必然补不出东西。
     *
     * 多个缺页窗口并行回读（每个窗口一个 ffmpeg 进程），所以缺页较多时也能在几十秒内处理完。
     */
    private static void rescueMissingPages(String videoFilePath, FFmpegUtil.VideoInfo info, int fps,
                                           PageCollector collector, Map<Integer, double[]> pageTimes,
                                           List<Integer> missingPages, List<Integer> unresolvedPages,
                                           ProgressListener listener,
                                           int percentFrom, int percentTo,
                                           long firstPassMillis, double videoDurationSeconds) throws Exception {
        List<List<Integer>> groups = groupConsecutive(missingPages);
        // 并行窗口数：窗口内部是单线程逐帧强解，靠多窗口并行吃满 CPU，同时避免一个窗口内串行太久
        int windowThreads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
        final ExecutorService pool = Executors.newFixedThreadPool(windowThreads);
        final int span = Math.max(1, percentTo - percentFrom);
        final AtomicInteger finished = new AtomicInteger();
        final AtomicInteger recovered = new AtomicInteger();
        double totalWindowSeconds = 0;
        for (List<Integer> group : groups) {
            RescueWindow window = rescueWindowFor(group.get(0), group.get(group.size() - 1),
                    pageTimes, info.durationSeconds);
            totalWindowSeconds += window.duration;
        }
        final long estimatedRescueMillis = Math.max(0L, Math.round(firstPassMillis
                * totalWindowSeconds / Math.max(1.0, videoDurationSeconds)));
        final long rescueStarted = System.currentTimeMillis();

        try {
            for (List<Integer> group : groups) {
                pool.submit(() -> {
                    try {
                        int pageCount = rescueWindow(videoFilePath, info, fps, collector, pageTimes,
                                group, unresolvedPages, listener);
                        if (pageCount > 0) {
                            recovered.addAndGet(pageCount);
                        }
                    } catch (Throwable t) {
                        System.out.println("[补采] 第 " + describeRange(group) + " 页补采异常: " + t);
                    } finally {
                        int done = finished.incrementAndGet();
                        if (listener != null) {
                            int percent = percentFrom + (int) (done * (long) span / groups.size());
                            long elapsed = System.currentTimeMillis() - rescueStarted;
                            long eta = done >= groups.size() ? 0L
                                    : Math.max(estimatedRescueMillis - elapsed,
                                        Math.round((double) elapsed * (groups.size() - done) / done));
                            listener.onProgress("[补采] 已完成 " + done + "/" + groups.size()
                                    + " 个缺页窗口，补回 " + recovered.get() + " 页，累计识别 "
                                    + collector.size() + " 页"
                                    + (eta > 0 ? "，预计剩余 " + formatEta(eta) : "，正在收尾"), percent);
                        }
                    }
                });
            }
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.MINUTES);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 补采一组连续缺页：检查相邻已识别页之间的完整时间窗口，不截断窗口头部。
     * 四码帧即使普通解码读出了其它象限，也会对定位到的各个二维码区域分别强解。
     *
     * @return 补回的页数
     */
    private static int rescueWindow(String videoFilePath, FFmpegUtil.VideoInfo info, int fps,
                                    PageCollector collector, Map<Integer, double[]> pageTimes,
                                    List<Integer> group, List<Integer> unresolvedPages,
                                    ProgressListener listener) throws Exception {
        int firstPage = group.get(0);
        int lastPage = group.get(group.size() - 1);
        RescueWindow rescueWindow = rescueWindowFor(firstPage, lastPage, pageTimes, info.durationSeconds);
        final double windowEnd = rescueWindow.start + rescueWindow.duration;
        System.out.println("[补采] 页 " + describeRange(group) + "：时间窗 "
                + String.format(java.util.Locale.ROOT, "%.2f", rescueWindow.start) + "s~"
                + String.format(java.util.Locale.ROOT, "%.2f", windowEnd) + "s，时长 "
                + String.format(java.util.Locale.ROOT, "%.2f", rescueWindow.duration) + " 秒，采样 "
                + fps + " fps，原分辨率 " + info.width + "x" + info.height + "；逐个检查完整窗口。");
        report(listener, "[补采] 缺页 " + describeRange(group) + "：完整扫描 "
                + String.format(java.util.Locale.ROOT, "%.2f", rescueWindow.start) + "~"
                + String.format(java.util.Locale.ROOT, "%.2f", windowEnd) + " 秒，原分辨率 "
                + info.width + "x" + info.height + "，" + fps + " fps", 96);

        final java.util.Set<Integer> targetPages = new java.util.HashSet<>(group);
        final int[] seen = new int[1];
        final int[] normalAttempts = new int[1];
        final int[] heavyAttempts = new int[1];
        final Map<Integer, Map<Integer, Map<String, Integer>>> pendingFourQrGroups = new TreeMap<>();
        final List<Integer> foundPages = new ArrayList<>();
        final List<Double> foundTimes = new ArrayList<>();

        FFmpegUtil.streamRawFrames(videoFilePath, info,
                new FFmpegUtil.RawFrameOptions(fps).from(rescueWindow.start, rescueWindow.duration).maxWidth(0),
                (data, w, h, idx) -> {
                    seen[0]++;
                    if (allPagesFound(group, collector)) {
                        return;
                    }
                    BufferedImage image = toImage(data, w, h);
                    // decodeAll may return Collections.singletonList; copy before merging quadrant results.
                    List<String> decoded = new ArrayList<>(QRCodeUtil.decodeAll(image));
                    addUniqueDecoded(decoded, QRCodeUtil.decodeFourQuadrants(image));
                    normalAttempts[0]++;
                    collectRescueFrame(decoded, collector, pageTimes, targetPages,
                            foundPages, foundTimes, pendingFourQrGroups,
                            rescueWindow.start + idx / (double) fps, "窗口帧 " + idx);
                }, null);

        // 普通解码扫描完整窗口后仍有缺页，再均匀抽取最多 30 帧做区域强解。
        // 不缓存整段高清视频帧，避免多个补采窗口并行时占用大量内存。
        if (!allPagesFound(group, collector) && seen[0] > 0
                && !hasUnambiguousCompletePageGroup(targetPages, pendingFourQrGroups, collector.getManifest())) {
            final int stride = Math.max(1, (seen[0] + MAX_RESCUE_HEAVY_ATTEMPTS - 1)
                    / MAX_RESCUE_HEAVY_ATTEMPTS);
            FFmpegUtil.streamRawFrames(videoFilePath, info,
                    new FFmpegUtil.RawFrameOptions(fps).from(rescueWindow.start, rescueWindow.duration).maxWidth(0),
                    (data, w, h, idx) -> {
                        if (idx % stride != 0 || allPagesFound(group, collector)
                                || heavyAttempts[0] >= MAX_RESCUE_HEAVY_ATTEMPTS) {
                            return;
                        }
                        BufferedImage image = toImage(data, w, h);
                        // decodeAllHeavy also returns an immutable singleton for one-code frames.
                        List<String> decoded = new ArrayList<>(QRCodeUtil.decodeAllHeavy(image));
                        addUniqueDecoded(decoded, QRCodeUtil.decodeFourQuadrants(image));
                        heavyAttempts[0]++;
                        collectRescueFrame(decoded, collector, pageTimes, targetPages,
                                foundPages, foundTimes, pendingFourQrGroups,
                                rescueWindow.start + idx / (double) fps, "窗口强解帧 " + idx);
                    }, null);
        }

        // 只在窗口所有普通帧和强解候选收集完成后统一投票、判定并整组提交。
        commitCompleteFourQrGroups(pendingFourQrGroups, collector, pageTimes,
                targetPages, foundPages, foundTimes,
                rescueWindow.start + rescueWindow.duration / 2.0,
                "左右锚点完整时间窗");

        List<Integer> stillMissing = new ArrayList<>();
        for (Integer page : group) {
            if (!collector.getPageContentMap().containsKey(page)) {
                stillMissing.add(page);
            }
        }
        int recoveredNow = group.size() - stillMissing.size();
        for (int i = 0; i < foundPages.size(); i++) {
            System.out.println("[补采] 找回页 " + foundPages.get(i) + "，画面时间 "
                    + String.format(java.util.Locale.ROOT, "%.3f", foundTimes.get(i)) + "s");
        }
        System.out.println("[补采] 页 " + describeRange(group) + " 结果：找回 " + recoveredNow
                + "/" + group.size() + " 页；窗口逐帧扫描 " + seen[0]
                + " 帧，普通解码 " + normalAttempts[0] + " 次，区域强解 "
                + heavyAttempts[0] + " 次"
                + (stillMissing.isEmpty() || heavyAttempts[0] < MAX_RESCUE_HEAVY_ATTEMPTS
                    ? "。" : "（达到单窗口强解上限 " + MAX_RESCUE_HEAVY_ATTEMPTS + " 次）。"));
        if (!stillMissing.isEmpty()) {
            synchronized (unresolvedPages) {
                unresolvedPages.addAll(stillMissing);
            }
            System.out.println("[补采] 页 " + describeRange(stillMissing) + " 在完整时间窗内仍未识别；"
                    + "不能据此断定二维码没有入镜。");
            report(listener, "[补采] 页 " + describeRange(stillMissing) + " 未找回；完整窗口 "
                    + seen[0] + " 帧逐帧扫描，普通解码 "
                    + normalAttempts[0] + " 次、强解 " + heavyAttempts[0] + " 次", 96);
        } else {
            StringBuilder summary = new StringBuilder("[补采] 找回页");
            for (int i = 0; i < foundPages.size(); i++) {
                if (i > 0) summary.append(",");
                summary.append(' ').append(foundPages.get(i)).append('@')
                        .append(String.format(java.util.Locale.ROOT, "%.2f", foundTimes.get(i))).append('s');
            }
            summary.append("；逐帧扫描 ").append(seen[0]).append(" 帧，普通解码 ").append(normalAttempts[0])
                    .append(" 次、强解 ").append(heavyAttempts[0]).append(" 次");
            report(listener, summary.toString(), 96);
        }
        return recoveredNow;
    }

    private static void collectRescueFrame(List<String> decoded, PageCollector collector,
                                           Map<Integer, double[]> pageTimes,
                                           java.util.Set<Integer> targetPages,
                                           List<Integer> foundPages, List<Double> foundTimes,
                                           Map<Integer, Map<Integer, Map<String, Integer>>> pendingFourQrGroups,
                                           double time, String source) {
        if (decoded == null || decoded.isEmpty()) return;
        if (!collector.hasMultiQrFrame() && decoded.size() > 1) {
            collector.markMultiQrFrame();
            pageTimes.clear();
        }
        if (collector.hasMultiQrFrame()) {
            // 每帧对每个页码只投一票；窗口完成前不提交，避免早期误读抢先决定页内容。
            accumulateFourQrPages(decoded, pendingFourQrGroups);
        } else {
            acceptRescuePages(decoded, collector, pageTimes, targetPages,
                    foundPages, foundTimes, time, source);
        }
    }

    static void accumulateFourQrPages(List<String> decoded,
                                      Map<Integer, Map<Integer, Map<String, Integer>>> pendingGroups) {
        Map<Integer, Map<Integer, String>> framePages = new TreeMap<>();
        for (String text : decoded) {
            Integer page = PageCollector.parsePage(text);
            if (page == null || text.length() <= 4) continue;
            String content = text.substring(4);
            int groupStart = (page / 4) * 4;
            framePages.computeIfAbsent(groupStart, key -> new TreeMap<Integer, String>())
                    .putIfAbsent(page, content);
        }
        for (Map.Entry<Integer, Map<Integer, String>> group : framePages.entrySet()) {
            Map<Integer, Map<String, Integer>> pages = pendingGroups.computeIfAbsent(group.getKey(),
                    key -> new TreeMap<Integer, Map<String, Integer>>());
            for (Map.Entry<Integer, String> page : group.getValue().entrySet()) {
                Map<String, Integer> votes = pages.computeIfAbsent(page.getKey(),
                        key -> new java.util.HashMap<String, Integer>());
                votes.put(page.getValue(), votes.getOrDefault(page.getValue(), 0) + 1);
            }
        }
    }

    static void commitCompleteFourQrGroups(Map<Integer, Map<Integer, Map<String, Integer>>> pendingGroups,
                                           PageCollector collector,
                                           Map<Integer, double[]> pageTimes,
                                           java.util.Set<Integer> targetPages,
                                           List<Integer> foundPages, List<Double> foundTimes,
                                           double time, String source) {
        TransferManifest manifest = collector.getManifest();
        for (Map.Entry<Integer, Map<Integer, Map<String, Integer>>> entry : new ArrayList<>(pendingGroups.entrySet())) {
            int start = entry.getKey();
            int expected = manifest == null ? 4 : Math.min(4, Math.max(0, manifest.totalPages - start));
            Map<Integer, Map<String, Integer>> pages = entry.getValue();
            boolean complete = expected > 0 && pages.size() == expected;
            List<String> batch = new ArrayList<>(expected);
            for (int offset = 0; complete && offset < expected; offset++) {
                int page = start + offset;
                Map<String, Integer> votes = pages.get(page);
                String content = mostVotedContent(votes);
                complete = content != null && PageCollector.isValidSegmentContent(content);
                if (complete) batch.add(String.format(java.util.Locale.ROOT, "%4d", page) + content);
            }
            if (!complete) {
                if (expected > 0 && pages.size() == expected) {
                    StringBuilder voteSummary = new StringBuilder("[补采] 页组 ").append(start)
                            .append("~").append(start + expected - 1).append(" 投票未通过：");
                    for (int offset = 0; offset < expected; offset++) {
                        int page = start + offset;
                        Map<String, Integer> votes = pages.get(page);
                        int totalVotes = 0;
                        int maxVotes = 0;
                        int winners = 0;
                        if (votes != null) {
                            for (Integer count : votes.values()) {
                                totalVotes += count;
                                if (count > maxVotes) {
                                    maxVotes = count;
                                    winners = 1;
                                } else if (count == maxVotes) {
                                    winners++;
                                }
                            }
                        }
                        if (offset > 0) voteSummary.append("，");
                        voteSummary.append(page).append(":候选").append(votes == null ? 0 : votes.size())
                                .append("种/").append(totalVotes).append("票/最高")
                                .append(maxVotes).append("票");
                        if (winners > 1) voteSummary.append("(平票)");
                    }
                    System.out.println(voteSummary);
                } else if (expected > 0) {
                    System.out.println("[补采] 页组 " + start + "~" + (start + expected - 1)
                            + " 候选页数不足：收到 " + pages.size() + "/" + expected + " 个不同页码。");
                }
                continue;
            }
            acceptRescuePages(batch, collector, pageTimes, targetPages, foundPages, foundTimes,
                    time, source + "页组" + start + "~" + (start + expected - 1));
            if (allPagesPresent(start, expected, collector)) {
                pendingGroups.remove(start);
                System.out.println("[补采] 左右锚点时间窗投票确认页组 " + start + "~"
                        + (start + expected - 1) + "，整组提交。");
            }
        }
    }

    private static String mostVotedContent(Map<String, Integer> votes) {
        if (votes == null || votes.isEmpty()) return null;
        String best = null;
        int bestCount = 0;
        boolean tied = false;
        for (Map.Entry<String, Integer> vote : votes.entrySet()) {
            if (vote.getValue() > bestCount) {
                best = vote.getKey();
                bestCount = vote.getValue();
                tied = false;
            } else if (vote.getValue() == bestCount) {
                tied = true;
            }
        }
        return tied ? null : best;
    }

    private static boolean hasUnambiguousCompletePageGroup(java.util.Set<Integer> targetPages,
                                                             Map<Integer, Map<Integer, Map<String, Integer>>> pending,
                                                             TransferManifest manifest) {
        if (targetPages.isEmpty()) return false;
        int start = (Collections.min(targetPages) / 4) * 4;
        int expected = manifest == null ? 4 : Math.min(4, Math.max(0, manifest.totalPages - start));
        if (expected <= 0) return false;
        Map<Integer, Map<String, Integer>> pages = pending.get(start);
        if (pages == null || pages.size() != expected) return false;
        for (int offset = 0; offset < expected; offset++) {
            String winner = mostVotedContent(pages.get(start + offset));
            if (winner == null || !PageCollector.isValidSegmentContent(winner)) return false;
        }
        return true;
    }

    private static boolean allPagesPresent(int start, int count, PageCollector collector) {
        for (int offset = 0; offset < count; offset++) {
            if (!collector.getPageContentMap().containsKey(start + offset)) return false;
        }
        return true;
    }

    private static boolean allPagesFound(List<Integer> group, PageCollector collector) {
        for (Integer page : group) {
            if (!collector.getPageContentMap().containsKey(page)) {
                return false;
            }
        }
        return true;
    }

    private static void acceptRescuePages(List<String> decodedPages, PageCollector collector,
                                          Map<Integer, double[]> pageTimes, java.util.Set<Integer> targetPages,
                                          List<Integer> foundPages, List<Double> foundTimes,
                                          double time, String source) {
        String frameSource = source + "@" + String.format(java.util.Locale.ROOT, "%.3fs", time);
        java.util.Set<Integer> previouslyMissing = new java.util.HashSet<>();
        for (Integer page : targetPages) {
            if (!collector.getPageContentMap().containsKey(page)) {
                previouslyMissing.add(page);
            }
        }
        for (Integer page : acceptFramePages(decodedPages, collector, pageTimes, time, frameSource)) {
            if (previouslyMissing.remove(page)) {
                foundPages.add(page);
                foundTimes.add(time);
            }
        }
    }

    /** 原子接收单帧数据：四码模式要求一帧中的预期连续页全部解出，否则整帧不入库。 */
    static List<Integer> acceptFramePages(List<String> decoded, PageCollector collector,
                                          Map<Integer, double[]> pageTimes, double time,
                                          String source) {
        boolean wasMultiQr = collector.hasMultiQrFrame();
        List<Integer> acceptedPages = collector.acceptFrame(decoded, source);
        if (!wasMultiQr && collector.hasMultiQrFrame()) {
            pageTimes.clear();
        }
        for (Integer page : acceptedPages) {
            String payload = collector.getPageContentMap().get(page);
            if (payload != null) {
                recordPageTime(pageTimes, String.format(java.util.Locale.ROOT, "%4d", page) + payload, time);
            }
        }
        return acceptedPages;
    }

    /**
     * 把连续缺页（851,852,853）合并成一组，一组只回读一次窗口
     */
    private static List<List<Integer>> groupConsecutive(List<Integer> pages) {
        List<List<Integer>> groups = new ArrayList<>();
        List<Integer> current = null;
        for (Integer p : pages) {
            if (current == null || p != current.get(current.size() - 1) + 1) {
                current = new ArrayList<>();
                groups.add(current);
            }
            current.add(p);
        }
        return groups;
    }

    /**
     * 缺页的补采窗口
     */
    private static final class RescueWindow {
        /** 开始时间（秒） */
        final double start;
        /** 读取时长（秒） */
        final double duration;
        /** 左邻居页号（小于缺页的最近已知页），null 表示缺的是第一页 */
        final Integer lowPage;
        /** 右邻居页号（大于缺页的最近已知页），null 表示缺的是最后一页 */
        final Integer highPage;
        /** 窗口是否完整覆盖了左邻居末次出现 → 右邻居首次出现之间的全部画面 */
        final boolean fullSpan;

        RescueWindow(double start, double duration, Integer lowPage, Integer highPage, boolean fullSpan) {
            this.start = start;
            this.duration = duration;
            this.lowPage = lowPage;
            this.highPage = highPage;
            this.fullSpan = fullSpan;
        }
    }

    /**
     * 计算补采窗口：从"左邻居最后一次出现"到"右邻居第一次出现"之间的时间范围。
     * 这只能定位候选片段；补采实现还会受时长上限和保留帧数限制，不能据此断言二维码从未入镜。
     */
    private static RescueWindow rescueWindowFor(int firstPage, int lastPage,
                                                Map<Integer, double[]> pageTimes, double durationSeconds) {
        double tLow = 0;
        double tHigh = durationSeconds;
        Integer lowPage = null;
        Integer highPage = null;
        for (Map.Entry<Integer, double[]> entry : pageTimes.entrySet()) {
            double[] times = entry.getValue();
            if (entry.getKey() < firstPage) {
                if (lowPage == null || times[1] > tLow) {
                    tLow = times[1];
                    lowPage = entry.getKey();
                }
            } else if (entry.getKey() > lastPage) {
                if (highPage == null || times[0] < tHigh) {
                    tHigh = times[0];
                    highPage = entry.getKey();
                }
            }
        }
        if (highPage == null) {
            // 缺的是最后一页：末端没有已知页做锚点，往后放宽一些
            tHigh = Math.min(durationSeconds, tLow + MAX_RESCUE_WINDOW);
        }
        double start = Math.max(0, tLow - RESCUE_PADDING);
        double end = Math.min(durationSeconds, tHigh + RESCUE_PADDING);
        double needed = Math.max(0, end - start);
        // 两侧都有已识别页时，覆盖左页末次出现到右页首次出现的完整区间；
        // 固定时长上限只用于视频首尾缺页、缺少一侧锚点的情况。
        double duration = lowPage != null && highPage != null
                ? needed : Math.min(MAX_RESCUE_WINDOW, needed);
        boolean fullSpan = duration >= needed - 0.001 && highPage != null && lowPage != null;
        return new RescueWindow(start, duration, lowPage, highPage, fullSpan);
    }

    /**
     * 记录某页出现的时间点
     */
    private static void recordPageTime(Map<Integer, double[]> pageTimes, String decode, double time) {
        Integer page = PageCollector.parsePage(decode);
        if (page == null) {
            return;
        }
        pageTimes.compute(page, (key, times) -> {
            if (times == null) {
                return new double[]{time, time};
            }
            double first = Math.min(times[0], time);
            double last = Math.max(times[1], time);
            return new double[]{first, last};
        });
    }

    private static String describeRange(List<Integer> group) {
        if (group.size() == 1) {
            return String.valueOf(group.get(0));
        }
        return group.get(0) + "~" + group.get(group.size() - 1);
    }

    /**
     * 找出缺失的页码（0~最大页码之间没有识别到的页）
     */
    private static List<Integer> findMissingPages(PageCollector collector) {
        if (collector.getManifest() != null) {
            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < collector.getManifest().totalPages; i++) {
                if (!collector.getPageContentMap().containsKey(i)) {
                    missing.add(i);
                }
            }
            return missing;
        }
        Map<Integer, String> pageContentMap = collector.getPageContentMap();
        List<Integer> missing = new ArrayList<>();
        if (pageContentMap.isEmpty()) {
            return missing;
        }
        int maxPage = Collections.max(pageContentMap.keySet());
        for (int i = 0; i <= maxPage; i++) {
            if (!pageContentMap.containsKey(i)) {
                missing.add(i);
            }
        }
        return missing;
    }

    /**
     * 组装一页的候选帧（按优先级：最后一帧 → 倒数第二帧 → 中间帧 → 首帧），同一张图不重复入列
     */
    private static List<BufferedImage> buildCandidates(BufferedImage last, BufferedImage prev,
                                                       BufferedImage mid, BufferedImage first) {
        List<BufferedImage> list = new ArrayList<>(4);
        for (BufferedImage img : new BufferedImage[]{last, prev, mid, first}) {
            if (img == null) {
                continue;
            }
            boolean duplicated = false;
            for (BufferedImage exist : list) {
                if (exist == img) {
                    duplicated = true;
                    break;
                }
            }
            if (!duplicated) {
                list.add(img);
            }
        }
        return list;
    }

    /**
     * 提交一页的候选帧去识别
     *
     * 同一页在视频里会连续出现多帧，其中翻页瞬间的那几帧常常是液晶撕裂/半新半旧。
     * 这里按"最后一帧优先"逐张尝试；若轻量引擎全部失败，但确实在画面里找到了二维码区域，
     * 再用最重的 OpenCV 引擎补试一次——很多页只有它才能解出来。
     *
     * @param runLength 该页面连续出现了多少帧：稳定停留的页走完整识别链，一闪而过的只做轻量识别
     */
    private static void submitRun(ExecutorService pool, Semaphore gate, PageCollector collector,
                                  AtomicInteger decodedFrames, Map<Integer, double[]> pageTimes,
                                  double frameSeconds, List<BufferedImage> images,
                                  int runLength, int frameIndex) {
        if (images == null || images.isEmpty()) {
            return;
        }
        final List<BufferedImage> candidates = images;
        final double time = frameIndex * frameSeconds;
        try {
            gate.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        pool.submit(() -> {
            try {
                boolean foundQrRegion = false;
                List<String> normalUnion = new ArrayList<>();
                for (BufferedImage image : candidates) {
                    List<String> pages = QRCodeUtil.decodeAll(image);
                    if (!pages.isEmpty()) {
                        foundQrRegion = true;
                        addUniqueDecoded(normalUnion, pages);
                        // 单码旧视频沿用首个成功候选策略；四码帧继续尝试候选图，
                        // 以便从另一张稳定候选图补到被过渡帧遮挡的格子。
                    }
                }
                List<Integer> accepted = acceptFramePages(normalUnion, collector, pageTimes, time,
                        "画面段 " + frameIndex + " 候选集合");
                // 轻量引擎全败，但画面里确实有二维码 → 用最重的引擎再试这一页渲染最完整的那帧
                if (foundQrRegion) {
                    if (collector.hasMultiQrFrame()) {
                        if (accepted.isEmpty() && !containsManifest(normalUnion)) {
                            List<String> heavyUnion = new ArrayList<>();
                            for (BufferedImage candidate : candidates) {
                                addUniqueDecoded(heavyUnion, QRCodeUtil.decodeAllHeavy(candidate));
                            }
                            acceptFramePages(heavyUnion, collector, pageTimes, time,
                                    "画面段 " + frameIndex + " 四码强解集合");
                        }
                    } else {
                        QRCodeUtil.Outcome heavy = QRCodeUtil.decodeDetailed(candidates.get(0), true, true);
                        if (heavy.text != null) {
                            acceptFramePages(Collections.singletonList(heavy.text), collector,
                                    pageTimes, time, "画面段 " + frameIndex + " 重试");
                        }
                    }
                }
            } catch (Throwable t) {
                System.out.println("识别帧异常: " + t);
            } finally {
                decodedFrames.incrementAndGet();
                gate.release();
            }
        });
    }

    private static void addUniqueDecoded(List<String> target, List<String> decoded) {
        if (decoded == null) {
            return;
        }
        for (String text : decoded) {
            if (text != null && !target.contains(text)) {
                target.add(text);
            }
        }
    }

    private static boolean containsManifest(List<String> decoded) {
        if (decoded == null) {
            return false;
        }
        for (String text : decoded) {
            if (text != null && text.startsWith(TransferManifest.PREFIX)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从原始灰度数据里抽 32x32 特征，用于快速判断是否为同一页二维码（纯算术，毫秒级）
     */
    private static int[] signature(byte[] data, int width, int height) {
        final int size = 32;
        int[] sig = new int[size * size];
        for (int y = 0; y < size; y++) {
            int rowOffset = (int) ((long) y * height / size) * width;
            for (int x = 0; x < size; x++) {
                sig[y * size + x] = data[rowOffset + (int) ((long) x * width / size)] & 0xFF;
            }
        }
        return sig;
    }

    private static boolean isDuplicate(int[] current, int[] last) {
        long diff = 0;
        for (int i = 0; i < current.length; i++) {
            diff += Math.abs(current[i] - last[i]);
        }
        return (double) diff / current.length < DUPLICATE_THRESHOLD;
    }

    /**
     * 灰度裸数据 ==》 BufferedImage（TYPE_BYTE_GRAY，识别引擎都能直接吃）
     */
    private static BufferedImage toImage(byte[] grayData, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        byte[] target = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        System.arraycopy(grayData, 0, target, 0, width * height);
        return image;
    }

    private static String formatDuration(long millis) {
        long totalSeconds = millis / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes > 0) {
            return minutes + "分" + seconds + "秒";
        }
        return seconds + "秒";
    }

    private static String formatEta(long millis) {
        if (millis > 0 && millis < 1000) {
            return "不足1秒";
        }
        return "约" + formatDuration((millis + 999) / 1000 * 1000);
    }
}
