# No Unused Chunks 3 for Minecraft 1.21.11

This is the command-driven Fabric port for Minecraft Java Edition 1.21.11, the final 1.21.x release.
It removes chunks whose
`InhabitedTime` is less than or equal to an administrator-selected threshold.

The cleanup runs only after a clean server shutdown. At that point Fabric guarantees that every
level has been closed, so the mod can compact the Anvil region files without racing Minecraft's
chunk I/O. This works on dedicated multiplayer servers and integrated single-player servers.

## Requirements

- Minecraft Java Edition 1.21.11
- Java 21
- Fabric Loader 0.19.5 or newer
- Fabric API 0.141.6+1.21.11

Only the server needs the mod for multiplayer use. An integrated single-player client can install it
normally. Commands require Minecraft's administrator permission level.

## Commands

```text
/nounusedchunks schedule <dimension|all> [maxInhabitedTime] [threads]
/nounusedchunks status
/nounusedchunks cancel
```

`dimension` accepts a loaded dimension identifier such as `minecraft:overworld`,
`minecraft:the_nether`, `minecraft:the_end`, or a modded dimension. Tab completion lists the
available identifiers. `all` selects every currently loaded dimension.

`maxInhabitedTime` defaults to `0`. A chunk is removed when its NBT value satisfies:

```text
InhabitedTime <= maxInhabitedTime
```

`threads` defaults to the number of available processors, capped at 32. It can be set from 1 to 64.
Each worker owns a complete region coordinate while processing it, so no two workers write the same
`.mca` file.

Examples:

```text
# Remove only never-inhabited chunks in every dimension, using the automatic worker count.
/nounusedchunks schedule all

# Remove Overworld chunks with InhabitedTime <= 1200, using 8 workers.
/nounusedchunks schedule minecraft:overworld 1200 8

# Inspect or cancel the pending operation before shutdown.
/nounusedchunks status
/nounusedchunks cancel
```

After scheduling, make a world backup and stop the server normally with `/stop`. The shutdown waits
for cleanup to finish. Progress and the final reclaimed-byte count are written to the server log.

## Safety and consistency

- A pending job is saved in the world as `nounusedchunks-pending.properties`, so it survives until a
  clean shutdown.
- Main chunk, entity, and point-of-interest region files are compacted together. Deleted chunk slots
  are removed from `region`, `entities`, and `poi`.
- Every replacement is written to a temporary file first, flushed, then atomically moved over the
  original where the filesystem supports it.
- Sidecar region files are committed before the main chunk region. If a sidecar operation fails, the
  original chunk NBT remains available and the pending job is retained for a safe retry.
- Chunks without a valid top-level `InhabitedTime` long tag are kept. Corrupt region data aborts that
  job instead of guessing.
- Gzip, zlib, uncompressed, LZ4, and external `.mcc` chunk streams use Minecraft 1.21.11's own region
  compression implementation.

This mod deliberately does not edit an open world. Always make a backup before deleting chunks;
`InhabitedTime` is a useful heuristic, not proof that a chunk contains nothing important.

## Building

The project uses the Gradle wrapper and Java 21:

```text
gradlew.bat build
```

The remapped mod JAR is produced in `build/libs`. Automated tests create synthetic Anvil region files
and verify threshold behavior, `entities`/`poi` synchronization, compaction, and parallel processing.
