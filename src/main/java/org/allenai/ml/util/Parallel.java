package org.allenai.ml.util;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import java.util.List;
import java.util.concurrent.*;

import static java.util.stream.Collectors.toList;

@Slf4j
public class Parallel {

    private Parallel() {
        // intentional no-op
    }

    public interface MapReduceDriver<T, D>  {
        D newData();
        void update(D data, T elem);
        void merge(D a, D b);
    }

    public static <T, D> D mapReduce(List<T> data, MapReduceDriver<T, D> driver) {
        return mapReduce(data, driver, new MROpts());
    }

    @RequiredArgsConstructor
    public static class MROpts {
        public int numWorkers = Runtime.getRuntime().availableProcessors();
        public ExecutorService executorService;
        public double maxSecs = 1000000.0;

        private static class CustomThreadFactory implements ThreadFactory {

            private final String id;
            public CustomThreadFactory(String id) {
                this.id = id;
            }

            public Thread newThread(Runnable r) {
                return new Thread(r, id);
            }
        }

        public static MROpts withThreads(int numThreads) {
            return withIdAndThreads("mr-opts", numThreads);
        }

        public static MROpts withIdAndThreads(String id, int numThreads) {
            val opts = new MROpts();
            opts.numWorkers = numThreads;
            ThreadFactory namedThreadFactory = new CustomThreadFactory(id);
            opts.executorService = Executors.newFixedThreadPool(numThreads, namedThreadFactory );
            return opts;
        }

    }

    @SneakyThrows
    /**
     * Trys to shutdown a thread pool and will try to do so over and over until
     * it has been successfully shutdown. The calling thread is halted up to `maxMillis`
     * to wait for the Thread pool to shutdown
     * @returns true if shutdown was clean and false if timed-out
     */
    public static boolean shutdownExecutor(ExecutorService executorService, long maxMillis) {
        final long sleepInterval = 100;
        long slept = 0L;
        executorService.shutdown();
        while (true) {
            executorService.shutdownNow();
            if (executorService.isShutdown()) {
                return true;
            }
            Thread.sleep(sleepInterval);
            slept += sleepInterval;
            if ((slept / sleepInterval) % 10 == 0) {
                log.info("Slept total of {} ms waiting for thread pool shutdown", slept);
            }
            if (slept >= maxMillis) {
                return false;
            }
        }
    }

    @SneakyThrows()
    /**
     * Perform an in-memory version of MapReduce targeted at accumulating sufficient statistics
     * from a data-set.
     */
    public static <T, D> D mapReduce(List<T> data, MapReduceDriver<T, D> driver, MROpts mrOpts) {
        ExecutorService executorService = mrOpts.executorService != null ?
            mrOpts.executorService :
            Executors.newFixedThreadPool(mrOpts.numWorkers);
        @RequiredArgsConstructor
        class Worker implements Runnable {
            private final List<T> dataSlice;
            private final D data = driver.newData();
            public void run() {
                for (T t : dataSlice) {
                    driver.update(data, t);
                }
            }
        }
        List<Worker> workers = Functional.partition(data, mrOpts.numWorkers).stream()
            .map(Worker::new)
            .collect(toList());
        List<Future<?>> futures =  workers.stream().map(executorService::submit).collect(toList());
        for (Future<?> future : futures) {
            future.get((long) mrOpts.maxSecs * 1000, TimeUnit.MILLISECONDS);
        }
        D finalData = driver.newData();
        for (Worker worker : workers) {
            driver.merge(finalData, worker.data);
        }
        // we created this thread pool, need to clean up
        if (mrOpts.executorService == null) {
            shutdownExecutor(executorService, (long) mrOpts.maxSecs * 1000);
        }
        return finalData;
    }
}
