package com.example.qrcode.utils;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 二维码识别结果收集器
 *
 * 文件转二维码是按页顺序生成的，每页二维码内容是「4位页码 + 1020位数据」。
 * 这里负责：解析页码、校验单页内容合法性、按页码去重收集，并统计冲突。
 */
public class PageCollector {

    /** 每页二维码实际承载的数据长度（前4位是页码，所以每页数据是1020） */
    public static final int SEGMENT_DATA_SIZE = 1020;

    /** 页码前缀长度 */
    private static final int PAGE_PREFIX_LENGTH = 4;

    private final Map<Integer, String> pageContentMap = new ConcurrentHashMap<>();

    /** 同一页被识别出不同内容的次数 */
    private final AtomicInteger conflictCount = new AtomicInteger(0);

    /** 成功收下的识别结果数（含重复页） */
    private final AtomicInteger acceptedCount = new AtomicInteger(0);
    /** Optional file-level integrity record used by four-QR frames. */
    private volatile TransferManifest manifest;
    private final AtomicBoolean multiQrFrame = new AtomicBoolean(false);

    /**
     * 从识别结果里解析页码（前4位）
     *
     * @return 页码，解析失败返回 null
     */
    public static Integer parsePage(String decode) {
        if (decode == null || decode.length() <= PAGE_PREFIX_LENGTH) {
            return null;
        }
        try {
            int page = Integer.parseInt(decode.substring(0, PAGE_PREFIX_LENGTH).replace(" ", ""));
            return page >= 0 && page <= 9999 ? page : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 接收一次识别结果
     * @param decode 识别出来的文本，格式：4位页码 + 数据
     * @param source 来源描述（帧文件名），仅用于日志
     * @return 是否被采纳
     */
    public boolean accept(String decode, String source) {
        if (decode == null || decode.length() <= PAGE_PREFIX_LENGTH) {
            return false;
        }
        if (decode.startsWith(TransferManifest.PREFIX)) {
            try {
                TransferManifest parsed = TransferManifest.parse(decode);
                synchronized (this) {
                    if (manifest == null) {
                        manifest = parsed;
                    } else if (!manifest.toPayload().equals(parsed.toPayload())) {
                        conflictCount.incrementAndGet();
                        System.out.println("警告：发现互相冲突的文件传输清单，保留首次清单 " + source);
                    }
                }
            } catch (IllegalArgumentException e) {
                System.out.println("传输清单无效，跳过！" + source + " 原因=" + e.getMessage());
            }
            return false;
        }
        Integer page = parsePage(decode);
        if (page == null) {
            // 页码位不是数字，说明识别结果是乱码
            System.out.println("页码解析失败跳过！" + source + " 识别结果=" + decode);
            return false;
        }
        String content = decode.substring(PAGE_PREFIX_LENGTH);
        // 内容校验：翻页过渡帧容易识别出乱码，直接采用会污染最终文件
        if (!isValidSegmentContent(content)) {
            System.out.println("二维码内容校验不通过跳过！" + source + " 页码=" + page);
            return false;
        }
        acceptedCount.incrementAndGet();
        pageContentMap.compute(page, (ignored, old) -> {
            if (old == null || old.equals(content)) {
                return old == null ? content : old;
            }
            // Video decoders can return a valid Base64 prefix while the QR is still
            // resolving. Prefer a later full decode when it extends that prefix.
            if (content.startsWith(old)) {
                System.out.println("第" + page + "页由部分识别更新为更完整内容（" + old.length()
                        + " → " + content.length() + " 字符） " + source);
                return content;
            }
            if (old.startsWith(content)) {
                return old;
            }
            conflictCount.incrementAndGet();
            System.out.println("警告：第" + page + "页识别出不同内容，保留首次完整结果 " + source);
            return old;
        });
        return true;
    }

    /**
     * 原子接收一张图片/视频帧解出的二维码。
     * 四码模式要求该帧预期的连续页全部解出且内容有效，否则一页也不写入；
     * 清单控制帧只接收清单，不把重复展示的第 0 页作为数据页。
     *
     * @return 本帧接受的页码；若是清单帧或四码不完整则返回空列表
     */
    public synchronized List<Integer> acceptFrame(List<String> decoded, String source) {
        if (decoded == null || decoded.isEmpty()) {
            return Collections.emptyList();
        }
        boolean hasManifest = false;
        Map<Integer, String> framePages = new TreeMap<>();
        for (String text : decoded) {
            if (text != null && text.startsWith(TransferManifest.PREFIX)) {
                accept(text, source);
                hasManifest = true;
                continue;
            }
            Integer page = parsePage(text);
            if (page != null && text.length() > 4) {
                framePages.put(page, text);
            }
        }

        if (hasManifest) {
            if (decoded.size() > 1) {
                markMultiQrFrame();
            }
            if (!framePages.isEmpty()) {
                System.out.println("[四码帧] " + source + " 为清单控制帧，忽略其中重复的数据页展示。");
            }
            return Collections.emptyList();
        }

        boolean multiFrame = decoded.size() > 1 || hasMultiQrFrame();
        if (decoded.size() > 1) {
            markMultiQrFrame();
        }
        if (framePages.isEmpty()) {
            return Collections.emptyList();
        }

        if (multiFrame) {
            int groupStart = (framePages.keySet().iterator().next() / 4) * 4;
            TransferManifest fileManifest = manifest;
            int expectedCount = fileManifest == null ? 4
                    : Math.min(4, Math.max(0, fileManifest.totalPages - groupStart));
            boolean complete = expectedCount > 0 && framePages.size() == expectedCount;
            for (int i = 0; complete && i < expectedCount; i++) {
                String text = framePages.get(groupStart + i);
                complete = text != null && isValidSegmentContent(text.substring(4));
            }
            if (!complete) {
                System.out.println("[四码帧] " + source + " 只解出 " + framePages.size()
                        + "/" + expectedCount + " 个应有二维码（页组 " + groupStart + "~"
                        + (groupStart + Math.max(0, expectedCount - 1))
                        + "），本帧数据整组丢弃，不与其它帧拼凑。");
                return Collections.emptyList();
            }
        }

        List<Integer> acceptedPages = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : framePages.entrySet()) {
            if (accept(entry.getValue(), source)) {
                acceptedPages.add(entry.getKey());
            }
        }
        return acceptedPages;
    }

    /**
     * 校验单页识别内容是否可信：长度不超过单页容量，且除前10位文件后缀外必须全是Base64字符。
     */
    public static boolean isValidSegmentContent(String content) {
        if (content == null || content.isEmpty() || content.length() > SEGMENT_DATA_SIZE) {
            return false;
        }
        int headerEnd = Math.min(10, content.length());
        for (int i = headerEnd; i < content.length(); i++) {
            char c = content.charAt(i);
            boolean base64Char = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=';
            if (!base64Char) {
                return false;
            }
        }
        return true;
    }

    public Map<Integer, String> getPageContentMap() {
        return Collections.unmodifiableMap(pageContentMap);
    }

    public int getConflictCount() {
        return conflictCount.get();
    }

    public int getAcceptedCount() {
        return acceptedCount.get();
    }

    public int size() {
        return pageContentMap.size();
    }

    public boolean isEmpty() {
        return pageContentMap.isEmpty();
    }

    public TransferManifest getManifest() {
        return manifest;
    }

    public synchronized void markMultiQrFrame() {
        if (multiQrFrame.compareAndSet(false, true)) {
            int discardedPages = pageContentMap.size();
            pageContentMap.clear();
            acceptedCount.set(0);
            conflictCount.set(0);
            if (discardedPages > 0) {
                System.out.println("[四码帧] 已切换到四码整帧校验，清除此前按单码暂存的 "
                        + discardedPages + " 页，避免把不同图片的部分结果拼在一起。");
            }
        }
    }

    public boolean hasMultiQrFrame() {
        return multiQrFrame.get();
    }
}
