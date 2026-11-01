package com.roomkeeper.common.security;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * In prod, refuses to start with Actuator served on the API's port. SecurityConfig leaves
 * Actuator's paths open because they normally answer only on a port of their own
 * (MANAGEMENT_PORT, 8081) that deployments keep private; on the API's port anyone could
 * read the metrics. A port of -1 (Actuator off) is fine.
 */
@Component
@Profile("prod")
class ProductionManagementPortCheck implements InitializingBean {

	private final int serverPort;

	private final Integer managementPort;

	ProductionManagementPortCheck(Environment environment) {
		Binder binder = Binder.get(environment);
		this.serverPort = binder.bind("server.port", Integer.class).orElse(8080);
		this.managementPort = binder.bind("management.server.port", Integer.class).orElse(null);
	}

	@Override
	public void afterPropertiesSet() {
		if (managementPort == null || managementPort == serverPort) {
			throw new IllegalStateException("Actuator would answer on the API's port " + serverPort
					+ ", where its open paths (metrics included) would be public; set MANAGEMENT_PORT to a port of its"
					+ " own that isn't published, or to -1 to turn Actuator's endpoints off");
		}
	}

}
