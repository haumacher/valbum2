/*
 * Copyright (c) 2022 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.ui.layout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import de.haumacher.imageServer.shared.model.AbstractImage;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.util.ToImage;
import de.haumacher.msgbuf.io.StringR;
import de.haumacher.msgbuf.json.JsonReader;
import junit.framework.TestCase;

/**
 * Test case for {@link AlbumLayout}
 */
@SuppressWarnings("javadoc")
public class TestAlbumLayout extends TestCase {
	
	public void testEmptyLayout() {
		AlbumLayout layout = new AlbumLayout(400, 100, images());
		assertEquals("", print(layout));
	}

	public void testOnlyLandscapeLayout() {
		AlbumLayout layout = new AlbumLayout(400, 100, 
			images(image("I1", 200, 100), image("I2", 200, 100), image("I3", 200, 100)));
		
		assertEquals(
			"[I1][I2]\n" + 
			"[I3 ][-]",
			
			print(layout));
	}

	public void testWithPortrait() {
		AlbumLayout layout = new AlbumLayout(400, 100, images(
			image("I1", 100, 200), 
			image("I2", 200, 100), 
			image("I3", 200, 100)));
		
		assertEquals(
			"[I1][I2][-]\n" + 
			"[  ][I3][ ]",
			
			print(layout));
	}
	
	public void testUnmatchedLandscape() {
		AlbumLayout layout = new AlbumLayout(400, 100, images(
			image("I1", 200, 100), 
			image("I2", 100, 200), 
			image("I3", 100, 200)));

		assertEquals(
			"[I2][I3][I1]\n" + 
			"[  ][  ][- ]", 
			
			print(layout));
	}
	
	public void testMultiLine() {
		AlbumLayout layout = new AlbumLayout(400, 100, images(
			image("L1", 200, 100), 
			image("P2", 100, 200), 
			image("P3", 100, 200),
			image("L4", 200, 100), 
			image("L5", 200, 100) 
		));
		
		assertEquals(
			"[P2][P3][L1]\n" + 
			"[  ][  ][L4]\n" + 
			"[ L5  ][ - ]",
			
			print(layout));
	}
	
