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

package org.apache.hadoop.mapreduce.v2.app2.client;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.hadoop.mapreduce.TypeConverter;
import org.apache.hadoop.mapreduce.v2.api.MRClientProtocol;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.*;
import org.apache.hadoop.mapreduce.v2.api.records.JobId;
import org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptId;
import org.apache.hadoop.mapreduce.v2.api.records.TaskId;
import org.apache.hadoop.mapreduce.v2.api.records.TaskType;
import org.apache.hadoop.mapreduce.v2.app2.AppContext;
import org.apache.hadoop.mapreduce.v2.app2.job.Job;
import org.apache.hadoop.mapreduce.v2.app2.job.Task;
import org.apache.hadoop.mapreduce.v2.app2.job.TaskAttempt;
import org.apache.hadoop.mapreduce.v2.app2.job.event.*;
import org.apache.hadoop.mapreduce.v2.app2.security.authorize.MRAMPolicyProvider;
import org.apache.hadoop.mapreduce.v2.app2.webapp.AMWebApp;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authorize.PolicyProvider;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.exceptions.YarnRemoteException;
import org.apache.hadoop.yarn.factories.RecordFactory;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.ipc.RPCUtil;
import org.apache.hadoop.yarn.ipc.YarnRPC;
import org.apache.hadoop.yarn.security.client.ClientToAMTokenSecretManager;
import org.apache.hadoop.yarn.service.AbstractService;
import org.apache.hadoop.yarn.webapp.WebApp;
import org.apache.hadoop.yarn.webapp.WebApps;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collection;

/**
 * This module is responsible for talking to the 
 * jobclient (user facing).
 *
 */
