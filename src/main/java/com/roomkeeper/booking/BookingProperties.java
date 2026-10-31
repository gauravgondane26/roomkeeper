package com.roomkeeper.booking;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code roomkeeper.booking}: how long an unpaid booking holds its rooms, and how a hold
 * keeps two guests from taking the same room.
 *
 * @param holdDuration how long unpaid rooms stay held
 * @param locking {@code pessimistic} (the default) locks the stay's nights while holding
 * them; {@code optimistic} reads them unlocked and starts over if another hold changed them
 * first. The optimistic way is kept to benchmark against (docs/locking-benchmark.md)
 * @param optimisticAttempts how many times an optimistic hold tries before answering 503
 */
@ConfigurationProperties("roomkeeper.booking")
public record BookingProperties(Duration holdDuration, Locking locking, Integer optimisticAttempts) {

	public enum Locking {
		PESSIMISTIC, OPTIMISTIC
	}

	public BookingProperties {
		locking = locking != null ? locking : Locking.PESSIMISTIC;
		optimisticAttempts = optimisticAttempts != null ? optimisticAttempts : 5;
	}

}
