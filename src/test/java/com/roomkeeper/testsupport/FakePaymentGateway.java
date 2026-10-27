package com.roomkeeper.testsupport;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;
import com.roomkeeper.payment.CheckoutRequest;
import com.roomkeeper.payment.CheckoutSession;
import com.roomkeeper.payment.PaymentGateway;

/**
 * Stands in for Stripe in integration tests. Checkout sessions, expiries and refunds are
 * kept in memory and keyed the way Stripe keys them: the same idempotency key gets the same
 * session or refund back. One instance serves every test in a Spring context, so tests look
 * up their own booking's calls rather than counting all of them, and the failure hooks fire
 * once.
 */
public class FakePaymentGateway implements PaymentGateway {

	/** A checkout session the fake opened, and what it was asked for. */
	public record OpenedCheckout(CheckoutRequest request, CheckoutSession session) {
	}

	private final AtomicLong ids = new AtomicLong();

	private final Map<String, CheckoutSession> sessionsByKey = new ConcurrentHashMap<>();

	private final List<OpenedCheckout> checkouts = new CopyOnWriteArrayList<>();

	private final Set<String> expiredSessions = ConcurrentHashMap.newKeySet();

	private final Map<String, String> refundsByKey = new ConcurrentHashMap<>();

	private final List<String> refundedPaymentIntents = new CopyOnWriteArrayList<>();

	private final AtomicBoolean failNextCheckout = new AtomicBoolean();

	private final AtomicReference<Runnable> beforeNextCheckout = new AtomicReference<>();

	@Override
	public CheckoutSession createCheckoutSession(CheckoutRequest request) {
		Runnable before = beforeNextCheckout.getAndSet(null);
		if (before != null) {
			before.run();
		}
		if (failNextCheckout.getAndSet(false)) {
			throw new ApiException(ProblemType.PAYMENTS_UNAVAILABLE, "Fake Stripe is down");
		}
		return sessionsByKey.computeIfAbsent(request.idempotencyKey(), key -> {
			String id = "cs_test_fake_" + ids.incrementAndGet();
			CheckoutSession session = new CheckoutSession(id, "https://checkout.stripe.test/c/pay/" + id);
			checkouts.add(new OpenedCheckout(request, session));
			return session;
		});
	}

	@Override
	public void expireCheckoutSession(String sessionId) {
		expiredSessions.add(sessionId);
	}

	@Override
	public String refund(String paymentIntentId, String idempotencyKey) {
		return refundsByKey.computeIfAbsent(idempotencyKey, key -> {
			refundedPaymentIntents.add(paymentIntentId);
			return "re_test_fake_" + ids.incrementAndGet();
		});
	}

	/** The next checkout session fails as if Stripe were down. */
	public void failNextCheckout() {
		failNextCheckout.set(true);
	}

	/** Runs the action while the next checkout session is being opened, as if it happened meanwhile. */
	public void beforeNextCheckout(Runnable action) {
		beforeNextCheckout.set(action);
	}

	/** The checkout sessions actually opened for the booking, oldest first. */
	public List<OpenedCheckout> checkoutsFor(long bookingId) {
		return checkouts.stream().filter(checkout -> checkout.request().bookingId() == bookingId).toList();
	}

	public boolean wasExpired(String sessionId) {
		return expiredSessions.contains(sessionId);
	}

	/** How many refunds were actually made against the payment. */
	public long refundsOf(String paymentIntentId) {
		return refundedPaymentIntents.stream().filter(paymentIntentId::equals).count();
	}

}
