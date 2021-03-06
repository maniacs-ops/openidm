/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2016 ForgeRock AS.
 */
package org.forgerock.openidm.scheduler;

import static java.lang.Math.min;
import static org.forgerock.json.JsonValue.*;
import static org.forgerock.json.resource.ResourceResponse.FIELD_CONTENT_ID;
import static org.forgerock.json.resource.Responses.*;
import static org.forgerock.openidm.quartz.impl.RepoJobStore.getTriggerId;
import static org.forgerock.openidm.repo.QueryConstants.QUERY_ALL_IDS;
import static org.forgerock.openidm.scheduler.SchedulerService.*;
import static org.forgerock.util.promise.Promises.newResultPromise;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import org.forgerock.http.util.Json;
import org.forgerock.json.JsonException;
import org.forgerock.json.JsonPointer;
import org.forgerock.json.JsonValue;
import org.forgerock.json.JsonValueException;
import org.forgerock.json.resource.AbstractRequestHandler;
import org.forgerock.json.resource.ActionRequest;
import org.forgerock.json.resource.ActionResponse;
import org.forgerock.json.resource.BadRequestException;
import org.forgerock.json.resource.ConflictException;
import org.forgerock.json.resource.ConnectionFactory;
import org.forgerock.json.resource.CountPolicy;
import org.forgerock.json.resource.CreateRequest;
import org.forgerock.json.resource.DeleteRequest;
import org.forgerock.json.resource.InternalServerErrorException;
import org.forgerock.json.resource.NotFoundException;
import org.forgerock.json.resource.PreconditionFailedException;
import org.forgerock.json.resource.QueryRequest;
import org.forgerock.json.resource.QueryResourceHandler;
import org.forgerock.json.resource.QueryResponse;
import org.forgerock.json.resource.ReadRequest;
import org.forgerock.json.resource.RequestHandler;
import org.forgerock.json.resource.Requests;
import org.forgerock.json.resource.ResourceException;
import org.forgerock.json.resource.ResourceResponse;
import org.forgerock.json.resource.SortKey;
import org.forgerock.json.resource.UpdateRequest;
import org.forgerock.openidm.filter.JsonValueFilterVisitor;
import org.forgerock.openidm.quartz.impl.ScheduledService;
import org.forgerock.openidm.quartz.impl.SchedulerServiceJob;
import org.forgerock.openidm.quartz.impl.StatefulSchedulerServiceJob;
import org.forgerock.openidm.util.JsonUtil;
import org.forgerock.services.context.Context;
import org.forgerock.util.promise.Promise;
import org.forgerock.util.query.QueryFilter;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.quartz.CronTrigger;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.ObjectAlreadyExistsException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates a {@link RequestHandler} that handles crudpaq operations for {@link Job Jobs}.
 */
class JobRequestHandler extends AbstractRequestHandler {
    private static final Logger logger = LoggerFactory.getLogger(JobRequestHandler.class);

    private static final String TRIGGERS = "triggers";
    private static final String NEXT_RUN_DATE = "nextRunDate";

    // Misfire Policies
    private static final String MISFIRE_POLICY_DO_NOTHING = "doNothing";
    private static final String MISFIRE_POLICY_FIRE_AND_PROCEED = "fireAndProceed";

    private static final JsonValueFilterVisitor JSONVALUE_FILTER_VISITOR = new JsonValueFilterVisitor();

    private static final Object LOCK = new Object();

    private final Scheduler persistentScheduler;
    private final Scheduler inMemoryScheduler;
    private final ConnectionFactory connectionFactory;

    /**
     * The resource path for jobs.
     */
    static final String JOB_RESOURCE_PATH = "/job";

    /**
     * Supported actions for jobs.
     */
    enum JobAction {
        create,
        listCurrentlyExecutingJobs,
        pauseJobs,
        resumeJobs
    }

    /**
     * Constructs a {@link JobRequestHandler} given a persistent {@link Scheduler}, a in-memory {@link Scheduler} and
     * a {@link ConnectionFactory}.
     * @param persistentScheduler the persistent {@link Scheduler}.
     * @param inMemoryScheduler the in-memory {@link Scheduler}.
     * @param connectionFactory the {@link ConnectionFactory}.
     */
    JobRequestHandler(final Scheduler persistentScheduler, final Scheduler inMemoryScheduler,
            final ConnectionFactory connectionFactory) {
        this.persistentScheduler = persistentScheduler;
        this.inMemoryScheduler = inMemoryScheduler;
        this.connectionFactory = connectionFactory;
    }

