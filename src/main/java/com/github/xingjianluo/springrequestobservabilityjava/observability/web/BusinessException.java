package com.github.xingjianluo.springrequestobservabilityjava.observability.web;

public class BusinessException extends RuntimeException {
    public BusinessException(String message) {
        super(message);
    }
}
