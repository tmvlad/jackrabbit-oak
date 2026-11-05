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

import static org.apache.jackrabbit.oak.commons.PathUtils.concat;
import static org.apache.jackrabbit.oak.plugins.index.IndexConstants.INDEX_DEFINITIONS_NAME;
import static org.apache.jackrabbit.oak.plugins.index.IndexDefinitionAudit.AUDIT_NODE_NAME;
import static org.apache.jackrabbit.oak.plugins.index.IndexDefinitionAudit.DELETED_NODE_NAME;
import static org.apache.jackrabbit.oak.plugins.index.IndexDefinitionAudit.ENTRY_ORDER;
import static org.apache.jackrabbit.oak.plugins.index.IndexDefinitionAudit.PROP_COMMIT_PATH;
import static org.apache.jackrabbit.oak.plugins.index.IndexDefinitionAudit.PROP_DIFF;
import static org.apache.jackrabbit.oak.plugins.index.IndexDefinitionAudit.PROP_OPERATION;
import static org.apache.jackrabbit.oak.plugins.index.IndexDefinitionAudit.PROP_ORIGIN;
import static org.apache.jackrabbit.oak.plugins.index.IndexDefinitionAudit.PROP_REINDEX_REQUESTED;
import static org.apache.jackrabbit.oak.plugins.index.IndexDefinitionAudit.PROP_THREAD;
import static org.apache.jackrabbit.oak.plugins.index.IndexDefinitionAudit.PROP_TIME;
import static org.apache.jackrabbit.oak.plugins.index.IndexDefinitionAudit.PROP_USER;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;

import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.commons.jmx.AnnotatedStandardMBean;
import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.apache.jackrabbit.oak.spi.state.NodeStateUtils;

/**
 * Reads the audit trail written by {@link IndexDefinitionAudit}.
 *
 * @see <a href="https://issues.apache.org/jira/browse/OAK-12458">OAK-12458</a>
 */
public class IndexDefinitionAuditMBeanImpl extends AnnotatedStandardMBean implements IndexDefinitionAuditMBean {

    private static final String[] INDEX_ITEMS = {
            "index", "lastOperation", "lastTime", "deleted", "entries", "lastUser"};

    private static final OpenType<?>[] INDEX_TYPES = {
            SimpleType.STRING, SimpleType.STRING, SimpleType.STRING, SimpleType.BOOLEAN, SimpleType.INTEGER,
            SimpleType.STRING};

    private static final String[] ENTRY_ITEMS = {
            "index", "op", "time", "user", "diff", "thread", "entry", "reindexRequested", "origin", "commitPath",
            "deleted"};

    private static final OpenType<?>[] ENTRY_TYPES = {
            SimpleType.STRING, SimpleType.STRING, SimpleType.STRING, SimpleType.STRING, SimpleType.STRING,
            SimpleType.STRING, SimpleType.STRING, SimpleType.BOOLEAN, SimpleType.STRING, SimpleType.STRING,
            SimpleType.BOOLEAN};

    private final NodeStore store;

    public IndexDefinitionAuditMBeanImpl(NodeStore store) {
        super(IndexDefinitionAuditMBean.class);
        this.store = store;
    }

    @Override
    public TabularData getIndexes() throws OpenDataException {
        CompositeType rowType = new CompositeType("IndexDefinitionAuditIndex", "Audited index",
                INDEX_ITEMS, INDEX_ITEMS, INDEX_TYPES);
        TabularDataSupport result = new TabularDataSupport(
                new TabularType("IndexDefinitionAuditIndexes", "Audited indexes", rowType, new String[] {"index"}));
        List<History> histories = getHistories();
        histories.sort(Comparator.comparing((History h) -> h.path));
        for (History history : histories) {
            List<String> entries = history.getEntryNames();
            NodeState last = history.state.getChildNode(entries.get(entries.size() - 1));
            result.put(new CompositeDataSupport(rowType, INDEX_ITEMS, new Object[] {
                    history.path, getString(last, PROP_OPERATION), getDate(last, PROP_TIME), history.deleted,
                    entries.size(), getString(last, PROP_USER)}));
        }
        return result;
    }

