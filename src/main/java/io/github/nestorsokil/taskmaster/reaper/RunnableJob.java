package io.github.nestorsokil.taskmaster.reaper;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/**
 * Quartz job adapter that delegates execution to a {@link Runnable} stored
 * in the job's {@code JobDataMap}. Lets us schedule plain reaper methods
 * without implementing {@link Job} on each reaper class.
 */
public class RunnableJob implements Job {

    public static final String KEY = "runnable";

    @Override
    public void execute(JobExecutionContext ctx) throws JobExecutionException {
        Runnable task = (Runnable) ctx.getMergedJobDataMap().get(KEY);
        try {
            task.run();
        } catch (Exception e) {
            throw new JobExecutionException(e);
        }
    }
}
