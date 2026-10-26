package com.roomkeeper.hotel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(nullable = false, columnDefinition = "text[]")
	private List<String> amenities = new ArrayList<>();

	@Column(nullable = false)
	private boolean active;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected Hotel() {
	}

	public Hotel(Long ownerId, String name, String city, List<String> amenities, Instant createdAt) {
		this.ownerId = ownerId;
		this.name = name;
		this.city = city;
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

	public List<String> getAmenities() {
		return List.copyOf(amenities);
	}

	public boolean isActive() {
		return active;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
