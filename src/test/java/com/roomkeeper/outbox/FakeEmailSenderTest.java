package com.roomkeeper.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

/** The stand-in email sender logs each email in one line, whatever its subject holds. */
@ExtendWith(OutputCaptureExtension.class)
class FakeEmailSenderTest {

	@Test
	void aLineBreakInTheSubjectCantStartAnotherLogLine(CapturedOutput output) {
		new FakeEmailSender().send(new Email("outbox-1", "guest@example.com",
				"Rooms are held for you at Seaside\n2026-11-01T12:00:00Z ERROR Forged line", "Body"));

		assertThat(output.getOut()).contains("Seaside_2026-11-01T12:00:00Z ERROR Forged line")
			.doesNotContain("Seaside\n");
	}

}
