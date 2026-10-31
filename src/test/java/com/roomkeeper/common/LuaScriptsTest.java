package com.roomkeeper.common;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Scripts are read once, through the application's class loader. A script built on a request
 * thread used to keep that thread's context class loader: an embedded server's, which stops
 * with its context, after which every use of the script failed (CI: "this web application
 * instance has been stopped already. Could not load [ratelimit/token_bucket.lua]").
 */
class LuaScriptsTest {

	@Test
	void readsTheScriptWhateverTheThreadsContextClassLoaderSees() throws Exception {
		AtomicReference<RedisScript<Long>> script = new AtomicReference<>();
		// A context class loader that sees nothing, as a stopped web app's effectively does
		try (URLClassLoader blind = new URLClassLoader(new URL[0], null)) {
			Thread thread = new Thread(() -> script.set(LuaScripts.load("auth/refresh_rotate.lua", Long.class)));
			thread.setContextClassLoader(blind);
			thread.start();
			thread.join();
		}

		assertThat(script.get().getScriptAsString()).contains("redis.call");
		assertThat(script.get().getResultType()).isEqualTo(Long.class);
		assertThat(script.get().getSha1()).hasSize(40);
	}

	@Test
	void refusesAScriptThatIsntThere() {
		assertThatIllegalStateException().isThrownBy(() -> LuaScripts.load("nowhere/missing.lua", Long.class))
			.withMessageContaining("nowhere/missing.lua");
	}

}
