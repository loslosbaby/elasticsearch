/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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

package org.elasticsearch.cluster.routing.allocation;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.RoutingNodes;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.cluster.ESAllocationTestCase;

import static org.elasticsearch.cluster.routing.ShardRoutingState.INITIALIZING;
import static org.elasticsearch.cluster.routing.ShardRoutingState.STARTED;
import static org.elasticsearch.cluster.routing.ShardRoutingState.UNASSIGNED;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 *
 */
public class UpdateNumberOfReplicasTests extends ESAllocationTestCase {
    private final ESLogger logger = Loggers.getLogger(UpdateNumberOfReplicasTests.class);

    public void testUpdateNumberOfReplicas() {
        AllocationService strategy = createAllocationService(Settings.builder().put("cluster.routing.allocation.node_concurrent_recoveries", 10).build());

        logger.info("Building initial routing table");

        MetaData metaData = MetaData.builder()
                .put(IndexMetaData.builder("test").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(1))
                .build();

        RoutingTable routingTable = RoutingTable.builder()
                .addAsNew(metaData.index("test"))
                .build();

        ClusterState clusterState = ClusterState.builder(org.elasticsearch.cluster.ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY)).metaData(metaData).routingTable(routingTable).build();

        assertThat(routingTable.index("test").shards().size(), equalTo(1));
        assertThat(routingTable.index("test").shard(0).size(), equalTo(2));
        assertThat(routingTable.index("test").shard(0).shards().size(), equalTo(2));
        assertThat(routingTable.index("test").shard(0).shards().get(0).state(), equalTo(UNASSIGNED));
        assertThat(routingTable.index("test").shard(0).shards().get(1).state(), equalTo(UNASSIGNED));
        assertThat(routingTable.index("test").shard(0).shards().get(0).currentNodeId(), nullValue());
        assertThat(routingTable.index("test").shard(0).shards().get(1).currentNodeId(), nullValue());


