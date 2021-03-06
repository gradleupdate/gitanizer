package org.wickedsource.gitanizer.subgit;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An OutputStream that checks for a percentage value in the output and reports
 * that percentage to a registered progress listener.
 */
public class SubgitImportProgressListenerOutputStream extends OutputStream {

    private StringBuilder currentContent = new StringBuilder();

    private final static Pattern PROGRESS_PATTERN = Pattern.compile(".*\\s([0-9]+)%$");

    private List<ProgressListener> progressListeners = new ArrayList<>();

    @Override
    public void write(int b) throws IOException {
        currentContent.append(new String(new byte[]{(byte) b}));
        Matcher matcher = PROGRESS_PATTERN.matcher(currentContent);
        if (matcher.find()) {
            String progress = matcher.group(1);
            for (ProgressListener progressMonitor : this.progressListeners) {
                progressMonitor.onProgress(Integer.valueOf(progress));
            }
            currentContent = new StringBuilder();
        }
    }

    /**
     * Register a listener that will be notified each time a progress event was
     * registered in this OutputStream.
     */
    public SubgitImportProgressListenerOutputStream withProgressListener(ProgressListener progressListener) {
        this.progressListeners.add(progressListener);
        return this;
    }

    @Override
    public void close() throws IOException {
        super.close();
    }
}
