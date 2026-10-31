package com.roomkeeper.common.metrics;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prometheus's scrape carries every series the Grafana dashboard queries, from the start: a
 * counter that only appears with its first event loses that event to rate() and increase().
 */
class PrometheusScrapeIT extends AbstractIntegrationTest {

	@Value("${local.management.port}")
	private int managementPort;

	@Test
	void carriesEverySeriesTheDashboardQueries() {
		api().when().get("/livez").then().statusCode(200);

		String scrape = given().port(managementPort).when().get("/actuator/prometheus")
			.then().statusCode(200).extract().asString();

		assertThat(scrape).contains("roomkeeper_bookings_initiated_total ", "roomkeeper_holds_expired_total ",
				"roomkeeper_webhooks_duplicates_ignored_total ", "roomkeeper_repricing_dropped_total ",
				"roomkeeper_rate_limit_rejections_total{route=\"login\"}",
				"roomkeeper_rate_limit_rejections_total{route=\"signup\"}",
				"roomkeeper_rate_limit_rejections_total{route=\"refresh\"}", "hikaricp_connections_active{",
				"hikaricp_connections_pending{", "roomkeeper_outbox_sent_total ", "roomkeeper_outbox_published_total ",
				"roomkeeper_outbox_claimed_total ", "roomkeeper_outbox_resent_total ",
				"roomkeeper_outbox_stream_pending{consumer=\"refunds\"}",
				"roomkeeper_outbox_stream_length{consumer=\"booking-emails\"}");
		assertThat(scrape).containsPattern("cache_gets_total\\{[^}]*cache=\"search\"[^}]*result=\"hit\"")
			.containsPattern("cache_gets_total\\{[^}]*cache=\"hotel\"[^}]*result=\"miss\"")
			// Latency buckets, for percentiles across instances
			.containsPattern("http_server_requests_seconds_bucket\\{[^}]*uri=\"/livez\"");
	}

}
