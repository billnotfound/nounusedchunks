package io.github.thecsdev.nounusedchunks.cleanup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

public record CleanupJob(
		Set<String> dimensions,
		long maxInhabitedTime,
		int threads,
		String scheduledBy,
		Instant scheduledAt
) {
	private static final String FILE_NAME = "nounusedchunks-pending.properties";
	private static final int FORMAT_VERSION = 1;

	public CleanupJob {
		dimensions = Collections.unmodifiableSet(new LinkedHashSet<>(dimensions));
		if (dimensions.isEmpty()) {
			throw new IllegalArgumentException("At least one dimension must be selected");
		}
		if (maxInhabitedTime < 0) {
			throw new IllegalArgumentException("maxInhabitedTime must be non-negative");
		}
		if (threads < 1 || threads > 64) {
			throw new IllegalArgumentException("threads must be between 1 and 64");
		}
	}

	public static Path file(Path worldRoot) {
		return worldRoot.resolve(FILE_NAME);
	}

	public void save(Path worldRoot) throws IOException {
		Files.createDirectories(worldRoot);
		Path target = file(worldRoot);
		Path temporary = target.resolveSibling(target.getFileName() + ".tmp");

		Properties properties = new Properties();
		properties.setProperty("format", Integer.toString(FORMAT_VERSION));
		properties.setProperty("dimensions", String.join(",", dimensions));
		properties.setProperty("maxInhabitedTime", Long.toString(maxInhabitedTime));
		properties.setProperty("threads", Integer.toString(threads));
		properties.setProperty("scheduledBy", scheduledBy);
		properties.setProperty("scheduledAt", scheduledAt.toString());

		try (OutputStream output = Files.newOutputStream(temporary)) {
			properties.store(output, "No Unused Chunks pending cleanup; runs after a clean server shutdown");
		}
		atomicReplace(temporary, target);
	}

	public static Optional<CleanupJob> load(Path worldRoot) throws IOException {
		Path path = file(worldRoot);
		if (!Files.isRegularFile(path)) {
			return Optional.empty();
		}

		Properties properties = new Properties();
		try (InputStream input = Files.newInputStream(path)) {
			properties.load(input);
		}

		try {
			int format = Integer.parseInt(require(properties, "format"));
			if (format != FORMAT_VERSION) {
				throw new IOException("Unsupported cleanup job format: " + format);
			}

			Set<String> dimensions = new LinkedHashSet<>(Arrays.asList(require(properties, "dimensions").split(",")));
			dimensions.removeIf(String::isBlank);
			return Optional.of(new CleanupJob(
					dimensions,
					Long.parseLong(require(properties, "maxInhabitedTime")),
					Integer.parseInt(require(properties, "threads")),
					properties.getProperty("scheduledBy", "unknown"),
					Instant.parse(require(properties, "scheduledAt"))
			));
		} catch (IllegalArgumentException | DateTimeParseException exception) {
			throw new IOException("Invalid pending cleanup job: " + path, exception);
		}
	}

	private static String require(Properties properties, String key) throws IOException {
		String value = properties.getProperty(key);
		if (value == null || value.isBlank()) {
			throw new IOException("Missing cleanup job property: " + key);
		}
		return value;
	}

	private static void atomicReplace(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException exception) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
