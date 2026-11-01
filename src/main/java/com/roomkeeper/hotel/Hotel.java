package com.roomkeeper.hotel;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "hotels")
public class Hotel {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// An id rather than a User association: the hotel module only needs to compare owners
	@Column(name = "owner_id", nullable = false)
	private Long ownerId;

	@Column(nullable = false, length = 200)
	private String name;

	@Column(nullable = false, length = 100)
	private String city;

	@Column(name = "time_zone", nullable = false, length = 64)
	private String timeZone;

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(nullable = false, columnDefinition = "text[]")
	private List<String> amenities = new ArrayList<>();

	@Column(nullable = false)
	private boolean active;

	@Enumerated(EnumType.STRING)
	@Column(name = "approval_status", nullable = false, length = 20)
	private ApprovalStatus approvalStatus = ApprovalStatus.PENDING;

	@Column(name = "rejection_reason", length = 500)
	private String rejectionReason;

	@Column(name = "reviewed_by")
	private Long reviewedBy;

	@Column(name = "reviewed_at")
	private Instant reviewedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected Hotel() {
	}

	public Hotel(Long ownerId, String name, String city, String timeZone, List<String> amenities,
			Instant createdAt) {
		this.ownerId = ownerId;
		this.name = name;
		this.city = city;
		this.timeZone = timeZone;
		this.amenities = new ArrayList<>(amenities);
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public Long getOwnerId() {
		return ownerId;
	}

	public String getName() {
		return name;
	}

	public String getCity() {
		return city;
	}

	public String getTimeZone() {
		return timeZone;
	}

	/** Today's date where the hotel is: the first night that can still be sold. */
	public LocalDate today(Clock clock) {
		return LocalDate.ofInstant(clock.instant(), ZoneId.of(timeZone));
	}

	public List<String> getAmenities() {
		return List.copyOf(amenities);
	}

	/**
	 * Edits the details. A rejected hotel goes back for review with the changes. So does an
	 * approved one whose name or city changes, off sale meanwhile: the admin approved the
	 * hotel as it was described, and it mustn't turn into a different listing unreviewed.
	 * Its time zone and amenities can change freely.
	 */
	public void changeDetails(String name, String city, String timeZone, List<String> amenities) {
		boolean redescribed = !this.name.equals(name) || !this.city.equals(city);
		this.name = name;
		this.city = city;
		this.timeZone = timeZone;
		this.amenities = new ArrayList<>(amenities);
		if (approvalStatus == ApprovalStatus.REJECTED) {
			approvalStatus = ApprovalStatus.PENDING;
			rejectionReason = null;
		}
		else if (approvalStatus == ApprovalStatus.APPROVED && redescribed) {
			approvalStatus = ApprovalStatus.PENDING;
			reviewedBy = null;
			reviewedAt = null;
			active = false;
		}
	}

	public boolean isActive() {
		return active;
	}

	public void activate() {
		this.active = true;
	}

	/** Off sale: out of search and closed to new bookings. Its inventory and bookings stay. */
	public void deactivate() {
		this.active = false;
	}

	public ApprovalStatus getApprovalStatus() {
		return approvalStatus;
	}

	public String getRejectionReason() {
		return rejectionReason;
	}

	/** The admin who last approved or rejected the hotel; null if none has. */
	public Long getReviewedBy() {
		return reviewedBy;
	}

	public Instant getReviewedAt() {
		return reviewedAt;
	}

	public void approve(long adminId, Instant at) {
		review(ApprovalStatus.APPROVED, null, adminId, at);
	}

	public void reject(String reason, long adminId, Instant at) {
		review(ApprovalStatus.REJECTED, reason, adminId, at);
	}

	private void review(ApprovalStatus status, String reason, long adminId, Instant at) {
		this.approvalStatus = status;
		this.rejectionReason = reason;
		this.reviewedBy = adminId;
		this.reviewedAt = at;
	}

}
