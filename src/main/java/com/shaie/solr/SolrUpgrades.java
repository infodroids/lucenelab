package com.shaie.solr;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.apache.curator.test.TestingServer;
import org.apache.lucene.util.IOUtils;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.cloud.ZkController;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.ShardParams;
import org.apache.solr.common.params.UpdateParams;
import org.apache.zookeeper.KeeperException;

import com.carrotsearch.ant.tasks.junit4.dependencies.com.google.common.base.Throwables;
import com.google.common.io.Files;
import com.shaie.solr.solrj.CollectionAdminHelper;
import com.shaie.utils.FileUtils;
import com.shaie.utils.Waiter;

public class SolrUpgrades {

    private static final String COLLECTION_NAME = "mycollection";
    private static final String SOLRXML_LOCATION_PROP_NAME = "solr.solrxml.location";
    private static final String SOLRXML_LOCATION_PROP_VALUE = "zookeeper";
    private static final String ZK_HOST_PROP_NAME = "zkHost";
    private static final String CONFIG_NAME = "upgrades";

    private static void waitForKeyPress() {
        System.out.println("Press any key to continue...");
        try {
            System.in.read();
        } catch (IOException e) {
            Throwables.propagate(e);
        }
    }

    private static TestingServer startZooKeeper(File workDir) throws Exception {
        final File zkWorkDir = new File(workDir, "zookeeper");
        final TestingServer server = new TestingServer(-1, zkWorkDir, true);
        System.setProperty(ZK_HOST_PROP_NAME, server.getConnectString());
        return server;
    }

    /** Uploads configuration files and solr.xml to ZooKeeper. */
    private static void uploadConfigToZk(String connectString) throws IOException, KeeperException,
            InterruptedException {
        final SolrZkClient zkClient = new SolrZkClient(connectString, 120000);
        try {
            final File confDir = FileUtils.getFileResource("solr/coreconf");
            ZkController.uploadConfigDir(zkClient, confDir, CONFIG_NAME);
            zkClient.makePath("/solr.xml", FileUtils.getFileResource("solr/solr.xml"), false, true);
            System.setProperty(SOLRXML_LOCATION_PROP_NAME, SOLRXML_LOCATION_PROP_VALUE);
        } finally {
            IOUtils.close(zkClient);
        }
    }

    private static void exitAndCleanup(File workDir) throws IOException {
        System.out.println("Deleting " + workDir);
        FileUtils.deleteDirectory(workDir);
        System.exit(0);
    }

    private static void waitForAllActive(final String collection, ZkStateReader zkStateReader, long timeoutSeconds) {
        final CollectionsStateHelper collectionsStateHelper = new CollectionsStateHelper(
                zkStateReader.getClusterState());
        Waiter.waitFor(new Waiter.Wait() {
            @Override
            public boolean isSatisfied() {
                boolean result = collectionsStateHelper.isCollectionFullyActive(collection);
                if (!result) {
                    System.out.println("--- Not all slices and replicas of collection [" + collection
                            + "] are active: all_slices=" + collectionsStateHelper.getSlices(collection)
                            + ", active_slices=" + collectionsStateHelper.getActiveSlices(collection));
                }
                return result;
            }
        }, timeoutSeconds, TimeUnit.SECONDS, 500, TimeUnit.MILLISECONDS);
    }

    private static void index_doc_to_two_nodes_and_verify(CloudSolrClient solrClient) throws SolrServerException,
            IOException {
        System.out.println("+++ adding document '1'");
        SolrInputDocument doc = new SolrInputDocument();
        doc.setField("id", "1");
        UpdateRequest docUpdate = new UpdateRequest();
        docUpdate.add(doc);
        docUpdate.setWaitSearcher(true);
        docUpdate.setParam(UpdateParams.SOFT_COMMIT, Boolean.TRUE.toString());
        docUpdate.process(solrClient);

        System.out.println("+++ searching any replica");
        SolrQuery query = new SolrQuery();
        query.setQuery("*:*");
        QueryResponse rsp = solrClient.query(query);
        System.out.println("  +++ numResults: " + rsp.getResults().getNumFound());
        System.out.println("  +++ full response: " + rsp);

        // search each replica
        CollectionsStateHelper csh = new CollectionsStateHelper(solrClient.getZkStateReader().getClusterState());
        for (Slice slice : csh.getSlices(COLLECTION_NAME)) {
            for (Replica replica : slice.getReplicas()) {
                System.out.println(replica);
                final String baseUrl = (String) replica.get(ZkStateReader.BASE_URL_PROP);
                System.out.println(baseUrl);
                System.out.println("+++ searching replica [" + replica.getName() + "] at Url [" + baseUrl + "]");
                query = new SolrQuery();
                query.setQuery("*:*");
                query.set(ShardParams.SHARDS, baseUrl + "/" + COLLECTION_NAME);
                System.out.println(query);
                rsp = solrClient.query(query);
                System.out.println("  +++ numResults: " + rsp.getResults().getNumFound());
                System.out.println("  +++ full response: " + rsp);
            }
        }
    }

    public static void main(String[] args) throws Exception {

        final File workDir = Files.createTempDir();
        final TestingServer zkServer = startZooKeeper(workDir);
        final CloudSolrClient solrClient = new CloudSolrClient(zkServer.getConnectString());
        final CollectionAdminHelper collectionAdminHelper = new CollectionAdminHelper(solrClient);
        final MiniSolrCloudCluster solrCluster = new MiniSolrCloudCluster(workDir);

        try {
            uploadConfigToZk(zkServer.getConnectString());

            solrCluster.startSolrNodes("node1", "node2");

            solrClient.connect();

            collectionAdminHelper.createCollection(COLLECTION_NAME, 1, 2, CONFIG_NAME);
            solrClient.setDefaultCollection(COLLECTION_NAME);

            waitForAllActive(COLLECTION_NAME, solrClient.getZkStateReader(), 10);

            index_doc_to_two_nodes_and_verify(solrClient);

            waitForKeyPress();
        } finally {
            solrClient.shutdown();
            solrCluster.close();
            zkServer.close();

            exitAndCleanup(workDir);
        }
    }

}