    @Override
    public TabularData getAuditTrail(String indexPath) throws OpenDataException {
        String path = indexPath == null ? "" : indexPath.trim();
        if (!path.startsWith("/")) {
            path = concat("/" + INDEX_DEFINITIONS_NAME, path);
        }
        List<Entry> entries = new ArrayList<>();
        for (History history : getHistories()) {
            if (history.path.equals(path)) {
                history.addEntries(entries);
            }
        }
        return toTable(entries);
    }

    @Override
    public TabularData filter(String text) throws OpenDataException {
        String term = text == null ? "" : text.trim().toLowerCase(Locale.ENGLISH);
        List<Entry> entries = new ArrayList<>();
        for (History history : getHistories()) {
            history.addEntries(entries);
        }
        entries.removeIf(entry -> !entry.matches(term));
        return toTable(entries);
    }

    private TabularData toTable(List<Entry> entries) throws OpenDataException {
        CompositeType rowType = new CompositeType("IndexDefinitionAuditEntry", "Audit entry",
                ENTRY_ITEMS, ENTRY_ITEMS, ENTRY_TYPES);
        TabularDataSupport result = new TabularDataSupport(
                new TabularType("IndexDefinitionAuditEntries", "Audit entries", rowType,
                        new String[] {"index", "entry"}));
        // newest first
        entries.sort(Comparator.comparing((Entry e) -> e.name, ENTRY_ORDER).reversed()
                .thenComparing(e -> e.history.path));
        for (Entry entry : entries) {
            result.put(new CompositeDataSupport(rowType, ENTRY_ITEMS, entry.values()));
        }
        return result;
    }

    private List<History> getHistories() {
        NodeState audit = store.getRoot().getChildNode(INDEX_DEFINITIONS_NAME).getChildNode(AUDIT_NODE_NAME);
        List<History> histories = new ArrayList<>();
        collect(audit, "/", false, histories);
        collect(audit.getChildNode(DELETED_NODE_NAME), "/", true, histories);
        return histories;
    }

    private static void collect(NodeState state, String path, boolean deleted, List<History> histories) {
        boolean hasEntries = false;
        for (ChildNodeEntry child : state.getChildNodeEntries()) {
            String name = child.getName();
            if (IndexDefinitionAudit.isEntryName(name)) {
                hasEntries = true;
            } else if (!NodeStateUtils.isHidden(name)) {
                collect(child.getNodeState(), concat(path, name), deleted, histories);
            }
        }
        if (hasEntries) {
            histories.add(new History(path, deleted, state));
        }
    }

    private static String getString(NodeState state, String name) {
        String value = state.getString(name);
        return value == null ? "" : value;
    }

    private static String getDate(NodeState state, String name) {
        PropertyState property = state.getProperty(name);
        return property == null ? "" : property.getValue(Type.DATE);
    }

    private static final class History {
        final String path;
        final boolean deleted;
        final NodeState state;

        History(String path, boolean deleted, NodeState state) {
            this.path = path;
            this.deleted = deleted;
            this.state = state;
        }

        List<String> getEntryNames() {
            List<String> names = new ArrayList<>();
            for (String name : state.getChildNodeNames()) {
                if (IndexDefinitionAudit.isEntryName(name)) {
                    names.add(name);
                }
            }
            names.sort(ENTRY_ORDER);
            return names;
        }

        void addEntries(List<Entry> entries) {
            for (String name : getEntryNames()) {
                entries.add(new Entry(this, name, state.getChildNode(name)));
            }
        }
    }

    private static final class Entry {
        final History history;
        final String name;
        final NodeState state;

        Entry(History history, String name, NodeState state) {
            this.history = history;
            this.name = name;
            this.state = state;
        }

        Object[] values() {
            return new Object[] {
                    history.path, getString(state, PROP_OPERATION), getDate(state, PROP_TIME),
                    getString(state, PROP_USER), getString(state, PROP_DIFF), getString(state, PROP_THREAD), name,
                    state.getBoolean(PROP_REINDEX_REQUESTED), getString(state, PROP_ORIGIN),
                    getString(state, PROP_COMMIT_PATH), history.deleted};
        }

        boolean matches(String term) {
            if (term.isEmpty()) {
                return true;
            }
            for (Object value : values()) {
                if (value instanceof String && ((String) value).toLowerCase(Locale.ENGLISH).contains(term)) {
                    return true;
                }
            }
            return false;
        }
    }
}
