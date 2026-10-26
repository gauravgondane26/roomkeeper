package com.roomkeeper.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every minute (roomkeeper.booking.expiry-interval), gives back the rooms of holds that ran out unpaid. */
@Component
public class HoldExpiryJob {

	private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);

	private final HoldReleaser releaser;

	HoldExpiryJob(HoldReleaser releaser) {
		this.releaser = releaser;
	}

	@Scheduled(fixedDelayString = "${roomkeeper.booking.expiry-interval}")
	public void run() {
		int released = releaser.releaseExpired();
		if (released > 0) {
			log.info("Released {} expired holds", released);
		}
	}

}
