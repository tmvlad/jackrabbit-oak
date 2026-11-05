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

import static org.apache.jackrabbit.oak.plugins.index.IndexConstants.ASYNC_PROPERTY_NAME;
import static org.apache.jackrabbit.oak.plugins.index.IndexConstants.INDEX_DEFINITIONS_NAME;
import static org.apache.jackrabbit.oak.plugins.index.IndexConstants.REINDEX_COUNT;
import static org.apache.jackrabbit.oak.plugins.index.IndexConstants.REINDEX_PROPERTY_NAME;
import static org.apache.jackrabbit.oak.plugins.index.IndexConstants.TYPE_DISABLED;
import static org.apache.jackrabbit.oak.plugins.index.IndexConstants.TYPE_PROPERTY_NAME;
import static org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState.EMPTY_NODE;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.commons.PathUtils;
import org.apache.jackrabbit.oak.json.JsopDiff;
import org.apache.jackrabbit.oak.plugins.index.importer.AsyncLaneSwitcher;
import org.apache.jackrabbit.oak.plugins.index.importer.IndexImporter;
import org.apache.jackrabbit.oak.plugins.memory.PropertyStates;
import org.apache.jackrabbit.oak.spi.commit.CommitHook;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry;
import org.apache.jackrabbit.oak.spi.state.DefaultNodeStateDiff;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStateUtils;
import org.apache.jackrabbit.util.ISO8601;

/**
 * Records changes of index definitions in the repository (in the same commit), so
 * that it is possible to find out when, by whom and how an index definition was
 * changed. The entries can be read with {@link IndexDefinitionAuditMBean}.
 * <p>
 * The history of an index is stored in the hidden node {@code /oak:index/:audit},
 * which mirrors the index path, for example
 * {@code /oak:index/:audit/oak:index/damAssetLucene-12/20261013T1405Z.0}. Entries
 * are named after the minute (UTC) of the change and a sequence number within
 * that minute. When an index is removed, its history is moved to
 * {@code /oak:index/:audit/:deleted/<index path>}, and moved back if an index with
 * the same path is created again.
 *
 * @see <a href="https://issues.apache.org/jira/browse/OAK-12458">OAK-12458</a>
 */
public final class IndexDefinitionAudit {

    /**
     * System property to enable the audit for all index updates in this JVM, including
     * tools (e.g. oak-run) where the feature toggle is not available.
     */
    public static final String ENABLED_PROPERTY = "oak.indexDefinitionAudit";

    /**
     * Commit origin ({@link CommitInfo#getSessionId()}) of the index importer. An import of
     * an existing index rebuilds it, so it is recorded as {@link Operation#REINDEX}.
     */
    public static final String ORIGIN_INDEX_IMPORTER = "IndexImporter";

    static final String AUDIT_NODE_NAME = ":audit";

    static final String DELETED_NODE_NAME = ":deleted";

    static final int MAX_ENTRIES = Integer.getInteger("oak.indexDefinitionAudit.maxEntries", 1000);

    static final int MAX_DELETED = Integer.getInteger("oak.indexDefinitionAudit.maxDeleted", 1000);

    static final int MAX_DIFF_LENGTH = 2048;

    static final String PROP_PATH = "path";
    static final String PROP_OPERATION = "op";
    static final String PROP_USER = "user";
    static final String PROP_ORIGIN = "origin";
    static final String PROP_TIME = "time";
    static final String PROP_THREAD = "thread";
    static final String PROP_COMMIT_PATH = "commitPath";
    static final String PROP_DIFF = "diff";
    static final String PROP_REINDEX_REQUESTED = "reindexRequested";
    static final String PROP_DELETED_AT = "deletedAt";

    private static final DateTimeFormatter MINUTE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm'Z'").withZone(ZoneOffset.UTC);

    private static final Pattern ENTRY_NAME = Pattern.compile("(\\d{8}T\\d{4}Z)\\.(\\d+)");

    static final Comparator<String> ENTRY_ORDER = Comparator
            .comparing((String name) -> entryNameMatcher(name).group(1))
            .thenComparingLong(name -> Long.parseLong(entryNameMatcher(name).group(2)));

    enum Operation { CREATED, MERGED, CHANGED, DELETED, REINDEX, DISABLED, ENABLED }

    /**
     * Property set on index definitions that are the result of merging a product index
     * with a customization (see {@code DiffIndexMerger} and {@code IndexDefMergerUtils}).
     */
    static final String MERGES_PROPERTY = "merges";

    /**
     * Properties that are internal bookkeeping of the index implementation and not part
     * of the definition as configured by the user.
     */
    private static final Set<String> IGNORED_PROPERTIES = Set.of(REINDEX_COUNT, "seed");