	public void testScenario() throws IOException {
		 int pageWidth = 1628;
		 int maxRowHeight = 400;
		 String imagesJSON = "[[\"ImagePart\",{\"kind\":\"IMAGE\",\"name\":\"20200805_165101.jpg\",\"date\":1596646260743,\"width\":3264,\"height\":1836,\"orientation\":\"IDENTITY\",\"rating\":0,\"privacy\":0,\"comment\":\"\"}], [\"ImagePart\",{\"kind\":\"IMAGE\",\"name\":\"20200806_155817.jpg\",\"date\":1596729498085,\"width\":2250,\"height\":4000,\"orientation\":\"IDENTITY\",\"rating\":0,\"privacy\":0,\"comment\":\"\"}], [\"ImagePart\",{\"kind\":\"IMAGE\",\"name\":\"20200806_160303.jpg\",\"date\":1596729785249,\"width\":2250,\"height\":4000,\"orientation\":\"IDENTITY\",\"rating\":0,\"privacy\":0,\"comment\":\"\"}], [\"ImagePart\",{\"kind\":\"IMAGE\",\"name\":\"20200806_160401.jpg\",\"date\":1596729841678,\"width\":4032,\"height\":2016,\"orientation\":\"IDENTITY\",\"rating\":0,\"privacy\":0,\"comment\":\"\"}], [\"ImagePart\",{\"kind\":\"IMAGE\",\"name\":\"20200806_192205.jpg\",\"date\":1596741726178,\"width\":2250,\"height\":4000,\"orientation\":\"IDENTITY\",\"rating\":0,\"privacy\":0,\"comment\":\"\"}], [\"ImagePart\",{\"kind\":\"VIDEO\",\"name\":\"20200807_142409.mp4\",\"date\":1596803072000,\"width\":1920,\"height\":1080,\"orientation\":\"IDENTITY\",\"rating\":0,\"privacy\":0,\"comment\":\"\"}], [\"ImagePart\",{\"kind\":\"IMAGE\",\"name\":\"20200807_170252_2.jpg\",\"date\":1596819777967,\"width\":3024,\"height\":4032,\"orientation\":\"IDENTITY\",\"rating\":0,\"privacy\":0,\"comment\":\"\"}], [\"ImagePart\",{\"kind\":\"IMAGE\",\"name\":\"20200807_172307.jpg\",\"date\":1596820987569,\"width\":4032,\"height\":2016,\"orientation\":\"IDENTITY\",\"rating\":0,\"privacy\":0,\"comment\":\"\"}], [\"ImagePart\",{\"kind\":\"IMAGE\",\"name\":\"20200808_132616.jpg\",\"date\":1596893176821,\"width\":3264,\"height\":1836,\"orientation\":\"IDENTITY\",\"rating\":0,\"privacy\":0,\"comment\":\"\"}], [\"ImagePart\",{\"kind\":\"IMAGE\",\"name\":\"20200808_180845.jpg\",\"date\":1596910125388,\"width\":4032,\"height\":2016,\"orientation\":\"IDENTITY\",\"rating\":0,\"privacy\":0,\"comment\":\"\"}], [\"ImagePart\",{\"kind\":\"IMAGE\",\"name\":\"20200809_123924.jpg\",\"date\":1596976763543,\"width\":3264,\"height\":1836,\"orientation\":\"IDENTITY\",\"rating\":0,\"privacy\":0,\"comment\":\"\"}], [\"ImagePart\",{\"kind\":\"IMAGE\",\"name\":\"20200809_130959.jpg\",\"date\":1596978599302,\"width\":2016,\"height\":4032,\"orientation\":\"IDENTITY\",\"rating\":0,\"privacy\":0,\"comment\":\"\"}], [\"ImagePart\",{\"kind\":\"IMAGE\",\"name\":\"20200809_154120.jpg\",\"date\":1596987681144,\"width\":4032,\"height\":2016,\"orientation\":\"IDENTITY\",\"rating\":0,\"privacy\":0,\"comment\":\"\"}], [\"ImagePart\",{\"kind\":\"IMAGE\",\"name\":\"20200809_175047.jpg\",\"date\":1596995447588,\"width\":2016,\"height\":4032,\"orientation\":\"IDENTITY\",\"rating\":0,\"privacy\":0,\"comment\":\"\"}], [\"ImagePart\",{\"kind\":\"IMAGE\",\"name\":\"20200810_121213.jpg\",\"date\":1597061533249,\"width\":4032,\"height\":2016,\"orientation\":\"IDENTITY\",\"rating\":0,\"privacy\":0,\"comment\":\"\"}], [\"ImagePart\",{\"kind\":\"IMAGE\",\"name\":\"20200811_175516.jpg\",\"date\":1597168516685,\"width\":4032,\"height\":2016,\"orientation\":\"IDENTITY\",\"rating\":0,\"privacy\":0,\"comment\":\"\"}], [\"ImagePart\",{\"kind\":\"IMAGE\",\"name\":\"20200813_145114.jpg\",\"date\":1597330275251,\"width\":4032,\"height\":2016,\"orientation\":\"IDENTITY\",\"rating\":0,\"privacy\":0,\"comment\":\"\"}], [\"ImagePart\",{\"kind\":\"IMAGE\",\"name\":\"20200814_090229.jpg\",\"date\":1597395751807,\"width\":4000,\"height\":2250,\"orientation\":\"IDENTITY\",\"rating\":0,\"privacy\":0,\"comment\":\"\"}], [\"ImagePart\",{\"kind\":\"IMAGE\",\"name\":\"20200814_122852.jpg\",\"date\":1597408133906,\"width\":4000,\"height\":2250,\"orientation\":\"IDENTITY\",\"rating\":0,\"privacy\":0,\"comment\":\"\"}], [\"ImagePart\",{\"kind\":\"VIDEO\",\"name\":\"20200814_155710.mp4\",\"date\":1597413444000,\"width\":1080,\"height\":1920,\"orientation\":\"IDENTITY\",\"rating\":0,\"privacy\":0,\"comment\":\"\"}], [\"ImagePart\",{\"kind\":\"IMAGE\",\"name\":\"20200815_134005.jpg\",\"date\":1597498805494,\"width\":3264,\"height\":1836,\"orientation\":\"IDENTITY\",\"rating\":0,\"privacy\":0,\"comment\":\"\"}], [\"ImagePart\",{\"kind\":\"IMAGE\",\"name\":\"20200816_130132.jpg\",\"date\":1597582892162,\"width\":3264,\"height\":1836,\"orientation\":\"IDENTITY\",\"rating\":0,\"privacy\":0,\"comment\":\"\"}], [\"ImagePart\",{\"kind\":\"IMAGE\",\"name\":\"20200816_184624.jpg\",\"date\":1597603584442,\"width\":1836,\"height\":3264,\"orientation\":\"IDENTITY\",\"rating\":0,\"privacy\":0,\"comment\":\"\"}]]";

		 List<ImagePart> images = new ArrayList<>();
		 
		 JsonReader json = new JsonReader(new StringR(imagesJSON));
		 json.beginArray();
		 while (json.hasNext()) {
			 ImagePart image = (ImagePart) ImagePart.readAbstractImage(json);
			images.add(image);
		 }
		 json.endArray();
		
		 AlbumLayout layout = new AlbumLayout(pageWidth, maxRowHeight, images);
		 
		 HashSet<ImagePart> missing = new HashSet<ImagePart>(images);
		 missing.removeAll(layout.getAllImages());
		 
		 assertEquals(Collections.emptySet(), missing);
	}
	
