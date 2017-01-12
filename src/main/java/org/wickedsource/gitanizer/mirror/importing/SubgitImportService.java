package org.wickedsource.gitanizer.mirror.importing;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.metrics.CounterService;
import org.springframework.boot.actuate.metrics.GaugeService;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.wickedsource.gitanizer.core.SubgitConfiguration;
import org.wickedsource.gitanizer.core.WorkdirConfiguration;
import org.wickedsource.gitanizer.mirror.domain.Mirror;
import org.wickedsource.gitanizer.mirror.importing.logging.ImportLoggerFactory;
import org.wickedsource.gitanizer.mirror.importing.logging.LoggingOutputStream;
import org.wickedsource.gitanizer.subgit.ImportCommand;

import javax.annotation.PreDestroy;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Service that manages a thread pool for subgit import tasks. The maximum number of parallel threads can be configured using the
 * environment variable 'gitanizer.importTasks.maxThreads' which defaults to 5.
 */
@Service
public class SubgitImportService {

    public static final String COUNTER_ACTIVE_TASKS = "gitanizer.counter.importTasks.currentlyActive";

    public static final String COUNTER_QUEUED_TASKS = "gitanizer.counter.importTasks.currentlyQueued";

    public static final String GAUGE_MAX_PARALLEL_TASKS = "gitanizer.gauge.importTasks.maxParallel";

    private StatusMessageService statusMessageService;

    private ExecutorService executor;

    private Map<Long, ImportTask> taskMap = new HashMap<>();

    private SubgitConfiguration subgitConfiguration;

    private WorkdirConfiguration workdirConfiguration;

    private CounterService counterService;

    private ImportLoggerFactory importLoggerFactory;

    private Logger logger = LoggerFactory.getLogger(SubgitImportService.class);

    private static final int MAX_THREADS_DEFAULT = 5;

    @Autowired
    public SubgitImportService(StatusMessageService statusMessageService, SubgitConfiguration subgitConfiguration, WorkdirConfiguration workdirConfiguration, CounterService counterService, GaugeService gaugeService, Environment environment, ImportLoggerFactory importLoggerFactory) {
        this.statusMessageService = statusMessageService;
        this.subgitConfiguration = subgitConfiguration;
        this.workdirConfiguration = workdirConfiguration;
        this.counterService = counterService;
        this.importLoggerFactory = importLoggerFactory;
        int maxParallelTasks = MAX_THREADS_DEFAULT;
        String maxParallelTasksString = environment.getProperty("gitanizer.importTasks.maxThreads");
        if (maxParallelTasksString != null) {
            try {
                maxParallelTasks = Integer.valueOf(maxParallelTasksString);
            } catch (NumberFormatException e) {
                logger.error("Environment variable 'gitanizer.importTasks.maxThreads' has an invalid value: '%s'! Must be an integer! Defaulting to %d", maxParallelTasksString, MAX_THREADS_DEFAULT);
            }
        }
        this.executor = Executors.newFixedThreadPool(maxParallelTasks);
        initMetrics(counterService, gaugeService, maxParallelTasks);
    }

    private void initMetrics(CounterService counterService, GaugeService gaugeService, int maxParallelTasks) {
        gaugeService.submit(GAUGE_MAX_PARALLEL_TASKS, maxParallelTasks);
        // incrementing and decrementing counters once to initialize them to 0
        counterService.increment(COUNTER_ACTIVE_TASKS);
        counterService.decrement(COUNTER_ACTIVE_TASKS);
        counterService.increment(COUNTER_QUEUED_TASKS);
        counterService.decrement(COUNTER_QUEUED_TASKS);
    }

