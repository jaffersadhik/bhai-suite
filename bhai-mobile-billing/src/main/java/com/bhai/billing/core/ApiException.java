package com.bhai.billing.core;

/** Error shown to the app user: HTTP status + message (Tamil / English). */
public class ApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
