package com.roomkeeper.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Runs the scheduled jobs (hold expiry, inventory roll). {@code roomkeeper.jobs.enabled:
 * false} turns them off; the tests do, and call the jobs themselves.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty(name = "roomkeeper.jobs.enabled", matchIfMissing = true)
class SchedulingConfig {

}
