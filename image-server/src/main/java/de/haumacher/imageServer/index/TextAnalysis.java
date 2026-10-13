/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.de.GermanLightStemFilter;
import org.apache.lucene.analysis.de.GermanNormalizationFilter;
import org.apache.lucene.analysis.en.EnglishPossessiveFilter;
import org.apache.lucene.analysis.en.PorterStemFilter;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

/**
 * How the texts of the index of a space are cut into words, see issue #264.
 *
 * <p>
 * A library is written in the languages of its owners, mostly German and English here, often both
 * in one album, and nothing says which a text is in. So every text is indexed three times:
 * </p>
 *
 * <ul>
 * <li>{@link #FOLDED}: the words as written, in lower case and with their accents folded
 * (<code>Württemberg</code> is <code>wurttemberg</code>, <code>Straße</code> is
 * <code>strasse</code>). A word typed is looked for as the start of a word here, so that a search
 * finds while it is typed, as the substring search before did.</li>
 * <li>{@link #ENGLISH}: the same, stemmed by Porter (<code>lakes</code>, <code>lake</code>).</li>
 * <li>{@link #GERMAN}: German umlauts normalised (<code>Mueller</code> is <code>Müller</code>) and
 * stemmed by the light German stemmer (<code>Hunde</code>, <code>Hund</code>), which takes off
 * inflections and leaves compounds alone.</li>
 * </ul>
 *
 * <p>
 * A query word matches a text where any of the three matches. Words are found by the Unicode word
 * rules of Lucene's standard tokenizer, so any script is cut into words; only German and English
 * are stemmed. No stop words are dropped: "the" typed is "the" looked for. The analysers are built
 * from their classes, never named through Lucene's service loader, whose files the server JAR
 * would merge wrongly.
 * </p>
 */
public final class TextAnalysis {

	/** The field of the folded words, see {@link TextAnalysis}. */
	public static final String FOLDED = "text";

	/** The field of the English stems, see {@link TextAnalysis}. */
	public static final String ENGLISH = "en";

	/** The field of the German stems, see {@link TextAnalysis}. */
	public static final String GERMAN = "de";

	private static final Analyzer FOLDING = new Analyzer() {
		@Override
		protected TokenStreamComponents createComponents(String fieldName) {
			Tokenizer source = new StandardTokenizer();
			TokenStream result = new LowerCaseFilter(source);
			result = new ASCIIFoldingFilter(result);
			return new TokenStreamComponents(source, result);
		}

		@Override
		protected TokenStream normalize(String fieldName, TokenStream in) {
			return new ASCIIFoldingFilter(new LowerCaseFilter(in));
		}
	};

	private static final Analyzer ENGLISH_STEMS = new Analyzer() {
		@Override
		protected TokenStreamComponents createComponents(String fieldName) {
			Tokenizer source = new StandardTokenizer();
			TokenStream result = new EnglishPossessiveFilter(source);
			result = new LowerCaseFilter(result);
			result = new ASCIIFoldingFilter(result);
			result = new PorterStemFilter(result);
			return new TokenStreamComponents(source, result);
		}
	};

	private static final Analyzer GERMAN_STEMS = new Analyzer() {
		@Override
		protected TokenStreamComponents createComponents(String fieldName) {
			Tokenizer source = new StandardTokenizer();
			TokenStream result = new LowerCaseFilter(source);
			result = new GermanNormalizationFilter(result);
			result = new GermanLightStemFilter(result);
			result = new ASCIIFoldingFilter(result);
			return new TokenStreamComponents(source, result);
		}
	};

	/** The analyser of every field: {@link #FOLDED} for any field not named here. */
	static final Analyzer ANALYZER = new PerFieldAnalyzerWrapper(FOLDING, Map.of(ENGLISH, ENGLISH_STEMS, GERMAN, GERMAN_STEMS));

	private TextAnalysis() {
		// Static only.
	}

	/**
	 * The words of the given text as the given field of the index holds them, in their order.
	 *
	 * @param field
	 *        {@link #FOLDED}, {@link #ENGLISH} or {@link #GERMAN}.
	 */
	public static List<String> words(String field, String text) {
		List<String> result = new ArrayList<>();
		if (text == null || text.isEmpty()) {
			return result;
		}
		try (TokenStream stream = ANALYZER.tokenStream(field, text)) {
			CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
			stream.reset();
			while (stream.incrementToken()) {
				result.add(term.toString());
			}
			stream.end();
		} catch (IOException ex) {
			// A string never fails to be read.
			throw new UncheckedIOException(ex);
		}
		return result;
	}

	/**
	 * The one word the given word of a query is in the given field, <code>null</code> where it is
	 * none there (a stemmer that leaves nothing).
	 */
	public static String word(String field, String word) {
		List<String> words = words(field, word);
		return words.isEmpty() ? null : words.get(0);
	}
}
