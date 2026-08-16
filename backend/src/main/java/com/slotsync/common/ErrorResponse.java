package com.slotsync.common;

import java.time.Instant;

/** Uniform error body for every non-2xx response. */
public record ErrorResponse(String code, String message, Instant timestamp) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, Instant.now());
    }
}
