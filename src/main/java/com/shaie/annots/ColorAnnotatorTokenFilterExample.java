package com.shaie.annots;

import java.io.IOException;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required byOCP applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.util.FilteringTokenFilter;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.RAMDirectory;

import com.google.common.collect.ImmutableMap;
import com.shaie.utils.IndexUtils;

/** Demonstrates indexing of a document with color annotations. */
public class ColorAnnotatorTokenFilterExample {

    private static final String COLOR_FIELD = "color";
    private static final String TEXT_FIELD = "text";
    public static final String TEXT = "quick brown fox and a red dog";

    @SuppressWarnings("resource")
    public static void main(String[] args) throws Exception {
        final Directory dir = new RAMDirectory();
        final Analyzer analyzer = createAnalyzer();
        final IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(analyzer));

        final Document doc = new Document();
        doc.add(new TextField(TEXT_FIELD, TEXT, Store.YES));
        doc.add(new TextField(COLOR_FIELD, TEXT, Store.NO));
        writer.addDocument(doc);
        writer.close();

        final DirectoryReader reader = DirectoryReader.open(dir);
        final LeafReader leaf = reader.leaves().get(0).reader(); // we only have one segment
        IndexUtils.printFieldTerms(leaf, TEXT_FIELD);
        IndexUtils.printFieldTerms(leaf, COLOR_FIELD);
        IndexUtils.printFieldTermsWithInfo(leaf, COLOR_FIELD);

        final IndexSearcher searcher = new IndexSearcher(reader);
        final Query q = new TermQuery(new Term(COLOR_FIELD, "red"));
        final TopDocs results = searcher.search(q, 10);
        System.out.println(searcher.doc(results.scoreDocs[0].doc).get(TEXT_FIELD));

        reader.close();
    }

    @SuppressWarnings("resource")
    private static Analyzer createAnalyzer() {
        final ColorAnnotatorAnalyzer colorAnnotatorAnalyzer = new ColorAnnotatorAnalyzer();
        final WhitespaceAnalyzer defaultAnalyzer = new WhitespaceAnalyzer();
        return new PerFieldAnalyzerWrapper(defaultAnalyzer,
                ImmutableMap.<String, Analyzer> of(COLOR_FIELD, colorAnnotatorAnalyzer));
    }

    /**
     * An {@link Analyzer} which chains {@link WhitespaceTokenizer} and {@link AnnotatingTokenFilter} with
     * {@link ColorAnnotator}.
     */
    @SuppressWarnings("resource")
    public static final class ColorAnnotatorAnalyzer extends Analyzer {
        @Override
        protected TokenStreamComponents createComponents(String fieldName) {
            final Tokenizer tokenizer = new WhitespaceTokenizer();
            final TokenStream stream = new AnnotatorTokenFilter(tokenizer, ColorAnnotator.withDefaultColors());
            return new TokenStreamComponents(tokenizer, stream);
        }
    }

    /** A {@link FilteringTokenFilter} which uses an {@link Annotator} to {@link #accept()} tokens. */
    public static final class AnnotatorTokenFilter extends FilteringTokenFilter {

        private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
        private final Annotator annotator;

        public AnnotatorTokenFilter(TokenStream input, Annotator annotator) {
            super(input);
            this.annotator = annotator;
        }

        @Override
        protected boolean accept() throws IOException {
            return annotator.accept(termAtt.buffer(), 0, termAtt.length());
        }

    }

}