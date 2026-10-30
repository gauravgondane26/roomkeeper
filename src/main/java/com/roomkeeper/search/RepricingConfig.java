package com.roomkeeper.search;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The threads that recompute cheapest prices after a booking commits. Few of them, so
 * recomputes can't take more than a couple of the pool's database connections from
 * requests; per-hotel recomputes queue on an advisory lock anyway. At most one recompute
 * per hotel waits (see {@link HotelMinPriceUpdater}), so the queue holds hotels, not
 * bookings. A full queue throws, and the updater counts the dropped recompute.
 */
@Configuration(proxyBeanMethods = false)
// For the @Async listeners elsewhere, such as CheckoutSessionCloser; repricing schedules its own tasks
@EnableAsync
class RepricingConfig {

	static final String EXECUTOR = "repricingExecutor";

	@Bean(EXECUTOR)
	ThreadPoolTaskExecutor repricingExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("repricing-");
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(2);
		executor.setQueueCapacity(10_000);
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(10);
		return executor;
	}

}
