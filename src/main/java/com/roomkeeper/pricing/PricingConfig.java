package com.roomkeeper.pricing;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PricingConfig {

	/** Base price first; the adjustments after it multiply, so their order doesn't change the result. */
	@Bean
	PricingChain pricingChain(PricingProperties properties) {
		return new PricingChain(List.of(
				new BasePriceRule(),
				new SurgeRule(),
				new OccupancyRule(properties.occupancy().threshold(), properties.occupancy().multiplier()),
				new WeekendRule(properties.weekend().nights(), properties.weekend().multiplier()),
				new HolidayRule(properties.holidays().dates(), properties.holidays().multiplier())));
	}

}