    /**
     * Property where the index importer keeps the original async lane while the index
     * is switched to a temporary lane (see {@code AsyncLaneSwitcher}).
     */
    static final String ASYNC_PREVIOUS = "async-previous";

    private final CommitInfo commitInfo;

    private final NodeBuilder rootBuilder;

    private final int maxEntries;

    private final int maxDeleted;

    /**
     * Returns a commit hook that records changes of the index definitions below
     * {@code /oak:index}, for tools that change index definitions without running an
     * index update (for example with {@link org.apache.jackrabbit.oak.spi.commit.EmptyHook}).
     * The origin of the entries is the session id of the {@link CommitInfo} passed to
     * the merge. The hook does nothing unless the audit is enabled with the system
     * property {@link #ENABLED_PROPERTY}.
     *
     * @return the commit hook
     */
    public static CommitHook newCommitHook() {
        if (!Boolean.getBoolean(ENABLED_PROPERTY)) {
            return EmptyHook.INSTANCE;
        }
        return (before, after, info) -> {
            NodeBuilder builder = after.builder();
            new IndexDefinitionAudit(info, builder, MAX_ENTRIES, MAX_DELETED).audit(
                    "/" + INDEX_DEFINITIONS_NAME, before.getChildNode(INDEX_DEFINITIONS_NAME),
                    builder.getChildNode(INDEX_DEFINITIONS_NAME));
            return builder.getNodeState();
        };
    }

    IndexDefinitionAudit(CommitInfo commitInfo, NodeBuilder rootBuilder, int maxEntries, int maxDeleted) {
        this.commitInfo = commitInfo == null ? CommitInfo.EMPTY : commitInfo;
        this.rootBuilder = rootBuilder;
        this.maxEntries = Math.max(1, maxEntries);
        this.maxDeleted = Math.max(1, maxDeleted);
    }

    /**
     * Records all visible changes of the index definitions below the given
     * {@code oak:index} node.
     *
     * @param definitionsPath path of the {@code oak:index} node
     * @param before          state of the {@code oak:index} node before the commit
     * @param definitions     builder of the {@code oak:index} node, including the changes
     *                        done by the commit
     */
    void audit(String definitionsPath, NodeState before, NodeBuilder definitions) {
        if (!before.exists() && !definitions.exists()) {
            return;
        }
        NodeState after = definitions.getNodeState();
        Set<String> changed = new TreeSet<>();
        after.compareAgainstBaseState(before, new DefaultNodeStateDiff() {
            @Override
            public boolean childNodeAdded(String name, NodeState after) {
                return collect(name);
            }

            @Override
            public boolean childNodeChanged(String name, NodeState before, NodeState after) {
                return collect(name);
            }

            @Override
            public boolean childNodeDeleted(String name, NodeState before) {
                return collect(name);
            }

            private boolean collect(String name) {
                if (!NodeStateUtils.isHidden(name)) {
                    changed.add(name);
                }
                return true;
            }
        });
        for (String name : changed) {
            audit(PathUtils.concat(definitionsPath, name), before.getChildNode(name), after.getChildNode(name));
        }
    }

