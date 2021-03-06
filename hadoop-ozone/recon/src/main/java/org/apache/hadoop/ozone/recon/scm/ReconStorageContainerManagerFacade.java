/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.recon.scm;

import static org.apache.hadoop.hdds.recon.ReconConfigKeys.RECON_SCM_CONFIG_PREFIX;
import static org.apache.hadoop.hdds.scm.server.StorageContainerManager.buildRpcServerStartMessage;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


import com.google.inject.Inject;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.scm.block.BlockManager;
import org.apache.hadoop.hdds.scm.container.CloseContainerEventHandler;
import org.apache.hadoop.hdds.scm.container.ContainerActionsHandler;
import org.apache.hadoop.hdds.scm.container.ContainerManager;
import org.apache.hadoop.hdds.scm.container.ContainerReportHandler;
import org.apache.hadoop.hdds.scm.container.IncrementalContainerReportHandler;
import org.apache.hadoop.hdds.scm.container.ReplicationManager;
import org.apache.hadoop.hdds.scm.events.SCMEvents;
import org.apache.hadoop.hdds.scm.net.NetworkTopology;
import org.apache.hadoop.hdds.scm.net.NetworkTopologyImpl;
import org.apache.hadoop.hdds.scm.node.DeadNodeHandler;
import org.apache.hadoop.hdds.scm.node.NodeManager;
import org.apache.hadoop.hdds.scm.node.NodeReportHandler;
import org.apache.hadoop.hdds.scm.node.StaleNodeHandler;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline;
import org.apache.hadoop.hdds.scm.pipeline.PipelineActionHandler;
import org.apache.hadoop.hdds.scm.pipeline.PipelineManager;
import org.apache.hadoop.hdds.scm.safemode.SafeModeManager;
import org.apache.hadoop.hdds.scm.server.SCMStorageConfig;
import org.apache.hadoop.hdds.scm.server.OzoneStorageContainerManager;
import org.apache.hadoop.hdds.server.events.EventQueue;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.ozone.recon.fsck.MissingContainerTask;
import org.apache.hadoop.ozone.recon.spi.StorageContainerServiceProvider;
import org.hadoop.ozone.recon.schema.tables.daos.MissingContainersDao;
import org.hadoop.ozone.recon.schema.tables.daos.ReconTaskStatusDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recon's 'lite' version of SCM.
 */