    @Override
    public Promise<ResourceResponse, ResourceException> handleCreate(Context context, CreateRequest request) {
        try {
            String id = request.getNewResourceId() == null
                    ? UUID.randomUUID().toString()
                    : request.getNewResourceId();
            Map<String, Object> object = request.getContent().asMap();
            object.put("_id", id);

            if (jobExists(id)) {
                throw new PreconditionFailedException("Schedule already exists");
            }

            ScheduleConfig scheduleConfig = new ScheduleConfig(new JsonValue(object));

            // Check defaults
            if (scheduleConfig.isEnabled() == null) {
                scheduleConfig.setEnabled(true);
            }
            if (scheduleConfig.isPersisted() == null) {
                scheduleConfig.setPersisted(true);
            }

            addSchedule(scheduleConfig, id, false);

            return newResourceResponse(id, null, getSchedule(context, id)).asPromise();
        } catch (ParseException e) {
            return new BadRequestException(e.getMessage(), e).asPromise();
        } catch (ObjectAlreadyExistsException e) {
            return new PreconditionFailedException(e.getMessage(), e).asPromise();
        } catch (SchedulerException e) {
            return new InternalServerErrorException(e.getMessage(), e).asPromise();
        } catch (JsonException e) {
            return new BadRequestException("Error creating schedule", e).asPromise();
        } catch (ResourceException e) {
            return e.asPromise();
        } catch (Exception e) {
            return new InternalServerErrorException(e).asPromise();
        }
    }

    @Override
    public Promise<ResourceResponse, ResourceException> handleRead(Context context, ReadRequest request) {
        try {
            if (request.getResourcePathObject().isEmpty()) {
                throw new BadRequestException("Empty resourceId");
            }
            // Get the schedule
            JsonValue schedule = getSchedule(context, request.getResourcePath());
            // Return the result
            return newResourceResponse(request.getResourcePath(), null, schedule).asPromise();
        } catch (SchedulerException e) {
            return new InternalServerErrorException(e.getMessage(), e).asPromise();
        } catch (JsonValueException e) {
            return new BadRequestException(e.getMessage(), e).asPromise();
        } catch (ResourceException e) {
            return e.asPromise();
        } catch (Exception e) {
            return new InternalServerErrorException(e).asPromise();
        }
    }

    @Override
    public Promise<ResourceResponse, ResourceException> handleUpdate(Context context, UpdateRequest request) {
        try {
            if (request.getResourcePathObject().isEmpty()) {
                throw new BadRequestException("Empty resourceId");
            }
            Map<String, Object> object = request.getContent().asMap();
            object.put("_id", request.getResourcePath());

            // Default incoming config to "persisted" if not specified
            Object persistedValue = object.get(SchedulerService.SCHEDULE_PERSISTED);
            if (persistedValue == null) {
                object.put(SchedulerService.SCHEDULE_PERSISTED, true);
            }

            ScheduleConfig scheduleConfig = new ScheduleConfig(new JsonValue(object));

            if (!jobExists(request.getResourcePath())) {
                throw new NotFoundException();
            } else {
                // Update the Job
                addSchedule(scheduleConfig, request.getResourcePath(), true);
                return newResultPromise(newResourceResponse(request.getResourcePath(), null,
                        getSchedule(context, request.getResourcePath())));
            }
        } catch (ParseException e) {
            return new BadRequestException(e.getMessage(), e).asPromise();
        } catch (ObjectAlreadyExistsException e) {
            return new ConflictException(e.getMessage(), e).asPromise();
        } catch (SchedulerException e) {
            return new InternalServerErrorException(e.getMessage(), e).asPromise();
        } catch (JsonException e) {
            return new BadRequestException("Error updating schedule", e).asPromise();
        } catch (ResourceException e) {
            return e.asPromise();
        } catch (Exception e) {
            return new InternalServerErrorException(e).asPromise();
        }
    }

