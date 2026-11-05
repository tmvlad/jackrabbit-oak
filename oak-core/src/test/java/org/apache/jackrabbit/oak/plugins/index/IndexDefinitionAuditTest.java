/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.plugins.index;

import static org.apache.jackrabbit.oak.InitialContentHelper.INITIAL_CONTENT;
import static org.apache.jackrabbit.oak.plugins.index.IndexConstants.INDEX_CONTENT_NODE_NAME;
import static org.apache.jackrabbit.oak.plugins.index.IndexConstants.INDEX_DEFINITIONS_NAME;
import static org.apache.jackrabbit.oak.plugins.index.IndexConstants.REINDEX_PROPERTY_NAME;
import static org.apache.jackrabbit.oak.plugins.index.IndexConstants.TYPE_DISABLED;
import static org.apache.jackrabbit.oak.plugins.index.IndexConstants.TYPE_PROPERTY_NAME;
import static org.apache.jackrabbit.oak.plugins.index.IndexDefinitionAudit.AUDIT_NODE_NAME;
import static org.apache.jackrabbit.oak.plugins.index.IndexDefinitionAudit.DELETED_NODE_NAME;
import static org.apache.jackrabbit.oak.plugins.index.IndexDefinitionAudit.MAX_DIFF_LENGTH;
import static org.apache.jackrabbit.oak.plugins.index.IndexDefinitionAudit.PROP_COMMIT_PATH;
import static org.apache.jackrabbit.oak.plugins.index.IndexDefinitionAudit.PROP_DELETED_AT;
import static org.apache.jackrabbit.oak.plugins.index.IndexDefinitionAudit.PROP_DIFF;
import static org.apache.jackrabbit.oak.plugins.index.IndexDefinitionAudit.PROP_OPERATION;
import static org.apache.jackrabbit.oak.plugins.index.IndexDefinitionAudit.PROP_ORIGIN;
import static org.apache.jackrabbit.oak.plugins.index.IndexDefinitionAudit.PROP_PATH;
import static org.apache.jackrabbit.oak.plugins.index.IndexDefinitionAudit.PROP_REINDEX_REQUESTED;
import static org.apache.jackrabbit.oak.plugins.index.IndexDefinitionAudit.PROP_THREAD;
import static org.apache.jackrabbit.oak.plugins.index.IndexDefinitionAudit.PROP_TIME;
import static org.apache.jackrabbit.oak.plugins.index.IndexDefinitionAudit.PROP_USER;
import static org.apache.jackrabbit.oak.plugins.index.IndexUtils.createIndexDefinition;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jackrabbit.JcrConstants;
import org.apache.jackrabbit.oak.InitialContent;
import org.apache.jackrabbit.oak.Oak;
import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.api.ContentRepository;
import org.apache.jackrabbit.oak.api.ContentSession;
import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.commons.collections.SetUtils;
import org.apache.jackrabbit.oak.plugins.index.importer.IndexImporter;
import org.apache.jackrabbit.oak.plugins.index.property.PropertyIndexEditorProvider;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.spi.security.OpenSecurityProvider;
import org.apache.jackrabbit.oak.spi.commit.CommitHook;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EditorDiff;
import org.apache.jackrabbit.oak.spi.commit.EditorHook;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStateUtils;
import org.apache.jackrabbit.oak.spi.toggle.Feature;
import org.apache.jackrabbit.oak.spi.toggle.FeatureToggle;
import org.apache.jackrabbit.oak.spi.whiteboard.DefaultWhiteboard;
import org.apache.jackrabbit.oak.spi.whiteboard.Whiteboard;
import org.apache.jackrabbit.oak.spi.whiteboard.WhiteboardUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * @see <a href="https://issues.apache.org/jira/browse/OAK-12458">OAK-12458</a>
 */
public class IndexDefinitionAuditTest {

    private static final CommitInfo ALICE = new CommitInfo("session-1", "alice",
            Map.of("path", "/oak:index"));

    private final Whiteboard whiteboard = new DefaultWhiteboard();

    private Feature feature;

    private EditorHook hook;

    private NodeState root;

    @Before
    public void setUp() throws CommitFailedException {
        feature = Feature.newFeature(IndexUpdateProvider.FT_OAK_12458, whiteboard);
        IndexUpdateProvider provider = new IndexUpdateProvider(new PropertyIndexEditorProvider());
        provider.setIndexDefinitionAuditFeature(feature);
        hook = new EditorHook(provider);

        // initial indexing of the default index definitions, without audit
        NodeBuilder builder = INITIAL_CONTENT.builder();
        builder.child("content").setProperty("foo", "abc");
        root = hook.processCommit(INITIAL_CONTENT, builder.getNodeState(), CommitInfo.EMPTY);
        setAuditEnabled(true);
    }

