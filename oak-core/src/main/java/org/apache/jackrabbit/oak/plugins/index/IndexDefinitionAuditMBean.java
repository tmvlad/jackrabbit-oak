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

import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.TabularData;

import org.apache.jackrabbit.oak.api.jmx.Description;
import org.apache.jackrabbit.oak.api.jmx.Name;

/**
 * Read access to the audit trail of index definition changes, which is stored
 * in a hidden node and therefore not visible with the JCR API.
 *
 * @see <a href="https://issues.apache.org/jira/browse/OAK-12458">OAK-12458</a>
 */
public interface IndexDefinitionAuditMBean {

    String TYPE = "IndexDefinitionAudit";

    @Description("All indexes with an audit trail, sorted by path. Removed indexes are marked as deleted.")
    TabularData getIndexes() throws OpenDataException;

    @Description("Audit trail of an index (also of a removed index), newest entry first.")
    TabularData getAuditTrail(
            @Name("indexPath")
            @Description("Index path, e.g. /oak:index/damAssetLucene-12")
            String indexPath) throws OpenDataException;

    @Description("Audit entries of all indexes where any field (index path, operation, user, origin, thread, "
            + "diff, ...) contains the given text, ignoring case. Newest entry first.")
    TabularData filter(
            @Name("text")
            @Description("Text to search for, e.g. an index name, a user id, DELETED, or a property name")
            String text) throws OpenDataException;
}
