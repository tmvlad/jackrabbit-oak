/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.jackrabbit.oak.index;

import org.apache.jackrabbit.oak.plugins.index.search.FulltextIndexConstants;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.junit.Test;

import static org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState.EMPTY_NODE;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class IndexerSupportTest {

    @Test
    public void failsWhenLuceneIndexHasNoIndexRules() {
        NodeBuilder idxBuilder = EMPTY_NODE.builder();
        idxBuilder.setProperty("type", "lucene");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> IndexerSupport.failIfNoIndexRules(idxBuilder, "/oak:index/damAssetLucene-11"));
        String message = e.getMessage();
        assertTrue(message, message.contains("/oak:index/damAssetLucene-11"));
        assertTrue(message, message.contains(FulltextIndexConstants.INDEX_RULES));
        assertTrue(message, message.contains("One possible root cause is that the filter in the index definition is on \"oak:index\""));
        assertTrue(message, message.contains("https://jackrabbit.apache.org/oak/docs/query/indexing.html"));
    }

    @Test
    public void failsWhenElasticsearchIndexHasNoIndexRules() {
        NodeBuilder idxBuilder = EMPTY_NODE.builder();
        idxBuilder.setProperty("type", "elasticsearch");

        assertThrows(IllegalStateException.class,
                () -> IndexerSupport.failIfNoIndexRules(idxBuilder, "/oak:index/damAssetLucene-11"));
    }

    @Test
    public void passesWhenIndexRulesPresent() {
        NodeBuilder idxBuilder = EMPTY_NODE.builder();
        idxBuilder.setProperty("type", "lucene");
        idxBuilder.child(FulltextIndexConstants.INDEX_RULES);

        // Must not throw when the indexRules child node is present.
        IndexerSupport.failIfNoIndexRules(idxBuilder, "/oak:index/testIndex");
    }

    @Test
    public void ignoresNonLuceneIndexWithoutIndexRules() {
        NodeBuilder idxBuilder = EMPTY_NODE.builder();
        idxBuilder.setProperty("type", "property");

        // Property (and other non-fulltext) indexes legitimately have no indexRules node.
        IndexerSupport.failIfNoIndexRules(idxBuilder, "/oak:index/propertyIndex");
    }

    @Test
    public void ignoresIndexWithoutTypeProperty() {
        NodeBuilder idxBuilder = EMPTY_NODE.builder();

        // A node without a 'type' property must be skipped, not throw a NullPointerException.
        IndexerSupport.failIfNoIndexRules(idxBuilder, "/oak:index/noType");
    }
}
