package com.github.xingjianluo.springrequestobservabilityjava.observability.trace;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * 链路标识生成器。
 *
 * <p>格式：32 位小写十六进制（128 bit）= 48 bit 毫秒时间戳（粗粒度时间有序，便于肉眼排序）
 * + 80 bit {@link SecureRandom} 随机数，保证高并发下全局唯一且不可预测。
 *
 * <p>示例：{@code 066f9a2c7b1e4f3a9c0d8e1b2a3c4d5e}。
 */
public final class TraceIdGenerator {

    /** 32 位小写 hex。 */
    private static final Pattern VALID = Pattern.compile("^[0-9a-f]{32}$");

    private static final SecureRandom RANDOM = new SecureRandom();

    private TraceIdGenerator() {
    }

    public static String generate() {
        long now = System.currentTimeMillis();
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);

        StringBuilder sb = new StringBuilder(32);
        // 48 bit 时间戳：取 long 的低 6 字节，写 12 个 hex 字符
        for (int shift = 40; shift >= 0; shift -= 8) {
            sb.append(Character.forDigit((int) ((now >>> shift) & 0xFF) >>> 4, 16));
            sb.append(Character.forDigit((int) ((now >>> shift) & 0xFF) & 0x0F, 16));
        }
        for (byte b : random) {
            sb.append(Character.forDigit((b & 0xFF) >>> 4, 16));
            sb.append(Character.forDigit(b & 0x0F, 16));
        }
        return sb.toString();
    }

    /**
     * 校验上游传入的链路标识是否合法。只允许严格的 32 位小写 hex，
     * 防止伪造头导致的日志换行注入或响应头污染。
     */
    public static boolean isValid(String traceId) {
        return traceId != null && VALID.matcher(traceId).matches();
    }
}
