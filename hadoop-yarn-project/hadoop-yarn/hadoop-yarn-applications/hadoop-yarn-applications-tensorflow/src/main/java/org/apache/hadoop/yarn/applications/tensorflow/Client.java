/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.applications.tensorflow;

import org.apache.commons.cli.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.yarn.api.ApplicationClientProtocol;
import org.apache.hadoop.yarn.api.protocolrecords.GetNewApplicationResponse;
import org.apache.hadoop.yarn.api.protocolrecords.KillApplicationRequest;
import org.apache.hadoop.yarn.api.records.*;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.client.api.YarnClientApplication;
import org.apache.hadoop.yarn.client.util.YarnClientUtils;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.util.timeline.TimelineUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

@InterfaceAudience.Public
@InterfaceStability.Unstable
public class Client {

  private static final Log LOG = LogFactory.getLog(Client.class);

  // Configuration
  private Configuration conf;
  private YarnClient yarnClient;
  // Application master specific info to register a new Application with RM/ASM
  private String appName = TFYarnConstants.APP_NAME;
  public String getAppName() {
    return  appName;
  }
  // App master priority
  private int amPriority = 0;
  // Queue for App master
  private String amQueue = "";
  // Amt. of memory resource to request for to run the App Master
  private long amMemory = 100;
  // Amt. of virtual core resource to request for to run the App Master
  private int amVCores = 1;

  // Application master jar file
  private String appMasterJar = "";

  private String tfConatinerJar = "";

  private String tfServerPy = "";
  // Main class to invoke application master
  private final String appMasterMainClass;

  private String tfClientPy;

  // Amt of memory to request for container in which shell script will be executed
  private int containerMemory = 10;
  // Amt. of virtual cores to request for container in which shell script will be executed
  private int containerVirtualCores = 1;

  private String nodeLabelExpression = null;

  // log4j.properties file
  // if available, add to local resources and set into classpath
  private String log4jPropFile = "";

  private long attemptFailuresValidityInterval = -1;

  private Vector<CharSequence> containerRetryOptions = new Vector<>(5);

  private String masterAddress;

  private String clusterSpecJsonString = null;

  // Command line options
  private Options opts;

  // Hardcoded path to custom log_properties
  private static final String log4jPath = "log4j.properties";

  private int workerNum;
  private int psNum;

  private TFApplicationRpc appRpc = null;

  /**
   * @param args Command line arguments
   */
  public static void main(String[] args) {
    LOG.info("start main in appmaster!");
    boolean result = false;
    try {
      Client client = new Client();
      LOG.info("Initializing tensorflow client");
      try {
        boolean doRun = client.init(args);
        if (!doRun) {
          System.exit(0);
        }
      } catch (IllegalArgumentException e) {
        System.err.println(e.getLocalizedMessage());
        System.exit(-1);
      }
      result = client.run();
    } catch (Throwable t) {
      LOG.fatal("Error running Client", t);
      System.exit(1);
    }
    if (result) {
      LOG.info("Application completed successfully");
      System.exit(0);
    }
    LOG.error("Application failed to complete successfully");
    System.exit(2);
  }

  /**
   */
  public Client(Configuration conf) throws Exception  {
    this(
      "org.apache.hadoop.yarn.applications.tensorflow.ApplicationMaster",
      conf);
  }