    @After
    public void tearDown() {
        feature.close();
    }

    @Test
    public void disabledWhenToggleIsOff() throws Exception {
        setAuditEnabled(false);
        NodeBuilder builder = root.builder();
        createIndexDefinition(builder.child(INDEX_DEFINITIONS_NAME), "fooIndex", true, false, Set.of("foo"), null);
        commit(builder);

        assertFalse(root.getChildNode(INDEX_DEFINITIONS_NAME).hasChildNode(AUDIT_NODE_NAME));
    }

    @Test
    public void created() throws Exception {
        NodeBuilder builder = root.builder();
        createIndexDefinition(builder.child(INDEX_DEFINITIONS_NAME), "fooIndex", true, false, Set.of("foo"), null);
        commit(builder);

        NodeState entry = singleEntry("fooIndex");
        assertEquals("/oak:index/fooIndex", entry.getString(PROP_PATH));
        assertEquals("CREATED", entry.getString(PROP_OPERATION));
        assertEquals("alice", entry.getString(PROP_USER));
        assertEquals("session-1", entry.getString(PROP_ORIGIN));
        assertTrue(entry.getBoolean(PROP_REINDEX_REQUESTED));
        assertEquals("/oak:index", entry.getString(PROP_COMMIT_PATH));
        assertEquals(Thread.currentThread().getName(), entry.getString(PROP_THREAD));
        assertEquals(Type.DATE, entry.getProperty(PROP_TIME).getType());
        assertTrue(entry.getString(PROP_DIFF), entry.getString(PROP_DIFF).contains("/propertyNames"));
        // the audit node must not prevent reindexing of new index definitions
        assertTrue(fooIndex().hasChildNode(INDEX_CONTENT_NODE_NAME));
    }

    @Test
    public void changed() throws Exception {
        addFooIndex();

        NodeBuilder builder = root.builder();
        fooIndex(builder).setProperty("entryCount", 100L);
        commit(builder);

        NodeState entry = lastEntry("fooIndex");
        assertEquals("CHANGED", entry.getString(PROP_OPERATION));
        assertEquals("^\"/entryCount\":100", entry.getString(PROP_DIFF));
        assertFalse(entry.hasProperty(PROP_REINDEX_REQUESTED));
    }

    @Test
    public void changedWithReindex() throws Exception {
        addFooIndex();

        NodeBuilder builder = root.builder();
        fooIndex(builder).setProperty("entryCount", 100L);
        fooIndex(builder).setProperty(REINDEX_PROPERTY_NAME, true);
        commit(builder);

        NodeState entry = lastEntry("fooIndex");
        assertEquals("CHANGED", entry.getString(PROP_OPERATION));
        assertTrue(entry.getBoolean(PROP_REINDEX_REQUESTED));
        assertTrue(entry.getString(PROP_DIFF), entry.getString(PROP_DIFF).contains("/entryCount"));
    }

    @Test
    public void reindexSyncIndex() throws Exception {
        addFooIndex();

        NodeBuilder builder = root.builder();
        fooIndex(builder).setProperty(REINDEX_PROPERTY_NAME, true);
        commit(builder);

        assertFalse(fooIndex().getBoolean(REINDEX_PROPERTY_NAME));
        NodeState entry = lastEntry("fooIndex");
        assertEquals("REINDEX", entry.getString(PROP_OPERATION));
        assertEquals("^\"/reindex\":true", entry.getString(PROP_DIFF));
        assertFalse(entry.hasProperty(PROP_REINDEX_REQUESTED));
    }

    @Test
    public void changesDoneByIndexUpdateAreIgnored() throws Exception {
        addFooIndex();

        // the missing index provider strategy sets the reindex flag
        IndexUpdateProvider provider = new IndexUpdateProvider(CompositeIndexEditorProvider.compose(List.of()));
        provider.setIndexDefinitionAuditFeature(feature);
        NodeBuilder builder = root.builder();
        builder.child("content").setProperty("foo", "xyz");
        root = new EditorHook(provider).processCommit(root, builder.getNodeState(), ALICE);

        assertTrue(fooIndex().getBoolean(REINDEX_PROPERTY_NAME));
        assertEquals(1, entries("fooIndex").size());
    }

