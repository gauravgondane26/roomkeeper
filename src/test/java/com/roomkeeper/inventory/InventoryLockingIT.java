package com.roomkeeper.inventory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import javax.sql.DataSource;

import com.roomkeeper.testsupport.AbstractDataTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * lockNights is the SELECT ... FOR UPDATE that booking init and inventory admin take
 * before changing counts. Another transaction can't lock the same nights until the first
 * ends, and the nights come back in date order, the order every caller locks them in.
 * <p>
 * Fixtures are committed rather than rolled back, so the second connection can see them.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InventoryLockingIT extends AbstractDataTest {

	private static final String LOCK_NOT_AVAILABLE = "55P03";

	private static final LocalDate FIRST = LocalDate.parse("2026-12-24");

	private static final LocalDate SECOND = FIRST.plusDays(1);

	private static final LocalDate AFTER = FIRST.plusDays(2);

	@Autowired
	InventoryRepository inventory;

	@Autowired
	DataSource dataSource;

	@Autowired
	PlatformTransactionManager transactions;

	private long roomId;

	private long secondId;

	private long afterId;

	@BeforeEach
	void createNightsOutOfOrder() {
		roomId = insertRoom(insertHotel(insertManager(), "Goa", "UTC", true));
		// Inserted out of date order, so ids don't give the order away
		afterId = insertNight(roomId, AFTER, 5);
		secondId = insertNight(roomId, SECOND, 5);
		insertNight(roomId, FIRST, 5);
	}

	@Test
	void returnsTheRangesNightsInDateOrder() {
		List<LocalDate> dates = new TransactionTemplate(transactions)
			.execute(status -> inventory.lockNights(roomId, FIRST, SECOND).stream().map(Inventory::getDate).toList());

		assertThat(dates).containsExactly(FIRST, SECOND);
	}

	@Test
	void anotherTransactionCantLockTheNightsUntilTheFirstEnds() {
		new TransactionTemplate(transactions).executeWithoutResult(status -> {
			inventory.lockNights(roomId, FIRST, SECOND);

			assertThat(lockElsewhere(secondId)).as("a night in the range").isFalse();
			assertThat(lockElsewhere(afterId)).as("a night outside the range").isTrue();
		});

		assertThat(lockElsewhere(secondId)).as("after the first transaction ends").isTrue();
	}

	/** Tries to lock the night from a separate connection without waiting; true if it got the lock. */
	private boolean lockElsewhere(long nightId) {
		try (Connection connection = dataSource.getConnection()) {
			connection.setAutoCommit(false);
			try (PreparedStatement statement = connection
				.prepareStatement("select id from inventory where id = ? for update nowait")) {
				statement.setLong(1, nightId);
				boolean locked = statement.executeQuery().next();
				connection.rollback();
				return locked;
			}
		}
		catch (SQLException ex) {
			if (LOCK_NOT_AVAILABLE.equals(ex.getSQLState())) {
				return false;
			}
			throw new IllegalStateException(ex);
		}
	}

}
