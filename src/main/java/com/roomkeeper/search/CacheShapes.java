package com.roomkeeper.search;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.stream.Collectors;

/**
 * A short hash of what a cached response type stores: its record components' names and
 * types, through nested records and the collections holding them. It's part of each
 * cache's key prefix, so after a deploy that changes a cached response the new code reads
 * none of the entries the old code wrote, and those expire on their own. Without it,
 * Jackson would read an old entry into the new type and leave a new field empty.
 */
final class CacheShapes {

	private CacheShapes() {
	}

	/** Eight hex digits; the same for any two types that serialize alike. */
	static String of(Class<?> type) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(describe(type).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash, 0, 4);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is always available", ex);
		}
	}

	private static String describe(Type type) {
		if (type instanceof ParameterizedType generic) {
			return describe(generic.getRawType()) + Arrays.stream(generic.getActualTypeArguments())
				.map(CacheShapes::describe)
				.collect(Collectors.joining(",", "<", ">"));
		}
		if (type instanceof Class<?> record && record.isRecord()) {
			return Arrays.stream(record.getRecordComponents())
				.map(component -> component.getName() + ":" + describe(component.getGenericType()))
				.collect(Collectors.joining(",", "{", "}"));
		}
		return type.getTypeName();
	}

}