    @Override
    public Promise<ResourceResponse, ResourceException> handleDelete(Context context, DeleteRequest request) {
        try {
            if (request.getResourcePathObject().isEmpty()) {
                throw new BadRequestException("Empty resourceId");
            }
            if (!jobExists(request.getResourcePath())) {
                throw new NotFoundException();
            }
            // Get the schedule
            JsonValue schedule = getSchedule(context, request.getResourcePath());
            // Delete the schedule
            deleteSchedule(request.getResourcePath());
            // Return the deleted resource
            return newResourceResponse(request.getResourcePath(), null, schedule).asPromise();
        } catch (JsonException e) {
            return new BadRequestException("Error deleting schedule", e).asPromise();
        } catch (SchedulerException e) {
            return new InternalServerErrorException(e.getMessage(), e).asPromise();
        } catch (ResourceException e) {
            return e.asPromise();
        } catch (Exception e) {
            return new InternalServerErrorException(e).asPromise();
        }
    }

    @Override
    public Promise<QueryResponse, ResourceException> handleQuery(final Context context, final QueryRequest request,
            final QueryResourceHandler handler) {
        try {
            QueryFilter<JsonPointer> queryFilter = request.getQueryFilter();
            if (null == queryFilter) {
                queryFilter = QueryFilter.alwaysTrue();
            }

            // default the sortKeys to include the _id field for consistency.
            final List<SortKey> sortKeys = request.getSortKeys();
            sortKeys.add(SortKey.ascendingOrder(FIELD_CONTENT_ID));

            // if a queryId is set, verify that it is query-all-ids; no other is supported.
            final String queryId = request.getQueryId();
            if (null != queryId && !QUERY_ALL_IDS.equals(queryId)) {
                throw new BadRequestException("only query-id of '" + QUERY_ALL_IDS + "' is supported.");
            }

            // Get all the jobs and then filter them.
            final List<String> allJobs = new ArrayList<>();
            allJobs.addAll(Arrays.asList(getJobNames(persistentScheduler)));
            allJobs.addAll(Arrays.asList(getJobNames(inMemoryScheduler)));
            final List<JsonValue> results = new ArrayList<>();
            for (final String jobName : allJobs) {
                if (queryId != null) {  // query-all-ids
                    results.add(json(object(field(FIELD_CONTENT_ID, jobName))));
                } else {
                    final JsonValue schedule = getSchedule(context, jobName);
                    if (queryFilter.accept(JSONVALUE_FILTER_VISITOR, schedule)) {
                        results.add(schedule);
                    }
                }
            }
            final int totalResultsFound = results.size();

            // we have to sort all the records so that pagination works consistently
            Collections.sort(results, JsonUtil.getComparator(sortKeys));

            // default the pageSize to all the records if it isn't set.
            int pageSize = request.getPageSize();
            if (pageSize <= 0) {
                pageSize = totalResultsFound;
            }

            // default the page results Cookie to 0 if not passed in or isn't a integer.
            String pagedResultsCookie = request.getPagedResultsCookie();
            if (null == pagedResultsCookie || pagedResultsCookie.isEmpty() || !pagedResultsCookie.matches("\\d+")) {
                pagedResultsCookie = "0";
            }

            // calculate what the last index will be after this page is returned, -1 if last page.
            final int fromIndex = request.getPagedResultsOffset() + Integer.valueOf(pagedResultsCookie);

            // extract the desired results.
            final List<JsonValue> pageOfResults =
                    results.subList(fromIndex, min(fromIndex + pageSize, totalResultsFound));

            final String newPageCookie = fromIndex + pageSize >= totalResultsFound
                    ? null
                    : Integer.toString(fromIndex + pageSize);

            for (final JsonValue result : pageOfResults) {
                handler.handleResource(
                        newResourceResponse(result.get(FIELD_CONTENT_ID).asString(), null, json(result)));
            }
            return newQueryResponse(newPageCookie, CountPolicy.EXACT, totalResultsFound).asPromise();
        } catch (JsonException e) {
            return new BadRequestException("Error performing query", e).asPromise();
        } catch (SchedulerException e) {
            return new InternalServerErrorException(e.getMessage(), e).asPromise();
        } catch (ResourceException e) {
            return e.asPromise();
        } catch (Exception e) {
            return new InternalServerErrorException(e).asPromise();
        }
    }