    @Test
    public void reindexAsyncIndex() throws Exception {
        NodeBuilder builder = root.builder();
        createIndexDefinition(builder.child(INDEX_DEFINITIONS_NAME), "fooIndex", false, false, Set.of("foo"), null)
                .setProperty(IndexConstants.ASYNC_PROPERTY_NAME, "async");
        commit(builder);

        builder = root.builder();
        fooIndex(builder).setProperty(REINDEX_PROPERTY_NAME, true);
        commit(builder);

        NodeState entry = lastEntry("fooIndex");
        assertEquals("REINDEX", entry.getString(PROP_OPERATION));
        assertEquals("^\"/reindex\":true", entry.getString(PROP_DIFF));
    }

    @Test
    public void disabled() throws Exception {
        addFooIndex();

        NodeBuilder builder = root.builder();
        fooIndex(builder).setProperty(TYPE_PROPERTY_NAME, TYPE_DISABLED);
        commit(builder);

        assertEquals("DISABLED", lastEntry("fooIndex").getString(PROP_OPERATION));

        builder = root.builder();
        fooIndex(builder).setProperty(TYPE_PROPERTY_NAME, "property");
        commit(builder);

        assertEquals("ENABLED", lastEntry("fooIndex").getString(PROP_OPERATION));
    }

    @Test
    public void enabledBySystemProperty() throws Exception {
        setAuditEnabled(false);
        System.setProperty(IndexDefinitionAudit.ENABLED_PROPERTY, "true");
        try {
            hook = new EditorHook(new IndexUpdateProvider(new PropertyIndexEditorProvider()));
        } finally {
            System.clearProperty(IndexDefinitionAudit.ENABLED_PROPERTY);
        }
        addFooIndex();

        assertEquals("CREATED", singleEntry("fooIndex").getString(PROP_OPERATION));
    }

    @Test
    public void importOfNewIndexIsCreated() throws Exception {
        NodeBuilder builder = root.builder();
        createIndexDefinition(builder.child(INDEX_DEFINITIONS_NAME), "fooIndex", false, false, Set.of("foo"), null)
                .setProperty(IndexConstants.ASYNC_PROPERTY_NAME, "temp-async")
                .setProperty(IndexDefinitionAudit.ASYNC_PREVIOUS, "async")
                .setProperty(IndexImporter.INDEX_IMPORT_STATE_KEY, "IMPORT_INDEX_DATA");
        root = hook.processCommit(root, builder.getNodeState(),
                new CommitInfo(IndexDefinitionAudit.ORIGIN_INDEX_IMPORTER, null));

        NodeState entry = singleEntry("fooIndex");
        assertEquals("CREATED", entry.getString(PROP_OPERATION));
        assertEquals(IndexDefinitionAudit.ORIGIN_INDEX_IMPORTER, entry.getString(PROP_ORIGIN));
        String diff = entry.getString(PROP_DIFF);
        assertTrue(diff, diff.contains("^\"/async\":\"async\""));
        assertFalse(diff, diff.contains("temp-async"));
        assertFalse(diff, diff.contains(IndexImporter.INDEX_IMPORT_STATE_KEY));
    }

    @Test
    public void importOfExistingIndexIsReindex() throws Exception {
        addFooIndex();

        NodeBuilder builder = root.builder();
        fooIndex(builder).setProperty(IndexConstants.ASYNC_PROPERTY_NAME, "temp-async")
                .setProperty(IndexDefinitionAudit.ASYNC_PREVIOUS, "none")
                .setProperty("entryCount", 100L);
        root = hook.processCommit(root, builder.getNodeState(),
                new CommitInfo(IndexDefinitionAudit.ORIGIN_INDEX_IMPORTER, null));

        NodeState entry = lastEntry("fooIndex");
        assertEquals("REINDEX", entry.getString(PROP_OPERATION));
        assertEquals("^\"/entryCount\":100", entry.getString(PROP_DIFF));
    }

    @Test
    public void importWithoutDefinitionChangeIsReindex() throws Exception {
        addFooIndex();

        NodeBuilder builder = root.builder();
        fooIndex(builder).setProperty(IndexConstants.REINDEX_COUNT, 5L).setProperty("seed", 42L);
        root = hook.processCommit(root, builder.getNodeState(),
                new CommitInfo(IndexDefinitionAudit.ORIGIN_INDEX_IMPORTER, null));

        NodeState entry = lastEntry("fooIndex");
        assertEquals("REINDEX", entry.getString(PROP_OPERATION));
        assertEquals("", entry.getString(PROP_DIFF));
    }

    @Test
    public void bookkeepingPropertiesAreIgnored() throws Exception {
        addFooIndex();

        NodeBuilder builder = root.builder();
        fooIndex(builder).setProperty(IndexConstants.REINDEX_COUNT, 5L).setProperty("seed", 42L);
        commit(builder);

        assertEquals(1, entries("fooIndex").size());
        assertFalse(singleEntry("fooIndex").getString(PROP_DIFF).contains(IndexConstants.REINDEX_COUNT));
    }

