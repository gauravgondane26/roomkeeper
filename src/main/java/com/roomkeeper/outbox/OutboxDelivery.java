package com.roomkeeper.outbox;

import java.time.Clock;

import com.roomkeeper.common.metrics.BusinessMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hands one event to its consumer, in one transaction: the event is locked (skipped if
 * another delivery holds it), handed over unless it's already published or parked, and
 * marked published. If the consumer throws, that's rolled back and a second transaction
 * counts the failed attempt; after {@value OutboxEvent#MAX_ATTEMPTS} the event is parked.
 * <p>
 * The stream entry that brought the event is only acknowledged once the outcome says to let
 * it go, so an entry whose delivery failed, or that another reader holds, comes round again.
 */
@Component
public class OutboxDelivery {

	private static final Logger log = LoggerFactory.getLogger(OutboxDelivery.class);

	/** What became of a delivery, and so whether its stream entry is done with. */
	public enum Outcome {

		/** The consumer acted on it and it's marked published. */
		PUBLISHED(true),

		/** Published or parked already: handed over again, so nothing to do. */
		ALREADY_DONE(true),

		/** No such event; nothing will ever deliver it. */
		UNKNOWN(true),

		/** The consumer failed and the event will be tried again. */
		FAILED(false),

		/** The consumer failed for the last time; the event is parked. */
		PARKED(true),

		/** Another delivery holds the event right now; it settles it. */
		BUSY(false);

		private final boolean letsGo;

		Outcome(boolean letsGo) {
			this.letsGo = letsGo;
		}

		/** Whether the stream entry can be acknowledged: nothing is left to do for it. */
		public boolean letsGo() {
			return letsGo;
		}

	}

	private final OutboxEventRepository events;

	private final OutboxRoutes routes;

	private final TransactionTemplate transaction;

	private final BusinessMetrics metrics;

	private final Clock clock;

	OutboxDelivery(OutboxEventRepository events, OutboxRoutes routes, PlatformTransactionManager transactions,
			BusinessMetrics metrics, Clock clock) {
		this.events = events;
		this.routes = routes;
		this.transaction = new TransactionTemplate(transactions);
		this.metrics = metrics;
		this.clock = clock;
	}

	public Outcome deliver(long id) {
		try {
			Outcome outcome = transaction.execute(status -> handOver(id));
			if (outcome == Outcome.PUBLISHED) {
				metrics.outboxPublished();
			}
			return outcome;
		}
		catch (RuntimeException ex) {
			return transaction.execute(status -> recordFailure(id, ex));
		}
	}

	private Outcome handOver(long id) {
		OutboxEvent event = events.lockById(id).orElse(null);
		if (event == null) {
			return events.existsById(id) ? Outcome.BUSY : Outcome.UNKNOWN;
		}
		if (event.isPublished() || event.isParked()) {
			return Outcome.ALREADY_DONE;
		}
		routes.consumerOf(event.getType()).consume(event);
		event.markPublished(clock.instant());
		return Outcome.PUBLISHED;
	}

	private Outcome recordFailure(long id, RuntimeException ex) {
		OutboxEvent event = events.lockById(id).orElse(null);
		if (event == null) {
			return Outcome.BUSY;
		}
		event.recordFailure(ex.getClass().getSimpleName() + ": " + ex.getMessage(), clock.instant());
		if (event.isParked()) {
			log.error("Outbox event {} ({}) failed {} times; parked, no more retries", id, event.getType(),
					event.getAttempts(), ex);
			return Outcome.PARKED;
		}
		log.warn("Outbox event {} ({}) failed, attempt {} of {}; trying again", id, event.getType(),
				event.getAttempts(), OutboxEvent.MAX_ATTEMPTS, ex);
		return Outcome.FAILED;
	}

}
