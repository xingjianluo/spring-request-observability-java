package com.github.xingjianluo.springrequestobservabilityjava.observability.web;

/**
 * 对外统一错误响应（不包含底层组件原始异常细节）。
 */
public record ApiErrorResponse(String traceId, String error, String message) {
}
