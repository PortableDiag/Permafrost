package com.portablediag.permafrost.api;

/** Anything a handler can fail with that the caller should see as a status code, not a 500. */
public class ApiException extends RuntimeException {
    public final int status;
    public final String hint;

    public ApiException(int status, String message) {
        this(status, message, null);
    }

    public ApiException(int status, String message, String hint) {
        super(message);
        this.status = status;
        this.hint = hint;
    }
}
