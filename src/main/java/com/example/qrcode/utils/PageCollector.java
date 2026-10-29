package com.example.qrcode.utils;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
            return Integer.parseInt(decode.substring(0, PAGE_PREFIX_LENGTH).replace(" ", ""));
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
        String old = pageContentMap.putIfAbsent(page, content);
        if (old != null && !old.equals(content)) {
            conflictCount.incrementAndGet();
            System.out.println("警告：第" + page + "页识别出不同内容，保留首次识别结果 " + source);
        }
        return true;
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
}
