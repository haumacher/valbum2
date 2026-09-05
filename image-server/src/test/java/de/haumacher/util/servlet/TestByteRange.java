/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.util.servlet;

import junit.framework.TestCase;

/**
 * Test case for {@link ByteRange}.
 */
@SuppressWarnings("javadoc")
public class TestByteRange extends TestCase {

	private static final long TOTAL = 1000;

	public void testNoHeader() {
		assertWhole(ByteRange.parse(null, TOTAL));
	}

	public void testFirstLast() {
		ByteRange range = ByteRange.parse("bytes=0-99", TOTAL);
		assertPartial(range, 0, 99, 100);
	}

	public void testOpenEnd() {
		ByteRange range = ByteRange.parse("bytes=900-", TOTAL);
		assertPartial(range, 900, 999, 100);
	}

	public void testSuffix() {
		ByteRange range = ByteRange.parse("bytes=-100", TOTAL);
		assertPartial(range, 900, 999, 100);
	}

	public void testSuffixLargerThanFile() {
		ByteRange range = ByteRange.parse("bytes=-5000", TOTAL);
		assertPartial(range, 0, 999, TOTAL);
	}

	public void testSuffixZero() {
		assertTrue(ByteRange.parse("bytes=-0", TOTAL).isUnsatisfiable());
	}

	public void testEndBeyondFileIsClamped() {
		ByteRange range = ByteRange.parse("bytes=500-5000", TOTAL);
		assertPartial(range, 500, 999, 500);
	}

	public void testStartBeyondFileIsUnsatisfiable() {
		ByteRange range = ByteRange.parse("bytes=1000-", TOTAL);
		assertTrue(range.isUnsatisfiable());
		assertFalse(range.isPartial());
		assertFalse(range.isWhole());
	}

	public void testEmptyFile() {
		assertTrue(ByteRange.parse("bytes=0-", 0).isUnsatisfiable());
	}

	public void testWholeFileRange() {
		assertPartial(ByteRange.parse("bytes=0-999", TOTAL), 0, 999, TOTAL);
	}

	public void testSingleByte() {
		assertPartial(ByteRange.parse("bytes=42-42", TOTAL), 42, 42, 1);
	}

	public void testCaseAndWhitespaceTolerance() {
		assertPartial(ByteRange.parse("  Bytes= 10 - 19 ", TOTAL), 10, 19, 10);
	}

	public void testMultiRangeServedWhole() {
		// Documented behaviour: a multi-range request is answered with the complete entity.
		assertWhole(ByteRange.parse("bytes=0-9,20-29", TOTAL));
	}

	public void testGarbageIgnored() {
		assertWhole(ByteRange.parse("", TOTAL));
		assertWhole(ByteRange.parse("garbage", TOTAL));
		assertWhole(ByteRange.parse("bytes=", TOTAL));
		assertWhole(ByteRange.parse("bytes=abc-def", TOTAL));
		assertWhole(ByteRange.parse("bytes=10", TOTAL));
		assertWhole(ByteRange.parse("bytes=-", TOTAL));
		assertWhole(ByteRange.parse("items=0-99", TOTAL));
		assertWhole(ByteRange.parse("bytes=99999999999999999999-", TOTAL));
	}

	public void testReversedRangeIgnored() {
		assertWhole(ByteRange.parse("bytes=99-10", TOTAL));
	}

	private static void assertWhole(ByteRange range) {
		assertTrue(String.valueOf(range), range.isWhole());
		assertFalse(range.isPartial());
		assertFalse(range.isUnsatisfiable());
	}

	private static void assertPartial(ByteRange range, long start, long end, long length) {
		assertTrue(String.valueOf(range), range.isPartial());
		assertFalse(range.isWhole());
		assertFalse(range.isUnsatisfiable());
		assertEquals(start, range.getStart());
		assertEquals(end, range.getEnd());
		assertEquals(length, range.getLength());
	}

}