public class MRClientService extends AbstractService 
    implements ClientService {

  static final Log LOG = LogFactory.getLog(MRClientService.class);
  
  protected MRClientProtocol protocolHandler;
  private Server server;
  private WebApp webApp;
  private InetSocketAddress bindAddress;
  protected AppContext appContext;

  public MRClientService(AppContext appContext) {
    this("MRClientService", appContext);
  }

  protected MRClientService(String name,
      AppContext appContext) {
    super(name);
    this.appContext = appContext;
    this.protocolHandler = new MRClientProtocolHandler();
  }

  protected void setProtocolHandler(MRClientProtocol protocolHandler) {
    this.protocolHandler = protocolHandler;
  }

  public void start() {
    Configuration conf = getConfig();
    YarnRPC rpc = YarnRPC.create(conf);
    InetSocketAddress address = new InetSocketAddress(0);

    ClientToAMTokenSecretManager secretManager = null;
    if (UserGroupInformation.isSecurityEnabled()) {
      String secretKeyStr =
          System
              .getenv(ApplicationConstants.APPLICATION_CLIENT_SECRET_ENV_NAME);
      byte[] bytes = Base64.decodeBase64(secretKeyStr);
      secretManager = new ClientToAMTokenSecretManager(
          this.appContext.getApplicationAttemptId(), bytes);
    }
    server =
        rpc.getServer(MRClientProtocol.class, protocolHandler, address,
            conf, secretManager,
            conf.getInt(MRJobConfig.MR_AM_JOB_CLIENT_THREAD_COUNT, 
                MRJobConfig.DEFAULT_MR_AM_JOB_CLIENT_THREAD_COUNT),
                MRJobConfig.MR_AM_JOB_CLIENT_PORT_RANGE);
    
    // Enable service authorization?
    if (conf.getBoolean(
        CommonConfigurationKeysPublic.HADOOP_SECURITY_AUTHORIZATION, 
        false)) {
      refreshServiceAcls(conf, new MRAMPolicyProvider());
    }

    server.start();
    this.bindAddress = NetUtils.getConnectAddress(server);
    LOG.info("Instantiated MRClientService at " + this.bindAddress);
    try {
      webApp = WebApps.$for("mapreduce", AppContext.class, appContext, "ws").with(conf).
          start(new AMWebApp());
    } catch (Exception e) {
      LOG.error("Webapps failed to start. Ignoring for now:", e);
    }
    super.start();
  }

  void refreshServiceAcls(Configuration configuration, 
      PolicyProvider policyProvider) {
    this.server.refreshServiceAcl(configuration, policyProvider);
  }

  public void stop() {
    server.stop();
    if (webApp != null) {
      webApp.stop();
    }
    super.stop();
  }

  @Override
  public InetSocketAddress getBindAddress() {
    return bindAddress;
  }

  @Override
  public int getHttpPort() {
    return webApp.port();
  }

  public class MRClientProtocolHandler implements MRClientProtocol {

    private RecordFactory recordFactory = 
      RecordFactoryProvider.getRecordFactory(null);

    @Override
    public InetSocketAddress getConnectAddress() {
      return getBindAddress();
    }
    
    private Job verifyAndGetJob(JobId jobID, 
        boolean modifyAccess) throws YarnRemoteException {
      Job job = appContext.getJob(jobID);
      return job;
    }
 
    private Task verifyAndGetTask(TaskId taskID, 
        boolean modifyAccess) throws YarnRemoteException {
      Task task = verifyAndGetJob(taskID.getJobId(), 
          modifyAccess).getTask(taskID);
      if (task == null) {
        throw RPCUtil.getRemoteException("Unknown Task " + taskID);
      }
      return task;
    }

    private TaskAttempt verifyAndGetAttempt(TaskAttemptId attemptID, 
        boolean modifyAccess) throws YarnRemoteException {
      TaskAttempt attempt = verifyAndGetTask(attemptID.getTaskId(), 
          modifyAccess).getAttempt(attemptID);
      if (attempt == null) {
        throw RPCUtil.getRemoteException("Unknown TaskAttempt " + attemptID);
      }
      return attempt;
    }

    @Override
    public GetCountersResponse getCounters(GetCountersRequest request) 
      throws YarnRemoteException {
      JobId jobId = request.getJobId();
      Job job = verifyAndGetJob(jobId, false);
      GetCountersResponse response =
        recordFactory.newRecordInstance(GetCountersResponse.class);
      response.setCounters(TypeConverter.toYarn(job.getAllCounters()));
      return response;
    }
    
    @Override
    public GetJobReportResponse getJobReport(GetJobReportRequest request) 
      throws YarnRemoteException {
      JobId jobId = request.getJobId();
      Job job = verifyAndGetJob(jobId, false);
      GetJobReportResponse response = 
        recordFactory.newRecordInstance(GetJobReportResponse.class);
      if (job != null) {
        response.setJobReport(job.getReport());
      }
      else {
        response.setJobReport(null);
      }
      return response;
    }

    @Override
    public GetTaskAttemptReportResponse getTaskAttemptReport(
        GetTaskAttemptReportRequest request) throws YarnRemoteException {
      TaskAttemptId taskAttemptId = request.getTaskAttemptId();
      GetTaskAttemptReportResponse response =
        recordFactory.newRecordInstance(GetTaskAttemptReportResponse.class);
      response.setTaskAttemptReport(
          verifyAndGetAttempt(taskAttemptId, false).getReport());
      return response;
    }

    @Override
    public GetTaskReportResponse getTaskReport(GetTaskReportRequest request) 
      throws YarnRemoteException {
      TaskId taskId = request.getTaskId();
      GetTaskReportResponse response = 
        recordFactory.newRecordInstance(GetTaskReportResponse.class);
      response.setTaskReport(verifyAndGetTask(taskId, false).getReport());
      return response;
    }

    @Override
    public GetTaskAttemptCompletionEventsResponse getTaskAttemptCompletionEvents(
        GetTaskAttemptCompletionEventsRequest request) 
        throws YarnRemoteException {
      JobId jobId = request.getJobId();
      int fromEventId = request.getFromEventId();
      int maxEvents = request.getMaxEvents();
      Job job = verifyAndGetJob(jobId, false);
      
      GetTaskAttemptCompletionEventsResponse response = 
        recordFactory.newRecordInstance(GetTaskAttemptCompletionEventsResponse.class);
      response.addAllCompletionEvents(Arrays.asList(
          job.getTaskAttemptCompletionEvents(fromEventId, maxEvents)));
      return response;
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public KillJobResponse killJob(KillJobRequest request) 
      throws YarnRemoteException {
      JobId jobId = request.getJobId();
      String message = "Kill Job received from client " + jobId;
      LOG.info(message);
  	  verifyAndGetJob(jobId, true);
      appContext.getEventHandler().handle(
          new JobEventDiagnosticsUpdate(jobId, message));
      appContext.getEventHandler().handle(
          new JobEvent(jobId, JobEventType.JOB_KILL));
      KillJobResponse response = 
        recordFactory.newRecordInstance(KillJobResponse.class);
      return response;
    }

    @SuppressWarnings("unchecked")
    @Override
    public KillTaskResponse killTask(KillTaskRequest request) 
      throws YarnRemoteException {
      TaskId taskId = request.getTaskId();
      String message = "Kill task received from client " + taskId;
      LOG.info(message);
      verifyAndGetTask(taskId, true);
      appContext.getEventHandler().handle(
          new TaskEvent(taskId, TaskEventType.T_KILL));
      KillTaskResponse response = 
        recordFactory.newRecordInstance(KillTaskResponse.class);
      return response;
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public KillTaskAttemptResponse killTaskAttempt(
        KillTaskAttemptRequest request) throws YarnRemoteException {
      TaskAttemptId taskAttemptId = request.getTaskAttemptId();
      String message = "Kill task attempt received from client " + taskAttemptId;
      LOG.info(message);
      verifyAndGetAttempt(taskAttemptId, true);
      appContext.getEventHandler().handle(
          new TaskAttemptEventKillRequest(taskAttemptId, message));
      KillTaskAttemptResponse response = 
        recordFactory.newRecordInstance(KillTaskAttemptResponse.class);
      return response;
    }

    @Override
    public GetDiagnosticsResponse getDiagnostics(
        GetDiagnosticsRequest request) throws YarnRemoteException {
      TaskAttemptId taskAttemptId = request.getTaskAttemptId();
      
      GetDiagnosticsResponse response = 
        recordFactory.newRecordInstance(GetDiagnosticsResponse.class);
      response.addAllDiagnostics(
          verifyAndGetAttempt(taskAttemptId, false).getDiagnostics());
      return response;
    }

    @SuppressWarnings("unchecked")
    @Override
    public FailTaskAttemptResponse failTaskAttempt(
        FailTaskAttemptRequest request) throws YarnRemoteException {
      TaskAttemptId taskAttemptId = request.getTaskAttemptId();
      String message = "Fail task attempt received from client " + taskAttemptId;
      LOG.info(message);
      verifyAndGetAttempt(taskAttemptId, true);
      appContext.getEventHandler().handle(
          new TaskAttemptEventFailRequest(taskAttemptId, message));
      FailTaskAttemptResponse response = recordFactory.
        newRecordInstance(FailTaskAttemptResponse.class);
      return response;
    }

    private final Object getTaskReportsLock = new Object();

    @Override
    public GetTaskReportsResponse getTaskReports(
        GetTaskReportsRequest request) throws YarnRemoteException {
      JobId jobId = request.getJobId();
      TaskType taskType = request.getTaskType();
      
      GetTaskReportsResponse response = 
        recordFactory.newRecordInstance(GetTaskReportsResponse.class);
      
      Job job = verifyAndGetJob(jobId, false);
      Collection<Task> tasks = job.getTasks(taskType).values();
      LOG.info("Getting task report for " + taskType + "   " + jobId
          + ". Report-size will be " + tasks.size());

      // Take lock to allow only one call, otherwise heap will blow up because
      // of counters in the report when there are multiple callers.
      synchronized (getTaskReportsLock) {
        for (Task task : tasks) {
          response.addTaskReport(task.getReport());
        }
      }

      return response;
    }

    @Override
    public GetDelegationTokenResponse getDelegationToken(
        GetDelegationTokenRequest request) throws YarnRemoteException {
      throw RPCUtil.getRemoteException("MR AM not authorized to issue delegation" +
      		" token");
    }


    @Override
    public RenewDelegationTokenResponse renewDelegationToken(
        RenewDelegationTokenRequest request) throws YarnRemoteException {
      throw RPCUtil.getRemoteException("MR AM not authorized to renew delegation" +
          " token");
    }

    @Override
    public CancelDelegationTokenResponse cancelDelegationToken(
        CancelDelegationTokenRequest request) throws YarnRemoteException {
      throw RPCUtil.getRemoteException("MR AM not authorized to cancel delegation" +
          " token");
    }
  }
}
