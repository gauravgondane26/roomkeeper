package com.roomkeeper.common.error;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;

import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.JpaSystemException;

import static org.assertj.core.api.Assertions.assertThat;

/** Which failures are a lost database connection (503, try again) rather than a bug (500). */
class LostConnectionTest {

	@Test
	void aConnectionThePoolClosedUnderARollbackFailureIsALostConnection() {
		// What HikariCP throws, with no SQL state, once a read timeout has broken the connection
		var closed = new SQLException("Connection is closed");

		assertThat(ProblemDetailsHandler.isLostConnection(
				new JpaSystemException(new RuntimeException("Unable to rollback against JDBC Connection", closed))))
			.isTrue();
	}

	@Test
	void aConnectionExceptionStateAnywhereInTheCausesOrSuppressedIsALostConnection() {
		var ioError = new SQLException("An I/O error occurred while sending to the backend", "08006");
		var rollback = new RuntimeException("Unable to rollback");
		rollback.addSuppressed(ioError);

		assertThat(ProblemDetailsHandler.isLostConnection(new RuntimeException(ioError))).isTrue();
		assertThat(ProblemDetailsHandler.isLostConnection(rollback)).isTrue();
	}

	@Test
	void aPoolTimeoutOrAnUnrecoverableDriverErrorIsALostConnection() {
		assertThat(ProblemDetailsHandler.isLostConnection(new SQLTransientConnectionException("pool timed out")))
			.isTrue();
		assertThat(ProblemDetailsHandler.isLostConnection(new SQLRecoverableException("socket closed"))).isTrue();
	}

	@Test
	void otherDatabaseErrorsAndBugsAreNot() {
		assertThat(ProblemDetailsHandler.isLostConnection(new SQLException("duplicate key", "23505"))).isFalse();
		assertThat(ProblemDetailsHandler.isLostConnection(new SQLException("no state"))).isFalse();
		assertThat(ProblemDetailsHandler.isLostConnection(new IllegalStateException("a bug"))).isFalse();
	}

}
