/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.facet.terms.bytes;

import org.apache.lucene.index.IndexReader;
import org.elasticsearch.ElasticSearchIllegalArgumentException;
import org.elasticsearch.common.collect.BoundedTreeSet;
import org.elasticsearch.common.collect.ImmutableList;
import org.elasticsearch.common.collect.Maps;
import org.elasticsearch.common.thread.ThreadLocals;
import org.elasticsearch.common.trove.TByteIntHashMap;
import org.elasticsearch.common.trove.TByteIntIterator;
import org.elasticsearch.index.cache.field.data.FieldDataCache;
import org.elasticsearch.index.field.data.FieldDataType;
import org.elasticsearch.index.field.data.bytes.ByteFieldData;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.script.search.SearchScript;
import org.elasticsearch.search.facet.AbstractFacetCollector;
import org.elasticsearch.search.facet.Facet;
import org.elasticsearch.search.facet.FacetPhaseExecutionException;
import org.elasticsearch.search.facet.terms.TermsFacet;
import org.elasticsearch.search.internal.SearchContext;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * @author kimchy (shay.banon)
 */
public class TermsByteFacetCollector extends AbstractFacetCollector {

    static ThreadLocal<ThreadLocals.CleanableValue<Deque<TByteIntHashMap>>> cache = new ThreadLocal<ThreadLocals.CleanableValue<Deque<TByteIntHashMap>>>() {
        @Override protected ThreadLocals.CleanableValue<Deque<TByteIntHashMap>> initialValue() {
            return new ThreadLocals.CleanableValue<Deque<TByteIntHashMap>>(new ArrayDeque<TByteIntHashMap>());
        }
    };

    private final FieldDataCache fieldDataCache;

    private final String fieldName;

    private final String indexFieldName;

    private final TermsFacet.ComparatorType comparatorType;

    private final int size;

    private final int numberOfShards;

    private final FieldDataType fieldDataType;

    private ByteFieldData fieldData;

    private final StaticAggregatorValueProc aggregator;

    private final SearchScript script;

    public TermsByteFacetCollector(String facetName, String fieldName, int size, TermsFacet.ComparatorType comparatorType, boolean allTerms, SearchContext context,
                                   String scriptLang, String script, Map<String, Object> params) {
        super(facetName);
        this.fieldDataCache = context.fieldDataCache();
        this.size = size;
        this.comparatorType = comparatorType;
        this.numberOfShards = context.numberOfShards();

        this.fieldName = fieldName;

        MapperService.SmartNameFieldMappers smartMappers = context.mapperService().smartName(fieldName);
        if (smartMappers == null || !smartMappers.hasMapper()) {
            throw new ElasticSearchIllegalArgumentException("Field [" + fieldName + "] doesn't have a type, can't run terms short facet collector on it");
        } else {
            // add type filter if there is exact doc mapper associated with it
            if (smartMappers.hasDocMapper()) {
                setFilter(context.filterCache().cache(smartMappers.docMapper().typeFilter()));
            }

            if (smartMappers.mapper().fieldDataType() != FieldDataType.DefaultTypes.BYTE) {
                throw new ElasticSearchIllegalArgumentException("Field [" + fieldName + "] is not of byte type, can't run terms short facet collector on it");
            }

            this.indexFieldName = smartMappers.mapper().names().indexName();
            this.fieldDataType = smartMappers.mapper().fieldDataType();
        }

        if (script != null) {
            this.script = new SearchScript(context.lookup(), scriptLang, script, params, context.scriptService());
        } else {
            this.script = null;
        }

        if (this.script == null) {
            aggregator = new StaticAggregatorValueProc(popFacets());
        } else {
            aggregator = new AggregatorValueProc(popFacets(), this.script);
        }

        if (allTerms) {
            try {
                for (IndexReader reader : context.searcher().subReaders()) {
                    ByteFieldData fieldData = (ByteFieldData) fieldDataCache.cache(fieldDataType, reader, indexFieldName);
                    fieldData.forEachValue(aggregator);
                }
            } catch (Exception e) {
                throw new FacetPhaseExecutionException(facetName, "failed to load all terms", e);
            }
        }
    }

    @Override protected void doSetNextReader(IndexReader reader, int docBase) throws IOException {
        fieldData = (ByteFieldData) fieldDataCache.cache(fieldDataType, reader, indexFieldName);
        if (script != null) {
            script.setNextReader(reader);
        }
    }

    @Override protected void doCollect(int doc) throws IOException {
        fieldData.forEachValueInDoc(doc, aggregator);
    }

    @Override public Facet facet() {
        TByteIntHashMap facets = aggregator.facets();
        if (facets.isEmpty()) {
            pushFacets(facets);
            return new InternalByteTermsFacet(facetName, fieldName, comparatorType, size, ImmutableList.<InternalByteTermsFacet.ByteEntry>of(), aggregator.missing());
        } else {
            // we need to fetch facets of "size * numberOfShards" because of problems in how they are distributed across shards
            BoundedTreeSet<InternalByteTermsFacet.ByteEntry> ordered = new BoundedTreeSet<InternalByteTermsFacet.ByteEntry>(comparatorType.comparator(), size * numberOfShards);
            for (TByteIntIterator it = facets.iterator(); it.hasNext();) {
                it.advance();
                ordered.add(new InternalByteTermsFacet.ByteEntry(it.key(), it.value()));
            }
            pushFacets(facets);
            return new InternalByteTermsFacet(facetName, fieldName, comparatorType, size, ordered, aggregator.missing());
        }
    }

    static TByteIntHashMap popFacets() {
        Deque<TByteIntHashMap> deque = cache.get().get();
        if (deque.isEmpty()) {
            deque.add(new TByteIntHashMap());
        }
        TByteIntHashMap facets = deque.pollFirst();
        facets.clear();
        return facets;
    }

    static void pushFacets(TByteIntHashMap facets) {
        facets.clear();
        Deque<TByteIntHashMap> deque = cache.get().get();
        if (deque != null) {
            deque.add(facets);
        }
    }

    public static class AggregatorValueProc extends StaticAggregatorValueProc {

        private final SearchScript script;

        private final Map<String, Object> scriptParams;

        public AggregatorValueProc(TByteIntHashMap facets, SearchScript script) {
            super(facets);
            this.script = script;
            if (script != null) {
                scriptParams = Maps.newHashMapWithExpectedSize(4);
            } else {
                scriptParams = null;
            }
        }

        @Override public void onValue(int docId, byte value) {
            if (script != null) {
                scriptParams.put("term", value);
                Object scriptValue = script.execute(docId, scriptParams);
                if (scriptValue == null) {
                    return;
                }
                if (scriptValue instanceof Boolean) {
                    if (!((Boolean) scriptValue)) {
                        return;
                    }
                } else {
                    value = ((Number) scriptValue).byteValue();
                }
            }
            super.onValue(docId, value);
        }
    }

    public static class StaticAggregatorValueProc implements ByteFieldData.ValueInDocProc, ByteFieldData.ValueProc {

        private final TByteIntHashMap facets;

        private int missing;

        public StaticAggregatorValueProc(TByteIntHashMap facets) {
            this.facets = facets;
        }

        @Override public void onValue(byte value) {
            facets.putIfAbsent(value, 0);
        }

        @Override public void onValue(int docId, byte value) {
            facets.adjustOrPutValue(value, 1, 1);
        }

        @Override public void onMissing(int docID) {
            missing++;
        }

        public final TByteIntHashMap facets() {
            return facets;
        }

        public final int missing() {
            return this.missing;
        }
    }
}