    private void audit(String indexPath, NodeState before, NodeState after) {
        if (!before.hasProperty(TYPE_PROPERTY_NAME) && !after.hasProperty(TYPE_PROPERTY_NAME)) {
            // not an index definition (e.g. node type definitions use the name oak:index)
            return;
        }
        NodeState visibleBefore = cloneDefinition(before);
        NodeState visibleAfter = cloneDefinition(after);
        boolean reindexRequested = after.getBoolean(REINDEX_PROPERTY_NAME) && !before.getBoolean(REINDEX_PROPERTY_NAME);
        Operation op;
        String diff;
        if (!before.exists()) {
            op = visibleAfter.hasProperty(MERGES_PROPERTY) ? Operation.MERGED : Operation.CREATED;
            diff = JsopDiff.diffToJsop(EMPTY_NODE, visibleAfter);
        } else if (!after.exists()) {
            op = Operation.DELETED;
            diff = JsopDiff.diffToJsop(EMPTY_NODE, visibleBefore);
        } else {
            diff = JsopDiff.diffToJsop(visibleBefore, visibleAfter);
            if (diff.isEmpty() && !isImport()) {
                // only hidden content (index data) or internal state changed
                return;
            }
            op = getOperation(visibleBefore, visibleAfter, reindexRequested);
        }
        if (diff.length() > MAX_DIFF_LENGTH) {
            diff = diff.substring(0, MAX_DIFF_LENGTH) + "...";
        }
        String user = commitInfo.getUserId();
        String origin = commitInfo.getSessionId();
        String thread = Thread.currentThread().getName();
        boolean flagReindexRequested = reindexRequested && op != Operation.REINDEX;
        NodeBuilder globalDefinitions = rootBuilder.getChildNode(INDEX_DEFINITIONS_NAME);
        if (!globalDefinitions.exists()) {
            // the global oak:index node was removed: nowhere to store the entry
            return;
        }
        NodeBuilder audit = globalDefinitions.child(AUDIT_NODE_NAME);
        long now = System.currentTimeMillis();
        if (!before.exists()) {
            restoreDeletedHistory(audit, indexPath);
        }
        NodeBuilder history = childBuilder(audit, indexPath);
        NodeBuilder entry = history.child(newEntryName(history, MINUTE_FORMAT.format(Instant.ofEpochMilli(now))));
        entry.setProperty(PROP_PATH, indexPath);
        entry.setProperty(PROP_OPERATION, op.name());
        entry.setProperty(PROP_USER, user);
        entry.setProperty(PROP_ORIGIN, origin);
        entry.setProperty(PROP_TIME, formatDate(now), Type.DATE);
        entry.setProperty(PROP_THREAD, thread);
        entry.setProperty(PROP_COMMIT_PATH, commitInfo.getPath());
        entry.setProperty(PROP_DIFF, diff);
        if (flagReindexRequested) {
            entry.setProperty(PROP_REINDEX_REQUESTED, true);
        }
        removeOldEntries(history);
        if (op == Operation.DELETED) {
            moveToDeleted(audit, indexPath, now);
        }
    }

    private boolean isImport() {
        return ORIGIN_INDEX_IMPORTER.equals(commitInfo.getSessionId());
    }

    private Operation getOperation(NodeState before, NodeState after, boolean reindexRequested) {
        if (isImport()) {
            return Operation.REINDEX;
        }
        boolean disabledBefore = TYPE_DISABLED.equals(before.getString(TYPE_PROPERTY_NAME));
        boolean disabledAfter = TYPE_DISABLED.equals(after.getString(TYPE_PROPERTY_NAME));
        if (disabledAfter && !disabledBefore) {
            return Operation.DISABLED;
        }
        if (disabledBefore && !disabledAfter) {
            return Operation.ENABLED;
        }
        if (reindexRequested && withoutReindexFlag(before).equals(withoutReindexFlag(after))) {
            return Operation.REINDEX;
        }
        return Operation.CHANGED;
    }

    private static NodeState withoutReindexFlag(NodeState definition) {
        NodeBuilder builder = definition.builder();
        builder.removeProperty(REINDEX_PROPERTY_NAME);
        return builder.getNodeState();
    }

    /**
     * Moves the history of a removed index to the deleted indexes, merging it with
     * the history of a previously removed index with the same path.
     */
    private void moveToDeleted(NodeBuilder audit, String indexPath, long now) {
        NodeBuilder deleted = audit.child(DELETED_NODE_NAME);
        NodeBuilder target = childBuilder(deleted, indexPath);
        moveEntries(childBuilder(audit, indexPath), target);
        removeEmptyNodes(audit, indexPath);
        target.setProperty(PROP_DELETED_AT, formatDate(now), Type.DATE);
        removeOldDeleted(deleted);
    }

    /**
     * Moves the history of a previously removed index with the same path back.
     */
    private void restoreDeletedHistory(NodeBuilder audit, String indexPath) {
        NodeBuilder deleted = audit.getChildNode(DELETED_NODE_NAME);
        NodeBuilder source = getBuilder(deleted, indexPath);
        if (!source.exists()) {
            return;
        }
        moveEntries(source, childBuilder(audit, indexPath));
        removeEmptyNodes(audit, "/" + DELETED_NODE_NAME + indexPath);
    }

    private void moveEntries(NodeBuilder source, NodeBuilder target) {
        for (String name : getEntryNames(source)) {
            String targetName = target.hasChildNode(name)
                    ? newEntryName(target, entryNameMatcher(name).group(1))
                    : name;
            target.setChildNode(targetName, source.getChildNode(name).getNodeState());
        }
        source.remove();
        removeOldEntries(target);
    }

    private void removeOldEntries(NodeBuilder history) {
        List<String> names = getEntryNames(history);
        for (String name : names.subList(0, Math.max(0, names.size() - maxEntries))) {
            history.getChildNode(name).remove();
        }
    }

