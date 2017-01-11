package org.wickedsource.gitanizer.mirror.importing;

import ch.qos.logback.classic.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.wickedsource.gitanizer.mirror.domain.Mirror;
import org.wickedsource.gitanizer.mirror.domain.MirrorRepository;
import org.wickedsource.gitanizer.mirror.importing.logging.ImportLoggerFactory;

import java.time.LocalDateTime;

@Service
public class StatusMessageService {

    private final MirrorRepository mirrorRepository;

    private ImportLoggerFactory importLoggerFactory;

    @Autowired
    public StatusMessageService(MirrorRepository mirrorRepository, ImportLoggerFactory importLoggerFactory) {
        this.mirrorRepository = mirrorRepository;
        this.importLoggerFactory = importLoggerFactory;
    }

    /**
     * Save a status message that describes that a certain progress is made in the
     * synchronization process.
     *
     * @param mirrorId   ID of the mirror for which to store the status message.
     * @param percentage percentage of the progress.
     */
    public void progress(Long mirrorId, int percentage) {
        saveMessage(mirrorId, String.format("Importing. Progress: %d%%.", percentage), false);
    }

    /**
     * Save a status message that describes that some kind of error was encountered in the
     * synchronization process
     *
     * @param mirrorId     ID of the mirror for which to store the status message.
     * @param errorMessage the error message to include in the status message.
     */
    public void error(Long mirrorId, String errorMessage) {
        saveMessage(mirrorId, String.format("Error during import task: %s.", errorMessage), true);
        // not logging error message since it is already logged in the subgit import command
    }

    /**
     * Save a status message that describes that the synchronization process is currently finished
     * and the local mirror is up-to-date to the remote repository.
     *
     * @param mirrorId ID of the mirror for which to store the status message.
     */
    public void upToDate(Long mirrorId) {
        saveMessage(mirrorId, "Mirror is up-to-date.", true);
        Logger logger = importLoggerFactory.getImportLoggerForMirror(mirrorId, "gitanizer");
        logger.info("Mirror is up-to-date.");
    }

    /**
     * Save a status message that says an error occurred.
     *
     * @param mirrorId ID of the mirror for which to store the status message.
     * @param cause    the exception that caused the error
     */
    public void error(Long mirrorId, Throwable cause) {
        saveMessage(mirrorId, "Error during execution of import task. Please check the log.", true);
        Logger logger = importLoggerFactory.getImportLoggerForMirror(mirrorId, "gitanizer");
        logger.error("Error during execution of import task. See above error messages and stacktrace for details", cause);
    }

    /**
     * Save a status message that describes that the synchronization process is currently paused.
     *
     * @param mirrorId ID of the mirror for which to store the status message.
     */
    public void paused(Long mirrorId) {
        saveMessage(mirrorId, "Importing paused.", false);
        Logger logger = importLoggerFactory.getImportLoggerForMirror(mirrorId, "gitanizer");
        logger.info("Importing paused.");
    }

    /**
     * Save a status message that describes that the synchronization process just started and is currently
     * initializing.
     *
     * @param mirrorId ID of the mirror for which to store the status message.
     */
    public void syncStarted(Long mirrorId) {
        saveMessage(mirrorId, "Starting import task.", false);
        Logger logger = importLoggerFactory.getImportLoggerForMirror(mirrorId, "gitanizer");
        logger.info("Starting import task.");
    }

    private void saveMessage(Long mirrorId, String message, boolean updateLastImportDate) {
        Mirror mirror = mirrorRepository.findOne(mirrorId);
        // mirror may be null when deleted
        if (mirror != null) {
            mirror.setLastStatusUpdate(LocalDateTime.now());
            mirror.setLastStatusMessage(message);
            if (updateLastImportDate) {
                mirror.setLastImportFinished(LocalDateTime.now());
            }
            mirrorRepository.save(mirror);
        }
    }

}