/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
*/

package org.apache.griffin.core.job;

import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.lang.StringUtils;
import org.apache.griffin.core.error.exception.GriffinException.GetHealthInfoFailureException;
import org.apache.griffin.core.error.exception.GriffinException.GetJobsFailureException;
import org.apache.griffin.core.job.entity.*;
import org.apache.griffin.core.job.repo.JobInstanceRepo;
import org.apache.griffin.core.job.repo.JobRepo;
import org.apache.griffin.core.job.repo.JobScheduleRepo;
import org.apache.griffin.core.measure.entity.DataConnector;
import org.apache.griffin.core.measure.entity.DataSource;
import org.apache.griffin.core.measure.entity.GriffinMeasure;
import org.apache.griffin.core.measure.repo.MeasureRepo;
import org.apache.griffin.core.util.GriffinOperationMessage;
import org.apache.griffin.core.util.JsonUtil;
import org.quartz.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.text.ParseException;
import java.util.*;

import static org.apache.griffin.core.util.GriffinOperationMessage.CREATE_JOB_FAIL;
import static org.apache.griffin.core.util.GriffinOperationMessage.CREATE_JOB_SUCCESS;
import static org.quartz.JobBuilder.newJob;
import static org.quartz.JobKey.jobKey;
import static org.quartz.TriggerBuilder.newTrigger;
import static org.quartz.TriggerKey.triggerKey;

@Service
public class JobServiceImpl implements JobService {
    private static final Logger LOGGER = LoggerFactory.getLogger(JobServiceImpl.class);
    static final String JOB_SCHEDULE_ID = "jobScheduleId";
    static final String GRIFFIN_JOB_ID = "griffinJobId";
    static final int MAX_PAGE_SIZE = 1024;
    static final int DEFAULT_PAGE_SIZE = 10;

    @Autowired
    private SchedulerFactoryBean factory;
    @Autowired
    private JobInstanceRepo jobInstanceRepo;
    @Autowired
    private Properties livyConfProps;
    @Autowired
    private MeasureRepo<GriffinMeasure> measureRepo;
    @Autowired
    private JobRepo<GriffinJob> jobRepo;
    @Autowired
    private JobScheduleRepo jobScheduleRepo;

    private RestTemplate restTemplate;

    public JobServiceImpl() {
        restTemplate = new RestTemplate();
    }

