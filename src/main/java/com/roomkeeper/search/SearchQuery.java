package com.roomkeeper.search;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** A validated search with its defaults filled in: the criteria and which page of results. */
public record SearchQuery(SearchCriteria criteria, int page, int size) {

	/**
	 * {@code {city}:{checkIn}:{checkOut}:{rooms}:{page}:{size}}, so the same search spelled
	 * differently ("Goa" or "GOA", defaults given or not) shares one entry. The city comes
	 * first, as a hash, so every search for a city can be found (and evicted) by one
	 * pattern without any user text in it.
	 */
	public String cacheKey() {
		return cityKey(criteria.city()) + ":" + criteria.checkIn() + ":" + criteria.checkOut() + ":"
				+ criteria.rooms() + ":" + page + ":" + size;
	}

	/** The first 16 hex digits of the SHA-256 of the lower-cased city. */
	public static String cityKey(String city) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256")
				.digest(city.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash, 0, 8);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Every Java runtime has SHA-256", ex);
		}
	}

}