  Client(String appMasterMainClass, Configuration conf) {
    this.conf = conf;
    this.appMasterMainClass = appMasterMainClass;
    yarnClient = YarnClient.createYarnClient();
    yarnClient.init(conf);
    opts = new Options();
    opts.addOption("appname", true, "Application Name. Default value - tensorflow");
    opts.addOption("priority", true, "Application Priority. Default 0");
    opts.addOption("queue", true, "RM Queue in which this application is to be submitted");
    opts.addOption("jar", true, "Jar file containing the application master");
    opts.addOption("master_memory", true, "Amount of memory in MB to be requested to run the application master");
    opts.addOption("master_vcores", true, "Amount of virtual cores to be requested to run the application master");
    opts.addOption("container_memory", true, "Amount of memory in MB to be requested to run a tensorflow worker");
    opts.addOption("container_vcores", true, "Amount of virtual cores to be requested to run a tensorflow worker");
    opts.addOption("log_properties", true, "log4j.properties file");
    opts.addOption("attempt_failures_validity_interval", true,
      "when attempt_failures_validity_interval in milliseconds is set to > 0," +
      "the failure number will not take failures which happen out of " +
      "the validityInterval into failure count. " +
      "If failure count reaches to maxAppAttempts, " +
      "the application will be failed.");
    opts.addOption("node_label_expression", true,
        "Node label expression to determine the nodes"
            + " where all the containers of this application"
            + " will be allocated, \"\" means containers"
            + " can be allocated anywhere, if you don't specify the option,"
            + " default node_label_expression of queue will be used.");
    opts.addOption("container_retry_policy", true,
        "Retry policy when container fails to run, "
            + "0: NEVER_RETRY, 1: RETRY_ON_ALL_ERRORS, "
            + "2: RETRY_ON_SPECIFIC_ERROR_CODES");
    opts.addOption("container_retry_error_codes", true,
        "When retry policy is set to RETRY_ON_SPECIFIC_ERROR_CODES, error "
            + "codes is specified with this option, "
            + "e.g. --container_retry_error_codes 1,2,3");
    opts.addOption("container_max_retries", true,
        "If container could retry, it specifies max retires");
    opts.addOption("container_retry_interval", true,
        "Interval between each retry, unit is milliseconds");
    opts.addOption(TFApplication.OPT_TF_CLIENT, true,
            "Provide client python of tensorflow");
    opts.addOption(TFApplication.OPT_TF_SERVER_JAR, true,
            "Provide server jar of tensorflow");
    opts.addOption(TFApplication.OPT_TF_SERVER_PY, true,
            "Provide server pyscript of tensorflow");
    opts.addOption(TFApplication.OPT_TF_WORKER_NUM, true,
            "worker quantity of tensorflow");
    opts.addOption(TFApplication.OPT_TF_PS_NUM, true,
            "ps quantity of tensorflow");
  }

  /**
   */
  public Client() throws Exception  {
    this(new YarnConfiguration());
  }

  /**
   * Parse command line options
   * @param args Parsed command line options
   * @return Whether the init was successful to run the client
   * @throws ParseException
   */
  public boolean init(String[] args) throws ParseException {

    CommandLine cliParser = new GnuParser().parse(opts, args);

    if (args.length == 0) {
      throw new IllegalArgumentException("No args specified for client to initialize");
    }

    if (cliParser.hasOption("log_properties")) {
      String log4jPath = cliParser.getOptionValue("log_properties");
      try {
        Log4jPropertyHelper.updateLog4jConfiguration(Client.class, log4jPath);
      } catch (Exception e) {
        LOG.warn("Can not set up custom log4j properties. " + e);
      }
    }

    appName = cliParser.getOptionValue("appname", "tensorflow");
    amPriority = Integer.parseInt(cliParser.getOptionValue("priority", "0"));
    amQueue = cliParser.getOptionValue("queue", "default");
    amMemory = Integer.parseInt(cliParser.getOptionValue("master_memory", "100"));
    amVCores = Integer.parseInt(cliParser.getOptionValue("master_vcores", "1"));
    tfClientPy = cliParser.getOptionValue(TFApplication.OPT_TF_CLIENT, TFClient.TF_CLIENT_PY);
    tfConatinerJar = cliParser.getOptionValue(TFApplication.OPT_TF_SERVER_JAR, TFAmContainer.APPMASTER_JAR_PATH);
    workerNum = Integer.parseInt(cliParser.getOptionValue(TFApplication.OPT_TF_WORKER_NUM, "1"));
    psNum = Integer.parseInt(cliParser.getOptionValue(TFApplication.OPT_TF_PS_NUM, "0"));

    if (amMemory < 0) {
      throw new IllegalArgumentException("Invalid memory specified for application master, exiting."
          + " Specified memory=" + amMemory);
    }
    if (amVCores < 0) {
      throw new IllegalArgumentException("Invalid virtual cores specified for application master, exiting."
          + " Specified virtual cores=" + amVCores);
    }

    if (!cliParser.hasOption("jar")) {
      throw new IllegalArgumentException("No jar file specified for application master");
    }

    appMasterJar = cliParser.getOptionValue("jar");



    if (!cliParser.hasOption(TFApplication.OPT_TF_CLIENT)) {
      throw new IllegalArgumentException(
          "No tensorflow client specified to be executed by application master");
    } else {
        tfClientPy = cliParser.getOptionValue(TFApplication.OPT_TF_CLIENT);
    }

    containerMemory = Integer.parseInt(cliParser.getOptionValue("container_memory", "256"));
    containerVirtualCores = Integer.parseInt(cliParser.getOptionValue("container_vcores", "1"));


    if (containerMemory < 0 || containerVirtualCores < 0 || workerNum < 1 || psNum < 0) {
      throw new IllegalArgumentException("Invalid no. of containers or container memory/vcores specified,"
          + " exiting."
          + " Specified containerMemory=" + containerMemory
          + ", containerVirtualCores=" + containerVirtualCores
          + ", workers=" + workerNum
          + ", ps=" + psNum);
    }

    nodeLabelExpression = cliParser.getOptionValue("node_label_expression", null);

    attemptFailuresValidityInterval =
        Long.parseLong(cliParser.getOptionValue(
          "attempt_failures_validity_interval", "-1"));

    log4jPropFile = cliParser.getOptionValue("log_properties", "");

    // Get container retry options
    if (cliParser.hasOption("container_retry_policy")) {
      containerRetryOptions.add("--container_retry_policy "
          + cliParser.getOptionValue("container_retry_policy"));
    }
    if (cliParser.hasOption("container_retry_error_codes")) {
      containerRetryOptions.add("--container_retry_error_codes "
          + cliParser.getOptionValue("container_retry_error_codes"));
    }
    if (cliParser.hasOption("container_max_retries")) {
      containerRetryOptions.add("--container_max_retries "
          + cliParser.getOptionValue("container_max_retries"));
    }
    if (cliParser.hasOption("container_retry_interval")) {
      containerRetryOptions.add("--container_retry_interval "
          + cliParser.getOptionValue("container_retry_interval"));
    }

    return true;
  }