    /**
     * Gets the job names of the scheduler, returning an empty array if no jobs are found.
     *
     * @param scheduler to get the jobNames from.
     * @return array of job names, or empty array.
     * @throws SchedulerException when there is trouble calling {@link Scheduler#getJobNames(String)}
     */
    private String[] getJobNames(Scheduler scheduler) throws SchedulerException {
        String[] jobNames = scheduler.getJobNames(GROUP_NAME);
        return null == jobNames
                ? new String[0]
                : jobNames;
    }

    @Override
    public Promise<ActionResponse, ResourceException> handleAction(Context context, ActionRequest request) {
        try {
            final Map<String, String> params = request.getAdditionalParameters();
            final String action = request.getAction();

            switch (request.getActionAsEnum(JobAction.class)) {
                case create:
                    String id = UUID.randomUUID().toString();
                    params.put(ResourceResponse.FIELD_CONTENT_ID, id);
                    if (jobExists(id)) {
                        throw new BadRequestException("Schedule already exists");
                    }
                    CreateRequest createRequest = Requests.newCreateRequest(id, new JsonValue(params));
                    ResourceResponse response = handleCreate(context, createRequest).getOrThrow();
                    return newActionResponse(response.getContent()).asPromise();
                case listCurrentlyExecutingJobs:
                    return newActionResponse(getCurrentlyExecutingJobs()).asPromise();
                case pauseJobs:
                    persistentScheduler.pauseAll();
                    inMemoryScheduler.pauseAll();
                    return newActionResponse(json(object(field("success",true)))).asPromise();
                case resumeJobs:
                    persistentScheduler.resumeAll();
                    inMemoryScheduler.resumeAll();
                    return newActionResponse(json(object(field("success",true)))).asPromise();
                default:
                    throw new BadRequestException("Unknown action: " + action);
            }
        } catch (JsonException e) {
            return new BadRequestException("Error performing action " + request.getAction(), e).asPromise();
        } catch (SchedulerException e) {
            return new InternalServerErrorException(e.getMessage(), e).asPromise();
        } catch (ResourceException e) {
            return e.asPromise();
        } catch (Exception e) {
            return new InternalServerErrorException(e).asPromise();
        }
    }

    /**
     * Schedules a job.
     *
     * @param scheduleConfig    The schedule configuration
     * @param jobName           The job name
     * @param update            Whether to delete the old job if present
     * @return                  true if the job was scheduled, false otherwise
     * @throws SchedulerException if unable to create schedule
     * @throws ParseException if unable to parse scheduler configuration
     */
    private boolean addSchedule(ScheduleConfig scheduleConfig, String jobName, boolean update)
            throws SchedulerException, ParseException {
        try {
            // Lock access to the scheduler so that a schedule is not added during a config update
            synchronized (LOCK) {
                // Determine the schedule class based on whether the job has concurrent execution enabled/disabled
                final Class<?> scheduleClass = scheduleConfig.getConcurrentExecution()
                        ? SchedulerServiceJob.class
                        : StatefulSchedulerServiceJob.class;

                // Attempt to add the schedule
                if (scheduleConfig.getCronSchedule() != null
                        && scheduleConfig.getCronSchedule().length() > 0) {
                    final JobDetail job = new JobDetail(jobName, GROUP_NAME, scheduleClass);
                    job.setVolatility(scheduleConfig.isPersisted());
                    job.setJobDataMap(createJobDataMap(jobName, scheduleConfig));
                    final Trigger trigger = createTrigger(scheduleConfig, jobName);
                    final Scheduler scheduler = scheduleConfig.isPersisted() ? persistentScheduler : inMemoryScheduler;

                    if (update) {
                        // Update the job by first deleting it, then scheduling the new version
                        deleteSchedule(jobName);
                    }

                    // check if it is enabled
                    if (scheduleConfig.isEnabled()) {
                        // Set to non-durable so that jobs won't persist after last firing
                        job.setDurability(false);
                        // Schedule the Job (with trigger)
                        scheduler.scheduleJob(job, trigger);
                        logger.info("Job {} scheduled with schedule {}, timezone {}, start time {}, end time {}.",
                                jobName, scheduleConfig.getCronSchedule(), scheduleConfig.getTimeZone(),
                                scheduleConfig.getStartTime(), scheduleConfig.getEndTime());
                    } else {
                        // Set the job to durable so that it can exist without a trigger (since the job is "disabled")
                        job.setDurability(true);
                        // Add the job (no trigger)
                        scheduler.addJob(job, false);
                        logger.info("Job {} added with schedule {}, timezone {}, start time {}, end time {}.",
                                jobName, scheduleConfig.getCronSchedule(), scheduleConfig.getTimeZone(),
                                scheduleConfig.getStartTime(), scheduleConfig.getEndTime());
                    }

                }
            }
        } catch (ParseException ex) {
            logger.warn("Parsing of scheduler configuration failed, can not create scheduler service for "
                    + jobName + ": " + ex.getMessage(), ex);
            throw ex;
        } catch (ObjectAlreadyExistsException ex) {
            throw ex;
        } catch (SchedulerException ex) {
            logger.warn("Failed to create scheduler service for " + jobName + ": " + ex.getMessage(), ex);
            throw ex;
        }

        return true;
    }

