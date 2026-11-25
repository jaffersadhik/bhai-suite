package com.mhotel.core;

/** An error with an HTTP status and a message shown to the user (Tamil / English). */
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