    @Test
    public void merged() throws Exception {
        NodeBuilder builder = root.builder();
        createIndexDefinition(builder.child(INDEX_DEFINITIONS_NAME), "fooIndex-2-custom-1", true, false,
                Set.of("foo"), null)
                .setProperty("merges", List.of("/oak:index/fooIndex-2", "/oak:index/fooIndex-1-custom-1"),
                        Type.STRINGS);
        commit(builder);

        assertEquals("MERGED", singleEntry("fooIndex-2-custom-1").getString(PROP_OPERATION));
    }

    @Test
    public void commitHookForTools() throws Exception {
        assertSame(EmptyHook.INSTANCE, IndexDefinitionAudit.newCommitHook());
        System.setProperty(IndexDefinitionAudit.ENABLED_PROPERTY, "true");
        CommitHook auditHook;
        try {
            auditHook = IndexDefinitionAudit.newCommitHook();
        } finally {
            System.clearProperty(IndexDefinitionAudit.ENABLED_PROPERTY);
        }
        NodeBuilder builder = root.builder();
        createIndexDefinition(builder.child(INDEX_DEFINITIONS_NAME), "fooIndex", false, false, Set.of("foo"), null);
        root = auditHook.processCommit(root, builder.getNodeState(), new CommitInfo("IndexingJob", null));

        NodeState entry = singleEntry("fooIndex");
        assertEquals("CREATED", entry.getString(PROP_OPERATION));
        assertEquals("IndexingJob", entry.getString(PROP_ORIGIN));
        // the hook only records, it does not update indexes
        assertFalse(fooIndex().hasChildNode(INDEX_CONTENT_NODE_NAME));
    }

    @Test
    public void removedHistoryMovesToDeleted() throws Exception {
        addFooIndex();
        removeFooIndex();

        assertFalse(auditNode().hasChildNode(INDEX_DEFINITIONS_NAME));
        NodeState deleted = deletedHistory("/oak:index/fooIndex");
        assertEquals(Type.DATE, deleted.getProperty(PROP_DELETED_AT).getType());
        List<NodeState> entries = entries(deleted);
        assertEquals(2, entries.size());
        NodeState entry = entries.get(1);
        assertEquals("DELETED", entry.getString(PROP_OPERATION));
        assertTrue(entry.getString(PROP_DIFF), entry.getString(PROP_DIFF).contains("/propertyNames"));
    }

    @Test
    public void recreatedIndexRestoresHistory() throws Exception {
        addFooIndex();
        removeFooIndex();
        addFooIndex();

        assertFalse(auditNode().hasChildNode(DELETED_NODE_NAME));
        List<NodeState> entries = entries("fooIndex");
        assertEquals(List.of("CREATED", "DELETED", "CREATED"), operations(entries));
    }

    @Test
    public void deletedAgainMergesHistory() throws Exception {
        addFooIndex();
        removeFooIndex();
        addFooIndex();
        removeFooIndex();

        assertEquals(List.of("CREATED", "DELETED", "CREATED", "DELETED"),
                operations(entries(deletedHistory("/oak:index/fooIndex"))));
    }

    @Test
    public void entryNamesUseMinuteAndSequence() throws Exception {
        addFooIndex();
        NodeBuilder builder = root.builder();
        fooIndex(builder).setProperty("entryCount", 100L);
        commit(builder);

        List<String> names = entryNames(history("/oak:index/fooIndex"));
        assertEquals(2, names.size());
        String first = names.get(0);
        String second = names.get(1);
        assertTrue(first, first.matches("\\d{8}T\\d{4}Z\\.0"));
        String minute = first.substring(0, first.indexOf('.'));
        // the second entry is in the same minute (sequence 1) or in a later minute (sequence 0)
        assertTrue(second, second.equals(minute + ".1") || (second.endsWith(".0") && second.compareTo(first) > 0));
    }

    @Test
    public void entrySequenceIsOrderedNumerically() {
        assertTrue(IndexDefinitionAudit.ENTRY_ORDER.compare("20261013T1405Z.2", "20261013T1405Z.10") < 0);
        assertTrue(IndexDefinitionAudit.ENTRY_ORDER.compare("20261013T1405Z.10", "20261013T1406Z.0") < 0);
    }