    /**
     * Deletes a schedule from the scheduler
     *
     * @param jobName the job name associated with this schedule.
     * @throws SchedulerException if unable to delete schedule
     */
    private void deleteSchedule(String jobName) throws SchedulerException {
        if (inMemoryScheduler.getJobDetail(jobName, GROUP_NAME) != null) {
            inMemoryScheduler.deleteJob(jobName, GROUP_NAME);
        }
        if (persistentScheduler.getJobDetail(jobName, GROUP_NAME) != null) {
            persistentScheduler.deleteJob(jobName, GROUP_NAME);
        }
    }

    /**
     * Creates and returns a CronTrigger using the supplied schedule configuration.
     *
     * @param scheduleConfig    The schedule configuration
     * @param jobName           The name of the job to associate the trigger with
     * @return                  The created Trigger
     * @throws ParseException if unable to create trigger
     */
    private CronTrigger createTrigger(ScheduleConfig scheduleConfig, String jobName) throws ParseException {
        String cronSchedule = scheduleConfig.getCronSchedule();
        Date startTime = scheduleConfig.getStartTime();
        Date endTime = scheduleConfig.getEndTime();
        String misfirePolicy = scheduleConfig.getMisfirePolicy();
        TimeZone timeZone = scheduleConfig.getTimeZone();

        CronTrigger trigger = new CronTrigger("trigger-" + jobName, GROUP_NAME, cronSchedule);
        trigger.setJobName(jobName);
        trigger.setJobGroup(GROUP_NAME);

        if (startTime != null) {
            trigger.setStartTime(startTime); // TODO: review time zone consistency with cron trigger timezone
        }

        if (endTime != null) {
            trigger.setEndTime(endTime);
        }
        if (timeZone != null) {
            trigger.setTimeZone(timeZone);
        }

        if (misfirePolicy.equals(MISFIRE_POLICY_FIRE_AND_PROCEED)) {
            trigger.setMisfireInstruction(CronTrigger.MISFIRE_INSTRUCTION_FIRE_ONCE_NOW);
        } else if (misfirePolicy.equals(MISFIRE_POLICY_DO_NOTHING)) {
            trigger.setMisfireInstruction(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING);
        }
        return trigger;
    }

    /**
     * Creates and returns a JobDataMap using the supplied schedule configuration.
     *
     * @param scheduleConfig    The schedule configuration
     * @return                  The created JobDataMap
     */
    private JobDataMap createJobDataMap(String jobName, ScheduleConfig scheduleConfig) {
        String invokeService = scheduleConfig.getInvokeService();
        Object invokeContext = scheduleConfig.getInvokeContext();
        String invokeLogLevel = scheduleConfig.getInvokeLogLevel();
        JobDataMap map = new JobDataMap();
        map.put(ScheduledService.CONFIG_NAME, "scheduler"+ (jobName != null ? "-" + jobName : ""));
        map.put(ScheduledService.CONFIGURED_INVOKE_SERVICE, invokeService);
        map.put(ScheduledService.CONFIGURED_INVOKE_CONTEXT, invokeContext);
        map.put(ScheduledService.CONFIGURED_INVOKE_LOG_LEVEL, invokeLogLevel);
        map.put(CONFIG, scheduleConfig.getConfig().toString());
        return map;
    }

    /**
     * Determines if a job already exists.
     *
     * @param jobName       The name of the job
     * @return              True if the job exists, false otherwise
     * @throws SchedulerException if unable to get job detail for check
     */
    private boolean jobExists(String jobName) throws SchedulerException {
        final boolean existsInMemory = inMemoryScheduler.getJobDetail(jobName, GROUP_NAME) != null;
        final boolean existsInPersistent = persistentScheduler.getJobDetail(jobName, GROUP_NAME) != null;
        return existsInMemory || existsInPersistent;
    }

