/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.cache;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
import static org.bytedeco.ffmpeg.global.avcodec.AV_FIELD_BB;
import static org.bytedeco.ffmpeg.global.avcodec.AV_FIELD_BT;
import static org.bytedeco.ffmpeg.global.avcodec.AV_FIELD_TB;
import static org.bytedeco.ffmpeg.global.avcodec.AV_FIELD_TT;
import static org.bytedeco.ffmpeg.global.avcodec.AV_PKT_DATA_DISPLAYMATRIX;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_free;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avformat.av_find_best_stream;
import static org.bytedeco.ffmpeg.global.avformat.av_read_frame;
import static org.bytedeco.ffmpeg.global.avformat.av_stream_get_side_data;
import static org.bytedeco.ffmpeg.global.avformat.avformat_close_input;
import static org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info;
import static org.bytedeco.ffmpeg.global.avformat.avformat_open_input;
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_get;
import static org.bytedeco.ffmpeg.global.avutil.av_display_rotation_get;

import de.haumacher.util.servlet.Util;
import java.io.File;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVDictionaryEntry;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;

/**
 * What the bundled FFmpeg says about a video whose container metadata-extractor cannot read, see
 * issue #192.
 *
 * <p>
 * An AVCHD camcorder's <code>.mts</code>/<code>.m2ts</code> (MPEG transport stream), an older
 * digital camera's <code>.avi</code>, a Matroska <code>.mkv</code> and a <code>.webm</code> of a
 * download or a screen recorder: FFmpeg reads them all, so their size, rotation, scan type and
 * recording time are asked of its <code>avformat</code> library in process — the library the
 * poster frame of {@link de.haumacher.imageServer.PreviewCache} already opens them with. Opening
 * the container and reading its stream headers (<code>avformat_find_stream_info</code>) is all it
 * costs; no frame is decoded here, and for an H.264 stream at most {@link #MDPM_PACKETS} packets
 * are looked through for the camcorder's recording time.
 * </p>
 */
public final class VideoProbe {

	/**
	 * The extensions of the videos read through FFmpeg rather than metadata-extractor, lower case.
	 */
	public static final Set<String> EXTENSIONS =
		Collections.unmodifiableSet(new HashSet<>(Arrays.asList("mts", "m2ts", "avi", "mkv", "webm")));

	/** How many packets of an H.264 stream are searched for the AVCHD recording time. */
	static final int MDPM_PACKETS = 30;

	/**
	 * The UUID of the H.264 SEI <code>user_data_unregistered</code> message in which Sony and
	 * Panasonic AVCHD camcorders write their "modified digital video pack metadata"
	 * (<code>MDPM</code>), the recording time among it.
	 */
	private static final byte[] MDPM_UUID = { 0x17, (byte) 0xee, (byte) 0x8c, 0x60, (byte) 0xf8, 0x4d, 0x11,
		(byte) 0xd9, (byte) 0x8c, (byte) 0xd6, 0x08, 0x00, 0x20, 0x0c, (byte) 0x9a, 0x66 };

	private static final byte[] MDPM = { 'M', 'D', 'P', 'M' };

	private final int _rawWidth;

	private final int _rawHeight;

	private final int _width;

	private final int _height;

	private final int _rotation;

	private final boolean _interlaced;

	private final String _creationTime;

	private final String _date;

	private final Date _mdpmDate;

	private VideoProbe(int rawWidth, int rawHeight, int width, int height, int rotation, boolean interlaced,
			String creationTime, String date, Date mdpmDate) {
		_rawWidth = rawWidth;
		_rawHeight = rawHeight;
		_width = width;
		_height = height;
		_rotation = rotation;
		_interlaced = interlaced;
		_creationTime = creationTime;
		_date = date;
		_mdpmDate = mdpmDate;
	}

	/** Whether a file of the given name is read through FFmpeg, by its extension in any case. */
	public static boolean handles(String name) {
		String suffix = Util.suffix(name);
		return suffix != null && EXTENSIONS.contains(suffix);
	}

