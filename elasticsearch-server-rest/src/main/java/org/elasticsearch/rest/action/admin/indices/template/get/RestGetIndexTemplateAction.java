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

package org.elasticsearch.rest.action.admin.indices.template.get;

import org.elasticsearch.rest.XContentRestResponse;
import org.elasticsearch.rest.XContentThrowableRestResponse;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.state.ClusterStateRequest;
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponse;
import org.elasticsearch.client.ClusterAdminClient;
import org.elasticsearch.client.AdminRequests;
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.common.compress.CompressedString;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsFilter;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.rest.*;
import org.elasticsearch.rest.action.support.RestXContentBuilder;

import java.io.IOException;
import java.util.Map;

import static org.elasticsearch.rest.RestRequest.Method.GET;
import static org.elasticsearch.rest.RestStatus.OK;

/**
 *
 */
public class RestGetIndexTemplateAction extends BaseRestHandler<ClusterAdminClient> {

    private final SettingsFilter settingsFilter;

    @Inject
    public RestGetIndexTemplateAction(Settings settings, ClusterAdminClient client, RestController controller,
                                      SettingsFilter settingsFilter) {
        super(settings, client);
        this.settingsFilter = settingsFilter;

        controller.registerHandler(GET, "/_template/{name}", this);
    }

    @Override
    public void handleRequest(final RestRequest request, final RestChannel channel) {
        ClusterStateRequest clusterStateRequest = AdminRequests.clusterStateRequest()
                .filterRoutingTable(true)
                .filterNodes(true)
                .filteredIndexTemplates(request.param("name"))
                .filteredIndices("_na");

        clusterStateRequest.listenerThreaded(false);

        client.state(clusterStateRequest, new ActionListener<ClusterStateResponse>() {
            @Override
            public void onResponse(ClusterStateResponse response) {
                try {
                    MetaData metaData = response.state().metaData();
                    XContentBuilder builder = RestXContentBuilder.restContentBuilder(request);
                    builder.startObject();

                    for (IndexTemplateMetaData indexMetaData : metaData.templates().values()) {
                        builder.startObject(indexMetaData.name(), XContentBuilder.FieldCaseConversion.NONE);

                        builder.field("template", indexMetaData.template());
                        builder.field("order", indexMetaData.order());

                        builder.startObject("settings");
                        Settings settings = settingsFilter.filterSettings(indexMetaData.settings());
                        for (Map.Entry<String, String> entry : settings.getAsMap().entrySet()) {
                            builder.field(entry.getKey(), entry.getValue());
                        }
                        builder.endObject();

                        builder.startObject("mappings");
                        for (Map.Entry<String, CompressedString> entry : indexMetaData.mappings().entrySet()) {
                            byte[] mappingSource = entry.getValue().uncompressed();
                            XContentParser parser = XContentFactory.xContent(mappingSource).createParser(mappingSource);
                            Map<String, Object> mapping = parser.map();
                            if (mapping.size() == 1 && mapping.containsKey(entry.getKey())) {
                                // the type name is the root value, reduce it
                                mapping = (Map<String, Object>) mapping.get(entry.getKey());
                            }
                            builder.field(entry.getKey());
                            builder.map(mapping);
                        }
                        builder.endObject();

                        builder.endObject();
                    }

                    builder.endObject();

                    channel.sendResponse(new XContentRestResponse(request, OK, builder));
                } catch (Exception e) {
                    onFailure(e);
                }
            }

            @Override
            public void onFailure(Throwable e) {
                try {
                    channel.sendResponse(new XContentThrowableRestResponse(request, e));
                } catch (IOException e1) {
                    logger.error("Failed to send failure response", e1);
                }
            }
        });
    }
}
