package uk.co.flax.luwak.termextractor;

import org.apache.lucene.search.Query;
import uk.co.flax.luwak.presearcher.PresearcherComponent;
import uk.co.flax.luwak.termextractor.querytree.QueryTree;
import uk.co.flax.luwak.termextractor.treebuilder.TreeBuilders;
import uk.co.flax.luwak.termextractor.weights.TermWeightor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/*
 * Copyright (c) 2014 Lemur Consulting Ltd.
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

/**
 * Class to analyze and extract terms from a lucene query, to be used by
 * a {@link uk.co.flax.luwak.Presearcher} in indexing.
 */
public class QueryAnalyzer {

    private final List<QueryTreeBuilder<?>> queryTreeBuilders;

    /**
     * Create a QueryAnalyzer using provided QueryTreeBuilders, in addition to the default set
     *
     * @param queryTreeBuilders QueryTreeBuilders used to analyze queries
     */
    public QueryAnalyzer(List<QueryTreeBuilder<?>> queryTreeBuilders) {
        this.queryTreeBuilders = new ArrayList<>();
        this.queryTreeBuilders.addAll(queryTreeBuilders);
        this.queryTreeBuilders.addAll(TreeBuilders.DEFAULT_BUILDERS);
    }

    /**
     * Create a QueryAnalyzer using provided QueryTreeBuilders, in addition to the default set
     *
     * @param queryTreeBuilders QueryTreeBuilders used to analyze queries
     */
    public QueryAnalyzer(QueryTreeBuilder<?>... queryTreeBuilders) {
        this(Arrays.asList(queryTreeBuilders));
    }

    /**
     * Build a new QueryAnalyzer using a TreeWeightor and a list of PresearcherComponents
     *
     * A list of QueryTreeBuilders is extracted from each component, and combined to use
     * on the QueryAnalyzer
     *
     * @param components a list of PresearcherComponents
     * @return a QueryAnalyzer
     */
    public static QueryAnalyzer fromComponents(PresearcherComponent... components) {
        List<QueryTreeBuilder<?>> builders = new ArrayList<>();
        for (PresearcherComponent component : components) {
            builders.addAll(component.getQueryTreeBuilders());
        }
        return new QueryAnalyzer(builders);
    }

    /**
     * Create a {@link QueryTree} from a passed in Query or Filter
     * @param luceneQuery the query to analyze
     * @return a QueryTree describing the analyzed query
     */
    @SuppressWarnings("unchecked")
    public QueryTree buildTree(Query luceneQuery, TermWeightor weightor) {
        QueryTreeBuilder builder = getTreeBuilderForQuery(luceneQuery.getClass());
        if (builder == null)
            throw new UnsupportedOperationException("Can't build query tree from query of type " + luceneQuery.getClass());
        return builder.buildTree(this, weightor, luceneQuery);
    }

    public QueryTreeBuilder getTreeBuilderForQuery(Class<? extends Query> queryClass) {
        for (QueryTreeBuilder<?> builder : queryTreeBuilders) {
            if (builder.cls.isAssignableFrom(queryClass)) {
                return builder;
            }
        }
        return null;
    }

    /**
     * Collect terms from a QueryTree
     * @param queryTree the analyzed QueryTree to collect terms from
     * @return a list of QueryTerms
     */
    public Set<QueryTerm> collectTerms(QueryTree queryTree) {
        Set<QueryTerm> terms = new HashSet<>();
        queryTree.collectTerms(terms);
        return terms;
    }

    /**
     * Collect terms from a lucene Query
     * @param luceneQuery the query to analyze and collect terms from
     * @return a list of QueryTerms
     */
    public Set<QueryTerm> collectTerms(Query luceneQuery, TermWeightor weightor) {
        return collectTerms(buildTree(luceneQuery, weightor));
    }

}