        logger.info("Adding two nodes and performing rerouting");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder().add(newNode("node1")).add(newNode("node2"))).build();

        RoutingTable prevRoutingTable = routingTable;
        routingTable = strategy.reroute(clusterState, "reroute").routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();

        logger.info("Start all the primary shards");
        RoutingNodes routingNodes = clusterState.getRoutingNodes();
        prevRoutingTable = routingTable;
        routingTable = strategy.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING)).routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();

        logger.info("Start all the replica shards");
        routingNodes = clusterState.getRoutingNodes();
        prevRoutingTable = routingTable;
        routingTable = strategy.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING)).routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();
        final String nodeHoldingPrimary = routingTable.index("test").shard(0).primaryShard().currentNodeId();
        final String nodeHoldingReplica = routingTable.index("test").shard(0).replicaShards().get(0).currentNodeId();
        assertThat(nodeHoldingPrimary, not(equalTo(nodeHoldingReplica)));
        assertThat(prevRoutingTable != routingTable, equalTo(true));
        assertThat(routingTable.index("test").shards().size(), equalTo(1));
        assertThat(routingTable.index("test").shard(0).size(), equalTo(2));
        assertThat(routingTable.index("test").shard(0).shards().size(), equalTo(2));
        assertThat(routingTable.index("test").shard(0).primaryShard().state(), equalTo(STARTED));
        assertThat(routingTable.index("test").shard(0).primaryShard().currentNodeId(), equalTo(nodeHoldingPrimary));
        assertThat(routingTable.index("test").shard(0).replicaShards().size(), equalTo(1));
        assertThat(routingTable.index("test").shard(0).replicaShards().get(0).state(), equalTo(STARTED));
        assertThat(routingTable.index("test").shard(0).replicaShards().get(0).currentNodeId(), equalTo(nodeHoldingReplica));


        logger.info("add another replica");
        routingNodes = clusterState.getRoutingNodes();
        prevRoutingTable = routingTable;
        routingTable = RoutingTable.builder(routingTable).updateNumberOfReplicas(2).build();
        metaData = MetaData.builder(clusterState.metaData()).updateNumberOfReplicas(2).build();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).metaData(metaData).build();

        assertThat(clusterState.metaData().index("test").getNumberOfReplicas(), equalTo(2));

        assertThat(prevRoutingTable != routingTable, equalTo(true));
        assertThat(routingTable.index("test").shards().size(), equalTo(1));
        assertThat(routingTable.index("test").shard(0).size(), equalTo(3));
        assertThat(routingTable.index("test").shard(0).primaryShard().state(), equalTo(STARTED));
        assertThat(routingTable.index("test").shard(0).primaryShard().currentNodeId(), equalTo(nodeHoldingPrimary));
        assertThat(routingTable.index("test").shard(0).replicaShards().size(), equalTo(2));
        assertThat(routingTable.index("test").shard(0).replicaShards().get(0).state(), equalTo(STARTED));
        assertThat(routingTable.index("test").shard(0).replicaShards().get(0).currentNodeId(), equalTo(nodeHoldingReplica));
        assertThat(routingTable.index("test").shard(0).replicaShards().get(1).state(), equalTo(UNASSIGNED));

        logger.info("Add another node and start the added replica");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes()).add(newNode("node3"))).build();
        prevRoutingTable = routingTable;
        routingTable = strategy.reroute(clusterState, "reroute").routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();

        assertThat(prevRoutingTable != routingTable, equalTo(true));
        assertThat(routingTable.index("test").shards().size(), equalTo(1));
        assertThat(routingTable.index("test").shard(0).size(), equalTo(3));
        assertThat(routingTable.index("test").shard(0).primaryShard().state(), equalTo(STARTED));
        assertThat(routingTable.index("test").shard(0).primaryShard().currentNodeId(), equalTo(nodeHoldingPrimary));
        assertThat(routingTable.index("test").shard(0).replicaShards().size(), equalTo(2));
        assertThat(routingTable.index("test").shard(0).replicaShardsWithState(STARTED).size(), equalTo(1));
        assertThat(routingTable.index("test").shard(0).replicaShardsWithState(STARTED).get(0).currentNodeId(), equalTo(nodeHoldingReplica));
        assertThat(routingTable.index("test").shard(0).replicaShardsWithState(INITIALIZING).size(), equalTo(1));
        assertThat(routingTable.index("test").shard(0).replicaShardsWithState(INITIALIZING).get(0).currentNodeId(), equalTo("node3"));

        routingNodes = clusterState.getRoutingNodes();
        prevRoutingTable = routingTable;
        routingTable = strategy.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING)).routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();

        assertThat(prevRoutingTable != routingTable, equalTo(true));
        assertThat(routingTable.index("test").shards().size(), equalTo(1));
        assertThat(routingTable.index("test").shard(0).size(), equalTo(3));
        assertThat(routingTable.index("test").shard(0).primaryShard().state(), equalTo(STARTED));
        assertThat(routingTable.index("test").shard(0).primaryShard().currentNodeId(), equalTo(nodeHoldingPrimary));
        assertThat(routingTable.index("test").shard(0).replicaShards().size(), equalTo(2));
        assertThat(routingTable.index("test").shard(0).replicaShardsWithState(STARTED).size(), equalTo(2));
        assertThat(routingTable.index("test").shard(0).replicaShardsWithState(STARTED).get(0).currentNodeId(), anyOf(equalTo(nodeHoldingReplica), equalTo("node3")));
        assertThat(routingTable.index("test").shard(0).replicaShardsWithState(STARTED).get(1).currentNodeId(), anyOf(equalTo(nodeHoldingReplica), equalTo("node3")));

        logger.info("now remove a replica");
        routingNodes = clusterState.getRoutingNodes();
        prevRoutingTable = routingTable;
        routingTable = RoutingTable.builder(routingTable).updateNumberOfReplicas(1).build();
        metaData = MetaData.builder(clusterState.metaData()).updateNumberOfReplicas(1).build();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).metaData(metaData).build();

        assertThat(clusterState.metaData().index("test").getNumberOfReplicas(), equalTo(1));

        assertThat(prevRoutingTable != routingTable, equalTo(true));
        assertThat(routingTable.index("test").shards().size(), equalTo(1));
        assertThat(routingTable.index("test").shard(0).size(), equalTo(2));
        assertThat(routingTable.index("test").shard(0).primaryShard().state(), equalTo(STARTED));
        assertThat(routingTable.index("test").shard(0).primaryShard().currentNodeId(), equalTo(nodeHoldingPrimary));
        assertThat(routingTable.index("test").shard(0).replicaShards().size(), equalTo(1));
        assertThat(routingTable.index("test").shard(0).replicaShards().get(0).state(), equalTo(STARTED));
        assertThat(routingTable.index("test").shard(0).replicaShards().get(0).currentNodeId(), anyOf(equalTo(nodeHoldingReplica), equalTo("node3")));

        logger.info("do a reroute, should remain the same");
        prevRoutingTable = routingTable;
        routingTable = strategy.reroute(clusterState, "reroute").routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();

        assertThat(prevRoutingTable != routingTable, equalTo(false));
    }
}
