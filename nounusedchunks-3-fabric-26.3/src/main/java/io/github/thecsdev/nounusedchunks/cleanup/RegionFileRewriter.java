package io.github.thecsdev.nounusedchunks.cleanup;

import io.github.thecsdev.nounusedchunks.NoUnusedChunks;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;
import java.util.OptionalLong;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.world.level.chunk.storage.RegionFileVersion;

final class RegionFileRewriter {
	private static final int SECTOR_BYTES = 4096;
	private static final int HEADER_BYTES = SECTOR_BYTES * 2;
	private static final int SLOT_COUNT = 1024;
	private static final int MAX_INTERNAL_PAYLOAD = 255 * SECTOR_BYTES;
	private static final int EXTERNAL_STREAM_FLAG = 0x80;
	private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

	private RegionFileRewriter() {
	}

	static RegionCoordinates coordinates(Path regionFile) throws IOException {
		Matcher matcher = REGION_FILE.matcher(regionFile.getFileName().toString());
		if (!matcher.matches()) {
			throw new IOException("Invalid region file name: " + regionFile);
		}
		try {
			return new RegionCoordinates(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
		} catch (NumberFormatException exception) {
			throw new IOException("Invalid region coordinates: " + regionFile, exception);
		}
	}

	static RegionResult clean(Path dimensionRoot, Path chunkRegionFile, long maxInhabitedTime) throws IOException {
		RegionCoordinates coordinates = coordinates(chunkRegionFile);
		Header chunkHeader = readHeader(chunkRegionFile);
		BitSet removedSlots = new BitSet(SLOT_COUNT);
		long scannedChunks = 0;

		try (FileChannel source = FileChannel.open(chunkRegionFile, StandardOpenOption.READ)) {
			for (int slot = 0; slot < SLOT_COUNT; slot++) {
				int location = chunkHeader.locations()[slot];
				if (location == 0) {
					continue;
				}
				scannedChunks++;
				OptionalLong inhabitedTime = readInhabitedTime(
						source,
						chunkRegionFile.getParent(),
						coordinates,
						slot,
						location,
						chunkHeader.fileSize()
				);
				if (inhabitedTime.isPresent() && inhabitedTime.getAsLong() <= maxInhabitedTime) {
					removedSlots.set(slot);
				}
			}
		}

		if (removedSlots.isEmpty()) {
			return new RegionResult(scannedChunks, 0, 1, 0);
		}

		long reclaimed = 0;
		String fileName = chunkRegionFile.getFileName().toString();
		for (String sidecarDirectory : new String[]{"entities", "poi"}) {
			Path sidecar = dimensionRoot.resolve(sidecarDirectory).resolve(fileName);
			if (Files.isRegularFile(sidecar)) {
				Header sidecarHeader = readHeader(sidecar);
				reclaimed += rewrite(sidecar, sidecarHeader, removedSlots::get);
				reclaimed += deleteExternalChunks(sidecar.getParent(), coordinates, removedSlots);
			}
		}

		// Commit the main chunk region last. If a sidecar rewrite fails, the original
		// InhabitedTime values remain available and the pending job can safely retry.
		reclaimed += rewrite(chunkRegionFile, chunkHeader, removedSlots::get);
		reclaimed += deleteExternalChunks(chunkRegionFile.getParent(), coordinates, removedSlots);

		return new RegionResult(scannedChunks, removedSlots.cardinality(), 1, reclaimed);
	}

	private static OptionalLong readInhabitedTime(
			FileChannel source,
			Path regionDirectory,
			RegionCoordinates region,
			int slot,
			int location,
			long fileSize
	) throws IOException {
		ChunkLocation chunk = validateLocation(location, fileSize);
		ByteBuffer prefix = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN);
		readFully(source, prefix, (long) chunk.sectorOffset() * SECTOR_BYTES);
		prefix.flip();
		int length = prefix.getInt();
		int compressionByte = Byte.toUnsignedInt(prefix.get());
		boolean external = (compressionByte & EXTERNAL_STREAM_FLAG) != 0;
		int compression = compressionByte & ~EXTERNAL_STREAM_FLAG;

		if (length < 1) {
			throw new IOException("Invalid chunk length " + length + " in " + regionDirectory);
		}

		InputStream raw;
		if (external) {
			int chunkX = region.x() * 32 + slot % 32;
			int chunkZ = region.z() * 32 + slot / 32;
			Path externalPath = regionDirectory.resolve("c." + chunkX + "." + chunkZ + ".mcc");
			if (!Files.isRegularFile(externalPath)) {
				throw new IOException("Missing external chunk stream: " + externalPath);
			}
			raw = Files.newInputStream(externalPath);
		} else {
			int payloadLength = length - 1;
			if (payloadLength > chunk.sectorCount() * SECTOR_BYTES - 5 || payloadLength > MAX_INTERNAL_PAYLOAD) {
				throw new IOException("Invalid chunk payload length " + payloadLength + " in " + regionDirectory);
			}
			ByteBuffer payload = ByteBuffer.allocate(payloadLength);
			readFully(source, payload, (long) chunk.sectorOffset() * SECTOR_BYTES + 5);
			raw = new ByteArrayInputStream(payload.array());
		}

		try (InputStream compressed = raw; InputStream nbt = decompress(compressed, compression)) {
			return NbtInhabitedTimeReader.read(nbt);
		}
	}

	private static InputStream decompress(InputStream input, int compression) throws IOException {
		RegionFileVersion version = RegionFileVersion.fromId(compression);
		if (version == null) {
			throw new IOException("Unsupported region compression type: " + compression);
		}
		return version.wrap(input);
	}

