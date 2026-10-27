package com.roomkeeper.common.idempotency;

/** What a request finds when it tries to claim its idempotency key. */
public sealed interface Claim {

	/** The key was free; this request runs. */
	record Claimed() implements Claim {
	}

	/** The same request is already running under this key. */
	record InProgress() implements Claim {
	}

	/** The same request already finished; here is its response. */
	record Completed(StoredResponse response) implements Claim {
	}

	/** The key was used for a different request (another path or body). */
	record Reused() implements Claim {
	}

}
