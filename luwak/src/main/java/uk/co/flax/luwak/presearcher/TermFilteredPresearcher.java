package uk.co.flax.luwak.presearcher;

/*
 * Copyright (c) 2013 Lemur Consulting Ltd.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.TermToBytesRefAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;
import org.apache.lucene.util.BytesRefIterator;
import uk.co.flax.luwak.Presearcher;
import uk.co.flax.luwak.QueryTermFilter;
import uk.co.flax.luwak.analysis.BytesRefFilteredTokenFilter;
import uk.co.flax.luwak.analysis.TermsEnumTokenStream;
import uk.co.flax.luwak.termextractor.QueryAnalyzer;
import uk.co.flax.luwak.termextractor.QueryTerm;
import uk.co.flax.luwak.termextractor.querytree.QueryTree;
import uk.co.flax.luwak.termextractor.querytree.QueryTreeViewer;
import uk.co.flax.luwak.termextractor.weights.TermWeightor;
import uk.co.flax.luwak.termextractor.weights.TokenLengthNorm;
import uk.co.flax.luwak.util.CollectionUtils;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Presearcher implementation that uses terms extracted from queries to index
 * them in the Monitor, and builds a BooleanQuery from InputDocuments to match
 * them.
 *
 * This Presearcher uses a QueryTermExtractor to extract terms from queries.
 */
public class TermFilteredPresearcher extends Presearcher {

    /**
     * The default TermWeightor, weighting by token length
     */
    public static final TermWeightor DEFAULT_WEIGHTOR = new TermWeightor(new TokenLengthNorm());

    static {
        BooleanQuery.setMaxClauseCount(Integer.MAX_VALUE);
    }

    protected final QueryAnalyzer extractor;
    protected final TermWeightor weightor;

    private final List<PresearcherComponent> components = new ArrayList<>();

    public static final String ANYTOKEN_FIELD = "__anytokenfield";

    public static final String ANYTOKEN = "__ANYTOKEN__";

    /**
     * Create a new TermFilteredPresearcher using a defined TermWeightor
     * @param weightor      the TermWeightor
     * @param components    optional PresearcherComponents
     */
    public TermFilteredPresearcher(TermWeightor weightor, PresearcherComponent... components) {
        this.extractor = QueryAnalyzer.fromComponents(components);
        this.components.addAll(Arrays.asList(components));
        this.weightor = weightor;
    }

    /**
     * Create a new TermFilteredPresearcher using the default term weighting
     * @param components    optional PresearcherComponents
     */
    public TermFilteredPresearcher(PresearcherComponent... components) {
        this(DEFAULT_WEIGHTOR, components);
    }

    @Override
    public final Query buildQuery(LeafReader reader, QueryTermFilter queryTermFilter) {
        try {
            DocumentQueryBuilder queryBuilder = getQueryBuilder();
            for (FieldInfo fi : reader.getFieldInfos()) {

                final String field = fi.name;

                TokenStream ts = new TermsEnumTokenStream(reader.terms(field).iterator());
                for (PresearcherComponent component : components) {
                    ts = component.filterDocumentTokens(field, ts);
                }

                ts = new BytesRefFilteredTokenFilter(ts, queryTermFilter.getTerms(field));

                TermToBytesRefAttribute termAtt = ts.addAttribute(TermToBytesRefAttribute.class);
                while (ts.incrementToken()) {
                    queryBuilder.addTerm(field, BytesRef.deepCopyOf(termAtt.getBytesRef()));
                }
                ts.close();

            }
            Query presearcherQuery = queryBuilder.build();

            BooleanQuery.Builder bq = new BooleanQuery.Builder();
            bq.add(presearcherQuery, BooleanClause.Occur.SHOULD);
            bq.add(new TermQuery(new Term(ANYTOKEN_FIELD, ANYTOKEN)), BooleanClause.Occur.SHOULD);
            presearcherQuery = bq.build();

            for (PresearcherComponent component : components) {
                presearcherQuery = component.adjustPresearcherQuery(reader, presearcherQuery);
            }

            return presearcherQuery;
        }
        catch (IOException e) {
            // We're a MemoryIndex, so this shouldn't happen...
            throw new RuntimeException(e);
        }
    }