    @Test
    public void deletedHistoriesAreCapped() throws Exception {
        NodeBuilder rootBuilder = root.builder();
        NodeBuilder definitions = rootBuilder.child(INDEX_DEFINITIONS_NAME);
        IndexDefinitionAudit audit = new IndexDefinitionAudit(ALICE, rootBuilder, 10, 2);
        for (String name : List.of("a", "b", "c")) {
            NodeState before = definitions.getNodeState();
            createIndexDefinition(definitions, name, true, false, Set.of("foo"), null);
            audit.audit("/oak:index", before, definitions);
            before = definitions.getNodeState();
            definitions.getChildNode(name).remove();
            audit.audit("/oak:index", before, definitions);
            // deletion times have millisecond precision
            Thread.sleep(2);
        }

        NodeState deleted = definitions.getChildNode(AUDIT_NODE_NAME).getChildNode(DELETED_NODE_NAME)
                .getChildNode(INDEX_DEFINITIONS_NAME).getNodeState();
        assertEquals(Set.of("b", "c"), SetUtils.toSet(deleted.getChildNodeNames()));
    }

    @Test
    public void hiddenChangesAreIgnored() throws Exception {
        addFooIndex();

        NodeBuilder builder = root.builder();
        fooIndex(builder).child(":hidden").setProperty("x", 1L);
        fooIndex(builder).setProperty(":hiddenProperty", 1L);
        commit(builder);

        assertEquals(1, entries("fooIndex").size());
    }

    @Test
    public void versionsHaveSeparateHistories() throws Exception {
        NodeBuilder builder = root.builder();
        createIndexDefinition(builder.child(INDEX_DEFINITIONS_NAME), "fooIndex-1", true, false, Set.of("foo"), null);
        createIndexDefinition(builder.child(INDEX_DEFINITIONS_NAME), "fooIndex-2-custom-1", true, false, Set.of("foo"), null);
        commit(builder);

        assertEquals(Set.of("fooIndex-1", "fooIndex-2-custom-1"),
                SetUtils.toSet(auditNode().getChildNode(INDEX_DEFINITIONS_NAME).getChildNodeNames()));
        assertEquals("/oak:index/fooIndex-2-custom-1", singleEntry("fooIndex-2-custom-1").getString(PROP_PATH));
    }

    @Test
    public void nestedIndexDefinitions() throws Exception {
        NodeBuilder builder = root.builder();
        createIndexDefinition(builder.child("content").child(INDEX_DEFINITIONS_NAME), "fooIndex", true, false, Set.of("foo"), null);
        commit(builder);

        assertFalse(root.getChildNode("content").getChildNode(INDEX_DEFINITIONS_NAME).hasChildNode(AUDIT_NODE_NAME));
        List<NodeState> entries = entries(history("/content/oak:index/fooIndex"));
        assertEquals(1, entries.size());
        assertEquals("/content/oak:index/fooIndex", entries.get(0).getString(PROP_PATH));

        // removing the nested oak:index node moves the history to the deleted indexes
        builder = root.builder();
        builder.getChildNode("content").getChildNode(INDEX_DEFINITIONS_NAME).remove();
        commit(builder);

        assertFalse(auditNode().hasChildNode("content"));
        assertEquals(List.of("CREATED", "DELETED"), operations(entries(deletedHistory("/content/oak:index/fooIndex"))));
    }

    @Test
    public void changedChildNode() throws Exception {
        addFooIndex();

        NodeBuilder builder = root.builder();
        fooIndex(builder).child("indexRules").child("nt:base").child("properties").child("bar")
                .setProperty("propertyIndex", true);
        commit(builder);

        NodeState entry = lastEntry("fooIndex");
        assertEquals("CHANGED", entry.getString(PROP_OPERATION));
        assertTrue(entry.getString(PROP_DIFF), entry.getString(PROP_DIFF).contains("/indexRules"));
    }

    @Test
    public void disabledWithOtherChanges() throws Exception {
        addFooIndex();

        NodeBuilder builder = root.builder();
        fooIndex(builder).setProperty(TYPE_PROPERTY_NAME, TYPE_DISABLED).setProperty("entryCount", 100L)
                .setProperty(REINDEX_PROPERTY_NAME, true);
        commit(builder);

        NodeState entry = lastEntry("fooIndex");
        assertEquals("DISABLED", entry.getString(PROP_OPERATION));
        assertTrue(entry.getBoolean(PROP_REINDEX_REQUESTED));
        assertTrue(entry.getString(PROP_DIFF), entry.getString(PROP_DIFF).contains("/entryCount"));
    }

