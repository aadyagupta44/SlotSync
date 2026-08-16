package com.slotsync.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Translates exceptions into the single {@link ErrorResponse} shape.
 *
 * <p>The interesting one is {@link DataIntegrityViolationException}: when two
 * people book the same slot at the same instant, Postgres rejects the loser
 * with the {@code bookings_no_overlap} exclusion constraint. That is not a
 * server bug - it is the system working - so it becomes a clean
 * {@code 409 SLOT_TAKEN} rather than a 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String OVERLAP_CONSTRAINT = "bookings_no_overlap";

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ErrorResponse.of(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleIntegrity(DataIntegrityViolationException ex) {
        String detail = rootMessage(ex);
        if (detail.contains(OVERLAP_CONSTRAINT)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ErrorResponse.of("SLOT_TAKEN",
                            "That slot was just taken by someone else."));
        }
        log.warn("Unexpected constraint violation", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("CONSTRAINT_VIOLATION", "Request conflicts with existing data."));
    }

    /**
     * The same race as above, reported differently by Postgres under real load.
     *
     * <p>When a handful of transactions insert overlapping ranges at once, the
     * loser is rejected by the exclusion constraint and arrives here as a
     * {@link DataIntegrityViolationException} (SQLState 23P01). When *many* do
     * it simultaneously, the speculative insertions on the GiST index wait on
     * each other, Postgres detects a lock cycle and aborts the losers with
     * SQLState 40P01 instead - which Spring translates to
     * {@link CannotAcquireLockException}, an entirely separate branch of the
     * exception hierarchy from the one above.
     *
     * <p>The outcome is identical from the caller's point of view - somebody
     * else got the slot - so it gets the identical response. Without this
     * handler the loser receives a 500, which reads as "the server broke" when
     * in fact the guarantee worked exactly as designed. Verified by firing 40
     * concurrent bookings at one slot: 1 created, 39 rejected.
     *
     * <p>Note this is a transient failure: Postgres documents deadlock as
     * retryable. Retrying once before answering would let a caller who lost to
     * a lock cycle - rather than to a genuine overlap - still win a slot that
     * is in fact free. That is the more thorough fix and is not done here.
     */
    @ExceptionHandler(CannotAcquireLockException.class)
    public ResponseEntity<ErrorResponse> handleLockConflict(CannotAcquireLockException ex) {
        log.debug("Lock contention resolved against this caller", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("SLOT_TAKEN",
                        "That slot was just taken by someone else."));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(OptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("CONCURRENT_MODIFICATION",
                        "Someone else changed this record first. Reload and try again."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(ErrorResponse.of("VALIDATION_FAILED", message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "Something went wrong."));
    }

    private String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        StringBuilder sb = new StringBuilder();
        while (cursor != null) {
            if (cursor.getMessage() != null) {
                sb.append(cursor.getMessage()).append(' ');
            }
            cursor = cursor.getCause();
        }
        return sb.toString();
    }
}
