package de.haumacher.util.servlet;

import java.util.Random;
import junit.framework.TestCase;

/**
 * Probe review for issue #26: invariants of {@link ByteRange} over many inputs.
 */
public class TestByteRangeProbe extends TestCase {

	public void testPartialRangesAlwaysLieInsideTheEntity() {
		Random rnd = new Random(20260905);
		for (int i = 0; i < 5000; i++) {
			long total = 1 + rnd.nextInt(5000);
			long a = rnd.nextInt(6000);
			long b = rnd.nextInt(6000);
			String header = switch (rnd.nextInt(3)) {
				case 0 -> "bytes=" + a + "-" + b;
				case 1 -> "bytes=" + a + "-";
				default -> "bytes=-" + a;
			};
			ByteRange r = ByteRange.parse(header, total);
			if (r.isPartial()) {
				assertTrue(header, r.getStart() >= 0);
				assertTrue(header, r.getStart() <= r.getEnd());
				assertTrue(header, r.getEnd() < total);
				assertEquals(header, r.getEnd() - r.getStart() + 1, r.getLength());
			} else if (r.isUnsatisfiable()) {
				// Only a start beyond the entity, or a zero suffix, may be unsatisfiable.
				assertTrue(header, header.startsWith("bytes=-0") || a >= total);
			} else {
				assertTrue(header, r.isWhole());
				// Whole answers come from reversed ranges or a suffix covering everything.
				assertTrue(header, (header.endsWith("-" + b) && a > b && !header.startsWith("bytes=-"))
					|| (header.startsWith("bytes=-") && a >= total));
			}
		}
	}

	public void testOneByteAndEmptyEntities() {
		ByteRange one = ByteRange.parse("bytes=0-0", 1);
		assertTrue(one.isPartial());
		assertEquals(1, one.getLength());
		assertTrue(ByteRange.parse("bytes=1-", 1).isUnsatisfiable());
		assertTrue(ByteRange.parse("bytes=0-", 0).isUnsatisfiable());
		assertTrue(ByteRange.parse("bytes=0-99", 0).isUnsatisfiable());
		assertTrue(ByteRange.parse(null, 0).isWhole());
	}

	public void testFirstAndLastByteOfTheVideoFixture() {
		long total = 3883;
		ByteRange first = ByteRange.parse("bytes=0-0", total);
		ByteRange last = ByteRange.parse("bytes=-1", total);
		assertEquals(0, first.getStart());
		assertEquals(total - 1, last.getStart());
		assertEquals(total - 1, last.getEnd());
		ByteRange rest = ByteRange.parse("bytes=100-", total);
		assertEquals(total - 100, rest.getLength());
	}
}