  /**
   * Main run function for the client
   * @return true if application completed successfully
   * @throws IOException
   * @throws YarnException
   */
  public boolean run() throws IOException, YarnException {

    yarnClient.start();

    YarnClusterMetrics clusterMetrics = yarnClient.getYarnClusterMetrics();
    LOG.info("Got Cluster metric info from ASM"
        + ", numNodeManagers=" + clusterMetrics.getNumNodeManagers());

    List<NodeReport> clusterNodeReports = yarnClient.getNodeReports(
        NodeState.RUNNING);
    LOG.info("Got Cluster node info from ASM");
    for (NodeReport node : clusterNodeReports) {
      LOG.info("Got node report from ASM for"
          + ", nodeId=" + node.getNodeId()
          + ", nodeAddress=" + node.getHttpAddress()
          + ", nodeRackName=" + node.getRackName()
          + ", nodeNumContainers=" + node.getNumContainers());
    }

    QueueInfo queueInfo = yarnClient.getQueueInfo(this.amQueue);
    LOG.info("Queue info"
        + ", queueName=" + queueInfo.getQueueName()
        + ", queueCurrentCapacity=" + queueInfo.getCurrentCapacity()
        + ", queueMaxCapacity=" + queueInfo.getMaximumCapacity()
        + ", queueApplicationCount=" + queueInfo.getApplications().size()
        + ", queueChildQueueCount=" + queueInfo.getChildQueues().size());

    List<QueueUserACLInfo> listAclInfo = yarnClient.getQueueAclsInfo();
    for (QueueUserACLInfo aclInfo : listAclInfo) {
      for (QueueACL userAcl : aclInfo.getUserAcls()) {
        LOG.info("User ACL Info for Queue"
            + ", queueName=" + aclInfo.getQueueName()
            + ", userAcl=" + userAcl.name());
      }
    }

    // Get a new application id
    YarnClientApplication app = yarnClient.createApplication();
    GetNewApplicationResponse appResponse = app.getNewApplicationResponse();
    // TODO get min/max resource capabilities from RM and change memory ask if needed

    long maxMem = appResponse.getMaximumResourceCapability().getMemorySize();
    LOG.info("Max mem capability of resources in this cluster " + maxMem);

    if (amMemory > maxMem) {
      LOG.info("AM memory specified above max threshold of cluster. Using max value."
          + ", specified=" + amMemory
          + ", max=" + maxMem);
      amMemory = maxMem;
    }

    int maxVCores = appResponse.getMaximumResourceCapability().getVirtualCores();
    LOG.info("Max virtual cores capability of resources in this cluster " + maxVCores);

    if (amVCores > maxVCores) {
      LOG.info("AM virtual cores specified above max threshold of cluster. "
          + "Using max value." + ", specified=" + amVCores
          + ", max=" + maxVCores);
      amVCores = maxVCores;
    }

    ApplicationSubmissionContext appContext = app.getApplicationSubmissionContext();
    ApplicationId appId = appContext.getApplicationId();

    appContext.setApplicationName(appName);

    if (attemptFailuresValidityInterval >= 0) {
      appContext
        .setAttemptFailuresValidityInterval(attemptFailuresValidityInterval);
    }

    Set<String> tags = new HashSet<String>();
    appContext.setApplicationTags(tags);

    // set local resources for the application master
    // local files or archives as needed
    // In this scenario, the jar file for the application master is part of the local resources
    Map<String, LocalResource> localResources = new HashMap<String, LocalResource>();

    TFAmContainer tfAmContainer = new TFAmContainer(this);

    LOG.info("Copy App Master jar from local filesystem and add to local environment");
    // Copy the application master jar to the filesystem
    // Create a local resource to point to the destination jar path
    FileSystem fs = FileSystem.get(conf);
    tfAmContainer.addToLocalResources(fs, appMasterJar, TFAmContainer.APPMASTER_JAR_PATH, appId.toString(),
            localResources, null);

    // Set the log4j properties if needed
    if (!log4jPropFile.isEmpty()) {
      tfAmContainer.addToLocalResources(fs, log4jPropFile, log4jPath, appId.toString(),
          localResources, null);
    }

    // Set the necessary security tokens as needed
    //amContainer.setContainerTokens(containerToken);

    Map<String, String> env = tfAmContainer.setJavaEnv(conf);

    // Set the necessary command to execute the application master

    if (null != nodeLabelExpression) {
      appContext.setNodeLabelExpression(nodeLabelExpression);
    }

    StringBuilder command = tfAmContainer.makeCommands(amMemory, appMasterMainClass, containerMemory, containerVirtualCores,
    workerNum, psNum, tfConatinerJar, containerRetryOptions);

    LOG.info("Completed setting up app master command " + command.toString());
    List<String> commands = new ArrayList<String>();
    commands.add(command.toString());

    // Set up the container launch context for the application master
    ContainerLaunchContext amContainer = ContainerLaunchContext.newInstance(
      localResources, env, commands, null, null, null);

    // Set up resource type requirements
    // For now, both memory and vcores are supported, so we set memory and
    // vcores requirements
    Resource capability = Resource.newInstance(amMemory, amVCores);
    appContext.setResource(capability);

    // Service data is a binary blob that can be passed to the application
    // Not needed in this scenario
    // amContainer.setServiceData(serviceData);

    // Setup security tokens
    if (UserGroupInformation.isSecurityEnabled()) {
      // Note: Credentials class is marked as LimitedPrivate for HDFS and MapReduce
      Credentials credentials = new Credentials();
      String tokenRenewer = YarnClientUtils.getRmPrincipal(conf);
      if (tokenRenewer == null || tokenRenewer.length() == 0) {
        throw new IOException(
          "Can't get Master Kerberos principal for the RM to use as renewer");
      }

      // For now, only getting tokens for the default file-system.
      final Token<?> tokens[] =
          fs.addDelegationTokens(tokenRenewer, credentials);
      if (tokens != null) {
        for (Token<?> token : tokens) {
          LOG.info("Got dt for " + fs.getUri() + "; " + token);
        }
      }
      DataOutputBuffer dob = new DataOutputBuffer();
      credentials.writeTokenStorageToStream(dob);
      ByteBuffer fsTokens = ByteBuffer.wrap(dob.getData(), 0, dob.getLength());
      amContainer.setTokens(fsTokens);
    }

    appContext.setAMContainerSpec(amContainer);

    // Set the priority for the application master
    // TODO - what is the range for priority? how to decide?
    Priority pri = Priority.newInstance(amPriority);
    appContext.setPriority(pri);

    appContext.setQueue(amQueue);

    LOG.info("Submitting application to ASM");

    yarnClient.submitApplication(appContext);

    return monitorApplication(appId);

  }

