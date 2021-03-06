/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.test.unit.cluster.routing.allocation;

import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.test.integration.ElasticsearchTestCase;
import org.junit.Test;

import static org.elasticsearch.cluster.ClusterState.newClusterStateBuilder;
import static org.elasticsearch.cluster.metadata.IndexMetaData.newIndexMetaDataBuilder;
import static org.elasticsearch.cluster.metadata.MetaData.newMetaDataBuilder;
import static org.elasticsearch.cluster.node.DiscoveryNodes.newNodesBuilder;
import static org.elasticsearch.cluster.routing.RoutingBuilders.routingTable;
import static org.elasticsearch.cluster.routing.ShardRoutingState.INITIALIZING;
import static org.elasticsearch.common.settings.ImmutableSettings.settingsBuilder;
import static org.elasticsearch.test.unit.cluster.routing.allocation.RoutingAllocationTests.newNode;
import static org.hamcrest.Matchers.equalTo;

/**
 */
public class PreferPrimaryAllocationTests extends ElasticsearchTestCase {

    private final ESLogger logger = Loggers.getLogger(PreferPrimaryAllocationTests.class);

    @Test
    public void testPreferPrimaryAllocationOverReplicas() {
        logger.info("create an allocation with 1 initial recoveries");
        AllocationService strategy = new AllocationService(settingsBuilder()
                .put("cluster.routing.allocation.node_concurrent_recoveries", 1)
                .put("cluster.routing.allocation.node_initial_primaries_recoveries", 1)
                .build());

        logger.info("create several indices with no replicas, and wait till all are allocated");

        MetaData metaData = newMetaDataBuilder()
                .put(newIndexMetaDataBuilder("test1").numberOfShards(10).numberOfReplicas(0))
                .put(newIndexMetaDataBuilder("test2").numberOfShards(10).numberOfReplicas(0))
                .build();

        RoutingTable routingTable = routingTable()
                .addAsNew(metaData.index("test1"))
                .addAsNew(metaData.index("test2"))
                .build();

        ClusterState clusterState = newClusterStateBuilder().metaData(metaData).routingTable(routingTable).build();

        logger.info("adding two nodes and performing rerouting till all are allocated");
        clusterState = newClusterStateBuilder().state(clusterState).nodes(newNodesBuilder().put(newNode("node1")).put(newNode("node2"))).build();
        routingTable = strategy.reroute(clusterState).routingTable();
        clusterState = newClusterStateBuilder().state(clusterState).routingTable(routingTable).build();

        while (!clusterState.routingNodes().shardsWithState(INITIALIZING).isEmpty()) {
            routingTable = strategy.applyStartedShards(clusterState, clusterState.routingNodes().shardsWithState(INITIALIZING)).routingTable();
            clusterState = newClusterStateBuilder().state(clusterState).routingTable(routingTable).build();
        }

        logger.info("increasing the number of replicas to 1, and perform a reroute (to get the replicas allocation going)");
        routingTable = RoutingTable.builder().routingTable(routingTable).updateNumberOfReplicas(1).build();
        metaData = MetaData.newMetaDataBuilder().metaData(clusterState.metaData()).updateNumberOfReplicas(1).build();
        clusterState = newClusterStateBuilder().state(clusterState).routingTable(routingTable).metaData(metaData).build();

        routingTable = strategy.reroute(clusterState).routingTable();
        clusterState = newClusterStateBuilder().state(clusterState).routingTable(routingTable).build();

        logger.info("2 replicas should be initializing now for the existing indices (we throttle to 1)");
        assertThat(clusterState.routingNodes().shardsWithState(INITIALIZING).size(), equalTo(2));

        logger.info("create a new index");
        metaData = newMetaDataBuilder().metaData(clusterState.metaData())
                .put(newIndexMetaDataBuilder("new_index").numberOfShards(4).numberOfReplicas(0))
                .build();

        routingTable = routingTable().routingTable(clusterState.routingTable())
                .addAsNew(metaData.index("new_index"))
                .build();

        clusterState = newClusterStateBuilder().state(clusterState).metaData(metaData).routingTable(routingTable).build();

        logger.info("reroute, verify that primaries for the new index primary shards are allocated");
        routingTable = strategy.reroute(clusterState).routingTable();
        clusterState = newClusterStateBuilder().state(clusterState).routingTable(routingTable).build();

        assertThat(clusterState.routingTable().index("new_index").shardsWithState(INITIALIZING).size(), equalTo(2));
    }
}
