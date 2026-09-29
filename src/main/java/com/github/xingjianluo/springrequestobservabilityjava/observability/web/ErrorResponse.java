package com.github.xingjianluo.springrequestobservabilityjava.observability.web;

public record ErrorResponse(String traceId, String error, String message) {
}