    private void removeOldDeleted(NodeBuilder deleted) {
        List<Map.Entry<String, String>> histories = new ArrayList<>();
        collectDeletedHistories(deleted.getNodeState(), "/", histories);
        if (histories.size() <= maxDeleted) {
            return;
        }
        // ISO 8601 dates in UTC sort chronologically
        histories.sort(Map.Entry.comparingByValue());
        for (Map.Entry<String, String> history : histories.subList(0, histories.size() - maxDeleted)) {
            getBuilder(deleted, history.getKey()).remove();
            removeEmptyNodes(deleted, PathUtils.getParentPath(history.getKey()));
        }
    }

    private static void collectDeletedHistories(NodeState state, String path, List<Map.Entry<String, String>> histories) {
        PropertyState deletedAt = state.getProperty(PROP_DELETED_AT);
        if (deletedAt != null) {
            histories.add(Map.entry(path, deletedAt.getValue(Type.DATE)));
        }
        for (ChildNodeEntry child : state.getChildNodeEntries()) {
            if (!ENTRY_NAME.matcher(child.getName()).matches()) {
                collectDeletedHistories(child.getNodeState(), PathUtils.concat(path, child.getName()), histories);
            }
        }
    }

    private static String newEntryName(NodeBuilder history, String minute) {
        long sequence = 0;
        for (String name : history.getChildNodeNames()) {
            Matcher matcher = ENTRY_NAME.matcher(name);
            if (matcher.matches() && matcher.group(1).equals(minute)) {
                sequence = Math.max(sequence, Long.parseLong(matcher.group(2)) + 1);
            }
        }
        return minute + "." + sequence;
    }

    private static List<String> getEntryNames(NodeBuilder history) {
        List<String> names = new ArrayList<>();
        for (String name : history.getChildNodeNames()) {
            if (ENTRY_NAME.matcher(name).matches()) {
                names.add(name);
            }
        }
        names.sort(ENTRY_ORDER);
        return names;
    }

    static boolean isEntryName(String name) {
        return ENTRY_NAME.matcher(name).matches();
    }

    private static Matcher entryNameMatcher(String name) {
        Matcher matcher = ENTRY_NAME.matcher(name);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(name);
        }
        return matcher;
    }

    private static NodeBuilder childBuilder(NodeBuilder builder, String path) {
        for (String name : PathUtils.elements(path)) {
            builder = builder.child(name);
        }
        return builder;
    }

    private static NodeBuilder getBuilder(NodeBuilder builder, String path) {
        for (String name : PathUtils.elements(path)) {
            builder = builder.getChildNode(name);
        }
        return builder;
    }

    /**
     * Removes the node at the given path and its ancestors (up to, excluding, the
     * given base) if they have no child nodes and no properties.
     */
    private static void removeEmptyNodes(NodeBuilder base, String path) {
        while (!PathUtils.denotesRoot(path)) {
            NodeBuilder builder = getBuilder(base, path);
            if (builder.exists() && (builder.getChildNodeCount(1) > 0 || builder.getPropertyCount() > 0)) {
                return;
            }
            builder.remove();
            path = PathUtils.getParentPath(path);
        }
    }

    private static String formatDate(long millis) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(millis);
        return ISO8601.format(cal);
    }

    /**
     * Returns the visible part of the definition, without the internal state of an
     * out-of-band index import (temporary async lane, import state) and without
     * bookkeeping properties of the index implementation.
     */
    private static NodeState cloneDefinition(NodeState state) {
        NodeBuilder builder = EMPTY_NODE.builder();
        copyVisible(state, builder);
        PropertyState previousAsync = builder.getProperty(ASYNC_PREVIOUS);
        if (previousAsync != null) {
            if (AsyncLaneSwitcher.isNone(previousAsync)) {
                builder.removeProperty(ASYNC_PROPERTY_NAME);
            } else {
                builder.setProperty(PropertyStates.createProperty(ASYNC_PROPERTY_NAME,
                        previousAsync.getValue(previousAsync.getType()), previousAsync.getType()));
            }
            builder.removeProperty(ASYNC_PREVIOUS);
        }
        builder.removeProperty(IndexImporter.INDEX_IMPORT_STATE_KEY);
        IGNORED_PROPERTIES.forEach(builder::removeProperty);
        return builder.getNodeState();
    }

    private static void copyVisible(NodeState state, NodeBuilder builder) {
        for (PropertyState property : state.getProperties()) {
            if (!NodeStateUtils.isHidden(property.getName())) {
                builder.setProperty(property);
            }
        }
        for (ChildNodeEntry child : state.getChildNodeEntries()) {
            if (!NodeStateUtils.isHidden(child.getName())) {
                copyVisible(child.getNodeState(), builder.child(child.getName()));
            }
        }
    }
}
