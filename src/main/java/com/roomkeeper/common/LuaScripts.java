package com.roomkeeper.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.springframework.data.redis.core.script.RedisScript;

/**
 * Lua scripts kept on the classpath, read once through the application's own class loader.
 * Not {@code RedisScript.of(new ClassPathResource(...))}: that resource takes the context
 * class loader of whichever thread first loads the class (a request thread's is its embedded
 * server's) and reads the file again later, so once that server stopped every use of the
 * script failed. A missing script fails at startup instead of at first use.
 */
public final class LuaScripts {

	private LuaScripts() {
	}

	public static <T> RedisScript<T> load(String path, Class<T> resultType) {
		try (InputStream in = LuaScripts.class.getClassLoader().getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalStateException("No Lua script on the classpath at " + path);
			}
			return RedisScript.of(new String(in.readAllBytes(), StandardCharsets.UTF_8), resultType);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Couldn't read the Lua script at " + path, ex);
		}
	}

}