	/** The width of the stored raster. */
	public int getRawWidth() {
		return _rawWidth;
	}

	/** The height of the stored raster. */
	public int getRawHeight() {
		return _rawHeight;
	}

	/**
	 * The width the video is shown at: the raster stretched by its sample aspect ratio (an HDV or
	 * AVCHD 1440 × 1080 is shown 1920 wide) and turned by its rotation.
	 */
	public int getWidth() {
		return _width;
	}

	/** The height the video is shown at, see {@link #getWidth()}. */
	public int getHeight() {
		return _height;
	}

	/**
	 * The quarter turn the container's display matrix says, in degrees: <code>0</code>,
	 * <code>90</code>, <code>180</code> or <code>270</code>, in the convention of
	 * {@link ImageData#rotation(com.drew.metadata.Metadata)}, which the poster frame is turned by.
	 */
	public int getRotation() {
		return _rotation;
	}

	/** Whether the video stream says it is interlaced (a field order other than progressive). */
	public boolean isInterlaced() {
		return _interlaced;
	}

	/** The container's <code>creation_time</code> as FFmpeg reports it, <code>null</code> if none. */
	public String getCreationTime() {
		return _creationTime;
	}

	/**
	 * The recording time of the video, see issue #192, <code>null</code> where the container says
	 * none.
	 *
	 * <ol>
	 * <li>The AVCHD recording time of the H.264 stream (<code>MDPM</code>), a wall clock with the
	 * camcorder's own offset;</li>
	 * <li>the container's <code>creation_time</code> — Matroska's and WebM's <code>DateUTC</code>,
	 * an AVI's <code>IDIT</code> chunk — with its offset where it carries one, else a wall clock in
	 * the given zone;</li>
	 * <li>an AVI's <code>ICRD</code> (FFmpeg's <code>date</code>), a day or a day and a time, read
	 * in the given zone.</li>
	 * </ol>
	 */
	public Date recordingTime(ZoneId zone) {
		if (_mdpmDate != null) {
			return _mdpmDate;
		}
		Date created = timestamp(_creationTime, zone);
		if (created != null) {
			return created;
		}
		return timestamp(_date, zone);
	}

	private static final Pattern DAY = Pattern.compile("(\\d{4})[-:](\\d{2})[-:](\\d{2})");

