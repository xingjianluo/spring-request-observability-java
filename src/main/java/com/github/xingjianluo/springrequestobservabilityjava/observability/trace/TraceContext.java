package com.github.xingjianluo.springrequestobservabilityjava.observability.trace;

import org.slf4j.MDC;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 请求链路标识（traceId）的生成与传播载体。
 *
 * <p>traceId 同时保存在两处：
 * <ul>
 *     <li>{@link ThreadLocal}：供业务代码随时读取当前请求标识；</li>
 *     <li>SLF4J {@link MDC}：logback 配置中的 {@code [%X{traceId}]} 会把它打印到每一条日志。</li>
 * </ul>
 *
 * <p>两者成对设置、成对清除，保证线程池复用线程时不会残留上一个请求的标识。
 */
public final class TraceContext {

    /** 返回给调用方、并从调用方接收链路标识时使用的 HTTP 头。 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** MDC 中保存 traceId 的 key，与 logback pattern 里的 %X{traceId} 对应。 */
    public static final String MDC_KEY = "traceId";

    /** 新生成 traceId 的长度：8 位时间 + 24 位随机数，共 32 位十六进制。 */
    static final int TRACE_ID_LENGTH = 32;

    /** 允许接受的外部传入标识长度范围，过短/过长都拒绝。 */
    private static final int MIN_INCOMING_LENGTH = 16;
    private static final int MAX_INCOMING_LENGTH = 64;

    /**
     * 只允许大小写字母、数字和连字符，拒绝换行、空格等字符，
     * 避免外部传入的标识污染 MDC / 响应头（日志注入、HTTP 头注入）。
     */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]+");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TraceContext() {
    }

    /**
     * 生成稳定且全局唯一的 traceId：
     * 高 8 位十六进制来自当前毫秒时间戳，保证按时间大致有序；
     * 其余 12 字节来自 {@link SecureRandom}，保证高并发下不冲突、不可预测。
     */
    public static String generateTraceId() {
        byte[] randomBytes = new byte[12];
        RANDOM.nextBytes(randomBytes);
        String timePart = Long.toHexString(System.currentTimeMillis());
        // 时间部分固定取低 8 位，随机部分 24 位，合计 32 位。
        if (timePart.length() >= 8) {
            timePart = timePart.substring(timePart.length() - 8);
        } else {
            timePart = "0".repeat(8 - timePart.length()) + timePart;
        }
        return timePart + HEX.formatHex(randomBytes);
    }

    /**
     * 校验外部传入的 traceId 是否可被接受沿用。
     * 不合法时调用方应重新生成，而不是拒绝请求。
     */
    public static boolean isValidIncomingId(String value) {
        if (value == null) {
            return false;
        }
        int len = value.length();
        return len >= MIN_INCOMING_LENGTH && len <= MAX_INCOMING_LENGTH && SAFE_ID.matcher(value).matches();
    }

    /** 成对设置 ThreadLocal 与 MDC。 */
    public static void setTraceId(String traceId) {
        CURRENT.set(traceId);
        MDC.put(MDC_KEY, traceId);
    }

    /** 读取当前线程绑定的 traceId；未处于请求上下文时返回 {@code null}。 */
    public static String getTraceId() {
        return CURRENT.get();
    }

    /** 成对清除 ThreadLocal 与 MDC，必须在请求结束时（finally）调用。 */
    public static void clear() {
        CURRENT.remove();
        MDC.remove(MDC_KEY);
    }

    /**
     * 把当前线程的 traceId 捕获下来，包装成在任意线程执行结束后都会清理上下文的任务。
     * 适用于显式提交到线程池 / {@code CompletableFuture} 的异步任务。
     */
    public static Runnable wrap(Runnable task) {
        String captured = CURRENT.get();
        return () -> {
            String previous = CURRENT.get();
            if (captured != null) {
                setTraceId(captured);
            }
            try {
                task.run();
            } finally {
                if (captured != null) {
                    // 恢复而不是无条件清除：目标线程可能本身就运行在另一个请求上下文里。
                    if (previous != null) {
                        setTraceId(previous);
                    } else {
                        clear();
                    }
                }
            }
        };
    }
}