    /**
     * Starts or resumes the subgit import of a remote SVN. Runs asynchronously since it may take a while. The started
     * import task can be cancelled by calling {@link #cancelImport(Long)} with the ID of the mirror. Exposes the following
     * metrics to Spring Boot Actuator:
     * <ul>
     * <li><strong>gitanizer.counter.importTasks.currentlyActive:</strong> number of currently running import tasks</li>
     * <li><strong>gitanizer.counter.importTasks.currentlyQueued:</strong> number of currently queued import tasks that are waiting for a free slot</li>
     * <li><strong>gitanizer.gauge.importTasks.maxParallel:</strong> number of import tasks that are allowed to run in parallel</li>
     * </ul>
     *
     * @param mirror the Mirror object for which to start the subgit import.
     */
    public void startImport(Mirror mirror) {
        Path subgitPath = subgitConfiguration.getSubgitExecutable();
        Path gitPath = subgitConfiguration.getGitExecutable();
        Path workdir = workdirConfiguration.getWorkdir(mirror.getId());
        Path gitDir = workdirConfiguration.getGitDir(mirror.getId());

        StatusMessageListener listener = new StatusMessageListener(mirror.getId(), statusMessageService);

        Logger importLogger = importLoggerFactory.getImportLoggerForMirror(mirror.getId(), "subgit");
        OutputStream logOutputStream = new LoggingOutputStream(importLogger);

        ImportCommand importCommand = new ImportCommand(subgitPath.toString(), gitPath.toString())
                .withTargetGitPath(gitDir.toString())
                .withPassword(mirror.getSvnPassword())
                .withUsername(mirror.getSvnUsername())
                .withSourceSvnUrl(mirror.getRemoteSvnUrl().toString())
                .withListener(listener)
                .withWorkingDirectory(workdir.toString())
                .withLogger(importLogger);

        statusMessageService.syncStarted(mirror.getId());

        Runnable task = () -> {
            try {
                counterService.decrement(COUNTER_QUEUED_TASKS);
                counterService.increment(COUNTER_ACTIVE_TASKS);
                importCommand.execute();
                statusMessageService.upToDate(mirror.getId());
                taskMap.remove(mirror.getId());
            } catch (Exception e) {
                statusMessageService.error(mirror.getId());
                logger.error(String.format("IOException during async execution of subgit import command: %s", importCommand), e);
            } finally {
                IOUtils.closeQuietly(logOutputStream);
                counterService.decrement(COUNTER_ACTIVE_TASKS);
            }
        };

        Future future = executor.submit(task);
        taskMap.put(mirror.getId(), new ImportTask(future, logOutputStream));
        counterService.increment(COUNTER_QUEUED_TASKS);
    }

    /**
     * Returns the file where the import log for the specified mirror is contained.
     *
     * @param mirror the mirror whose logfile to find.
     * @return Path object pointing to the logfile.
     */
    public Path getLogFile(Mirror mirror) {
        Path workdir = workdirConfiguration.getWorkdir(mirror.getId());
        String logFileName = String.format("%s/subgit-import.log", workdir);
        return Paths.get(logFileName);
    }

    /**
     * Cancels the subgit import of the mirror with the given ID. If no import is currently running, does nothing.
     * The import task can be resumed at a later time by calling {@link #startImport(Mirror)}.
     *
     * @param mirrorId ID of the mirror whose import to cancel.
     */
    public void cancelImport(Long mirrorId) {
        ImportTask task = taskMap.get(mirrorId);
        Future future = task.getFuture();
        if (future != null) {
            if (future.cancel(true)) {
                IOUtils.closeQuietly(task.getLogOutputStream());
                counterService.decrement(COUNTER_ACTIVE_TASKS);
                statusMessageService.paused(mirrorId);
                taskMap.remove(mirrorId);
            } else {
                logger.warn("Could not cancel ImportTask for mirror {}", mirrorId);
            }
        }
    }

    /**
     * Returns true if there is an import task currently running for the specified mirror.
     *
     * @param mirrorId ID of the mirror for which to check if there is a task currently running.
     * @return true if there is an import task currently running.
     */
    public boolean isImportRunning(Long mirrorId) {
        return taskMap.containsKey(mirrorId);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

}
