package com.flowagent.engine.util;

import cn.hutool.core.thread.ExecutorBuilder;
import com.alibaba.ttl.threadpool.TtlExecutors;
import com.google.common.util.concurrent.SimpleTimeLimiter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Async utility with bounded thread pool and timeout support.
 */
@Slf4j
public class AsyncUtil {
    private static final ThreadFactory THREAD_FACTORY = new ThreadFactory() {
        private final ThreadFactory defaultFactory = Executors.defaultThreadFactory();
        private final AtomicInteger threadNumber = new AtomicInteger(1);

        public Thread newThread(Runnable r) {
            Thread thread = this.defaultFactory.newThread(r);
            if (!thread.isDaemon()) {
                thread.setDaemon(true);
            }

            thread.setName("workflow-" + this.threadNumber.getAndIncrement());
            return thread;
        }
    };
    private static ExecutorService executorService;
    private static SimpleTimeLimiter simpleTimeLimiter;

    static {
        initExecutorService(Runtime.getRuntime().availableProcessors() * 2, 50);
    }

    public static void initExecutorService(int core, int max) {
        // IO-intensive thread pool design principles:
        //  1. No CPU-intensive tasks exist; most operations involve database or external API I/O
        //  2. SynchronousQueue: core threads busy → create new thread; max threads busy → CallerRunsPolicy backpressure
        //  3. Idle threads recycled immediately; cpu * 2 core threads sufficient for most IO scenarios
        max = Math.max(core, max);
        executorService = new ExecutorBuilder()
                .setCorePoolSize(core)
                .setMaxPoolSize(max)
                .setKeepAliveTime(0)
                .setKeepAliveTime(0, TimeUnit.SECONDS)
                .setWorkQueue(new SynchronousQueue<Runnable>())
                .setHandler(new ThreadPoolExecutor.CallerRunsPolicy())
                .setThreadFactory(THREAD_FACTORY)
                .buildFinalizable();
        // Wrap with TTL to propagate thread-local context across async boundaries
        executorService = TtlExecutors.getTtlExecutorService(executorService);
        simpleTimeLimiter = SimpleTimeLimiter.create(executorService);
    }


    /**
     * Execute a callable with timeout; if timeout exceeded, throws TimeoutException.
     * If completed within timeout, returns result directly.
     *
     * @param time  timeout duration
     * @param unit  timeout unit
     * @param call  callable to execute
     * @param <T>   return type
     * @return callable result
     */
    public static <T> T callWithTimeLimit(long time, TimeUnit unit, Callable<T> call) throws ExecutionException, InterruptedException, TimeoutException {
        return simpleTimeLimiter.callWithTimeout(call, time, unit);
    }
    public static void execute(Runnable call) {
        executorService.execute(call);
    }

    public static <T> Future<T> submit(Callable<T> t) {
        return executorService.submit(t);
    }

    // Sleep utility for brief pauses
    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            log.error("Thread interrupted", e);
            Thread.currentThread().interrupt();
        }
    }
}