	private static long rewrite(Path sourcePath, Header header, IntPredicate removeSlot) throws IOException {
		Path temporary = sourcePath.resolveSibling(sourcePath.getFileName() + ".nounusedchunks.tmp");
		Files.deleteIfExists(temporary);
		int[] newLocations = new int[SLOT_COUNT];
		int[] newTimestamps = header.timestamps().clone();
		int nextSector = 2;

		try (FileChannel source = FileChannel.open(sourcePath, StandardOpenOption.READ);
			 FileChannel target = FileChannel.open(
					 temporary,
					 StandardOpenOption.CREATE_NEW,
					 StandardOpenOption.WRITE
			 )) {
			writeFully(target, ByteBuffer.allocate(HEADER_BYTES), 0);

			for (int slot = 0; slot < SLOT_COUNT; slot++) {
				int location = header.locations()[slot];
				if (location == 0) {
					continue;
				}
				if (removeSlot.test(slot)) {
					newTimestamps[slot] = 0;
					continue;
				}

				ChunkLocation chunk = validateLocation(location, header.fileSize());
				if (nextSector > 0xFFFFFF - chunk.sectorCount()) {
					throw new IOException("Compacted region file exceeds the Anvil sector limit: " + sourcePath);
				}
				copyFully(
						source,
						target,
						(long) chunk.sectorOffset() * SECTOR_BYTES,
						(long) nextSector * SECTOR_BYTES,
						(long) chunk.sectorCount() * SECTOR_BYTES
				);
				newLocations[slot] = nextSector << 8 | chunk.sectorCount();
				nextSector += chunk.sectorCount();
			}

			ByteBuffer newHeader = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
			for (int location : newLocations) {
				newHeader.putInt(location);
			}
			for (int timestamp : newTimestamps) {
				newHeader.putInt(timestamp);
			}
			newHeader.flip();
			writeFully(target, newHeader, 0);
			target.truncate((long) nextSector * SECTOR_BYTES);
			target.force(true);
		} catch (IOException | RuntimeException exception) {
			Files.deleteIfExists(temporary);
			throw exception;
		}

		atomicReplace(temporary, sourcePath);
		return Math.max(0, header.fileSize() - Files.size(sourcePath));
	}

	private static Header readHeader(Path path) throws IOException {
		long fileSize = Files.size(path);
		if (fileSize < HEADER_BYTES || fileSize % SECTOR_BYTES != 0) {
			throw new IOException("Invalid Anvil region file size: " + path + " (" + fileSize + " bytes)");
		}

		ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
			readFully(channel, buffer, 0);
		}
		buffer.flip();
		int[] locations = new int[SLOT_COUNT];
		int[] timestamps = new int[SLOT_COUNT];
		for (int index = 0; index < SLOT_COUNT; index++) {
			locations[index] = buffer.getInt();
		}
		for (int index = 0; index < SLOT_COUNT; index++) {
			timestamps[index] = buffer.getInt();
		}
		return new Header(locations, timestamps, fileSize);
	}

	private static ChunkLocation validateLocation(int location, long fileSize) throws IOException {
		int sectorOffset = location >>> 8;
		int sectorCount = location & 0xFF;
		if (sectorOffset < 2 || sectorCount == 0 || (long) (sectorOffset + sectorCount) * SECTOR_BYTES > fileSize) {
			throw new IOException("Invalid Anvil chunk location: offset=" + sectorOffset + ", sectors=" + sectorCount);
		}
		return new ChunkLocation(sectorOffset, sectorCount);
	}

	private static long deleteExternalChunks(Path regionDirectory, RegionCoordinates region, BitSet removedSlots) {
		long reclaimed = 0;
		for (int slot = removedSlots.nextSetBit(0); slot >= 0; slot = removedSlots.nextSetBit(slot + 1)) {
			int chunkX = region.x() * 32 + slot % 32;
			int chunkZ = region.z() * 32 + slot / 32;
			Path externalPath = regionDirectory.resolve("c." + chunkX + "." + chunkZ + ".mcc");
			try {
				long bytes = Files.isRegularFile(externalPath) ? Files.size(externalPath) : 0;
				if (Files.deleteIfExists(externalPath)) {
					reclaimed += bytes;
				}
			} catch (IOException exception) {
				// The rewritten header no longer references this file. Leaving an orphan is safer than failing the job.
				NoUnusedChunks.LOGGER.warn("Could not delete orphaned external chunk stream {}", externalPath, exception);
			}
		}
		return reclaimed;
	}

	private static void atomicReplace(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException exception) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
		long current = position;
		while (buffer.hasRemaining()) {
			int read = channel.read(buffer, current);
			if (read < 0) {
				throw new IOException("Unexpected end of region file");
			}
			if (read == 0) {
				continue;
			}
			current += read;
		}
	}

	private static void writeFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
		long current = position;
		while (buffer.hasRemaining()) {
			int written = channel.write(buffer, current);
			if (written == 0) {
				continue;
			}
			current += written;
		}
	}

	private static void copyFully(
			FileChannel source,
			FileChannel target,
			long sourcePosition,
			long targetPosition,
			long bytes
	) throws IOException {
		long copied = 0;
		while (copied < bytes) {
			long transferred = source.transferTo(sourcePosition + copied, bytes - copied, target.position(targetPosition + copied));
			if (transferred <= 0) {
				throw new IOException("Could not copy complete chunk record");
			}
			copied += transferred;
		}
	}

	record RegionCoordinates(int x, int z) {
	}

	record RegionResult(long scannedChunks, long removedChunks, long processedRegions, long reclaimedBytes) {
	}

	private record Header(int[] locations, int[] timestamps, long fileSize) {
	}

	private record ChunkLocation(int sectorOffset, int sectorCount) {
	}
}