	private String print(AlbumLayout layout) {
		int width = 0;
		for (Row row : layout) {
			int rowWidth = row.visit(CharWidth.INSTANCE, null);
			if (rowWidth > width) {
				width = rowWidth;
			}
		}

		AsciiArt result = null;
		for (Row row : layout) {
			int rowWidth = row.visit(CharWidth.INSTANCE, null);
			int rowHeight = row.getUnitHeight();
			AsciiArt rowResult = row.visit(new AsciiArtPrinter(rowHeight), width - rowWidth);
			if (result == null) {
				result = rowResult;
			} else {
				result = result.appendVertically(rowResult);
			}
		}
		
		return (result == null ? AsciiArt.EMPTY : result).toString();
	}

	private ImagePart image(String name, int width, int height) {
		return ImagePart.create().setWidth(width).setHeight(height).setName(name);
	}

	private List<ImagePart> images(ImagePart...parts) {
		return Arrays.asList(parts);
	}
	
	static class AsciiArt {
		
		public static final AsciiArt EMPTY = new AsciiArt();
		
		private List<String> _content = new ArrayList<String>();
		
		
		@Override
		public String toString() {
			return _content.stream().collect(Collectors.joining("\n"));
		}

		public static AsciiArt line(String string) {
			AsciiArt result = new AsciiArt();
			result._content.add(string);
			return result;
		}

		public AsciiArt appendHorizontally(AsciiArt other) {
			AsciiArt result = new AsciiArt();
			Iterator<String> i1 = _content.iterator();
			Iterator<String> i2 = other._content.iterator();
			
			while (i1.hasNext() && i2.hasNext()) {
				result._content.add(i1.next() + i2.next());
			}
			while (i1.hasNext()) {
				result._content.add(i1.next() + space(other.width()));
			}
			while (i2.hasNext()) {
				result._content.add(space(width()) + i2.next());
			}
			return result;
		}
		
		public AsciiArt appendVertically(AsciiArt other) {
			assert width() == other.width();
			AsciiArt result = new AsciiArt();
			result._content.addAll(_content);
			result._content.addAll(other._content);
			return result;
		}

		private int width() {
			return _content.isEmpty() ? 0 : _content.get(0).length();
		}
	}
	
