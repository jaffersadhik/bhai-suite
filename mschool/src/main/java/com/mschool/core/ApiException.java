package com.mschool.core;

/** An error with an HTTP status and a message that is shown to the user (Tamil). */
public class ApiException extends RuntimeException {
    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
