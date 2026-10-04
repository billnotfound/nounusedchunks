package io.github.thecsdev.nounusedchunks.cleanup;

import io.github.thecsdev.nounusedchunks.NoUnusedChunks;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class ParallelChunkCleaner {
	private ParallelChunkCleaner() {
	}

	public static CleanupSummary clean(Path worldRoot, CleanupJob job) throws IOException, InterruptedException {
		Instant started = Instant.now();
		List<RegionTask> tasks = discoverRegions(worldRoot, job.dimensions());
		if (tasks.isEmpty()) {
			return new CleanupSummary(0, 0, 0, 0, Duration.between(started, Instant.now()).toMillis());
		}

		AtomicLong scannedChunks = new AtomicLong();
		AtomicLong removedChunks = new AtomicLong();
		AtomicLong reclaimedBytes = new AtomicLong();
		AtomicInteger completedRegions = new AtomicInteger();
		AtomicInteger nextProgress = new AtomicInteger(10);
		List<Throwable> failures = new ArrayList<>();

		Thread.Builder.OfPlatform threadBuilder = Thread.ofPlatform().name("nounusedchunks-worker-", 1);
		ExecutorService executor = Executors.newFixedThreadPool(job.threads(), threadBuilder.factory());
		try {
			List<Future<RegionFileRewriter.RegionResult>> futures = tasks.stream()
					.map(task -> executor.submit(() -> {
						RegionFileRewriter.RegionResult result = RegionFileRewriter.clean(
								task.dimensionRoot(), task.regionFile(), job.maxInhabitedTime()
						);
						scannedChunks.addAndGet(result.scannedChunks());
						removedChunks.addAndGet(result.removedChunks());
						reclaimedBytes.addAndGet(result.reclaimedBytes());
						int done = completedRegions.incrementAndGet();
						logProgress(done, tasks.size(), nextProgress, removedChunks.get());
						return result;
					}))
					.toList();

			for (Future<RegionFileRewriter.RegionResult> future : futures) {
				try {
					future.get();
				} catch (ExecutionException exception) {
					failures.add(exception.getCause());
				}
			}
		} finally {
			executor.shutdownNow();
		}

		if (!failures.isEmpty()) {
			IOException exception = new IOException(
					"Failed to process " + failures.size() + " of " + tasks.size() + " region files"
			);
			failures.forEach(exception::addSuppressed);
			throw exception;
		}

		return new CleanupSummary(
				tasks.size(),
				scannedChunks.get(),
				removedChunks.get(),
				reclaimedBytes.get(),
				Duration.between(started, Instant.now()).toMillis()
		);
	}

	private static List<RegionTask> discoverRegions(Path worldRoot, Iterable<String> dimensions) throws IOException {
		List<RegionTask> tasks = new ArrayList<>();
		for (String dimension : dimensions) {
			Path dimensionRoot = DimensionPaths.resolve(worldRoot, dimension);
			Path regionDirectory = dimensionRoot.resolve("region");
			if (!Files.isDirectory(regionDirectory)) {
				NoUnusedChunks.LOGGER.warn("Skipping dimension without a region directory: {} ({})", dimension, regionDirectory);
				continue;
			}

			try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDirectory, "r.*.*.mca")) {
				for (Path regionFile : stream) {
					if (Files.isRegularFile(regionFile) && Files.size(regionFile) > 0) {
						RegionFileRewriter.coordinates(regionFile);
						tasks.add(new RegionTask(dimensionRoot, regionFile));
					}
				}
			}
		}
		return tasks;
	}

	private static void logProgress(int completed, int total, AtomicInteger nextProgress, long removedChunks) {
		int percent = completed * 100 / total;
		while (true) {
			int next = nextProgress.get();
			if (percent < next) {
				return;
			}
			int followingThreshold = Math.max(next + 10, (percent / 10 + 1) * 10);
			if (!nextProgress.compareAndSet(next, followingThreshold)) {
				continue;
			}
			NoUnusedChunks.LOGGER.info(
					"Cleanup progress: {}% ({}/{} regions, {} chunks removed)",
					Math.min(percent, 100), completed, total, removedChunks
			);
			return;
		}
	}

	private record RegionTask(Path dimensionRoot, Path regionFile) {
	}

	public record CleanupSummary(
			long processedRegions,
			long scannedChunks,
			long removedChunks,
			long reclaimedBytes,
			long elapsedMillis
	) {
	}
}