	/** An ISO 8601 time with or without its offset, or a bare day, in the given zone. */
	static Date timestamp(String value, ZoneId zone) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		Date moment = ImageData.appleCreationDate(value, zone);
		if (moment != null) {
			return moment;
		}
		Matcher day = DAY.matcher(value.trim());
		if (day.matches()) {
			try {
				LocalDate date = LocalDate.of(Integer.parseInt(day.group(1)), Integer.parseInt(day.group(2)),
					Integer.parseInt(day.group(3)));
				return Date.from(date.atStartOfDay(zone == null ? ZoneId.systemDefault() : zone).toInstant());
			} catch (DateTimeException ex) {
				return null;
			}
		}
		return null;
	}

	/**
	 * Opens the given video and reads what its container and its video stream say.
	 *
	 * @throws IOException
	 *         If FFmpeg cannot open the file or finds no video stream in it.
	 */
	public static VideoProbe probe(File file) throws IOException {
		AVFormatContext context = new AVFormatContext(null);
		int status = avformat_open_input(context, file.getAbsolutePath(), null, null);
		if (status < 0) {
			throw new IOException("FFmpeg cannot open '" + file.getName() + "' (" + status + ").");
		}
		try {
			if (avformat_find_stream_info(context, (PointerPointer<?>) null) < 0) {
				throw new IOException("FFmpeg cannot read the streams of '" + file.getName() + "'.");
			}
			int index = av_find_best_stream(context, AVMEDIA_TYPE_VIDEO, -1, -1, (PointerPointer<?>) null, 0);
			if (index < 0) {
				throw new IOException("No video stream in '" + file.getName() + "'.");
			}
			AVStream stream = context.streams(index);
			AVCodecParameters codec = stream.codecpar();
			int rawWidth = codec.width();
			int rawHeight = codec.height();
			if (rawWidth <= 0 || rawHeight <= 0) {
				throw new IOException("No picture size in '" + file.getName() + "'.");
			}

			int shownWidth = rawWidth;
			AVRational sar = codec.sample_aspect_ratio();
			if (sar != null && sar.num() > 0 && sar.den() > 0 && sar.num() != sar.den()) {
				shownWidth = (int) Math.round(((double) rawWidth) * sar.num() / sar.den());
			}

			int rotation = rotation(stream);
			boolean quarter = rotation == 90 || rotation == 270;

			int order = codec.field_order();
			boolean interlaced =
				order == AV_FIELD_TT || order == AV_FIELD_BB || order == AV_FIELD_TB || order == AV_FIELD_BT;

			String creationTime = tag(context.metadata(), stream.metadata(), "creation_time");
			String date = tag(context.metadata(), stream.metadata(), "date");

			Date mdpm = codec.codec_id() == AV_CODEC_ID_H264 ? mdpmDate(context, index) : null;

			return new VideoProbe(rawWidth, rawHeight, quarter ? rawHeight : shownWidth,
				quarter ? shownWidth : rawHeight, rotation, interlaced, creationTime, date, mdpm);
		} finally {
			avformat_close_input(context);
		}
	}

	/** The rotation the display matrix of the given stream says, see {@link #getRotation()}. */
	private static int rotation(AVStream stream) {
		BytePointer matrix = av_stream_get_side_data(stream, AV_PKT_DATA_DISPLAYMATRIX, null);
		if (matrix == null || matrix.isNull()) {
			return 0;
		}
		double degrees = av_display_rotation_get(new IntPointer(matrix));
		if (Double.isNaN(degrees)) {
			return 0;
		}
		// Taken as av_display_rotation_get answers it: the very number metadata-extractor reads
		// out of the track matrix of the same movie (TestFfmpegContainers pins both on one file).
		return normalized((int) Math.round(degrees));
	}

	/** The given angle as a quarter turn in <code>0..270</code>, <code>0</code> for no quarter. */
	static int normalized(int degrees) {
		int turn = ((degrees % 360) + 360) % 360;
		return turn % 90 == 0 ? turn : 0;
	}

	/** The value of the given tag, of the container first and of the stream second. */
	private static String tag(AVDictionary container, AVDictionary stream, String key) {
		for (AVDictionary dictionary : new AVDictionary[] { container, stream }) {
			if (dictionary == null || dictionary.isNull()) {
				continue;
			}
			AVDictionaryEntry entry = av_dict_get(dictionary, key, null, 0);
			if (entry != null && !entry.isNull() && entry.value() != null) {
				String value = entry.value().getString().trim();
				if (!value.isEmpty()) {
					return value;
				}
			}
		}
		return null;
	}

	/** The AVCHD recording time in the first packets of the given H.264 stream. */
	private static Date mdpmDate(AVFormatContext context, int index) {
		AVPacket packet = av_packet_alloc();
		try {
			for (int n = 0; n < MDPM_PACKETS && av_read_frame(context, packet) >= 0; n++) {
				try {
					if (packet.stream_index() != index || packet.size() <= 0) {
						continue;
					}
					byte[] data = new byte[packet.size()];
					packet.data().get(data);
					Date date = mdpm(data);
					if (date != null) {
						return date;
					}
				} finally {
					av_packet_unref(packet);
				}
			}
			return null;
		} finally {
			av_packet_free(packet);
		}
	}

	/**
	 * The recording time of an <code>MDPM</code> message somewhere in the given H.264 data,
	 * <code>null</code> where there is none or it says no valid time.
	 *
	 * <p>
	 * After the UUID and <code>MDPM</code> follow a count and as many entries of a tag byte and
	 * four data bytes. Tag <code>0x18</code> carries the camcorder's time zone and, in BCD, the
	 * year and the month; tag <code>0x19</code> the day, hour, minute and second (as ExifTool reads
	 * them). The zone byte holds the sign (<code>0x20</code>), the hours (<code>0x1e</code>) and a
	 * half hour (<code>0x01</code>).
	 * </p>
	 */
	public static Date mdpm(byte[] data) {
		int at = indexOf(data, MDPM_UUID, 0);
		while (at >= 0) {
			byte[] payload = unescape(data, at + MDPM_UUID.length, 4 + 1 + 255 * 5);
			if (payload.length >= 5 && startsWith(payload, MDPM)) {
				Date date = mdpmEntries(payload);
				if (date != null) {
					return date;
				}
			}
			at = indexOf(data, MDPM_UUID, at + 1);
		}
		return null;
	}

	private static Date mdpmEntries(byte[] payload) {
		int count = payload[4] & 0xFF;
		int[] first = null;
		int[] second = null;
		for (int n = 0, pos = 5; n < count && pos + 5 <= payload.length; n++, pos += 5) {
			int tag = payload[pos] & 0xFF;
			int[] value = { payload[pos + 1] & 0xFF, payload[pos + 2] & 0xFF, payload[pos + 3] & 0xFF,
				payload[pos + 4] & 0xFF };
			if (tag == 0x18) {
				first = value;
			} else if (tag == 0x19) {
				second = value;
			}
		}
		if (first == null || second == null) {
			return null;
		}
		try {
			int zone = first[0];
			int year = bcd(first[1]) * 100 + bcd(first[2]);
			LocalDateTime wall = LocalDateTime.of(year, bcd(first[3]), bcd(second[0]), bcd(second[1]),
				bcd(second[2]), bcd(second[3]));
			int minutes = ((zone >> 1) & 0x0F) * 60 + ((zone & 0x01) != 0 ? 30 : 0);
			if ((zone & 0x20) != 0) {
				minutes = -minutes;
			}
			return Date.from(wall.atOffset(ZoneOffset.ofTotalSeconds(minutes * 60)).toInstant());
		} catch (DateTimeException | IllegalArgumentException ex) {
			return null;
		}
	}

	/** A BCD byte as a number; a nibble above 9 is no BCD. */
	private static int bcd(int value) {
		int high = value >> 4;
		int low = value & 0x0F;
		if (high > 9 || low > 9) {
			throw new IllegalArgumentException("No BCD: " + value);
		}
		return high * 10 + low;
	}

	/** At most the given number of bytes from the given position, emulation prevention removed. */
	private static byte[] unescape(byte[] data, int from, int max) {
		byte[] result = new byte[Math.max(0, Math.min(max, data.length - from))];
		int length = 0;
		int zeros = 0;
		for (int pos = from; pos < data.length && length < result.length; pos++) {
			int value = data[pos] & 0xFF;
			if (zeros >= 2 && value == 0x03) {
				zeros = 0;
				continue;
			}
			zeros = value == 0 ? zeros + 1 : 0;
			result[length++] = (byte) value;
		}
		return Arrays.copyOf(result, length);
	}

	private static boolean startsWith(byte[] data, byte[] prefix) {
		if (data.length < prefix.length) {
			return false;
		}
		for (int n = 0; n < prefix.length; n++) {
			if (data[n] != prefix[n]) {
				return false;
			}
		}
		return true;
	}

	private static int indexOf(byte[] data, byte[] pattern, int from) {
		outer: for (int pos = from; pos <= data.length - pattern.length; pos++) {
			for (int n = 0; n < pattern.length; n++) {
				if (data[pos + n] != pattern[n]) {
					continue outer;
				}
			}
			return pos;
		}
		return -1;
	}
}
