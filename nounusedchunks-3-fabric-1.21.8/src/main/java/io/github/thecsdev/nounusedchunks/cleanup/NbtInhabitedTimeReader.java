package io.github.thecsdev.nounusedchunks.cleanup;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.OptionalLong;

final class NbtInhabitedTimeReader {
	private static final int TAG_END = 0;
	private static final int TAG_BYTE = 1;
	private static final int TAG_SHORT = 2;
	private static final int TAG_INT = 3;
	private static final int TAG_LONG = 4;
	private static final int TAG_FLOAT = 5;
	private static final int TAG_DOUBLE = 6;
	private static final int TAG_BYTE_ARRAY = 7;
	private static final int TAG_STRING = 8;
	private static final int TAG_LIST = 9;
	private static final int TAG_COMPOUND = 10;
	private static final int TAG_INT_ARRAY = 11;
	private static final int TAG_LONG_ARRAY = 12;
	private static final int MAX_DEPTH = 512;
	private static final int MAX_COLLECTION_LENGTH = 64 * 1024 * 1024;

	private NbtInhabitedTimeReader() {
	}

	static OptionalLong read(InputStream input) throws IOException {
		DataInputStream data = new DataInputStream(input);
		int rootType = data.readUnsignedByte();
		if (rootType != TAG_COMPOUND) {
			throw new IOException("Chunk NBT root is not a compound tag: " + rootType);
		}
		data.readUTF();

		while (true) {
			int type = data.readUnsignedByte();
			if (type == TAG_END) {
				return OptionalLong.empty();
			}
			String name = data.readUTF();
			if (type == TAG_LONG && name.equals("InhabitedTime")) {
				return OptionalLong.of(data.readLong());
			}
			skipPayload(data, type, 0);
		}
	}

	private static void skipPayload(DataInputStream data, int type, int depth) throws IOException {
		if (depth > MAX_DEPTH) {
			throw new IOException("NBT exceeds maximum nesting depth");
		}

		switch (type) {
			case TAG_BYTE -> skipFully(data, 1);
			case TAG_SHORT -> skipFully(data, 2);
			case TAG_INT, TAG_FLOAT -> skipFully(data, 4);
			case TAG_LONG, TAG_DOUBLE -> skipFully(data, 8);
			case TAG_BYTE_ARRAY -> skipFully(data, checkedLength(data.readInt(), 1));
			case TAG_STRING -> data.readUTF();
			case TAG_LIST -> {
				int childType = data.readUnsignedByte();
				int length = checkedCount(data.readInt());
				for (int index = 0; index < length; index++) {
					skipPayload(data, childType, depth + 1);
				}
			}
			case TAG_COMPOUND -> {
				while (true) {
					int childType = data.readUnsignedByte();
					if (childType == TAG_END) {
						break;
					}
					data.readUTF();
					skipPayload(data, childType, depth + 1);
				}
			}
			case TAG_INT_ARRAY -> skipFully(data, checkedLength(data.readInt(), Integer.BYTES));
			case TAG_LONG_ARRAY -> skipFully(data, checkedLength(data.readInt(), Long.BYTES));
			default -> throw new IOException("Unknown NBT tag type: " + type);
		}
	}

	private static int checkedCount(int count) throws IOException {
		if (count < 0 || count > MAX_COLLECTION_LENGTH) {
			throw new IOException("Invalid NBT collection length: " + count);
		}
		return count;
	}

	private static long checkedLength(int count, int elementBytes) throws IOException {
		checkedCount(count);
		try {
			return Math.multiplyExact((long) count, elementBytes);
		} catch (ArithmeticException exception) {
			throw new IOException("NBT collection length overflow", exception);
		}
	}

	private static void skipFully(DataInputStream data, long bytes) throws IOException {
		long remaining = bytes;
		while (remaining > 0) {
			long skipped = data.skip(remaining);
			if (skipped > 0) {
				remaining -= skipped;
				continue;
			}
			if (data.read() < 0) {
				throw new EOFException("Unexpected end of NBT data");
			}
			remaining--;
		}
	}
}
