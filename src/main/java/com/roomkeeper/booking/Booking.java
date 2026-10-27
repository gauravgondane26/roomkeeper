package com.roomkeeper.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.InvalidFieldException;
import com.roomkeeper.common.error.ProblemType;
import com.roomkeeper.hotel.Room;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.BatchSize;

/**
 * A stay of {@code roomsCount} rooms of one room type, from the check-in night up to the
 * check-out morning. The amount is the price quoted when the rooms were held, so a later
 * price change doesn't touch it.
 */
@Entity
@Table(name = "bookings")
public class Booking {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "room_id", nullable = false)
	private Room room;

	@Column(name = "check_in", nullable = false)
	private LocalDate checkIn;

	@Column(name = "check_out", nullable = false)
	private LocalDate checkOut;

	@Column(name = "rooms_count", nullable = false)
	private int roomsCount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private BookingStatus status = BookingStatus.RESERVED;

	@Column(nullable = false, precision = 10, scale = 2)
	private BigDecimal amount;

	@Column(name = "hold_expires_at", nullable = false)
	private Instant holdExpiresAt;

	@ManyToMany
	@JoinTable(name = "booking_guests", joinColumns = @JoinColumn(name = "booking_id"),
			inverseJoinColumns = @JoinColumn(name = "guest_id"))
	@OrderBy("id")
	@BatchSize(size = 50) // a page of bookings loads its guests in one query
	private List<Guest> guests = new ArrayList<>();

	// The hold expiry job and the payment webhook can race on one booking; the loser retries
	@Version
	private long version;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected Booking() {
	}

	/** A new hold on the rooms, until {@code holdExpiresAt}. */
	public Booking(Long userId, Room room, LocalDate checkIn, LocalDate checkOut, int roomsCount, BigDecimal amount,
			Instant holdExpiresAt, Instant createdAt) {
		this.userId = userId;
		this.room = room;
		this.checkIn = checkIn;
		this.checkOut = checkOut;
		this.roomsCount = roomsCount;
		this.amount = amount;
		this.holdExpiresAt = holdExpiresAt;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public Room getRoom() {
		return room;
	}

	public LocalDate getCheckIn() {
		return checkIn;
	}

	public LocalDate getCheckOut() {
		return checkOut;
	}

	public int getRoomsCount() {
		return roomsCount;
	}

	public BookingStatus getStatus() {
		return status;
	}

	/**
	 * The status as the guest should see it at {@code now}: a hold that has run out is
	 * EXPIRED, even before the expiry job releases its rooms and records it.
	 */
	public BookingStatus statusAt(Instant now) {
		return status.isHold() && holdRanOut(now) ? BookingStatus.EXPIRED : status;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public Instant getHoldExpiresAt() {
		return holdExpiresAt;
	}

	public List<Guest> getGuests() {
		return List.copyOf(guests);
	}

	/** True from {@code holdExpiresAt} on: the rooms are no longer held for this booking, whatever its status says. */
	public boolean holdRanOut(Instant now) {
		return !now.isBefore(holdExpiresAt);
	}

	/**
	 * Puts these guests on the booking, replacing any already on it, while the rooms are still
	 * held. At least one, and no more than the rooms sleep.
	 */
	public void putGuests(Collection<Guest> newGuests, Instant now) {
		requireHeld(now);
		int fits = room.getCapacity() * roomsCount;
		if (newGuests.isEmpty() || newGuests.size() > fits) {
			throw new InvalidFieldException("guestIds", "must name 1 to " + fits + " guests: " + roomsCount
					+ (roomsCount == 1 ? " room" : " rooms") + " sleeping " + room.getCapacity());
		}
		moveTo(BookingStatus.GUESTS_ADDED);
		guests.clear();
		guests.addAll(newGuests);
		// In id order, as when loaded (@OrderBy); saved guests always have ids
		guests.sort(Comparator.comparing(Guest::getId, Comparator.nullsLast(Comparator.naturalOrder())));
	}

	/**
	 * Checks the guest can start paying: the guests are on and the rooms are still held.
	 * Throws otherwise, changing nothing.
	 */
	public void requirePaymentCanStart(Instant now) {
		requireHeld(now);
		if (status == BookingStatus.RESERVED) {
			throw new ApiException(ProblemType.BOOKING_STATE, "Put the guests on booking " + id + " before paying");
		}
		if (!status.canMoveTo(BookingStatus.PAYMENT_PENDING)) {
			throw new ApiException(ProblemType.BOOKING_STATE, "Booking " + id + " is " + status + " and can't be paid");
		}
	}

	/** The guest is paying: the rooms stay held until the payment confirms or the hold runs out. */
	public void startPayment(Instant now) {
		requirePaymentCanStart(now);
		moveTo(BookingStatus.PAYMENT_PENDING);
	}

	/** The hold ran out without payment; the caller gives the rooms back. */
	public void expire() {
		moveTo(BookingStatus.EXPIRED);
	}

	private void requireHeld(Instant now) {
		if (status == BookingStatus.EXPIRED || (status.isHold() && holdRanOut(now))) {
			throw new ApiException(ProblemType.HOLD_EXPIRED, "Booking " + id + "'s hold ran out at " + holdExpiresAt);
		}
	}

	private void moveTo(BookingStatus next) {
		if (!status.canMoveTo(next)) {
			throw new ApiException(ProblemType.BOOKING_STATE,
					"Booking " + id + " is " + status + " and can't become " + next);
		}
		status = next;
	}

}
