package io.github.nestorsokil.taskmaster.reaper;

import io.github.nestorsokil.taskmaster.config.TaskmasterConfig;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;

import java.util.Properties;

/**
 * Owns the Quartz scheduler and the repeating reaper jobs.
 */
@Slf4j
public final class ReaperScheduler implements AutoCloseable {

    private final Scheduler scheduler;

    private ReaperScheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    public static ReaperScheduler start(
            HeartbeatReaper heartbeatReaper,
            RetentionReaper retentionReaper,
            DeadlineReaper deadlineReaper,
            TaskmasterConfig config) throws SchedulerException {

        var scheduler = createScheduler();
        scheduleJob(scheduler, "heartbeat", heartbeatReaper::reap, config.reaper().intervalMs());
        scheduleJob(scheduler, "retention", retentionReaper::reap, config.retention().intervalMs());
        scheduleJob(scheduler, "deadline", deadlineReaper::reap, 30_000L);
        scheduler.start();
        return new ReaperScheduler(scheduler);
    }

    @Override
    public void close() {
        try {
            if (scheduler == null || scheduler.isShutdown()) {
                return;
            }
            scheduler.shutdown(true);
        } catch (SchedulerException e) {
            log.warn("Scheduler shutdown error", e);
        }
    }

    private static Scheduler createScheduler() throws SchedulerException {
        var props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", "taskmaster");
        props.setProperty("org.quartz.threadPool.threadCount", "3");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        return new StdSchedulerFactory(props).getScheduler();
    }

    private static void scheduleJob(Scheduler scheduler, String name, Runnable task, long intervalMs)
            throws SchedulerException {
        var jobData = new JobDataMap();
        jobData.put(RunnableJob.KEY, task);

        JobDetail detail = JobBuilder.newJob(RunnableJob.class)
                .withIdentity(name, "reapers")
                .usingJobData(jobData)
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(name, "reapers")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInMilliseconds(intervalMs)
                        .repeatForever())
                .startNow()
                .build();

        scheduler.scheduleJob(detail, trigger);
    }
}
