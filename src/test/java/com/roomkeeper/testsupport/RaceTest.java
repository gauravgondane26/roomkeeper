package com.roomkeeper.testsupport;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A test whose threads, or background work, race each other. A rare interleaving can pass
 * a single run, so the nightly workflow repeats every test tagged {@value #TAG}
 * ({@code -Dgroups=race}); a new one is picked up there without listing it.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Test
@Tag(RaceTest.TAG)
public @interface RaceTest {

	String TAG = "race";

}
