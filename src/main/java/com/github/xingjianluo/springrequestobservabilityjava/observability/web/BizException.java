package com.github.xingjianluo.springrequestobservabilityjava.observability.web;

import com.github.xingjianluo.springrequestobservabilityjava.observability.metrics.ErrorCategory;

/**
 * 带类别标识的业务异常。
 */
public class BizException extends RuntimeException {

    private final ErrorCategory category;

    public BizException(ErrorCategory category, String message) {
        super(message);
        this.category = category;
    }

    public ErrorCategory getCategory() {
        return category;
    }
}
