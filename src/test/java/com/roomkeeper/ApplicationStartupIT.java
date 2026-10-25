package com.roomkeeper;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationStartupIT extends AbstractIntegrationTest {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	RedisConnectionFactory redis;

	@Autowired
	Flyway flyway;

	@Test
	void connectsToPostgres18() {
		String version = jdbc.queryForObject("select version()", String.class);

		assertThat(version).startsWith("PostgreSQL 18");
	}

	@Test
	void connectsToRedis() {
		try (var connection = redis.getConnection()) {
			assertThat(connection.ping()).isEqualTo("PONG");
		}
	}

	@Test
	void appliesBaselineMigration() {
		var current = flyway.info().current();

		assertThat(current).as("latest applied migration").isNotNull();
		assertThat(current.getVersion().getVersion()).isEqualTo("1");
	}

}