public class ReconStorageContainerManagerFacade
    implements OzoneStorageContainerManager {

  private static final Logger LOG = LoggerFactory
      .getLogger(ReconStorageContainerManagerFacade.class);

  private final OzoneConfiguration ozoneConfiguration;
  private final ReconDatanodeProtocolServer datanodeProtocolServer;
  private final EventQueue eventQueue;
  private final SCMStorageConfig scmStorageConfig;

  private ReconNodeManager nodeManager;
  private ReconPipelineManager pipelineManager;
  private ContainerManager containerManager;
  private NetworkTopology clusterMap;
  private StorageContainerServiceProvider scmServiceProvider;
  private Set<ReconScmTask> reconScmTasks = new HashSet<>();

  @Inject
  public ReconStorageContainerManagerFacade(OzoneConfiguration conf,
      StorageContainerServiceProvider scmServiceProvider,
      MissingContainersDao missingContainersDao,
      ReconTaskStatusDao reconTaskStatusDao)
      throws IOException {
    this.eventQueue = new EventQueue();
    eventQueue.setSilent(true);
    this.ozoneConfiguration = getReconScmConfiguration(conf);
    this.scmStorageConfig = new ReconStorageConfig(conf);
    this.clusterMap = new NetworkTopologyImpl(conf);
    this.nodeManager =
        new ReconNodeManager(conf, scmStorageConfig, eventQueue, clusterMap);
    this.datanodeProtocolServer = new ReconDatanodeProtocolServer(
        conf, this, eventQueue);
    this.pipelineManager =
        new ReconPipelineManager(conf, nodeManager, eventQueue);
    this.containerManager = new ReconContainerManager(conf, pipelineManager,
        scmServiceProvider);
    this.scmServiceProvider = scmServiceProvider;

    NodeReportHandler nodeReportHandler =
        new NodeReportHandler(nodeManager);

    SafeModeManager safeModeManager = new ReconSafeModeManager();
    ReconPipelineReportHandler pipelineReportHandler =
        new ReconPipelineReportHandler(
            safeModeManager, pipelineManager, conf, scmServiceProvider);

    PipelineActionHandler pipelineActionHandler =
        new PipelineActionHandler(pipelineManager, conf);

    StaleNodeHandler staleNodeHandler =
        new StaleNodeHandler(nodeManager, pipelineManager, conf);
    DeadNodeHandler deadNodeHandler = new DeadNodeHandler(nodeManager,
        pipelineManager, containerManager);

    ContainerReportHandler containerReportHandler =
        new ReconContainerReportHandler(nodeManager, containerManager);

    IncrementalContainerReportHandler icrHandler =
        new ReconIncrementalContainerReportHandler(nodeManager,
            containerManager);
    CloseContainerEventHandler closeContainerHandler =
        new CloseContainerEventHandler(pipelineManager, containerManager);
    ContainerActionsHandler actionsHandler = new ContainerActionsHandler();
    ReconNewNodeHandler newNodeHandler = new ReconNewNodeHandler(nodeManager);

    eventQueue.addHandler(SCMEvents.NODE_REPORT, nodeReportHandler);
    eventQueue.addHandler(SCMEvents.PIPELINE_REPORT, pipelineReportHandler);
    eventQueue.addHandler(SCMEvents.PIPELINE_ACTIONS, pipelineActionHandler);
    eventQueue.addHandler(SCMEvents.STALE_NODE, staleNodeHandler);
    eventQueue.addHandler(SCMEvents.DEAD_NODE, deadNodeHandler);
    eventQueue.addHandler(SCMEvents.CONTAINER_REPORT, containerReportHandler);
    eventQueue.addHandler(SCMEvents.INCREMENTAL_CONTAINER_REPORT, icrHandler);
    eventQueue.addHandler(SCMEvents.CONTAINER_ACTIONS, actionsHandler);
    eventQueue.addHandler(SCMEvents.CLOSE_CONTAINER, closeContainerHandler);
    eventQueue.addHandler(SCMEvents.NEW_NODE, newNodeHandler);

    reconScmTasks.add(new PipelineSyncTask(
        this,
        scmServiceProvider,
        reconTaskStatusDao));
    reconScmTasks.add(new MissingContainerTask(
        this,
        reconTaskStatusDao,
        missingContainersDao));
    reconScmTasks.forEach(ReconScmTask::register);
  }

  /**
   *  For every config key which is prefixed by 'recon.scm', create a new
   *  config key without the prefix keeping the same value.
   *  For example, if recon.scm.a.b. = xyz, we add a new config like
   *  a.b.c = xyz. This is done to override Recon's passive SCM configs if
   *  needed.
   * @param configuration configuration object.
   * @return same configuration object with possible added elements.
   */
  private OzoneConfiguration getReconScmConfiguration(
      OzoneConfiguration configuration) {
    OzoneConfiguration reconScmConfiguration =
        new OzoneConfiguration(configuration);
    Map<String, String> reconScmConfigs =
        configuration.getPropsWithPrefix(RECON_SCM_CONFIG_PREFIX);
    for (Map.Entry<String, String> entry : reconScmConfigs.entrySet()) {
      reconScmConfiguration.set(entry.getKey(), entry.getValue());
    }
    return reconScmConfiguration;
  }

  /**
   * Start the Recon SCM subsystems.
   */
  public void start() {
    if (LOG.isInfoEnabled()) {
      LOG.info(buildRpcServerStartMessage(
          "Recon ScmDatanodeProtocol RPC server",
          getDatanodeProtocolServer().getDatanodeRpcAddress()));
    }
    initializePipelinesFromScm();
    getDatanodeProtocolServer().start();
    this.reconScmTasks.forEach(ReconScmTask::start);
  }

  /**
   * Wait until service has completed shutdown.
   */
  public void join() {
    try {
      getDatanodeProtocolServer().join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.info("Interrupted during StorageContainerManager join.");
    }
  }

  /**
   * Stop the Recon SCM subsystems.
   */
  public void stop() {
    getDatanodeProtocolServer().stop();
    reconScmTasks.forEach(ReconScmTask::stop);
    try {
      LOG.info("Stopping SCM Event Queue.");
      eventQueue.close();
    } catch (Exception ex) {
      LOG.error("SCM Event Queue stop failed", ex);
    }
    IOUtils.cleanupWithLogger(LOG, nodeManager);
    IOUtils.cleanupWithLogger(LOG, containerManager);
    IOUtils.cleanupWithLogger(LOG, pipelineManager);
  }

  public ReconDatanodeProtocolServer getDatanodeProtocolServer() {
    return datanodeProtocolServer;
  }

  private void initializePipelinesFromScm() {
    try {
      List<Pipeline> pipelinesFromScm = scmServiceProvider.getPipelines();
      LOG.info("Obtained {} pipelines from SCM.", pipelinesFromScm.size());
      pipelineManager.initializePipelines(pipelinesFromScm);
    } catch (IOException ioEx) {
      LOG.error("Exception encountered while getting pipelines from SCM.",
          ioEx);
    }
  }

  @Override
  public NodeManager getScmNodeManager() {
    return nodeManager;
  }

  @Override
  public BlockManager getScmBlockManager() {
    return null;
  }

  @Override
  public PipelineManager getPipelineManager() {
    return pipelineManager;
  }

  @Override
  public ContainerManager getContainerManager() {
    return containerManager;
  }

  @Override
  public ReplicationManager getReplicationManager() {
    return null;
  }

  public EventQueue getEventQueue() {
    return eventQueue;
  }
}
