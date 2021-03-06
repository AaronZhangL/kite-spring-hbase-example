/**
 * Copyright 2014 Cloudera Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kitesdk.spring.hbase.example.cluster;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.MiniHBaseCluster;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.master.ServerManager;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.zookeeper.MiniZooKeeperCluster;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.kitesdk.data.DatasetDescriptor;
import org.kitesdk.data.RandomAccessDataset;
import org.kitesdk.data.hbase.HBaseDatasetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An in process mini HBase cluster that can be configured to clean or keep data
 * around across restarts. It also configures the appropriate HBase system
 * tables required by the Kite HBase module.
 */
public class MiniKiteHBaseCluster {

  private static final Logger LOG = LoggerFactory
      .getLogger(MiniKiteHBaseCluster.class);

  private static final String CLASSPATH_PREFIX = "classpath:";

  private final String localFsLocation;
  private final int zkPort;
  private final boolean clean;

  private Configuration config;

  private MiniDFSCluster dfsCluster;
  private MiniZooKeeperCluster zkCluster;
  private MiniHBaseCluster hbaseCluster;

  private HBaseDatasetRepository repo;

  /**
   * Construct the MiniKiteHBaseCluster
   * 
   * @param localFsLocation
   *          The location on the local filesystem where the HDFS filesystem
   *          blocks and metadata are stored.
   * @param zkPort
   *          The zookeeper port
   * @param clean
   *          True if we want any old filesystem to be removed, and we want to
   *          start fresh. Otherwise, false.
   */
  public MiniKiteHBaseCluster(String localFsLocation, int zkPort, boolean clean) {
    this.localFsLocation = localFsLocation;
    this.zkPort = zkPort;
    this.clean = clean;
  }

  /**
   * Startup the mini cluster
   * 
   * @throws IOException
   * @throws InterruptedException
   */
  public void startup() throws IOException, InterruptedException {

    // Initialize the Hadoop config we'll use
    config = new Configuration();

    // If clean, then remove the localFsLocation so we can start fresh.
    if (clean) {
      LOG.info("Cleaning cluster data at: " + localFsLocation
          + " and starting fresh.");
      File file = new File(localFsLocation);
      file.delete();
    }
    String dfsLocation = localFsLocation + "/dfs";
    String zkLocation = localFsLocation + "/zk";

    // If the localFsLocation exists (it wasn't cleaned), then we don't want to
    // format since it probably has the filesystem initialized.
    boolean format = true;
    File f = new File(dfsLocation);
    if (f.exists() && f.isDirectory()) {
      format = false;
    }

    // Start a 1 namenode, 1 datanode mini DFS cluster
    config.set("hdfs.minidfs.basedir", dfsLocation);
    dfsCluster = new MiniDFSCluster.Builder(config).format(format)
        .numDataNodes(1).manageDataDfsDirs(true).manageNameDfsDirs(true)
        .build();
    dfsCluster.waitClusterUp();
    FileSystem fs = dfsCluster.getFileSystem();

    // Start a 1 node ZK Cluster
    zkCluster = new MiniZooKeeperCluster(config);
    zkCluster.setDefaultClientPort(zkPort);
    int clientPort = zkCluster.startup(new File(zkLocation), 1);
    config.set(HConstants.ZOOKEEPER_CLIENT_PORT, Integer.toString(clientPort));

    // Initialize HDFS path configs required by HBase
    Path hbaseDir = new Path(fs.makeQualified(fs.getHomeDirectory()), "hbase");
    FSUtils.setRootDir(config, hbaseDir);
    fs.mkdirs(hbaseDir);
    config.set("fs.defaultFS", fs.getUri().toString());
    config.set("fs.default.name", fs.getUri().toString());
    FSUtils.setVersion(fs, hbaseDir);

    // These settings will make the server waits until this exact number of
    // regions servers are connected.
    int numMasters = 1;
    int numSlaves = 1;
    if (config.getInt(ServerManager.WAIT_ON_REGIONSERVERS_MINTOSTART, -1) == -1) {
      config.setInt(ServerManager.WAIT_ON_REGIONSERVERS_MINTOSTART, numSlaves);
    }
    if (config.getInt(ServerManager.WAIT_ON_REGIONSERVERS_MAXTOSTART, -1) == -1) {
      config.setInt(ServerManager.WAIT_ON_REGIONSERVERS_MAXTOSTART, numSlaves);
    }

    // Start the HBase cluster
    hbaseCluster = new MiniHBaseCluster(config, numMasters, numSlaves, null,
        null);
    // Don't leave here till we've done a successful scan of the hbase:meta
    HTable t = new HTable(config, ".META.");
    ResultScanner s = t.getScanner(new Scan());
    while (s.next() != null) {
      continue;
    }
    s.close();
    t.close();

    // Initialize the HBase cluster with the Kite required managed_schemas table
    // if it doesn't exist.
    HBaseAdmin admin = new HBaseAdmin(config);
    try {
      if (!admin.tableExists("managed_schemas")) {
        HTableDescriptor desc = new HTableDescriptor("managed_schemas");
        desc.addFamily(new HColumnDescriptor("meta"));
        desc.addFamily(new HColumnDescriptor("schema"));
        desc.addFamily(new HColumnDescriptor("_s"));
        admin.createTable(desc);
      }
    } finally {
      admin.close();
    }
    repo = new HBaseDatasetRepository.Builder().configuration(config).build();
  }

