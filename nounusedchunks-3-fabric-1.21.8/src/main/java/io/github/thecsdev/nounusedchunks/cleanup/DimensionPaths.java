package io.github.thecsdev.nounusedchunks.cleanup;

import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Pattern;

final class DimensionPaths {
	private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
	private static final Pattern PATH = Pattern.compile("[a-z0-9/._-]+");

	private DimensionPaths() {
	}

	static Path resolve(Path worldRoot, String dimension) throws IOException {
		Path normalizedRoot = worldRoot.toAbsolutePath().normalize();
		Path result = switch (dimension) {
			case "minecraft:overworld" -> normalizedRoot;
			case "minecraft:the_nether" -> normalizedRoot.resolve("DIM-1");
			case "minecraft:the_end" -> normalizedRoot.resolve("DIM1");
			default -> resolveCustom(normalizedRoot, dimension);
		};

		result = result.toAbsolutePath().normalize();
		if (!result.startsWith(normalizedRoot)) {
			throw new IOException("Dimension path escapes the world directory: " + dimension);
		}
		return result;
	}

	private static Path resolveCustom(Path worldRoot, String dimension) throws IOException {
		int separator = dimension.indexOf(':');
		if (separator <= 0 || separator == dimension.length() - 1) {
			throw new IOException("Invalid dimension identifier: " + dimension);
		}

		String namespace = dimension.substring(0, separator);
		String path = dimension.substring(separator + 1);
		if (!NAMESPACE.matcher(namespace).matches() || !PATH.matcher(path).matches() || path.contains("..")) {
			throw new IOException("Invalid dimension identifier: " + dimension);
		}
		return worldRoot.resolve("dimensions").resolve(namespace).resolve(path);
	}
}
