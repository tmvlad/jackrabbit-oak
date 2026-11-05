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
import static org.apache.jackrabbit.oak.plugins.index.IndexConstants.INDEX_DEFINITIONS_NAME;
import static org.apache.jackrabbit.oak.plugins.index.IndexUtils.createIndexDefinition;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.commons.PathUtils;
import org.apache.jackrabbit.oak.plugins.index.property.PropertyIndexEditorProvider;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EditorHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * @see <a href="https://issues.apache.org/jira/browse/OAK-12458">OAK-12458</a>
 */
public class IndexDefinitionAuditMBeanImplTest {

    private static final CommitInfo ALICE = new CommitInfo("session-1", "alice");

    private MemoryNodeStore store;

    private EditorHook hook;

    private IndexDefinitionAuditMBeanImpl mbean;

    @Before
    public void setUp() throws Exception {
        System.setProperty(IndexDefinitionAudit.ENABLED_PROPERTY, "true");
        try {
            hook = new EditorHook(new IndexUpdateProvider(new PropertyIndexEditorProvider()));
        } finally {
            System.clearProperty(IndexDefinitionAudit.ENABLED_PROPERTY);
        }
        store = new MemoryNodeStore(INITIAL_CONTENT);
        mbean = new IndexDefinitionAuditMBeanImpl(store);
    }

    @After
    public void tearDown() {
        System.clearProperty(IndexDefinitionAudit.ENABLED_PROPERTY);
    }

    @Test
    public void noAudit() throws Exception {
        assertTrue(mbean.getIndexes().isEmpty());
        assertTrue(mbean.getAuditTrail("/oak:index/fooIndex").isEmpty());
        assertTrue(mbean.filter(null).isEmpty());
    }

    @Test
    public void indexes() throws Exception {
        addIndex("/content", "barIndex");
        addIndex("/", "fooIndex");
        addIndex("/", "removedIndex");
        removeIndex("removedIndex");

        List<CompositeData> rows = rows(mbean.getIndexes());
        assertEquals(List.of("/content/oak:index/barIndex", "/oak:index/fooIndex", "/oak:index/removedIndex"),
                column(rows, "index"));
        CompositeData foo = rows.get(1);
        assertEquals(false, foo.get("deleted"));
        assertEquals(1, foo.get("entries"));
        assertEquals("CREATED", foo.get("lastOperation"));
        assertEquals("alice", foo.get("lastUser"));
        assertFalse(((String) foo.get("lastTime")).isEmpty());
        CompositeData removed = rows.get(2);
        assertEquals(true, removed.get("deleted"));
        assertEquals(2, removed.get("entries"));
        assertEquals("DELETED", removed.get("lastOperation"));
    }

    @Test
    public void auditTrailNewestFirst() throws Exception {
        addIndex("/", "fooIndex");
        changeIndex("fooIndex", "type", "disabled");

        List<CompositeData> rows = rows(mbean.getAuditTrail("/oak:index/fooIndex"));
        assertEquals(List.of("DISABLED", "CREATED"), column(rows, "op"));
        CompositeData added = rows.get(1);
        assertEquals("/oak:index/fooIndex", added.get("index"));
        assertEquals(true, added.get("reindexRequested"));
        assertEquals("session-1", added.get("origin"));
        assertEquals("/", added.get("commitPath"));
        assertEquals(Thread.currentThread().getName(), added.get("thread"));
        assertTrue((String) added.get("diff"), ((String) added.get("diff")).contains("propertyNames"));
        assertTrue(((String) added.get("entry")).endsWith(".0"));
        // the index name alone is resolved below /oak:index
        assertEquals(2, mbean.getAuditTrail(" fooIndex ").size());
    }

    @Test
    public void auditTrailOfRemovedIndex() throws Exception {
        addIndex("/", "fooIndex");
        removeIndex("fooIndex");

        List<CompositeData> rows = rows(mbean.getAuditTrail("/oak:index/fooIndex"));
        assertEquals(List.of("DELETED", "CREATED"), column(rows, "op"));
        assertEquals(true, rows.get(0).get("deleted"));
    }

    @Test
    public void filter() throws Exception {
        addIndex("/", "fooIndex");
        addIndex("/", "barIndex");
        changeIndex("barIndex", "type", "disabled");

        assertEquals(3, mbean.filter("").size());
        assertEquals(List.of("/oak:index/barIndex", "/oak:index/barIndex"),
                column(rows(mbean.filter("BARINDEX")), "index"));
        assertEquals(List.of("DISABLED"), column(rows(mbean.filter("disabled")), "op"));
        assertEquals(3, mbean.filter("alice").size());
        assertTrue(mbean.filter("nothing matches this").isEmpty());
    }

    @Test
    public void registeredAsStandardMBean() throws Exception {
        addIndex("/", "fooIndex");
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName("org.apache.jackrabbit.oak:type=" + IndexDefinitionAuditMBean.TYPE
                + ",name=test");
        server.registerMBean(mbean, name);
        try {
            assertEquals(1, ((TabularData) server.getAttribute(name, "Indexes")).size());
            TabularData trail = (TabularData) server.invoke(name, "getAuditTrail",
                    new Object[] {"fooIndex"}, new String[] {String.class.getName()});
            assertEquals(1, trail.size());
        } finally {
            server.unregisterMBean(name);
        }
    }

    private void addIndex(String parent, String name) throws CommitFailedException {
        NodeBuilder builder = store.getRoot().builder();
        NodeBuilder definitions = builder;
        for (String element : PathUtils.elements(parent)) {
            definitions = definitions.child(element);
        }
        createIndexDefinition(definitions.child(INDEX_DEFINITIONS_NAME), name, true, false, Set.of("foo"), null);
        store.merge(builder, hook, ALICE);
    }

    private void changeIndex(String name, String property, String value) throws CommitFailedException {
        NodeBuilder builder = store.getRoot().builder();
        builder.getChildNode(INDEX_DEFINITIONS_NAME).getChildNode(name).setProperty(property, value);
        store.merge(builder, hook, ALICE);
    }

    private void removeIndex(String name) throws CommitFailedException {
        NodeBuilder builder = store.getRoot().builder();
        builder.getChildNode(INDEX_DEFINITIONS_NAME).getChildNode(name).remove();
        store.merge(builder, hook, ALICE);
    }

    private static List<CompositeData> rows(TabularData data) {
        List<CompositeData> rows = new ArrayList<>();
        for (Object row : data.values()) {
            rows.add((CompositeData) row);
        }
        return rows;
    }

    private static List<Object> column(List<CompositeData> rows, String name) {
        List<Object> values = new ArrayList<>();
        for (CompositeData row : rows) {
            values.add(row.get(name));
        }
        return values;
    }
}