  private boolean isEmptyString(String s) {
    return s == null || s.equals("");
  }

  /**
   * Monitor the submitted application for completion.
   * @param appId Application Id of application to be monitored
   * @return true if application completed successfully
   * @throws YarnException
   * @throws IOException
   */
  private boolean monitorApplication(ApplicationId appId)
      throws YarnException, IOException {

    while (true) {

      // Check app status every 1 second.
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        LOG.debug("Thread sleep in monitoring loop interrupted");
      }

      // Get application report for the appId we are interested in
      ApplicationReport report = yarnClient.getApplicationReport(appId);

      LOG.info("Got application report from ASM for"
          + ", appId=" + appId.getId()
          + ", clientToAMToken=" + report.getClientToAMToken()
          + ", appDiagnostics=" + report.getDiagnostics()
          + ", appMasterHost=" + report.getHost()
          + ", appQueue=" + report.getQueue()
          + ", appMasterRpcPort=" + report.getRpcPort()
          + ", appStartTime=" + report.getStartTime()
          + ", yarnAppState=" + report.getYarnApplicationState().toString()
          + ", tfAppFinalState=" + report.getFinalApplicationStatus().toString()
          + ", appTrackingUrl=" + report.getTrackingUrl()
          + ", appUser=" + report.getUser());

      YarnApplicationState state = report.getYarnApplicationState();
      FinalApplicationStatus tfStatus = report.getFinalApplicationStatus();

      if (YarnApplicationState.RUNNING == state) {
        if (appRpc == null) {
          String hostname = report.getHost();
          int port = report.getRpcPort();
          LOG.info("application master rpc host: " + hostname + "; port: " + port);
          appRpc = new TFApplicationRpcClient(hostname, port).getRpc();
        }

        if (appRpc != null && isEmptyString(clusterSpecJsonString)) {
          clusterSpecJsonString = appRpc.getClusterSpec();
          LOG.info("cluster spec is " + clusterSpecJsonString);
          if (!isEmptyString(clusterSpecJsonString)) {
            TFClient tfClient = new TFClient(tfClientPy);
            tfClient.startTensorflowClient(clusterSpecJsonString);
          }
        }
      }

      if (YarnApplicationState.FINISHED == state) {
        if (FinalApplicationStatus.SUCCEEDED == tfStatus) {
          LOG.info("Application has completed successfully. Breaking monitoring loop");
          return true;
        }
        else {
          LOG.info("Application did finished unsuccessfully."
              + " YarnState=" + state.toString() + ", tfAppFinalState=" + tfStatus.toString()
              + ". Breaking monitoring loop");
          return false;
        }
      }
      else if (YarnApplicationState.KILLED == state
          || YarnApplicationState.FAILED == state) {
        LOG.info("Application did not finish."
            + " YarnState=" + state.toString() + ", tfAppFinalState=" + tfStatus.toString()
            + ". Breaking monitoring loop");
        return false;
      }

/*      if (System.currentTimeMillis() > (clientStartTime + clientTimeout)) {
        LOG.info("Reached client specified timeout for application. Killing application");
        forceKillApplication(appId);
        return false;
      }*/
    }

  }

  /**
   * Kill a submitted application by sending a call to the ASM
   * @param appId Application Id to be killed.
   * @throws YarnException
   * @throws IOException
   */
  private void forceKillApplication(ApplicationId appId)
      throws YarnException, IOException {
    // TODO clarify whether multiple jobs with the same app id can be submitted and be running at
    // the same time.
    // If yes, can we kill a particular attempt only?

    // Response can be ignored as it is non-null on success or
    // throws an exception in case of failures
    yarnClient.killApplication(appId);
  }

}
