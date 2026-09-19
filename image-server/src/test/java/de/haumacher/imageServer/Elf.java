/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * The little of the ELF format {@link TestDebianPackageLibraries} needs: the shared libraries an
 * executable or a library asks the dynamic loader for.
 *
 * <p>
 * Hand-written on purpose. Reading a handful of header fields is less than a dependency costs, and
 * shelling out to <code>readelf</code> would make the test depend on binutils being installed on
 * whatever machine builds a release.
 * </p>
 *
 * <p>
 * Both address sizes and both byte orders are understood: the <code>linux-armhf</code> natives are
 * 32-bit, the others 64-bit, and a future platform artifact may be big-endian.
 * </p>
 */
class Elf {

	/** The first four bytes of every ELF file. */
	private static final byte[] MAGIC = { 0x7F, 'E', 'L', 'F' };

	private static final int SHT_DYNAMIC = 6;

	private static final long DT_NULL = 0;

	private static final long DT_NEEDED = 1;

	private final ByteBuffer _data;

	private final boolean _wide;

	private Elf(ByteBuffer data, boolean wide) {
		_data = data;
		_wide = wide;
	}

	/** Whether the given content starts with the ELF magic. */
	static boolean isElf(byte[] content) {
		if (content.length < MAGIC.length) {
			return false;
		}
		for (int n = 0; n < MAGIC.length; n++) {
			if (content[n] != MAGIC[n]) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The <code>DT_NEEDED</code> sonames of the given ELF file, in the order the file lists them.
	 *
	 * @param content
	 *        The whole file; {@link #isElf(byte[])} must have said yes.
	 */
	static List<String> needed(byte[] content) {
		int addressSize = content[4];
		boolean wide = addressSize == 2;
		ByteOrder order = content[5] == 2 ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
		ByteBuffer data = ByteBuffer.wrap(content).order(order);
		return new Elf(data, wide).readNeeded();
	}

	private List<String> readNeeded() {
		long sectionHeaderOffset = _wide ? _data.getLong(0x28) : unsigned(_data.getInt(0x20));
		int entrySize = _data.getShort(_wide ? 0x3A : 0x2E) & 0xFFFF;
		int entryCount = _data.getShort(_wide ? 0x3C : 0x30) & 0xFFFF;

		List<String> result = new ArrayList<>();
		for (int n = 0; n < entryCount; n++) {
			int header = (int) (sectionHeaderOffset + (long) n * entrySize);
			int type = _data.getInt(header + 4);
			if (type != SHT_DYNAMIC) {
				continue;
			}
			long offset = _wide ? _data.getLong(header + 0x18) : unsigned(_data.getInt(header + 0x10));
			long size = _wide ? _data.getLong(header + 0x20) : unsigned(_data.getInt(header + 0x14));
			int stringSection = _data.getInt(header + (_wide ? 0x28 : 0x18));

			int stringHeader = (int) (sectionHeaderOffset + (long) stringSection * entrySize);
			long strings = _wide
				? _data.getLong(stringHeader + 0x18)
				: unsigned(_data.getInt(stringHeader + 0x10));

			int step = _wide ? 16 : 8;
			for (long at = offset; at + step <= offset + size; at += step) {
				int entry = (int) at;
				long tag = _wide ? _data.getLong(entry) : unsigned(_data.getInt(entry));
				long value = _wide ? _data.getLong(entry + 8) : unsigned(_data.getInt(entry + 4));
				if (tag == DT_NULL) {
					break;
				}
				if (tag == DT_NEEDED) {
					result.add(string(strings + value));
				}
			}
		}
		return result;
	}

	private String string(long at) {
		StringBuilder buffer = new StringBuilder();
		for (int n = (int) at; n < _data.limit(); n++) {
			byte next = _data.get(n);
			if (next == 0) {
				break;
			}
			buffer.append((char) (next & 0xFF));
		}
		return buffer.toString();
	}

	private static long unsigned(int value) {
		return value & 0xFFFFFFFFL;
	}

}
