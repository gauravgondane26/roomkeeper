package com.roomkeeper.search;

import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The threads that recompute cheapest prices after a booking commits. Few of them, so
 * recomputes can't take more than a couple of the pool's database connections from
 * requests; per-hotel recomputes queue on an advisory lock anyway.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
class RepricingConfig {

	static final String EXECUTOR = "repricingExecutor";

	private static final Logger log = LoggerFactory.getLogger(RepricingConfig.class);

	@Bean(EXECUTOR)
	Executor repricingExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("repricing-");
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(2);
		executor.setQueueCapacity(10_000);
		// The booking has committed by now, so a full queue must not fail its request. A dropped
		// recompute leaves the old cheapest price until the next change to those nights.
		executor.setRejectedExecutionHandler((task, pool) -> log.warn("Repricing queue full; dropped a recompute"));
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(10);
		return executor;
	}

}