    /**
     * Returns a JsonValue representation of a schedule with the supplied name from the supplied scheduler.
     *
     * @param scheduleName the name of the scheduler
     * @return the JsonValue representation of the schedule
     * @throws SchedulerException if unable to get job detail
     * @throws NotFoundException if job does not exist
     */
    private JsonValue getSchedule(Context context, String scheduleName) throws SchedulerException, IOException {
        final Scheduler scheduler;
        if (inMemoryScheduler.getJobDetail(scheduleName, GROUP_NAME) != null) {
            scheduler = inMemoryScheduler;
        } else if (persistentScheduler.getJobDetail(scheduleName, GROUP_NAME) != null) {
            scheduler = persistentScheduler;
        } else {
            throw new NotFoundException("Schedule does not exist");
        }
        final JobDetail job = scheduler.getJobDetail(scheduleName, GROUP_NAME);
        final JsonValue resultMap =
                new ScheduleConfig(json(Json.readJson((String) job.getJobDataMap().get(CONFIG)))).getConfig();
        resultMap.put(TRIGGERS, getAllTriggersOfJob(context, scheduler, job).getObject());
        resultMap.put(NEXT_RUN_DATE, getNextFireTime(scheduler, job));
        resultMap.put(ResourceResponse.FIELD_CONTENT_ID, scheduleName);
        return resultMap;
    }

    private JsonValue getAllTriggersOfJob(final Context context, final Scheduler scheduler, final JobDetail jobDetail)
            throws SchedulerException {
        final JsonValue results = json(array());
        final Trigger[] triggers = scheduler.getTriggersOfJob(jobDetail.getName(), jobDetail.getGroup());
        for (final Trigger trigger : triggers) {
            final String triggerId = getTriggerId(GROUP_NAME, trigger.getKey().getName());
            final ReadRequest readRequest = Requests.newReadRequest("/scheduler/trigger", triggerId);
            try {
                final ResourceResponse triggerResponse = connectionFactory.getConnection().read(context, readRequest);
                results.add(triggerResponse.getContent().getObject());
            } catch (final ResourceException e) {
                logger.warn("Unable to read trigger: {}", triggerId, e);
            }
        }
        return results;
    }

    private String getNextFireTime(final Scheduler scheduler, final JobDetail jobDetail) throws SchedulerException {
        final Trigger[] triggers = scheduler.getTriggersOfJob(jobDetail.getName(), jobDetail.getGroup());
        if (triggers.length <= 0 ) {
            return null;
        }
        Date fireTime = triggers[0].getFireTimeAfter(new Date());
        for (final Trigger trigger : triggers) {
            final Date newFireTime = trigger.getFireTimeAfter(new Date());
            if (newFireTime.before(fireTime)) {
                fireTime = newFireTime;
            }
        }
        return new DateTime(fireTime).withZone(DateTimeZone.UTC).toString();
    }

    private JsonValue getCurrentlyExecutingJobs() throws SchedulerException, IOException {
        final JsonValue currentlyExecutingJobs = json(array());
        List<?> jobs = persistentScheduler.getCurrentlyExecutingJobs();
        for (final Object job : jobs) {
            final JobDetail jobDetail = ((JobExecutionContext) job).getJobDetail();
            final JsonValue config =
                    json(Json.readJson((String) jobDetail.getJobDataMap().get(CONFIG)));
            config.put(ResourceResponse.FIELD_CONTENT_ID, jobDetail.getName());
            currentlyExecutingJobs.add(new ScheduleConfig(config).getConfig().getObject());
        }
        jobs = inMemoryScheduler.getCurrentlyExecutingJobs();
        for (final Object job : jobs) {
            final JobDetail jobDetail = ((JobExecutionContext) job).getJobDetail();
            final JsonValue config =
                    json(Json.readJson((String) jobDetail.getJobDataMap().get(CONFIG)));
            config.put(ResourceResponse.FIELD_CONTENT_ID, jobDetail.getName());
            currentlyExecutingJobs.add(new ScheduleConfig(config).getConfig().getObject());
        }
        return currentlyExecutingJobs;
    }
}