    protected DocumentQueryBuilder getQueryBuilder() {
        return new DocumentQueryBuilder() {

            Map<String, BytesRefHash> terms = new HashMap<>();

            @Override
            public void addTerm(String field, BytesRef term) throws IOException {
                BytesRefHash hash = terms.computeIfAbsent(field, f -> new BytesRefHash());
                hash.add(term);
            }

            @Override
            public Query build() {
                BooleanQuery.Builder builder = new BooleanQuery.Builder();
                for (String field : terms.keySet()) {
                    builder.add(new TermInSetQuery(field, CollectionUtils.convertHash(terms.get(field))), BooleanClause.Occur.SHOULD);
                }
                return builder.build();
            }
        };
    }

    public static final FieldType QUERYFIELDTYPE;
    static {
        QUERYFIELDTYPE = new FieldType(TextField.TYPE_NOT_STORED);
        QUERYFIELDTYPE.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
        QUERYFIELDTYPE.freeze();
    }

    @Override
    public final Document indexQuery(Query query, Map<String, String> metadata) {

        QueryTree querytree = extractor.buildTree(query, weightor);
        Document doc = buildQueryDocument(querytree);

        for (PresearcherComponent component : components) {
            component.adjustQueryDocument(doc, metadata);
        }

        return doc;
    }

    /**
     * Debugging: write the parsed query tree to a PrintStream
     *
     * @param query the query to analyze
     * @param out   a {@link PrintStream}
     */
    public void showQueryTree(Query query, PrintStream out) {
        QueryTreeViewer.view(extractor.buildTree(query, weightor), out);
    }

    protected Document buildQueryDocument(QueryTree querytree) {
        Map<String, BytesRefHash> fieldTerms = collectTerms(querytree);
        Document doc = new Document();
        for (Map.Entry<String, BytesRefHash> entry : fieldTerms.entrySet()) {
            doc.add(new Field(entry.getKey(),
                    new TermsEnumTokenStream(new BytesRefHashIterator(entry.getValue())), QUERYFIELDTYPE));
        }
        return doc;
    }

    protected class BytesRefHashIterator implements BytesRefIterator {

        final BytesRef scratch = new BytesRef();
        final BytesRefHash terms;
        final int[] sortedTerms;
        int upto = -1;


        public BytesRefHashIterator(BytesRefHash terms) {
            this.terms = terms;
            this.sortedTerms = terms.sort();
        }

        @Override
        public BytesRef next() throws IOException {
            if (upto >= sortedTerms.length)
                return null;
            upto++;
            if (sortedTerms[upto] == -1)
                return null;
            this.terms.get(sortedTerms[upto], scratch);
            return scratch;
        }
    }

    protected Map<String, BytesRefHash> collectTerms(QueryTree tree) {

        Map<String, BytesRefHash> fieldTerms = new HashMap<>();

        for (QueryTerm queryTerm : extractor.collectTerms(tree)) {
            if (queryTerm.type.equals(QueryTerm.Type.ANY)) {
                fieldTerms.computeIfAbsent(ANYTOKEN_FIELD, f -> {
                    BytesRefHash hash = new BytesRefHash();
                    hash.add(new BytesRef(ANYTOKEN));
                    return hash;
                });
            } else {
                BytesRefHash termslist = fieldTerms.computeIfAbsent(queryTerm.term.field(), f -> new BytesRefHash());
                termslist.add(queryTerm.term.bytes());
                if (!queryTerm.type.equals(QueryTerm.Type.EXACT)) {
                    for (PresearcherComponent component : components) {
                        BytesRef extratoken = component.extraToken(queryTerm);
                        if (extratoken != null)
                            termslist.add(extratoken);
                    }
                }
            }
        }

        return fieldTerms;
    }


}
