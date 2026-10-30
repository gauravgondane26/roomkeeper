package com.roomkeeper.testsupport;

import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * Puts the shared {@link MutableClock} back to {@link MutableClockConfig#START} before
 * every test, so no test inherits the time an earlier one left it at. Runs before
 * {@code @BeforeEach}, which can still set its own time. Registered for every Spring test
 * in META-INF/spring.factories; contexts without the clock are left alone.
 */
public class MutableClockReset extends AbstractTestExecutionListener {

	@Override
	public void beforeTestMethod(TestContext testContext) {
		if (testContext.hasApplicationContext()) {
			testContext.getApplicationContext()
				.getBeanProvider(MutableClock.class)
				.ifAvailable(clock -> clock.setInstant(MutableClockConfig.START));
		}
	}

}