    @Test
    public void enabledWithReindex() throws Exception {
        addFooIndex();
        NodeBuilder builder = root.builder();
        fooIndex(builder).setProperty(TYPE_PROPERTY_NAME, TYPE_DISABLED);
        commit(builder);

        builder = root.builder();
        fooIndex(builder).setProperty(TYPE_PROPERTY_NAME, "property").setProperty(REINDEX_PROPERTY_NAME, true);
        commit(builder);

        NodeState entry = lastEntry("fooIndex");
        assertEquals("ENABLED", entry.getString(PROP_OPERATION));
        assertTrue(entry.getBoolean(PROP_REINDEX_REQUESTED));
    }

    @Test
    public void supersededIndexIsNotRecorded() throws Exception {
        addFooIndex();
        NodeBuilder builder = root.builder();
        createIndexDefinition(builder.child(INDEX_DEFINITIONS_NAME), "barIndex", true, false, Set.of("bar"), null)
                .setProperty(IndexConstants.SUPERSEDED_INDEX_PATHS, List.of("/oak:index/fooIndex"), Type.STRINGS);
        commit(builder);
        // the superseded index is disabled by the index update in the next cycle
        builder = root.builder();
        builder.child("content").setProperty("bar", "x");
        commit(builder);

        assertEquals(TYPE_DISABLED, fooIndex().getString(TYPE_PROPERTY_NAME));
        assertEquals(List.of("CREATED"), operations(entries("fooIndex")));
        assertTrue(singleEntry("barIndex").getString(PROP_DIFF).contains("supersedes"));
    }

    @Test
    public void diffIndexMerge() throws Exception {
        commitDiffIndex("2026-01-01T00:00:00.000Z", "{\"acme.testIndex\": {\"jcr:primaryType\": "
                + "\"oak:QueryIndexDefinition\", \"type\": \"lucene\", \"async\": [\"async\", \"nrt\"], "
                + "\"includedPaths\": [\"/content\"], \"queryPaths\": [\"/content\"]}}");

        assertEquals(List.of("CREATED"), operations(entries("diff.index")));
        NodeState merged = singleEntry("acme.testIndex-1-custom-1");
        assertEquals("MERGED", merged.getString(PROP_OPERATION));
        assertEquals("alice", merged.getString(PROP_USER));
        assertTrue(merged.getString(PROP_DIFF), merged.getString(PROP_DIFF).contains("/oak:index/acme.testIndex"));

        commitDiffIndex("2026-01-01T00:00:00.001Z", "{\"acme.testIndex\": {\"jcr:primaryType\": "
                + "\"oak:QueryIndexDefinition\", \"type\": \"lucene\", \"async\": [\"async\", \"nrt\"], "
                + "\"includedPaths\": [\"/content/dam\"], \"queryPaths\": [\"/content/dam\"]}}");

        assertEquals(List.of("CREATED", "CHANGED"), operations(entries("diff.index")));
        assertEquals("MERGED", singleEntry("acme.testIndex-1-custom-2").getString(PROP_OPERATION));
        assertFalse(root.getChildNode(INDEX_DEFINITIONS_NAME).hasChildNode("acme.testIndex-1-custom-1"));
        assertEquals(List.of("MERGED", "DELETED"),
                operations(entries(deletedHistory("/oak:index/acme.testIndex-1-custom-1"))));
    }

    @Test
    public void startupIndexesAreRecorded() throws Exception {
        MemoryNodeStore store = new MemoryNodeStore();
        System.setProperty(IndexDefinitionAudit.ENABLED_PROPERTY, "true");
        ContentRepository repository;
        try {
            repository = new Oak(store).with(new InitialContent()).with(new OpenSecurityProvider())
                    .with(new PropertyIndexEditorProvider()).createContentRepository();
        } finally {
            System.clearProperty(IndexDefinitionAudit.ENABLED_PROPERTY);
        }
        ((Closeable) repository).close();

        NodeState history = store.getRoot().getChildNode(INDEX_DEFINITIONS_NAME).getChildNode(AUDIT_NODE_NAME)
                .getChildNode(INDEX_DEFINITIONS_NAME).getChildNode("uuid");
        List<NodeState> entries = entries(history);
        assertEquals(1, entries.size());
        assertEquals("CREATED", entries.get(0).getString(PROP_OPERATION));
        assertEquals("OakInitializer", entries.get(0).getString(PROP_ORIGIN));
    }

    @Test
    public void deletedSubtreeWithNestedIndexIsNotRecorded() throws Exception {
        NodeBuilder builder = root.builder();
        createIndexDefinition(builder.child("content").child(INDEX_DEFINITIONS_NAME), "fooIndex", true, false,
                Set.of("foo"), null);
        commit(builder);

        builder = root.builder();
        builder.getChildNode("content").remove();
        commit(builder);

        // known limitation: the removal of the parent of an oak:index node is not recorded
        assertEquals(List.of("CREATED"), operations(entries(history("/content/oak:index/fooIndex"))));
        assertFalse(auditNode().hasChildNode(DELETED_NODE_NAME));
    }

