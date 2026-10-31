package com.roomkeeper.booking;

import java.time.Instant;
import java.time.LocalDate;

import com.roomkeeper.common.error.ApiException;
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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A guest's place in line for a sold-out stay: {@code roomsCount} rooms of one room type,
 * the nights from {@code checkIn} up to {@code checkOut}. When rooms free up, the oldest
 * waiter whose stay then fits is offered them as a normal hold.
 */
@Entity
@Table(name = "waitlist_entries")
public class WaitlistEntry {

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
	private WaitlistStatus status = WaitlistStatus.WAITING;

	@Column(name = "booking_id")
	private Long bookingId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "offered_at")
	private Instant offeredAt;

	protected WaitlistEntry() {
	}

	public WaitlistEntry(long userId, Room room, LocalDate checkIn, LocalDate checkOut, int roomsCount,
			Instant createdAt) {
		this.userId = userId;
		this.room = room;
		this.checkIn = checkIn;
		this.checkOut = checkOut;
		this.roomsCount = roomsCount;
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

	/** The stay's last night: the one before check-out. */
	public LocalDate lastNight() {
		return checkOut.minusDays(1);
	}

	public int getRoomsCount() {
		return roomsCount;
	}

	public WaitlistStatus getStatus() {
		return status;
	}

	public boolean isWaiting() {
		return status == WaitlistStatus.WAITING;
	}

	/** The hold made for the guest, once rooms were offered. */
	public Long getBookingId() {
		return bookingId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getOfferedAt() {
		return offeredAt;
	}

	/** True once the stay's first night is before {@code today}: it can no longer be offered. */
	public boolean hasBegunBy(LocalDate today) {
		return checkIn.isBefore(today);
	}

	/** Rooms were held for the guest as booking {@code bookingId}. */
	void offer(long bookingId, Instant at) {
		requireWaiting();
		this.status = WaitlistStatus.OFFERED;
		this.bookingId = bookingId;
		this.offeredAt = at;
	}

	/** The guest gives up their place; only while still waiting. */
	public void leave() {
		if (!isWaiting()) {
			throw new ApiException(ProblemType.WAITLIST_STATE,
					"Waitlist entry " + id + " is " + status + "; only a waiting guest can leave");
		}
		this.status = WaitlistStatus.LEFT;
	}

	/** The stay began before rooms freed up. */
	void expire() {
		requireWaiting();
		this.status = WaitlistStatus.EXPIRED;
	}

	private void requireWaiting() {
		if (!isWaiting()) {
			throw new IllegalStateException("Waitlist entry " + id + " is " + status + ", not waiting");
		}
	}

}
