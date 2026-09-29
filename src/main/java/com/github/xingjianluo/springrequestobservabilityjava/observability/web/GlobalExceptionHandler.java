package com.github.xingjianluo.springrequestobservabilityjava.observability.web;

import com.github.xingjianluo.springrequestobservabilityjava.observability.metrics.ErrorCategory;
import com.github.xingjianluo.springrequestobservabilityjava.observability.trace.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理。
 *
 * <p>职责：
 * <ol>
 *   <li>把异常分类（参数错误 / 业务拒绝 / 系统异常），分类结果写入请求属性，
 *       由最外层 {@link TraceFilter} 在 finally 中统一记录指标，保证一次请求恰好记录一次；</li>
 *   <li>对外只返回脱敏后的 {@link ApiErrorResponse}：系统异常不泄露底层组件原始信息，
 *       原始堆栈仅记录在服务端日志中（日志带 traceId）。</li>
 * </ol>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 显式分类的业务异常（含参数错误、业务拒绝）。 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiErrorResponse> handleBiz(BizException ex, HttpServletRequest request) {
        ErrorCategory category = ex.getCategory();
        HttpStatus status = switch (category) {
            case PARAM_ERROR -> HttpStatus.BAD_REQUEST;
            case BIZ_REJECT -> HttpStatus.CONFLICT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        markResolved(request, category);
        log.warn("请求被业务规则拒绝或参数非法: category={}, message={}", category, ex.getMessage());
        // 业务类异常的 message 是我们自己定义的安全文案，可以透出
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(TraceContext.getTraceId(), category.name(), ex.getMessage()));
    }

    /** Spring MVC 常见参数绑定/反序列化异常 → 参数错误。 */
    @ExceptionHandler({
            IllegalArgumentException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiErrorResponse> handleParam(Exception ex, HttpServletRequest request) {
        markResolved(request, ErrorCategory.PARAM_ERROR);
        log.warn("请求参数错误: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse(TraceContext.getTraceId(),
                        ErrorCategory.PARAM_ERROR.name(), "请求参数不合法"));
    }

    /**
     * 兜底：任何未被前面处理的异常都视为系统异常。
     * 对外只返回通用文案，底层组件原始异常绝不透出。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleOther(Exception ex, HttpServletRequest request) {
        markResolved(request, ErrorCategory.SYSTEM_ERROR);
        log.error("未处理异常，按系统异常统计", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse(TraceContext.getTraceId(),
                        ErrorCategory.SYSTEM_ERROR.name(), "服务内部错误，请稍后重试"));
    }

    private void markResolved(HttpServletRequest request, ErrorCategory category) {
        request.setAttribute(TraceFilter.ERROR_CATEGORY_ATTRIBUTE, category);
        request.setAttribute(TraceFilter.EXCEPTION_RESOLVED_ATTRIBUTE, Boolean.TRUE);
    }
}
