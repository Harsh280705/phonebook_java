package com.phonebook.web;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final Object body;

    public ApiException(HttpStatus status, String detail) {
        super(detail);
        this.status = status;
        this.body = new com.phonebook.dto.Dtos.ErrorResponse(detail);
    }

    public ApiException(HttpStatus status, Object body, String message) {
        super(message);
        this.status = status;
        this.body = body;
    }

    public HttpStatus getStatus() { return status; }
    public Object getBody() { return body; }
}