	static class AsciiArtPrinter implements ContentVisitor<AsciiArt, Integer, RuntimeException> {
		
		private int _height;
		
		AsciiArtPrinter(int height) {
			_height = height;
		}

		@Override
		public AsciiArt visitRow(Row content, Integer padding) {
			int size = content.size();
			
			if (size == 0) {
				AsciiArt empty = AsciiArt.line(space(padding));
				AsciiArt result = empty;
				for (int n = 1; n < _height; n++) {
					result = result.appendVertically(empty);
				}
				return result;
			}
			
			int paddingPerEntry = padding / size;
			int extraPadding = padding % size; 
			
			AsciiArt result = AsciiArt.EMPTY;
			for (Content entry : content) {
				int entryPadding = paddingPerEntry;
				if (extraPadding > 0) {
					entryPadding++;
					extraPadding--;
				}
				AsciiArt entryResult = entry.visit(this, entryPadding);
				
				result = result.appendHorizontally(entryResult);
			}
			return result;
		}

		@Override
		public AsciiArt visitImg(Img content, Integer padding) {
			return print(padding, name(content.getImage()));
		}

		@Override
		public AsciiArt visitPadding(Padding content, Integer padding) {
			return print(padding, "-");
		}

		private AsciiArt print(int padding, String name) {
			int halfPadding = padding / 2;
			
			StringBuilder buffer = new StringBuilder();
			buffer.append('[');
			space(buffer, halfPadding);
			buffer.append(name);
			space(buffer, halfPadding + padding % 2);
			buffer.append(']');
			
			AsciiArt result = AsciiArt.line(buffer.toString());
			
			if (_height > 1) {
				StringBuilder empty = new StringBuilder();
				empty.append('[');
				space(empty, padding + name.length());
				empty.append(']');
				
				AsciiArt emptyLine = AsciiArt.line(empty.toString());
				
				for (int n = 1; n < _height; n++) {
					result = result.appendVertically(emptyLine);
				}
			}
			
			return result;
		}
		
		@Override
		public AsciiArt visitDoubleRow(DoubleRow content, Integer padding) {
			int upperWidth = content.getUpper().visit(CharWidth.INSTANCE, null);
			int lowerWidth = content.getLower().visit(CharWidth.INSTANCE, null);
			int width = Math.max(upperWidth, lowerWidth);
			
			AsciiArt upper = content.getUpper().visit(new AsciiArtPrinter(_height / 2 + _height % 2), padding + width - upperWidth);
			AsciiArt lower = content.getLower().visit(new AsciiArtPrinter(_height / 2), padding + width - lowerWidth);
			return upper.appendVertically(lower);
		}
		
	}

	static String space(int cnt) {
		StringBuilder result = new StringBuilder();
		space(result, cnt);
		return result.toString();
	}
	
	static final String name(AbstractImage image) {
		return ToImage.toImage(image).getName();
	}

	static void space(StringBuilder buffer, int cnt) {
		for (int n = 0; n < cnt; n++) {
			buffer.append(' ');
		}
	}

	static class CharWidth implements ContentVisitor<Integer, Void, RuntimeException> {
		
		/**
		 * Singleton {@link TestAlbumLayout.CharWidth} instance.
		 */
		public static final TestAlbumLayout.CharWidth INSTANCE = new TestAlbumLayout.CharWidth();
		
		private CharWidth() {
			// Singleton constructor.
		}

		@Override
		public Integer visitRow(Row content, Void arg) {
			int sum = 0;
			for (Content element : content) {
				sum += element.visit(this, arg);
			}
			return sum;
		}

		@Override
		public Integer visitImg(Img content, Void arg) {
			return 2 + name(content.getImage()).length();
		}
		
		@Override
		public Integer visitPadding(Padding padding, Void arg) {
			return 3;
		}

		@Override
		public Integer visitDoubleRow(DoubleRow content, Void arg) {
			return Math.max(content.getUpper().visit(this, arg), content.getLower().visit(this, arg));
		}
		
	}

}