    @Override
    public List<JobDataBean> getAliveJobs() {
        Scheduler scheduler = factory.getObject();
        List<JobDataBean> dataList = new ArrayList<>();
        try {
            List<GriffinJob> jobs = jobRepo.findByDeleted(false);
            for (GriffinJob job : jobs) {
                JobDataBean jobData = genJobData(scheduler, jobKey(job.getQuartzJobName(), job.getQuartzGroupName()), job);
                if (jobData != null) {
                    dataList.add(jobData);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to get running jobs.", e);
            throw new GetJobsFailureException();
        }
        return dataList;
    }

    private JobDataBean genJobData(Scheduler scheduler, JobKey jobKey, GriffinJob job) throws SchedulerException {
        List<Trigger> triggers = (List<Trigger>) scheduler.getTriggersOfJob(jobKey);
        if (CollectionUtils.isEmpty(triggers)) {
            return null;
        }
        JobDataBean jobData = new JobDataBean();
        Trigger trigger = triggers.get(0);
        setTriggerTime(trigger, jobData);
        jobData.setJobId(job.getId());
        jobData.setJobName(job.getJobName());
        jobData.setMeasureId(job.getMeasureId());
        jobData.setTriggerState(scheduler.getTriggerState(trigger.getKey()));
        jobData.setCronExpression(getCronExpression(triggers));
        return jobData;
    }

    private String getCronExpression(List<Trigger> triggers) {
        for (Trigger trigger : triggers) {
            if (trigger instanceof CronTrigger) {
                return ((CronTrigger) trigger).getCronExpression();
            }
        }
        return null;
    }

    private void setTriggerTime(Trigger trigger, JobDataBean jobBean) throws SchedulerException {
        Date nextFireTime = trigger.getNextFireTime();
        Date previousFireTime = trigger.getPreviousFireTime();
        jobBean.setNextFireTime(nextFireTime != null ? nextFireTime.getTime() : -1);
        jobBean.setPreviousFireTime(previousFireTime != null ? previousFireTime.getTime() : -1);
    }

    @Override
    public GriffinOperationMessage addJob(JobSchedule jobSchedule) {
        Long measureId = jobSchedule.getMeasureId();
        GriffinMeasure measure = getMeasureIfValid(measureId);
        if (measure != null) {
            return addJob(jobSchedule, measure);
        }
        return CREATE_JOB_FAIL;
    }

    private GriffinOperationMessage addJob(JobSchedule js, GriffinMeasure measure) {
        String qJobName = js.getJobName() + "_" + System.currentTimeMillis();
        String qGroupName = getQuartzGroupName();
        try {
            if (addJob(js, measure, qJobName, qGroupName)) {
                return CREATE_JOB_SUCCESS;
            }
        } catch (Exception e) {
            LOGGER.error("Add job exception happens.", e);
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
        return CREATE_JOB_FAIL;
    }

    private boolean addJob(JobSchedule js, GriffinMeasure measure, String qName, String qGroup) throws SchedulerException, ParseException {
        Scheduler scheduler = factory.getObject();
        TriggerKey triggerKey = triggerKey(qName, qGroup);
        if (!isJobScheduleParamValid(js, measure)) {
            return false;
        }
        if (scheduler.checkExists(triggerKey)) {
            return false;
        }
        GriffinJob job = saveGriffinJob(measure.getId(), js.getJobName(), qName, qGroup);
        return job != null && !saveAndAddQuartzJob(scheduler, triggerKey, js, job);
    }

    private String getQuartzGroupName() {
        return "BA";
    }

    private boolean isJobScheduleParamValid(JobSchedule jobSchedule, GriffinMeasure measure) throws SchedulerException {
        if (!isJobNameValid(jobSchedule.getJobName())) {
            return false;
        }
        if (!isBaseLineValid(jobSchedule.getSegments())) {
            return false;
        }
        List<String> names = getConnectorNames(measure);
        return isConnectorNamesValid(jobSchedule.getSegments(), names);
    }

    private boolean isJobNameValid(String jobName) {
        if (StringUtils.isEmpty(jobName)) {
            LOGGER.warn("Job name cannot be empty.");
            return false;
        }
        int size = jobRepo.countByJobNameAndDeleted(jobName, false);
        if (size > 0) {
            LOGGER.warn("Job name already exits.");
            return false;
        }
        return true;
    }

    //TODO get first baseline
    private boolean isBaseLineValid(List<JobDataSegment> segments) {
        for (JobDataSegment jds : segments) {
            if (jds.getBaseline()) {
                return true;
            }
        }
        LOGGER.warn("Please set segment timestamp baseline in as.baseline field.");
        return false;
    }

    private boolean isConnectorNamesValid(List<JobDataSegment> segments, List<String> names) {
        for (JobDataSegment segment : segments) {
            if (!isConnectorNameValid(segment.getDataConnectorName(), names)) {
                return false;
            }
        }
        return true;
    }

    private boolean isConnectorNameValid(String param, List<String> names) {
        for (String name : names) {
            if (name.equals(param)) {
                return true;
            }
        }
        LOGGER.warn("Param {} is a illegal string. Please input one of strings in {}.", param, names);
        return false;
    }

    //TODO exclude repeat
    private List<String> getConnectorNames(GriffinMeasure measure) {
        List<String> names = new ArrayList<>();
        List<DataSource> sources = measure.getDataSources();
        for (DataSource source : sources) {
            for (DataConnector dc : source.getConnectors()) {
                names.add(dc.getName());
            }
        }
        return names;
    }

    //TODO　deleted state
    private GriffinMeasure getMeasureIfValid(long measureId) {
        GriffinMeasure measure = measureRepo.findByIdAndDeleted(measureId, false);
        if (measure == null) {
            LOGGER.warn("The measure id {} isn't valid. Maybe it doesn't exist or is deleted.", measureId);
        }
        return measure;
    }

    private GriffinJob saveGriffinJob(Long measureId, String jobName, String quartzJobName, String quartzGroupName) {
        GriffinJob job = new GriffinJob(measureId, jobName, quartzJobName, quartzGroupName, false);
        return jobRepo.save(job);
    }

    private boolean saveAndAddQuartzJob(Scheduler scheduler, TriggerKey triggerKey, JobSchedule jobSchedule, GriffinJob job) throws SchedulerException, ParseException {
        jobSchedule = jobScheduleRepo.save(jobSchedule);
        JobDetail jobDetail = addJobDetail(scheduler, triggerKey, jobSchedule, job);
        scheduler.scheduleJob(genTriggerInstance(triggerKey, jobDetail, jobSchedule));
        return true;
    }


    private Trigger genTriggerInstance(TriggerKey triggerKey, JobDetail jobDetail, JobSchedule jobSchedule) throws ParseException {
        return newTrigger()
                .withIdentity(triggerKey)
                .forJob(jobDetail)
                .withSchedule(CronScheduleBuilder.cronSchedule(new CronExpression(jobSchedule.getCronExpression()))
                        .inTimeZone(TimeZone.getTimeZone(jobSchedule.getTimeZone()))
                )
                .build();
    }

    private JobDetail addJobDetail(Scheduler scheduler, TriggerKey triggerKey, JobSchedule jobSchedule, GriffinJob job) throws SchedulerException {
        JobKey jobKey = jobKey(triggerKey.getName(), triggerKey.getGroup());
        JobDetail jobDetail;
        Boolean isJobKeyExist = scheduler.checkExists(jobKey);
        if (isJobKeyExist) {
            jobDetail = scheduler.getJobDetail(jobKey);
        } else {
            jobDetail = newJob(JobInstance.class).storeDurably().withIdentity(jobKey).build();
        }
        setJobDataMap(jobDetail, jobSchedule, job);
        scheduler.addJob(jobDetail, isJobKeyExist);
        return jobDetail;
    }


    private void setJobDataMap(JobDetail jobDetail, JobSchedule jobSchedule, GriffinJob job) {
        jobDetail.getJobDataMap().put(JOB_SCHEDULE_ID, jobSchedule.getId().toString());
        jobDetail.getJobDataMap().put(GRIFFIN_JOB_ID, job.getId().toString());
    }

    private boolean pauseJob(List<JobInstanceBean> instances) {
        if (CollectionUtils.isEmpty(instances)) {
            return true;
        }
        List<JobInstanceBean> deletedInstances = new ArrayList<>();
        boolean pauseStatus = true;
        for (JobInstanceBean instance : instances) {
            boolean status = pauseJob(instance, deletedInstances);
            pauseStatus = pauseStatus && status;
        }
        jobInstanceRepo.save(deletedInstances);
        return pauseStatus;
    }

    private boolean pauseJob(JobInstanceBean instance, List<JobInstanceBean> deletedInstances) {
        boolean status;
        try {
            status = pauseJob(instance.getPredicateGroupName(), instance.getPredicateJobName());
            if (status) {
                instance.setDeleted(true);
                deletedInstances.add(instance);
            }
        } catch (SchedulerException e) {
            LOGGER.error("Pause predicate job({},{}) failure.", instance.getId(), instance.getPredicateJobName());
            status = false;
        }
        return status;
    }

    @Override
    public boolean pauseJob(String group, String name) throws SchedulerException {
        Scheduler scheduler = factory.getObject();
        JobKey jobKey = new JobKey(name, group);
        if (!scheduler.checkExists(jobKey)) {
            LOGGER.warn("Job({},{}) does not exist.", group, name);
            return false;
        }
        scheduler.pauseJob(jobKey);
        return true;
    }

    private boolean setJobDeleted(GriffinJob job) throws SchedulerException {
        job.setDeleted(true);
        jobRepo.save(job);
        return true;
    }

    private boolean deletePredicateJob(GriffinJob job) throws SchedulerException {
        boolean isPauseSuccess = true;
        List<JobInstanceBean> instances = job.getJobInstances();
        for (JobInstanceBean instance : instances) {
            if (!instance.getDeleted()) {
                //TODO real delete predicate
                isPauseSuccess = isPauseSuccess && pauseJob(instance.getPredicateGroupName(), instance.getPredicateJobName());
                instance.setDeleted(true);
            }
        }
        return isPauseSuccess;
    }

    /**
     * logically delete
     * 1. pause these jobs
     * 2. set these jobs as deleted status
     *
     * @param jobId griffin job id
     * @return custom information
     */
    @Override
    public GriffinOperationMessage deleteJob(Long jobId) {
        GriffinJob job = jobRepo.findByIdAndDeleted(jobId, false);
        return deleteJob(job) ? GriffinOperationMessage.DELETE_JOB_SUCCESS : GriffinOperationMessage.DELETE_JOB_FAIL;
    }

    /**
     * logically delete
     *
     * @param name griffin job name which may not be unique.
     * @return custom information
     */
    @Override
    public GriffinOperationMessage deleteJob(String name) {
        List<GriffinJob> jobs = jobRepo.findByJobNameAndDeleted(name, false);
        if (CollectionUtils.isEmpty(jobs)) {
            LOGGER.warn("There is no job with '{}' name.", name);
            return GriffinOperationMessage.DELETE_JOB_FAIL;
        }
        for (GriffinJob job : jobs) {
            if (!deleteJob(job)) {
                return GriffinOperationMessage.DELETE_JOB_FAIL;
            }
        }
        return GriffinOperationMessage.DELETE_JOB_SUCCESS;
    }

    private boolean deleteJob(GriffinJob job) {
        if (job == null) {
            LOGGER.warn("Griffin job does not exist.");
            return false;
        }
        try {
            if (pauseJob(job.getQuartzGroupName(), job.getQuartzJobName()) && deletePredicateJob(job) && setJobDeleted(job)) {
                return true;
            }
        } catch (Exception e) {
            LOGGER.error("Delete job failure.", e);
        }
        return false;
    }

    /**
     * deleteJobsRelateToMeasure
     * 1. search jobs related to measure
     * 2. deleteJob
     *
     * @param measureId measure id
     */
    public boolean deleteJobsRelateToMeasure(Long measureId) {
        List<GriffinJob> jobs = jobRepo.findByMeasureIdAndDeleted(measureId, false);
        if (CollectionUtils.isEmpty(jobs)) {
            LOGGER.warn("Measure id {} has no related jobs.", measureId);
            return false;
        }
        for (GriffinJob job : jobs) {
            deleteJob(job);
        }
        return true;
    }

    @Override
    public List<JobInstanceBean> findInstancesOfJob(Long jobId, int page, int size) {
        size = size > MAX_PAGE_SIZE ? MAX_PAGE_SIZE : size;
        size = size <= 0 ? DEFAULT_PAGE_SIZE : size;
        Pageable pageable = new PageRequest(page, size, Sort.Direction.DESC, "timestamp");
        List<JobInstanceBean> instances = jobInstanceRepo.findByJobIdAndDeleted(jobId, false, pageable);
        if (CollectionUtils.isEmpty(instances)) {
            LOGGER.warn("Job id {} does not exist.", jobId);
        }
        return instances;
    }

    @Scheduled(fixedDelayString = "${jobInstance.expired.milliseconds}")
    public void deleteExpiredJobInstance() {
        List<JobInstanceBean> instances = jobInstanceRepo.findByExpireTmsLessThanEqualAndDeleted(System.currentTimeMillis(), false);
        //TODO pause job not one time
        if (!pauseJob(instances)) {
            LOGGER.error("Pause job failure.");
            return;
        }
        jobInstanceRepo.deleteByExpireTimestamp(System.currentTimeMillis());
        LOGGER.info("Delete expired job instances success.");
    }

    @Scheduled(fixedDelayString = "${jobInstance.fixedDelay.in.milliseconds}")
    public void syncInstancesOfAllJobs() {
        List<JobInstanceBean> beans = jobInstanceRepo.findByActiveState();
        if (!CollectionUtils.isEmpty(beans)) {
            for (JobInstanceBean jobInstance : beans) {
                syncInstancesOfJob(jobInstance);
            }
        }
    }


    /**
     * call livy to update part of job instance table data associated with group and jobName in mysql.
     *
     * @param jobInstance job instance livy info
     */
    private void syncInstancesOfJob(JobInstanceBean jobInstance) {
        String uri = livyConfProps.getProperty("livy.uri") + "/" + jobInstance.getSessionId();
        TypeReference<HashMap<String, Object>> type = new TypeReference<HashMap<String, Object>>() {
        };
        try {
            String resultStr = restTemplate.getForObject(uri, String.class);
            HashMap<String, Object> resultMap = JsonUtil.toEntity(resultStr, type);
            setJobInstanceIdAndUri(jobInstance, resultMap);
        } catch (RestClientException e) {
            LOGGER.error("Spark session {} has overdue, set state as unknown!\n {}", jobInstance.getSessionId(), e.getMessage());
            setJobInstanceUnknownStatus(jobInstance);
        } catch (IOException e) {
            LOGGER.error("Job instance json converts to map failed. {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            LOGGER.error("Livy status is illegal. {}", e.getMessage());
        }
    }


    private void setJobInstanceIdAndUri(JobInstanceBean jobInstance, HashMap<String, Object> resultMap) {
        if (resultMap != null && resultMap.size() != 0 && resultMap.get("state") != null) {
            jobInstance.setState(LivySessionStates.State.valueOf(resultMap.get("state").toString()));
            if (resultMap.get("appId") != null) {
                jobInstance.setAppId(resultMap.get("appId").toString());
                jobInstance.setAppUri(livyConfProps.getProperty("spark.uri") + "/cluster/app/" + resultMap.get("appId").toString());
            }
            jobInstanceRepo.save(jobInstance);
        }

    }

    private void setJobInstanceUnknownStatus(JobInstanceBean jobInstance) {
        //if server cannot get session from Livy, set State as unknown.
        jobInstance.setState(LivySessionStates.State.unknown);
        jobInstanceRepo.save(jobInstance);
    }

    /**
     * a job is regard as healthy job when its latest instance is in healthy state.
     *
     * @return job healthy statistics
     */
    @Override
    public JobHealth getHealthInfo() {
        JobHealth jobHealth = new JobHealth();
        List<GriffinJob> jobs = jobRepo.findByDeleted(false);
        for (GriffinJob job : jobs) {
            jobHealth = getHealthInfo(jobHealth, job);
        }
        return jobHealth;
    }

    private JobHealth getHealthInfo(JobHealth jobHealth, GriffinJob job) {
        List<Trigger> triggers = getTriggers(job);
        if (!CollectionUtils.isEmpty(triggers)) {
            jobHealth.setJobCount(jobHealth.getJobCount() + 1);
            if (isJobHealthy(job.getId())) {
                jobHealth.setHealthyJobCount(jobHealth.getHealthyJobCount() + 1);
            }
        }
        return jobHealth;
    }

    private List<Trigger> getTriggers(GriffinJob job) {
        JobKey jobKey = new JobKey(job.getQuartzJobName(), job.getQuartzGroupName());
        List<Trigger> triggers;
        try {
            triggers = (List<Trigger>) factory.getObject().getTriggersOfJob(jobKey);
        } catch (SchedulerException e) {
            LOGGER.error("Job schedule exception. {}", e.getMessage());
            throw new GetHealthInfoFailureException();
        }
        return triggers;
    }

    private Boolean isJobHealthy(Long jobId) {
        Pageable pageable = new PageRequest(0, 1, Sort.Direction.DESC, "timestamp");
        List<JobInstanceBean> instances = jobInstanceRepo.findByJobIdAndDeleted(jobId, false, pageable);
        return !CollectionUtils.isEmpty(instances) && LivySessionStates.isHealthy(instances.get(0).getState());
    }


}
