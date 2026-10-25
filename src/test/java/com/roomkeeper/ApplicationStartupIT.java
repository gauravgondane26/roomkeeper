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
	void appliesEveryMigrationStartingFromTheBaseline() {
		var info = flyway.info();

		assertThat(info.applied()).as("applied migrations").isNotEmpty();
		assertThat(info.applied()[0].getVersion().getVersion()).isEqualTo("1");
		assertThat(info.pending()).as("pending migrations").isEmpty();
		assertThat(info.applied()).allSatisfy(migration -> assertThat(migration.getState().isFailed()).isFalse());
	}

}
