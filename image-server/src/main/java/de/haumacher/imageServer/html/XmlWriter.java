/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.html;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;

/**
 * {@link XmlAppendable} writing to an {@link Appendable} output.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class XmlWriter extends Writer implements XmlAppendable {

	private final ArrayList<String> _openTags = new ArrayList<String>();

	private final Appendable _out;

	private State _state = State.CONTENT;

	/**
	 * Creates a {@link XmlWriter}.
	 */
	public XmlWriter(Appendable out) {
		_out = out;
	}

	@Override
	public void begin(String name) throws IOException {
		closePotentialTag();
		
		_out.append('<');
		_out.append(name);
		_openTags.add(name);
		_state = State.ATTRIBUTES;
	}

	private void closePotentialTag() throws IOException {
		closePotentialAttr();

		if (_state == State.ATTRIBUTES) {
			_out.append('>');
			_state = State.CONTENT;
		}
	}

	private void closePotentialAttr() throws IOException {
		if (_state == State.ATTRIBUTE) {
			doCloseAttr();
		}
	}

	@Override
	public void endEmpty() throws IOException {
		closePotentialAttr();
		assertOpenTag();

		_out.append('/');
		_out.append('>');
		_state = State.CONTENT;
	}

	private void assertOpenTag() {
		if (_state != State.ATTRIBUTES) {
			throw new IllegalStateException("No open tag: " + _state);
		}
	}
	
	@Override
	public void write(int c) throws IOException {
		if (_state == State.ATTRIBUTE) {
			writeAttributeChar((char) c);
		} else {
			closePotentialTag();
			writeElementChar((char) c);
		}
	}
	
	@Override
	public void write(String str, int off, int len) throws IOException {
		if (_state == State.ATTRIBUTE) {
			writeAttributeText(str, off, off + len);
		} else {
			closePotentialTag();
			writeElementText(str, off, off + len);
		}
	}
	
	@Override
	public void write(char[] cbuf, int off, int len) throws IOException {
		if (_state == State.ATTRIBUTE) {
			for (int n = off, stop = off + len; n < stop; n++) {
				writeAttributeChar(cbuf[n]);
			}
		} else {
			closePotentialTag();
			for (int n = off, stop = off + len; n < stop; n++) {
				writeElementChar(cbuf[n]);
			}
		}
	}
	
	private void writeAttributeText(CharSequence value, int start, int end) throws IOException {
		if (value == null) {
			return;
		}
		for (int n = start; n < end; n++) {
			writeAttributeChar(value.charAt(n));
		}
	}

	private void writeAttributeChar(char ch) throws IOException {
		switch (ch) {
			case 0x00:
			case 0x01:
			case 0x02:
			case 0x03:
			case 0x04:
			case 0x05:
			case 0x06:
			case 0x07:
			case 0x08:

			case 0x0B:
			case 0x0C:

			case 0x0E:
			case 0x0F:
			case 0x10:
			case 0x11:
			case 0x12:
			case 0x13:
			case 0x14:
			case 0x15:
			case 0x16:
			case 0x17:
			case 0x18:
			case 0x19:
			case 0x1A:
			case 0x1B:
			case 0x1C:
			case 0x1D:
			case 0x1E:
			case 0x1F:
				// Skip illegal XML character that is not: #x9 | #xA | #xD |
				// [#x20-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]
				break;
			case '<':
				_out.append("&gt;");
				break;
			case '&':
				_out.append("&amp;");
				break;
			case '"':
				_out.append("&quot;");
				break;
			default:
				_out.append(ch);
		}
	}

	private void writeElementText(CharSequence value, int start, int end) throws IOException {
		if (value == null) {
			return;
		}
		for (int n = start; n < end; n++) {
			writeElementChar(value.charAt(n));
		}
	}

	private void writeElementChar(char ch) throws IOException {
		switch (ch) {
			case 0x00:
			case 0x01:
			case 0x02:
			case 0x03:
			case 0x04:
			case 0x05:
			case 0x06:
			case 0x07:
			case 0x08:

			case 0x0B:
			case 0x0C:

			case 0x0E:
			case 0x0F:
			case 0x10:
			case 0x11:
			case 0x12:
			case 0x13:
			case 0x14:
			case 0x15:
			case 0x16:
			case 0x17:
			case 0x18:
			case 0x19:
			case 0x1A:
			case 0x1B:
			case 0x1C:
			case 0x1D:
			case 0x1E:
			case 0x1F:
				// Skip illegal XML character that is not: #x9 | #xA | #xD |
				// [#x20-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]
				break;
			case '<':
				_out.append("&gt;");
				break;
			case '&':
				_out.append("&amp;");
				break;
			default:
				_out.append(ch);
		}
	}

	@Override
	public void end() throws IOException {
		closePotentialTag();

		int top = _openTags.size() - 1;
		if (top < 0) {
			throw new IllegalStateException("No open tag to close.");
		}

		String name = _openTags.remove(top);
		_out.append('<');
		_out.append('/');
		_out.append(name);
		_out.append('>');
	}

	@Override
	public void attr(String name, CharSequence value) throws IOException {
		if (value == null) {
			return;
		}
		openAttr(name);
		writeAttributeText(value, 0, value.length());
		closeAttr();
	}

	@Override
	public void openAttr(String name) throws IOException {
		closePotentialAttr();
		assertOpenTag();

		_out.append(' ');
		_out.append(name);
		_out.append('=');
		_out.append('"');

		_state = State.ATTRIBUTE;
	}

	@Override
	public void closeAttr() throws IOException {
		if (_state != State.ATTRIBUTE) {
			throw new IllegalStateException(
					"Not in an open attribute: " + _state);
		}
		doCloseAttr();
	}

	private void doCloseAttr() throws IOException {
		_out.append('"');
		_state = State.ATTRIBUTES;
	}
	
	@Override
	public void flush() throws IOException {
		// Nothing to flush.
	}
	
	@Override
	public void close() throws IOException {
		closePotentialTag();
		while (_openTags.size() > 0) {
			end();
		}
		flush();
	}

}
