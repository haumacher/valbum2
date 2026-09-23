/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Probe of issue #164, composing the archive download with the trash of #152 — a photograph rated
 * −2 is the editor's alone, whoever else may download — and with a folder name (#130) that needs
 * the UTF-8 spelling of the attachment header.
 */
@SuppressWarnings("javadoc")
public class TestZipDownloadProbe extends ShareTestCase {

	private static final String GARDEN = "2024/2024-06-01 Schloß & Garten";

	private void garden() throws Exception {
		SharingFixture.album(_base, GARDEN, "Schloß & Garten",
			"[\"ImagePart\",{\"name\":\"kept.jpg\",\"width\":4,\"height\":3}],"
				+ "[\"ImagePart\",{\"name\":\"trashed.jpg\",\"width\":4,\"height\":3,\"rating\":-2}]",
			"kept.jpg", "trashed.jpg");
	}

	public void testATrashedPhotographIsDownloadedByAnEditorAlone() throws Exception {
		garden();

		FakeResponse viewer = zip("/" + GARDEN + "/", SharingFixture.DAVE, "kept.jpg", "trashed.jpg");
		assertEquals("A viewer may download, but the trash is not part of the album for them (#152).",
			HttpServletResponse.SC_FORBIDDEN, viewer.status());
		assertEquals(ImageServlet.RATING_REFUSED, errorMessage(viewer));

		FakeResponse editor = zip("/" + GARDEN + "/", SharingFixture.ALICE, "kept.jpg", "trashed.jpg");
		assertEquals(editor.body(), HttpServletResponse.SC_OK, editor.status());
		assertEquals(List.of("kept.jpg", "trashed.jpg"), names(editor));
	}

	public void testTheArchiveIsNamedByTheFolderInBothSpellings() throws Exception {
		garden();

		FakeResponse response = zip("/" + GARDEN + "/", SharingFixture.DAVE, "kept.jpg");
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		String disposition = response.header("Content-Disposition");
		assertTrue(disposition, disposition.startsWith("attachment; filename=\""));
		assertTrue("The UTF-8 spelling carries the umlaut and the ampersand encoded: " + disposition,
			disposition.contains("filename*=UTF-8''2024-06-01%20Schlo%C3%9F%20%26%20Garten.zip"));
		assertFalse("The plain spelling must not break the header on a quote or a non-ASCII byte: " + disposition,
			disposition.contains("\"\""));
	}

	private FakeResponse zip(String pathInfo, String token, String... names) throws Exception {
		StringBuilder body = new StringBuilder("{\"target\":\"\",\"names\":[");
		for (int n = 0; n < names.length; n++) {
			body.append(n == 0 ? "" : ",").append("{\"name\":\"").append(names[n]).append("\"}");
		}
		body.append("]}");
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.ZIP_ACTION);
		return post(pathInfo, body.toString(), token, parameters);
	}

	private static List<String> names(FakeResponse response) throws Exception {
		List<String> result = new ArrayList<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(response.bodyBytes()))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				result.add(entry.getName());
			}
		}
		return result;
	}
}
