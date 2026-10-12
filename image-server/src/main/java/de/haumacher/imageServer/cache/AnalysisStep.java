/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.cache;

import de.haumacher.imageServer.pipeline.FolderPipeline;
import de.haumacher.imageServer.pipeline.FolderPipeline.Outcome;
import de.haumacher.imageServer.pipeline.FolderPipeline.Stage;
import java.io.File;
import java.io.IOException;

/**
 * Finding out what a folder holds, as the first step of the background work of a space, see issue
 * #253 and {@link AnalysisRecord}.
 *
 * <p>
 * A request never lists a folder's files and never opens a photograph; this step is where that is
 * done, for every folder of the library: at start-up by the walk (a cheap {@link #discover(File)
 * discovery} of every folder first, root first, then the analysis, newest album first), and
 * whenever the {@link de.haumacher.imageServer.pipeline.LibraryWatch watches} or the sweep report
 * a change. A file whose size and modification time are recorded is not opened again, a broken one
 * included. One file that cannot be read never fails the step: it is recorded as broken and
 * reported to the status (issue #251), again on every run while it stays broken, so that it stays
 * an outstanding failure there.
 * </p>
 *
 * <p>
 * A stage of its own ({@link Stage#RECORD}), before hashing: hashing reads every byte of every
 * file, the analysis only the headers, so a library copied in is shown long before it is hashed;
 * and every later step (previews, covers, faces, the search) reads the albums as a request does,
 * from the record this step wrote.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class AnalysisStep implements FolderPipeline.Step {

	/** The name of the step in the pipeline's record; never changes. */
	public static final String NAME = "analysis";

	private final AnalysisRecord.Analyst _analyst;

	private final FolderPipeline _pipeline;

	/** Creates the step analysing with the given cache's analysis, reporting to the given pipeline. */
	public AnalysisStep(ResourceCache cache, FolderPipeline pipeline) {
		_analyst = cache.analyst();
		_pipeline = pipeline;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public Stage stage() {
		return Stage.RECORD;
	}

	@Override
	public boolean trustsRecord() {
		// Its own check is a listing and a stat per file, as cheap as the fingerprint, and the
		// record beside the folder is the truth.
		return false;
	}

	@Override
	public void discover(File folder) throws IOException {
		AnalysisRecord.update(folder, _analyst, false);
	}

	@Override
	public Outcome run(File folder) throws IOException {
		AnalysisRecord.Result result = AnalysisRecord.update(folder, _analyst, true);
		for (String failure : result.getFailures()) {
			// Outstanding as long as the file stays broken, see FolderPipeline#reportFailure.
			_pipeline.reportFailure(folder, failure);
		}
		if (Thread.currentThread().isInterrupted()) {
			return Outcome.WAITING;
		}
		return result.isUnwritable() ? Outcome.UNWRITABLE : Outcome.DONE;
	}
}
