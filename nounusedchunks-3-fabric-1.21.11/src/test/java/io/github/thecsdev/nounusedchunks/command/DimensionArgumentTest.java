package io.github.thecsdev.nounusedchunks.command;

import com.mojang.brigadier.StringReader;
import net.minecraft.commands.arguments.IdentifierArgument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DimensionArgumentTest {
	@Test
	void acceptsNamespacedDimensionBeforeNumericArguments() throws Exception {
		StringReader reader = new StringReader("minecraft:overworld 20 32");

		assertEquals("minecraft:overworld", IdentifierArgument.id().parse(reader).toString());
		assertEquals(' ', reader.peek());
	}

	@Test
	void canonicalizesAllPseudoDimension() throws Exception {
		assertEquals("minecraft:all", IdentifierArgument.id().parse(new StringReader("all")).toString());
	}
}
