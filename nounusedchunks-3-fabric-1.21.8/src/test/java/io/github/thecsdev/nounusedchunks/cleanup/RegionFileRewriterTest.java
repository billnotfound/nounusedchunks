package io.github.thecsdev.nounusedchunks.cleanup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionFileRewriterTest {
	private static final int SECTOR_BYTES = 4096;
	private static final int HEADER_BYTES = 8192;

	@TempDir
	Path temporaryDirectory;

	@Test
	void removesThresholdMatchesAndTheSameSidecarSlots() throws Exception {
		Path dimension = temporaryDirectory.resolve("world");
		Path chunks = dimension.resolve("region").resolve("r.0.0.mca");
		Path entities = dimension.resolve("entities").resolve("r.0.0.mca");
		Path poi = dimension.resolve("poi").resolve("r.0.0.mca");

		Map<Integer, byte[]> records = new LinkedHashMap<>();
		records.put(0, chunkRecord(0L));
		records.put(1, chunkRecord(100L));
		records.put(2, chunkRecordWithoutInhabitedTime());
		writeRegion(chunks, records);
		writeRegion(entities, records);
		writeRegion(poi, records);
		long originalBytes = Files.size(chunks) + Files.size(entities) + Files.size(poi);

		RegionFileRewriter.RegionResult result = RegionFileRewriter.clean(dimension, chunks, 0L);

		assertEquals(3, result.scannedChunks());
		assertEquals(1, result.removedChunks());
		assertEquals(0, readLocation(chunks, 0));
		assertEquals(0, readLocation(entities, 0));
		assertEquals(0, readLocation(poi, 0));
		assertTrue(readLocation(chunks, 1) != 0);
		assertTrue(readLocation(chunks, 2) != 0);
		assertEquals(3L * SECTOR_BYTES, result.reclaimedBytes());
		assertEquals(originalBytes - result.reclaimedBytes(), Files.size(chunks) + Files.size(entities) + Files.size(poi));

		RegionFileRewriter.RegionResult secondPass = RegionFileRewriter.clean(dimension, chunks, 100L);
		assertEquals(1, secondPass.removedChunks());
		assertEquals(0, readLocation(chunks, 1));
		assertTrue(readLocation(chunks, 2) != 0, "Chunks without InhabitedTime must be kept");
	}

	@Test
	void parallelCleanerProcessesIndependentRegions() throws Exception {
		Path worldRoot = temporaryDirectory.resolve("parallel-world");
		Path regionDirectory = worldRoot.resolve("region");
		for (int region = 0; region < 12; region++) {
			Map<Integer, byte[]> records = new LinkedHashMap<>();
			records.put(0, chunkRecord(0L));
			records.put(1, chunkRecord(20L));
			writeRegion(regionDirectory.resolve("r." + region + ".0.mca"), records);
		}

		CleanupJob job = new CleanupJob(
				java.util.Set.of("minecraft:overworld"),
				0L,
				4,
				"test",
				java.time.Instant.EPOCH
		);
		ParallelChunkCleaner.CleanupSummary summary = ParallelChunkCleaner.clean(worldRoot, job);

		assertEquals(12, summary.processedRegions());
		assertEquals(24, summary.scannedChunks());
		assertEquals(12, summary.removedChunks());
		assertEquals(12L * SECTOR_BYTES, summary.reclaimedBytes());
		for (int region = 0; region < 12; region++) {
			Path file = regionDirectory.resolve("r." + region + ".0.mca");
			assertEquals(0, readLocation(file, 0));
			assertTrue(readLocation(file, 1) != 0);
		}
	}

	@Test
	void leavesMainRegionRetryableWhenASidecarIsCorrupt() throws Exception {
		Path dimension = temporaryDirectory.resolve("retryable-world");
		Path chunks = dimension.resolve("region").resolve("r.0.0.mca");
		Path entities = dimension.resolve("entities").resolve("r.0.0.mca");
		writeRegion(chunks, Map.of(0, chunkRecord(0L)));
		Files.createDirectories(entities.getParent());
		Files.write(entities, new byte[]{1, 2, 3});

		assertThrows(IOException.class, () -> RegionFileRewriter.clean(dimension, chunks, 0L));
		assertTrue(readLocation(chunks, 0) != 0, "The main region must retain retry information");
	}

	@Test
	void readsAndDeletesExternalChunkStreams() throws Exception {
		Path dimension = temporaryDirectory.resolve("external-world");
		Path chunks = dimension.resolve("region").resolve("r.0.0.mca");
		byte[] externalPayload = deflate(chunkNbt(0L));
		ByteBuffer marker = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN);
		marker.putInt(1);
		marker.put((byte) (0x80 | 2));
		writeRegion(chunks, Map.of(0, marker.array()));
		Path external = chunks.getParent().resolve("c.0.0.mcc");
		Files.write(external, externalPayload);

		RegionFileRewriter.RegionResult result = RegionFileRewriter.clean(dimension, chunks, 0L);

		assertEquals(1, result.removedChunks());
		assertEquals(0, readLocation(chunks, 0));
		assertTrue(Files.notExists(external));
		assertEquals(SECTOR_BYTES + externalPayload.length, result.reclaimedBytes());
	}

	@Test
	void ignoresZeroByteRegionPlaceholders() throws Exception {
		Path worldRoot = temporaryDirectory.resolve("empty-placeholders");
		Path chunks = worldRoot.resolve("region").resolve("r.0.0.mca");
		writeRegion(chunks, Map.of(0, chunkRecord(0L)));
		Path emptyMain = worldRoot.resolve("region").resolve("r.1.0.mca");
		Files.createFile(emptyMain);
		Path emptyEntities = worldRoot.resolve("entities").resolve("r.0.0.mca");
		Path emptyPoi = worldRoot.resolve("poi").resolve("r.0.0.mca");
		Files.createDirectories(emptyEntities.getParent());
		Files.createDirectories(emptyPoi.getParent());
		Files.createFile(emptyEntities);
		Files.createFile(emptyPoi);

		CleanupJob job = new CleanupJob(
				java.util.Set.of("minecraft:overworld"), 0L, 2, "test", java.time.Instant.EPOCH
		);
		ParallelChunkCleaner.CleanupSummary summary = ParallelChunkCleaner.clean(worldRoot, job);

		assertEquals(1, summary.processedRegions());
		assertEquals(1, summary.removedChunks());
		assertEquals(0, Files.size(emptyMain));
		assertEquals(0, Files.size(emptyEntities));
		assertEquals(0, Files.size(emptyPoi));
	}

	private static byte[] chunkRecord(long inhabitedTime) throws IOException {
		return deflateRecord(chunkNbt(inhabitedTime));
	}

	private static byte[] chunkNbt(long inhabitedTime) throws IOException {
		ByteArrayOutputStream nbtBytes = new ByteArrayOutputStream();
		try (DataOutputStream output = new DataOutputStream(nbtBytes)) {
			output.writeByte(10);
			output.writeUTF("");
			output.writeByte(4);
			output.writeUTF("InhabitedTime");
			output.writeLong(inhabitedTime);
			output.writeByte(0);
		}
		return nbtBytes.toByteArray();
	}

	private static byte[] chunkRecordWithoutInhabitedTime() throws IOException {
		ByteArrayOutputStream nbtBytes = new ByteArrayOutputStream();
		try (DataOutputStream output = new DataOutputStream(nbtBytes)) {
			output.writeByte(10);
			output.writeUTF("");
			output.writeByte(3);
			output.writeUTF("DataVersion");
			output.writeInt(1234);
			output.writeByte(0);
		}
		return deflateRecord(nbtBytes.toByteArray());
	}

	private static byte[] deflateRecord(byte[] nbt) throws IOException {
		byte[] compressed = deflate(nbt);
		ByteBuffer record = ByteBuffer.allocate(5 + compressed.length).order(ByteOrder.BIG_ENDIAN);
		record.putInt(compressed.length + 1);
		record.put((byte) 2);
		record.put(compressed);
		return record.array();
	}

	private static byte[] deflate(byte[] bytes) throws IOException {
		ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
		try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressedBytes)) {
			deflater.write(bytes);
		}
		return compressedBytes.toByteArray();
	}

	private static void writeRegion(Path path, Map<Integer, byte[]> records) throws IOException {
		Files.createDirectories(path.getParent());
		int[] locations = new int[1024];
		int sector = 2;
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
			channel.write(ByteBuffer.allocate(HEADER_BYTES));
			for (Map.Entry<Integer, byte[]> entry : records.entrySet()) {
				byte[] record = entry.getValue();
				int sectors = Math.ceilDiv(record.length, SECTOR_BYTES);
				locations[entry.getKey()] = sector << 8 | sectors;
				ByteBuffer padded = ByteBuffer.allocate(sectors * SECTOR_BYTES);
				padded.put(record);
				padded.position(0);
				channel.write(padded, (long) sector * SECTOR_BYTES);
				sector += sectors;
			}

			ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
			for (int location : locations) {
				header.putInt(location);
			}
			for (int slot = 0; slot < 1024; slot++) {
				header.putInt(123456789);
			}
			header.flip();
			channel.write(header, 0);
			channel.truncate((long) sector * SECTOR_BYTES);
		}
	}

	private static int readLocation(Path path, int slot) throws IOException {
		ByteBuffer location = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN);
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
			channel.read(location, (long) slot * Integer.BYTES);
		}
		location.flip();
		return location.getInt();
	}
}
