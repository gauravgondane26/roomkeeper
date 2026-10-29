package com.roomkeeper.payment;

import java.util.Collection;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

	/** The booking's payment in this status; the database allows one Pending or Succeeded at a time. */
	Optional<Payment> findByBookingIdAndStatus(Long bookingId, PaymentStatus status);

	/** The booking's latest payment in one of these statuses. */
	Optional<Payment> findFirstByBookingIdAndStatusInOrderByIdDesc(Long bookingId, Collection<PaymentStatus> statuses);

	/** Every attempt to pay for the booking, whatever became of it. */
	long countByBookingId(Long bookingId);

	Optional<Payment> findByProviderSessionId(String providerSessionId);

	/** Which booking the checkout session pays for, without loading the payment before the booking is locked. */
	@Query("select p.bookingId from Payment p where p.providerSessionId = :sessionId")
	Optional<Long> findBookingIdBySessionId(String sessionId);

}
