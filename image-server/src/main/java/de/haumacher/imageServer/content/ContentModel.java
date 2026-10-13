/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.content;

import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The model content search embeds with, pinned, see issue #257.
 *
 * <p>
 * EmbeddingGemma 2 (<code>google/embeddinggemma-2</code>, Apache-2.0) in the ONNX export of
 * <code>onnx-community/embeddinggemma-2-ONNX</code>, at one revision, every file with its size and
 * SHA-256. The pin is <code>model.json</code>, bundled beside the service code
 * (<code>image-server/src/embedder</code>): the server downloads by it, the service loads by it, so
 * the two never disagree.
 * </p>
 *
 * <p>
 * Only the text model and the vision encoder are listed, never the audio encoder, and no fp16
 * variant: the model card warns that fp16 gives NaN.
 * </p>
 */
public final class ContentModel {

	/** The folder of the bundled service code among the resources, see {@link ServiceCode}. */
	public static final String RESOURCE_FOLDER = "embedder/";

	/** The bundled pin. */
	public static final String MANIFEST = "model.json";

	/**
	 * The version of what the server makes of the model: the task prefixes of {@link Embedder.TextKind}
	 * and the image preprocessing of the service (resize rule, 280 soft tokens). Part of every
	 * {@link #stamp(String, int) stamp}; raise it with any change of either, and every cached vector
	 * is computed again.
	 */
	public static final int PREPROCESSING = 1;

	/** The precision of the original weights, reproduced by the service to a cosine above 0.999. */
	public static final String FP32 = "fp32";

	/** The 8-bit quantised export: smaller and, depending on the machine, faster; less exact. */
	public static final String INT8 = "int8";

	/** One file of the model. */
	public record ModelFile(String name, long size, String sha256) {
		// A triple.
	}

	private static volatile ContentModel _bundled;

	private final String _model;

	private final String _repository;

	private final String _revision;

	private final int _dimension;

	private final Map<String, List<ModelFile>> _files;

	ContentModel(String model, String repository, String revision, int dimension, Map<String, List<ModelFile>> files) {
		_model = model;
		_repository = repository;
		_revision = revision;
		_dimension = dimension;
		_files = files;
	}

	/** The pin bundled with this server. */
	public static ContentModel bundled() {
		ContentModel result = _bundled;
		if (result == null) {
			try (InputStream in = ContentModel.class.getResourceAsStream(RESOURCE_FOLDER + MANIFEST)) {
				if (in == null) {
					throw new IllegalStateException("The server was built without " + RESOURCE_FOLDER + MANIFEST + ".");
				}
				result = read(in);
			} catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
			_bundled = result;
		}
		return result;
	}

	/** Reads a <code>model.json</code>. */
	public static ContentModel read(InputStream in) throws IOException {
		String model = null;
		String repository = null;
		String revision = null;
		int dimension = 0;
		Map<String, List<ModelFile>> files = new LinkedHashMap<>();
		try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
				JsonReader json = new JsonReader(new ReaderAdapter(reader))) {
			json.beginObject();
			while (json.hasNext()) {
				switch (json.nextName()) {
					case "model" -> model = json.nextString();
					case "repository" -> repository = json.nextString();
					case "revision" -> revision = json.nextString();
					case "dimension" -> dimension = json.nextInt();
					case "files" -> {
						json.beginObject();
						while (json.hasNext()) {
							String set = json.nextName();
							List<ModelFile> list = new ArrayList<>();
							json.beginArray();
							while (json.hasNext()) {
								list.add(readFile(json));
							}
							json.endArray();
							files.put(set, Collections.unmodifiableList(list));
						}
						json.endObject();
					}
					default -> json.skipValue();
				}
			}
			json.endObject();
		} catch (RuntimeException ex) {
			throw new IOException("Cannot read the model pin: " + ex.getMessage(), ex);
		}
		if (model == null || repository == null || revision == null || dimension <= 0 || !files.containsKey("common")) {
			throw new IOException("The model pin is incomplete.");
		}
		return new ContentModel(model, repository, revision, dimension, files);
	}

	private static ModelFile readFile(JsonReader json) throws IOException {
		String name = null;
		long size = -1;
		String sha256 = null;
		json.beginObject();
		while (json.hasNext()) {
			switch (json.nextName()) {
				case "name" -> name = json.nextString();
				case "size" -> size = json.nextLong();
				case "sha256" -> sha256 = json.nextString().toLowerCase(Locale.ROOT);
				default -> json.skipValue();
			}
		}
		json.endObject();
		if (name == null || size < 0 || sha256 == null || !sha256.matches("[0-9a-f]{64}") || name.startsWith("/")
			|| name.contains("..") || name.contains("\\")) {
			throw new IOException("A file of the model pin is incomplete or names no file below the model: " + name);
		}
		return new ModelFile(name, size, sha256);
	}

	/** <code>google/embeddinggemma-2</code>. */
	public String getModel() {
		return _model;
	}

	/** Where the files come from: <code>onnx-community/embeddinggemma-2-ONNX</code>. */
	public String getRepository() {
		return _repository;
	}

	/** The pinned revision of {@link #getRepository()}, a commit id. */
	public String getRevision() {
		return _revision;
	}

	/** The full width of a vector, 768. */
	public int getDimension() {
		return _dimension;
	}

	/** Whether the given precision is one of the pin. */
	public boolean hasPrecision(String precision) {
		return !"common".equals(precision) && _files.containsKey(precision);
	}

	/** The precisions of the pin. */
	public List<String> precisions() {
		return _files.keySet().stream().filter(this::hasPrecision).toList();
	}

	/** Every file the given precision needs: the common ones and its own. */
	public List<ModelFile> files(String precision) {
		if (!hasPrecision(precision)) {
			throw new IllegalArgumentException("No precision '" + precision + "' in the model pin.");
		}
		List<ModelFile> result = new ArrayList<>(_files.get("common"));
		result.addAll(_files.get(precision));
		return result;
	}

	/** How many bytes the given precision downloads. */
	public long size(String precision) {
		return files(precision).stream().mapToLong(ModelFile::size).sum();
	}

	/**
	 * What a vector of this model is stamped with, see issue #257: the model and its revision, the
	 * dimension, the precision and {@link #PREPROCESSING}. Vectors of different stamps are never
	 * compared; a cached vector whose stamp is not the current one is computed again.
	 */
	public String stamp(String precision, int dimension) {
		return stamp(_revision, precision, dimension);
	}

	/** The stamp of a vector of the given revision, see {@link #stamp(String, int)}. */
	public static String stamp(String revision, String precision, int dimension) {
		return "embeddinggemma-2@" + revision.substring(0, Math.min(7, revision.length())) + "/" + dimension + "/"
			+ precision + "/" + PREPROCESSING;
	}
}
