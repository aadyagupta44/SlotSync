package com.slotsync.common;

import org.springframework.http.HttpStatus;

/**
 * A failure we chose to return, as opposed to a crash.
 *
 * <p>{@code code} is a stable machine-readable string ({@code SLOT_TAKEN},
 * {@code OFFER_EXPIRED}, ...) so the React app can branch on it without
 * parsing English.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found");
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public HttpStatus getStatus() { return status; }

    public String getCode() { return code; }
}
