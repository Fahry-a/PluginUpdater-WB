package me.webbeck.pluginUpdater;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dedicated executors for blocking network / disk I/O.
 *
 * <p>Previously everything used {@code CompletableFuture.runAsync()} on the
 * common ForkJoinPool, which starves CPU-bound tasks when many 15-30s HTTP
 * calls pile up. These pools isolate that blocking work and bound parallelism.
 */
public final class IoExecutors {
    private final ExecutorService checkPool;
    private final ExecutorService downloadPool;
    private final ExecutorService configPool;

    public IoExecutors() {
        this(Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors())),
                Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())));
    }

    IoExecutors(int checkThreads, int downloadThreads) {
        this.checkPool = Executors.newFixedThreadPool(checkThreads, named("PU-check"));
        this.downloadPool = Executors.newFixedThreadPool(downloadThreads, named("PU-dl"));
        this.configPool = Executors.newSingleThreadExecutor(named("PU-cfg"));
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger n = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }

    public ExecutorService checkPool() {
        return checkPool;
    }

    public ExecutorService downloadPool() {
        return downloadPool;
    }

    public ExecutorService configPool() {
        return configPool;
    }

    public void shutdown() {
        checkPool.shutdownNow();
        downloadPool.shutdownNow();
        configPool.shutdownNow();
    }
}
