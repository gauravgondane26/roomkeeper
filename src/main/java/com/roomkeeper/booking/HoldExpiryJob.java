package com.roomkeeper.booking;

import java.time.Duration;

import com.roomkeeper.common.JobLocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every minute (roomkeeper.booking.expiry-interval), gives back the rooms of holds that ran
 * out unpaid. Every instance schedules it; the one holding {@code lock:hold-expiry} runs it.
 */
@Component
public class HoldExpiryJob {

	private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);

	/** Shorter than the one-minute interval, so a crashed instance only costs a run. */
	private static final Duration LEASE = Duration.ofSeconds(50);

	private final HoldReleaser releaser;

	private final JobLocks locks;

	HoldExpiryJob(HoldReleaser releaser, JobLocks locks) {
		this.releaser = releaser;
		this.locks = locks;
	}

	@Scheduled(fixedDelayString = "${roomkeeper.booking.expiry-interval}")
	public void run() {
		locks.runLocked("hold-expiry", LEASE, releaser::releaseExpired)
			.filter(released -> released > 0)
			.ifPresent(released -> log.info("Released {} expired holds", released));
	}

}
