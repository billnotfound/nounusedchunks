package io.github.thecsdev.nounusedchunks.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.thecsdev.nounusedchunks.cleanup.CleanupJob;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public final class NoUnusedChunksCommands {
	private static final int DEFAULT_THREADS = Math.clamp(Runtime.getRuntime().availableProcessors(), 1, 32);

	private NoUnusedChunksCommands() {
	}

	public static void register(
			CommandDispatcher<CommandSourceStack> dispatcher,
			CommandBuildContext registryAccess,
			Commands.CommandSelection environment
	) {
		dispatcher.register(Commands.literal("nounusedchunks")
				.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
				.then(Commands.literal("schedule")
						.then(Commands.argument("dimension", StringArgumentType.word())
								.suggests((context, builder) -> SharedSuggestionProvider.suggest(
										Stream.concat(
												Stream.of("all"),
												StreamSupport.stream(context.getSource().getServer().getAllLevels().spliterator(), false)
														.map(level -> level.dimension().identifier().toString())
										),
										builder
								))
								.executes(context -> schedule(context, 0, DEFAULT_THREADS))
								.then(Commands.argument("maxInhabitedTime", LongArgumentType.longArg(0))
										.executes(context -> schedule(
												context,
												LongArgumentType.getLong(context, "maxInhabitedTime"),
												DEFAULT_THREADS
										))
										.then(Commands.argument("threads", IntegerArgumentType.integer(1, 64))
												.executes(context -> schedule(
														context,
														LongArgumentType.getLong(context, "maxInhabitedTime"),
														IntegerArgumentType.getInteger(context, "threads")
												))))))
				.then(Commands.literal("status").executes(NoUnusedChunksCommands::status))
				.then(Commands.literal("cancel").executes(NoUnusedChunksCommands::cancel))
		);
	}

	private static int schedule(CommandContext<CommandSourceStack> context, long maxInhabitedTime, int threads) {
		CommandSourceStack source = context.getSource();
		String requestedDimension = StringArgumentType.getString(context, "dimension");
		Set<String> loadedDimensions = new LinkedHashSet<>();
		source.getServer().getAllLevels().forEach(level -> loadedDimensions.add(level.dimension().identifier().toString()));

		Set<String> selectedDimensions;
		if (requestedDimension.equals("all")) {
			selectedDimensions = loadedDimensions;
		} else if (loadedDimensions.contains(requestedDimension)) {
			selectedDimensions = Set.of(requestedDimension);
		} else {
			source.sendFailure(Component.literal(
					"Unknown or unloaded dimension '" + requestedDimension + "'. Use tab completion to select one."
			));
			return 0;
		}

		Path worldRoot = source.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
		CleanupJob job = new CleanupJob(
				selectedDimensions,
				maxInhabitedTime,
				threads,
				source.getTextName(),
				Instant.now()
		);
		try {
			job.save(worldRoot);
		} catch (IOException exception) {
			source.sendFailure(Component.literal("Could not save the cleanup job: " + exception.getMessage()));
			return 0;
		}

		source.sendSuccess(() -> Component.literal(
				"Cleanup scheduled for " + String.join(", ", selectedDimensions)
						+ " with InhabitedTime <= " + maxInhabitedTime
						+ " using " + threads + " parallel workers. It will run after the server stops cleanly."
		), true);
		source.sendSuccess(() -> Component.literal("Back up the world, then use /stop when ready."), false);
		return selectedDimensions.size();
	}

	private static int status(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		Path worldRoot = source.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
		try {
			var pending = CleanupJob.load(worldRoot);
			if (pending.isEmpty()) {
				source.sendSuccess(() -> Component.literal("No cleanup is scheduled."), false);
				return 0;
			}

			CleanupJob job = pending.get();
			source.sendSuccess(() -> Component.literal(
					"Scheduled dimensions: " + String.join(", ", job.dimensions())
							+ "; InhabitedTime <= " + job.maxInhabitedTime()
							+ "; workers: " + job.threads()
							+ "; requested by: " + job.scheduledBy()
			), false);
			return 1;
		} catch (IOException exception) {
			source.sendFailure(Component.literal("Could not read the cleanup job: " + exception.getMessage()));
			return 0;
		}
	}

	private static int cancel(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		Path worldRoot = source.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
		try {
			boolean deleted = Files.deleteIfExists(CleanupJob.file(worldRoot));
			source.sendSuccess(
					() -> Component.literal(deleted ? "Scheduled cleanup cancelled." : "No cleanup was scheduled."),
					true
			);
			return deleted ? 1 : 0;
		} catch (IOException exception) {
			source.sendFailure(Component.literal("Could not cancel the cleanup job: " + exception.getMessage()));
			return 0;
		}
	}
}
