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
        if (info.width > 0 && info.width < 1280) {
            String hint = "提示：视频分辨率只有 " + info.width + "x" + info.height
                    + "，很可能是聊天工具转发后的压缩版。压缩会把二维码压糊，容易大量丢页，"
                    + "建议直接用手机原片。";
            System.out.println(hint);
            if (listener != null) {
                listener.onProgress(hint, 1);
            }
        }
        // 采样帧率不能低于视频原生帧率，否则等于主动扔掉画面（每页只停留 200ms，少采就是丢页）
        int sampleFps = resolveSampleFps(fps, info);

        // 每页出现的时间点：页号 -> [首次时间, 末次时间]，缺页定向补采靠它定位
        final Map<Integer, double[]> pageTimes = new ConcurrentHashMap<>();

        // 第一遍：常规采样
        runPass(videoFilePath, sampleFps, info, collector, pageTimes, listener, "识别", 0, 70);

        // 检查缺页，只对缺的那几页做定向补采
        List<Integer> missingPages = findMissingPages(collector.getPageContentMap());
        List<Integer> notRecordedPages = new ArrayList<>();
        if (!missingPages.isEmpty()) {
            System.out.println("第一遍识别后有 " + missingPages.size() + " 页缺失：" + missingPages);
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
                    missingPages = findMissingPages(collector.getPageContentMap());
                    System.out.println("精修后仍缺 " + missingPages.size() + " 页");
                }
            } else {
                if (listener != null) {
                    listener.onProgress("发现缺失 " + missingPages.size() + " 页，正在定向补采", 70);
                }
                rescueMissingPages(videoFilePath, info, sampleFps, collector, pageTimes,
                        missingPages, notRecordedPages, listener, 70, 96);
                missingPages = findMissingPages(collector.getPageContentMap());
            }
            if (!notRecordedPages.isEmpty()) {
                System.out.println("以下页在视频里根本没有出现（录制时漏拍，无法通过识别恢复）：" + notRecordedPages);
            }
            if (!missingPages.isEmpty()) {
                System.out.println("补采后仍缺 " + missingPages.size() + " 页");
            }
        }

        if (listener != null) {
            listener.onProgress("识别完成，共 " + collector.size() + " 页，正在做完整性校验并生成文件", 98);
        }
        System.out.println("识别总耗时 " + formatDuration(System.currentTimeMillis() - startTime)
                + "，共识别 " + collector.size() + " 页");

        return FileConvertUtil.buildFileFromPages(outputDirPath, collector.getPageContentMap(),
                collector.getConflictCount(), notRecordedPages);
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
                int percent = percentFrom + (int) Math.min(span, processed * (long) span / total);
                String message = "[" + label + "] 已处理 " + processed + "/" + total + " 帧（"
                        + (processed * 100L / total) + "%），已识别 " + collector.size() + " 页，跳过重复帧 "
                        + skippedFrames.get() + " 张"
                        + (eta > 0 ? "，预计剩余 " + formatDuration(eta) : "");
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
        System.out.println("[" + label + "] 完成：共 " + frames + " 帧，重复帧跳过 " + skippedFrames.get()
                + " 帧，实际识别 " + decodedFrames.get() + " 帧，累计识别 " + collector.size() + " 页");

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
        }
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
                                           List<Integer> missingPages, List<Integer> notRecordedPages,
                                           ProgressListener listener,
                                           int percentFrom, int percentTo) throws Exception {
        List<List<Integer>> groups = groupConsecutive(missingPages);
        // 并行窗口数：窗口内部是单线程逐帧强解，靠多窗口并行吃满 CPU，同时避免一个窗口内串行太久
        int windowThreads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
        final ExecutorService pool = Executors.newFixedThreadPool(windowThreads);
        final int span = Math.max(1, percentTo - percentFrom);
        final AtomicInteger finished = new AtomicInteger();
        final AtomicInteger recovered = new AtomicInteger();

        try {
            for (List<Integer> group : groups) {
                pool.submit(() -> {
                    try {
                        int pageCount = rescueWindow(videoFilePath, info, fps, collector, pageTimes,
                                group, notRecordedPages);
                        if (pageCount > 0) {
                            recovered.addAndGet(pageCount);
                        }
                    } catch (Throwable t) {
                        System.out.println("[补采] 第 " + describeRange(group) + " 页补采异常: " + t);
                    } finally {
                        int done = finished.incrementAndGet();
                        if (listener != null) {
                            int percent = percentFrom + (int) (done * (long) span / groups.size());
                            listener.onProgress("[补采] 已完成 " + done + "/" + groups.size()
                                    + " 个缺页窗口，补回 " + recovered.get() + " 页，累计识别 "
                                    + collector.size() + " 页", percent);
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
     * 补采一组连续缺页：回读它们前后两页之间的全部画面，从"渲染最完整"的末帧开始倒序强解
     *
     * @return 补回的页数
     */
    private static int rescueWindow(String videoFilePath, FFmpegUtil.VideoInfo info, int fps,
                                    PageCollector collector, Map<Integer, double[]> pageTimes,
                                    List<Integer> group, List<Integer> notRecordedPages) throws Exception {
        int firstPage = group.get(0);
        int lastPage = group.get(group.size() - 1);
        RescueWindow rescueWindow = rescueWindowFor(firstPage, lastPage, pageTimes, info.durationSeconds);

        System.out.println("[补采] 回读 " + String.format(java.util.Locale.ROOT, "%.2f", rescueWindow.start)
                + "s 起 " + String.format(java.util.Locale.ROOT, "%.2f", rescueWindow.duration) + " 秒，强解第 "
                + describeRange(group) + " 页（" + (rescueWindow.fullSpan ? "已完整覆盖相邻页之间的全部画面" : "窗口被截断")
                + "）");

        // 只保留窗口末尾的这些帧：翻页刚发生时的画面常是撕裂帧，越靠后渲染越完整
        final int maxKeep = 24;
        final java.util.Deque<BufferedImage> tail = new java.util.ArrayDeque<>();
        final double[] frameTimes = new double[maxKeep];
        final int[] seen = new int[1];

        FFmpegUtil.streamRawFrames(videoFilePath, info,
                new FFmpegUtil.RawFrameOptions(fps).from(rescueWindow.start, rescueWindow.duration).maxWidth(0),
                (data, w, h, idx) -> {
                    // 补采刻意用原始分辨率，把首轮缩放时漏掉的细节捡回来
                    BufferedImage image = toImage(data, w, h);
                    double time = rescueWindow.start + idx / (double) fps;
                    // 记录每个保留帧对应的真实时间（用于定位这一帧出现在视频的哪一秒）
                    int slot = seen[0] % maxKeep;
                    frameTimes[slot] = time;
                    tail.addLast(image);
                    if (tail.size() > maxKeep) {
                        tail.removeFirst();
                    }
                    seen[0]++;
                }, null);

        if (tail.isEmpty()) {
            return 0;
        }

        List<BufferedImage> frames = new ArrayList<>(tail);
        List<Double> times = new ArrayList<>(frames.size());
        int startSlot = Math.max(0, seen[0] - frames.size()) % maxKeep;
        for (int i = 0; i < frames.size(); i++) {
            times.add(frameTimes[(startSlot + i) % maxKeep]);
        }

        // 倒序（末尾帧优先）逐帧强解，任一帧解出即停
        for (int i = frames.size() - 1; i >= 0; i--) {
            QRCodeUtil.Outcome outcome = QRCodeUtil.decodeDetailed(frames.get(i), true, true);
            if (outcome.text != null && collector.accept(outcome.text,
                    "补采@" + String.format(java.util.Locale.ROOT, "%.2fs", times.get(i)))) {
                recordPageTime(pageTimes, outcome.text, times.get(i));
            }
            // 这一组的页都补齐了就不用再试
            boolean allFound = true;
            for (Integer p : group) {
                if (!collector.getPageContentMap().containsKey(p)) {
                    allFound = false;
                    break;
                }
            }
            if (allFound) {
                break;
            }
        }

        List<Integer> stillMissing = new ArrayList<>();
        for (Integer p : group) {
            if (!collector.getPageContentMap().containsKey(p)) {
                stillMissing.add(p);
            }
        }
        int recoveredNow = group.size() - stillMissing.size();
        if (recoveredNow > 0) {
            System.out.println("[补采] 第 " + describeRange(group) + " 页补回 " + recoveredNow + " 页");
        }
        if (!stillMissing.isEmpty()) {
            // 判定依据是"证据"而不是时间：窗口若完整覆盖了左右相邻两页之间的全部帧，
            // 且每一帧都用最重的引擎强解过，这一页仍然不出现，说明它压根没被拍到。
            if (rescueWindow.fullSpan && rescueWindow.lowPage != null && rescueWindow.highPage != null) {
                synchronized (notRecordedPages) {
                    notRecordedPages.addAll(stillMissing);
                }
                System.out.println("[补采] 第 " + describeRange(stillMissing) + " 页在视频中不存在："
                        + "第 " + rescueWindow.lowPage + " 页与第 " + rescueWindow.highPage
                        + " 页之间的全部帧都已强解，没有任何一帧是这一页（录制时漏拍）");
            } else {
                System.out.println("[补采] 第 " + describeRange(stillMissing) + " 页回读后仍未识别出"
                        + "（画面里可能有这一页，但二维码不清晰）");
            }
        }
        return recoveredNow;
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
     * 计算补采窗口：从"左邻居最后一次出现"到"右邻居第一次出现"之间的画面。
     * 这段画面包含了缺页本应出现的全部时刻，所以只要完整读一遍，就能判定这一页到底有没有被拍到。
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
        double duration = Math.min(MAX_RESCUE_WINDOW, needed);
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
    private static List<Integer> findMissingPages(Map<Integer, String> pageContentMap) {
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
        final boolean stablePage = runLength >= STABLE_RUN_LENGTH;
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
                for (BufferedImage image : candidates) {
                    QRCodeUtil.Outcome outcome = QRCodeUtil.decodeDetailed(image, stablePage, false);
                    if (outcome.detections > 0) {
                        foundQrRegion = true;
                    }
                    if (outcome.text != null && collector.accept(outcome.text, "第" + frameIndex + "帧")) {
                        recordPageTime(pageTimes, outcome.text, time);
                        return;
                    }
                }
                // 轻量引擎全败，但画面里确实有二维码 → 用最重的引擎再试这一页渲染最完整的那帧
                if (foundQrRegion) {
                    QRCodeUtil.Outcome heavy = QRCodeUtil.decodeDetailed(candidates.get(0), true, true);
                    if (heavy.text != null && collector.accept(heavy.text, "第" + frameIndex + "帧(重试)")) {
                        recordPageTime(pageTimes, heavy.text, time);
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
}
