package com.roomkeeper.common.idempotency;

import java.util.Map;

/** A successful response kept under its idempotency key, to be replayed to retries. */
public record StoredResponse(int status, Map<String, String> headers, String body) {
}
