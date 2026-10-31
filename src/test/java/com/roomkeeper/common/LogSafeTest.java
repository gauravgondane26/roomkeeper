package com.roomkeeper.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogSafeTest {

	@Test
	void lineBreaksCantStartAForgedLogLine() {
		assertThat(LogSafe.of("key-1\r\n2026-10-31 INFO Admin logged in")).isEqualTo("key-1__2026-10-31 INFO Admin logged in");
	}

	@Test
	void otherControlCharactersAreReplacedToo() {
		assertThat(LogSafe.of("tab\there\u001b[31mred")).isEqualTo("tab_here_[31mred");
	}

	@Test
	void ordinaryValuesAreUnchangedAndNullStaysNull() {
		assertThat(LogSafe.of("3f2c9a1e-booking-42")).isEqualTo("3f2c9a1e-booking-42");
		assertThat(LogSafe.of(null)).isNull();
	}

	@Test
	void longValuesAreCut() {
		assertThat(LogSafe.of("x".repeat(500))).hasSize(203).endsWith("...");
	}

}