  /**
   * Shutdown the mini cluster, and set member variables to null
   * 
   * @throws IOException
   */
  public void shutdown() throws IOException {
    // unset the configuration for MIN and MAX RS to start
    config.setInt(ServerManager.WAIT_ON_REGIONSERVERS_MINTOSTART, -1);
    config.setInt(ServerManager.WAIT_ON_REGIONSERVERS_MAXTOSTART, -1);
    if (hbaseCluster != null) {
      hbaseCluster.shutdown();
      // Wait till hbase is down before going on to shutdown zk.
      this.hbaseCluster.waitUntilShutDown();
      this.hbaseCluster = null;
    }

    zkCluster.shutdown();
    zkCluster = null;

    dfsCluster.shutdown();
    dfsCluster = null;
    repo = null;
  }

  /**
   * Create the HBase datasets in the map of dataset names to schema files
   * 
   * @param datasetNameSchemaMap
   *          A map of dataset names to the Avro schema files that we want to
   *          create. The schema files are a location, which can be a location
   *          on the classpath, represented with a "classpath:/" prefix.
   * @return THe list of created datasets.
   * @throws URISyntaxException
   * @throws IOException
   */
  public List<RandomAccessDataset<?>> createOrUpdateDatasets(
      Map<String, String> datasetNameSchemaMap) throws URISyntaxException,
      IOException {
    List<RandomAccessDataset<?>> datasets = new ArrayList<RandomAccessDataset<?>>();
    for (Entry<String, String> entry : datasetNameSchemaMap.entrySet()) {
      String datasetName = entry.getKey();
      String schemaLocation = entry.getValue();
      File schemaFile;
      if (schemaLocation.startsWith(CLASSPATH_PREFIX)) {
        schemaLocation = schemaLocation.substring(CLASSPATH_PREFIX.length());
        schemaFile = new File(MiniKiteHBaseCluster.class.getClassLoader()
            .getResource(schemaLocation).toURI());
      } else {
        schemaFile = new File(schemaLocation);
      }
      DatasetDescriptor desc = new DatasetDescriptor.Builder().schema(
          schemaFile).build();

      if (!repo.exists(datasetName)) {
        datasets.add(repo.create(datasetName, desc));
      } else {
        datasets.add(repo.update(datasetName, desc));
      }
    }
    return datasets;
  }
}
