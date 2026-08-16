package com.slotsync.idempotency;

import com.slotsync.common.ApiException;
import com.slotsync.common.JsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Makes a POST safe to retry.
 *
 * <p>The problem: a customer taps "Book" on a train, the request succeeds but
 * the response never arrives, the app retries - and now they have two
 * appointments. Retries are a fact of life in any distributed system (mobile
 * clients, load balancers, Kafka, the user's finger), so the server has to be
 * able to recognise "I have already done this exact thing".
 *
 * <p>How it works:
 * <ol>
 *   <li>Client sends {@code Idempotency-Key: <uuid>} with the request.</li>
 *   <li>We insert that key with {@code ON CONFLICT DO NOTHING}. Winning the
 *       insert means we are the first - go do the work.</li>
 *   <li>Losing the insert means this is a retry. If the stored request body
 *       hash matches, replay the saved response. If it does not match, the
 *       client reused a key for a different request, which is a bug worth
 *       reporting loudly.</li>
 * </ol>
 *
 * <p>This must run <b>outside</b> a surrounding transaction: the key row has to
 * be visible to the concurrent duplicate immediately, not at commit time.
 * That is why controllers call this, and the service layer opens its own
 * transaction inside {@code action}.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final JdbcTemplate jdbc;
    private final JsonCodec json;

    public IdempotencyService(JdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public <T> T run(UUID tenantId,
                     String endpoint,
                     String idempotencyKey,
                     Object request,
                     Class<T> responseType,
                     Supplier<T> action) {

        // The header is optional. Without it you get plain at-least-once
        // semantics, which is the client's choice to make.
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return action.get();
        }

        String requestHash = sha256(json.write(request));

        int inserted = jdbc.update("""
                INSERT INTO idempotency_keys (tenant_id, idem_key, endpoint, request_hash, state)
                VALUES (?, ?, ?, ?, 'IN_PROGRESS')
                ON CONFLICT (tenant_id, idem_key) DO NOTHING
                """, tenantId, idempotencyKey, endpoint, requestHash);

        if (inserted == 0) {
            return replay(tenantId, idempotencyKey, requestHash, responseType);
        }

        try {
            T result = action.get();
            jdbc.update("""
                    UPDATE idempotency_keys
                       SET state = 'COMPLETED',
                           response_status = 200,
                           response_body = CAST(? AS jsonb),
                           completed_at = now()
                     WHERE tenant_id = ? AND idem_key = ?
                    """, json.write(result), tenantId, idempotencyKey);
            return result;
        } catch (RuntimeException e) {
            // Free the key so an honest retry can succeed. Leaving it
            // IN_PROGRESS would permanently wedge that key.
            jdbc.update("DELETE FROM idempotency_keys WHERE tenant_id = ? AND idem_key = ?",
                    tenantId, idempotencyKey);
            throw e;
        }
    }

    private <T> T replay(UUID tenantId, String key, String requestHash, Class<T> responseType) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT state, request_hash, response_body
                  FROM idempotency_keys
                 WHERE tenant_id = ? AND idem_key = ?
                """, tenantId, key);

        if (rows.isEmpty()) {
            // The original attempt failed and deleted the row between our
            // insert and this read. Treat it as a fresh request.
            throw ApiException.conflict("IDEMPOTENCY_RETRY",
                    "Please retry this request.");
        }

        Map<String, Object> row = rows.get(0);
        if (!requestHash.equals(row.get("request_hash"))) {
            throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used with a different request body.");
        }
        if (!"COMPLETED".equals(row.get("state"))) {
            throw ApiException.conflict("REQUEST_IN_PROGRESS",
                    "An identical request is still being processed. Retry shortly.");
        }

        log.info("Replaying stored response for idempotency key {}", key);
        return json.read(String.valueOf(row.get("response_body")), responseType);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