    @Test
    public void nodesWithoutTypeAreIgnored() throws Exception {
        NodeBuilder builder = root.builder();
        builder.child("content").child(INDEX_DEFINITIONS_NAME).child("notAnIndex").setProperty("foo", "bar");
        commit(builder);

        assertFalse(root.getChildNode(INDEX_DEFINITIONS_NAME).hasChildNode(AUDIT_NODE_NAME));
    }

    @Test
    public void contentChangesAreIgnored() throws Exception {
        NodeBuilder builder = root.builder();
        builder.child("content").setProperty("foo", "xyz");
        commit(builder);

        assertFalse(root.getChildNode(INDEX_DEFINITIONS_NAME).hasChildNode(AUDIT_NODE_NAME));
    }

    @Test
    public void oldEntriesAreRemoved() throws Exception {
        NodeBuilder rootBuilder = root.builder();
        NodeBuilder definitions = rootBuilder.child(INDEX_DEFINITIONS_NAME);
        createIndexDefinition(definitions, "fooIndex", true, false, Set.of("foo"), null);
        NodeState before = root.getChildNode(INDEX_DEFINITIONS_NAME);
        IndexDefinitionAudit audit = new IndexDefinitionAudit(ALICE, rootBuilder, 2, 10);
        for (int i = 0; i < 4; i++) {
            definitions.child("fooIndex").setProperty("entryCount", (long) i);
            audit.audit("/oak:index", before, definitions);
            before = definitions.getNodeState();
        }

        List<NodeState> entries = entries(rootBuilder.getNodeState().getChildNode(INDEX_DEFINITIONS_NAME)
                .getChildNode(AUDIT_NODE_NAME).getChildNode(INDEX_DEFINITIONS_NAME).getChildNode("fooIndex"));
        assertEquals(2, entries.size());
        assertEquals("^\"/entryCount\":2", entries.get(0).getString(PROP_DIFF));
        assertEquals("^\"/entryCount\":3", entries.get(1).getString(PROP_DIFF));
    }

    @Test
    public void largeDiffIsTruncated() throws Exception {
        NodeBuilder builder = root.builder();
        createIndexDefinition(builder.child(INDEX_DEFINITIONS_NAME), "fooIndex", true, false, Set.of("foo"), null)
                .setProperty("description", "x".repeat(MAX_DIFF_LENGTH * 2));
        commit(builder);

        String diff = singleEntry("fooIndex").getString(PROP_DIFF);
        assertEquals(MAX_DIFF_LENGTH + "...".length(), diff.length());
        assertTrue(diff.endsWith("..."));
    }

    @Test
    public void removedIndexDefinitionsNodeIsNotRecorded() throws Exception {
        addFooIndex();

        NodeBuilder builder = root.builder();
        builder.getChildNode(INDEX_DEFINITIONS_NAME).remove();
        commit(builder);

        assertFalse(root.hasChildNode(INDEX_DEFINITIONS_NAME));
    }

    @Test
    public void notSupportedForAsyncIndexUpdate() throws Exception {
        NodeBuilder builder = root.builder();
        createIndexDefinition(builder.child(INDEX_DEFINITIONS_NAME), "fooIndex", false, false, Set.of("foo"), null)
                .setProperty(IndexConstants.ASYNC_PROPERTY_NAME, "async");
        NodeState after = builder.getNodeState();

        NodeBuilder asyncBuilder = after.builder();
        IndexUpdate update = new IndexUpdate(new PropertyIndexEditorProvider(), "async", after, asyncBuilder,
                IndexUpdateCallback.NOOP, ALICE);
        update.setIndexDefinitionAuditEnabled(true);
        EditorDiff.process(update, root, after);

        assertFalse(asyncBuilder.getChildNode(INDEX_DEFINITIONS_NAME).hasChildNode(AUDIT_NODE_NAME));
    }

    @Test
    public void featureRegisteredByOak() throws Exception {
        MemoryNodeStore store = new MemoryNodeStore();
        Whiteboard oakWhiteboard = new DefaultWhiteboard();
        ContentRepository repository = new Oak(store)
                .with(new InitialContent())
                .with(new OpenSecurityProvider())
                .with(new PropertyIndexEditorProvider())
                .with(oakWhiteboard)
                .createContentRepository();
        try (ContentSession session = repository.login(null, null)) {
            getToggle(oakWhiteboard).setEnabled(true);
            Root r = session.getLatestRoot();
            IndexUtils.createIndexDefinition(r.getTree("/oak:index"), "fooIndex", false, new String[] {"foo"});
            r.commit();
        } finally {
            ((Closeable) repository).close();
        }

        NodeState history = store.getRoot().getChildNode(INDEX_DEFINITIONS_NAME).getChildNode(AUDIT_NODE_NAME)
                .getChildNode(INDEX_DEFINITIONS_NAME).getChildNode("fooIndex");
        assertEquals(List.of("CREATED"), operations(entries(history)));
    }

