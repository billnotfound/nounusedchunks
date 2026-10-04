package io.github.thecsdev.nounusedchunks;

import io.github.thecsdev.nounusedchunks.cleanup.CleanupJob;
import io.github.thecsdev.nounusedchunks.cleanup.ParallelChunkCleaner;
import io.github.thecsdev.nounusedchunks.command.NoUnusedChunksCommands;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

public final class NoUnusedChunks implements ModInitializer {
	public static final String MOD_ID = "nounusedchunks";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register(NoUnusedChunksCommands::register);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			try {
				var pendingJob = CleanupJob.load(worldRoot);
				if (pendingJob.isEmpty()) {
					return;
				}

				CleanupJob job = pendingJob.get();
				LOGGER.info(
						"Starting scheduled cleanup: dimensions={}, maxInhabitedTime={}, threads={}",
						job.dimensions(), job.maxInhabitedTime(), job.threads()
				);
				ParallelChunkCleaner.CleanupSummary summary = ParallelChunkCleaner.clean(worldRoot, job);
				Files.deleteIfExists(CleanupJob.file(worldRoot));
				LOGGER.info(
						"Cleanup complete: {} chunks removed from {} regions; reclaimed {} bytes in {} ms",
						summary.removedChunks(), summary.processedRegions(), summary.reclaimedBytes(), summary.elapsedMillis()
				);
			} catch (Exception exception) {
				LOGGER.error(
						"Scheduled cleanup failed. The pending job was kept so it can be retried after the next clean shutdown.",
						exception
				);
			}
		});
		LOGGER.info("No Unused Chunks initialized for Minecraft 1.21.11");
	}
}
