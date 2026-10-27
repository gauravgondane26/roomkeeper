package com.roomkeeper.testsupport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Makes tasks race. */
public final class Concurrently {

	private Concurrently() {
	}

	/**
	 * Starts every task at the same moment, each on its own thread, and returns their results
	 * in order. Each thread waits on one latch, so none starts before all are ready to go.
	 */
	public static <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		List<Future<T>> futures = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
			for (Callable<T> task : tasks) {
				futures.add(pool.submit(() -> {
					start.await();
					return task.call();
				}));
			}
			start.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get());
			}
			return results;
		}
	}

}
