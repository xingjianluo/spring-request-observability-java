package com.github.xingjianluo.springrequestobservabilityjava.observability.web;

import com.github.xingjianluo.springrequestobservabilityjava.observability.metrics.ErrorCategory;
import com.github.xingjianluo.springrequestobservabilityjava.observability.trace.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理：把 controller 抛出的异常归一为三类并分别统计，
 * 同时保证：
 * <ul>
 *     <li>任何未处理异常都会落到这里，traceId 仍在 MDC 中，原始堆栈只写服务端日志；</li>
 *     <li>对外只返回统一结构 {@link ErrorResponse}，系统异常不暴露底层组件信息；</li>
 *     <li>异常分类写入 request 属性，TraceFilter 无论同步/异步都能据此精确记账。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    public static final String ERROR_CATEGORY_ATTRIBUTE = GlobalExceptionHandler.class.getName() + ".errorCategory";

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务拒绝：消息本身面向用户，可透出。 */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorResponse handleBusiness(BusinessException ex, HttpServletRequest request) {
        return build(request, HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_REJECTION",
                ex.getMessage(), ErrorCategory.BUSINESS_REJECTION, ex, false);
    }

    /** 参数错误：框架校验类异常，不把底层组件原始异常文本透出，仅提示参数不合法。 */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleParam(Exception ex, HttpServletRequest request) {
        return build(request, HttpStatus.BAD_REQUEST, "PARAM_ERROR",
                "请求参数不合法或缺失", ErrorCategory.PARAM_ERROR, ex, false);
    }

    /** 兜底：一切未预期异常视为系统异常，对外只给通用提示。 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleSystem(Exception ex, HttpServletRequest request) {
        return build(request, HttpStatus.INTERNAL_SERVER_ERROR, "SYSTEM_ERROR",
                "服务暂时不可用，请稍后重试", ErrorCategory.SYSTEM_ERROR, ex, true);
    }

    private ErrorResponse build(HttpServletRequest request, HttpStatus status, String error, String message,
                                ErrorCategory category, Exception ex, boolean errorLog) {
        // 分类写入请求属性，TraceFilter 完成时据此分类统计，保证异常不丢失计数。
        request.setAttribute(ERROR_CATEGORY_ATTRIBUTE, category);
        String traceId = TraceContext.getTraceId();
        if (errorLog) {
            // 系统异常打完整堆栈，便于凭 traceId 定位问题。
            log.error("unhandled exception status={} category={} traceId={}", status.value(), category, traceId, ex);
        } else {
            log.warn("handled exception status={} category={} traceId={} type={} message={}",
                    status.value(), category, traceId, ex.getClass().getSimpleName(), ex.getMessage());
        }
        return new ErrorResponse(traceId, error, message);
    }
}
