package com.roomkeeper.testsupport;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/** Every test starts with the shared clock at its start time, wherever the last test left it. */
@Import(MutableClockConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MutableClockResetIT extends AbstractIntegrationTest {

	@Autowired
	MutableClock clock;

	@Test
	@Order(1)
	void aTestMovesTheClock() {
		clock.setInstant(Instant.parse("2030-06-01T00:00:00Z"));
		clock.advance(Duration.ofDays(3));
	}

	@Test
	@Order(2)
	void theNextTestStartsAtTheStartTimeAgain() {
		assertThat(clock.instant()).isEqualTo(MutableClockConfig.START);
	}

}