    private void addFooIndex() throws CommitFailedException {
        NodeBuilder builder = root.builder();
        createIndexDefinition(builder.child(INDEX_DEFINITIONS_NAME), "fooIndex", true, false, Set.of("foo"), null);
        commit(builder);
    }

    private void commitDiffIndex(String lastModified, String json) throws CommitFailedException {
        NodeBuilder builder = root.builder();
        NodeBuilder diffIndex = builder.child(INDEX_DEFINITIONS_NAME).child("diff.index");
        diffIndex.setProperty(JcrConstants.JCR_PRIMARYTYPE, IndexConstants.INDEX_DEFINITIONS_NODE_TYPE, Type.NAME);
        diffIndex.setProperty(TYPE_PROPERTY_NAME, TYPE_DISABLED);
        NodeBuilder file = diffIndex.child("diff.json");
        file.setProperty(JcrConstants.JCR_PRIMARYTYPE, JcrConstants.NT_FILE, Type.NAME);
        NodeBuilder content = file.child(JcrConstants.JCR_CONTENT);
        content.setProperty(JcrConstants.JCR_PRIMARYTYPE, JcrConstants.NT_RESOURCE, Type.NAME);
        content.setProperty(JcrConstants.JCR_LASTMODIFIED, lastModified);
        content.setProperty(JcrConstants.JCR_DATA, json);
        commit(builder);
    }

    private void removeFooIndex() throws CommitFailedException {
        NodeBuilder builder = root.builder();
        fooIndex(builder).remove();
        commit(builder);
    }

    private void commit(NodeBuilder builder) throws CommitFailedException {
        root = hook.processCommit(root, builder.getNodeState(), ALICE);
    }

    private void setAuditEnabled(boolean enabled) {
        getToggle(whiteboard).setEnabled(enabled);
    }

    private static FeatureToggle getToggle(Whiteboard whiteboard) {
        return WhiteboardUtils.getServices(whiteboard, FeatureToggle.class).stream()
                .filter(t -> IndexUpdateProvider.FT_OAK_12458.equals(t.getName()))
                .findFirst().orElseThrow();
    }

    private NodeState fooIndex() {
        return root.getChildNode(INDEX_DEFINITIONS_NAME).getChildNode("fooIndex");
    }

    private static NodeBuilder fooIndex(NodeBuilder builder) {
        return builder.getChildNode(INDEX_DEFINITIONS_NAME).getChildNode("fooIndex");
    }

    private NodeState auditNode() {
        return root.getChildNode(INDEX_DEFINITIONS_NAME).getChildNode(AUDIT_NODE_NAME);
    }

    private NodeState history(String indexPath) {
        return NodeStateUtils.getNode(auditNode(), indexPath);
    }

    private NodeState deletedHistory(String indexPath) {
        return NodeStateUtils.getNode(auditNode().getChildNode(DELETED_NODE_NAME), indexPath);
    }

    private static List<String> entryNames(NodeState history) {
        List<String> names = new ArrayList<>();
        for (ChildNodeEntry e : history.getChildNodeEntries()) {
            names.add(e.getName());
        }
        names.sort(IndexDefinitionAudit.ENTRY_ORDER);
        return names;
    }

    private static List<NodeState> entries(NodeState history) {
        List<NodeState> entries = new ArrayList<>();
        for (String name : entryNames(history)) {
            entries.add(history.getChildNode(name));
        }
        return entries;
    }

    private static List<String> operations(List<NodeState> entries) {
        List<String> operations = new ArrayList<>();
        for (NodeState entry : entries) {
            operations.add(entry.getString(PROP_OPERATION));
        }
        return operations;
    }

    private List<NodeState> entries(String indexName) {
        return entries(history("/oak:index/" + indexName));
    }

    private NodeState singleEntry(String indexName) {
        List<NodeState> entries = entries(indexName);
        assertEquals(1, entries.size());
        return entries.get(0);
    }

    private NodeState lastEntry(String indexName) {
        List<NodeState> entries = entries(indexName);
        assertFalse(entries.isEmpty());
        NodeState last = entries.get(entries.size() - 1);
        assertNotNull(last);
        return last;
    }
}
