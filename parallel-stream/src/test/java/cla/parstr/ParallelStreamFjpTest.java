package cla.parstr;

import java.lang.annotation.Retention;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.LongStream;
import org.testng.ITestResult;
import org.testng.annotations.*;
import static java.lang.System.out;
import static java.lang.annotation.RetentionPolicy.*;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

//https://stackoverflow.com/questions/5457983/how-to-run-testng-programmatically-using-java
@Listeners(ParallelStreamFjpTestListener.class)
public class ParallelStreamFjpTest {

    //See README.md
    @Retention(RUNTIME) @interface Reproducible {}
    @Retention(RUNTIME) @interface NotReproducible {}

    /**
     * Repeat tests since we're testing concurrency stuff.
     * @Reproducible tests were tested up to 100_000 with STREAM_SIZE=1(most unfavorable)
     * (Do long runs with maven rather than in your IDE,
     * otherwise it will get slower and slower with report panel memory usage increasing)
     */
    //static final int TEST_TIMES = 100_000;
    static final int TEST_TIMES = 10;

    /**
     * The bigger, the more likely the framework will use a ForkJoinPool.
     */
    static final long STREAM_SIZE = chooseStreamSize();
    static long chooseStreamSize() {
        //return 1L; //very much sure to see >= 1/1000 @NotReproducible test fail (in fact almost all)
        return 100; //sure to see >= 1/1000 @NotReproducible test fail (lots of them)
        //return 20_000_000L; //not sure to see >= 1/1000 @NotReproducible test fail

    }

    /**
     * Used to check that we're really computing something.
     */
    static final long EXPECTED_SUM = expectedSum(STREAM_SIZE);
    static long expectedSum(long N) {
        return Math.multiplyExact(N, N + 1) / 2; //"arithmetic sum" formula
    }

    /**
     * The parallel streams framework default FJP, and a custom FJP that the trick allows using.
     */
    static final ForkJoinPool commonPool = ForkJoinPool.commonPool(),
                              anotherPool = new ForkJoinPool(
                                    commonPool.getParallelism(),
                                    commonPool.getFactory(),
                                    commonPool.getUncaughtExceptionHandler(),
                                    commonPool.getAsyncMode()
                              );

    static {
        out.printf("TEST_TIMES: %d, STREAM_SIZE: %d, availableProcessors: %d, parallelism: %d%n",
            TEST_TIMES,
            STREAM_SIZE,
            Runtime.getRuntime().availableProcessors(), //2
            commonPool.getParallelism()
        );
        //sleep(1, TimeUnit.MINUTES);
    }

    /**
     * Illustrates the default common pool execution; it is the experiment's "control sample".
     */
    @NotReproducible @Test(invocationCount=TEST_TIMES, enabled = true)
    public void executesInCommonPool() {
        assertTrue(executesInPool(commonPool));
    }

    /**
     * Do use the trick.
     */
    @Reproducible @Test(invocationCount=TEST_TIMES, enabled = true)
    public void executesInAnotherPool() {
        assertTrue(anotherPool.invoke(new ExecutesInPool(anotherPool)));
    }

    //Sanity checks: those don't test what we really want to make sure, only internal consistency VVVVVVVVVVVVVV
    @Reproducible @Test(invocationCount=TEST_TIMES, enabled = true)
    public void executesInCommonPool_sanityCheck() {
        assertFalse(executesInPool(anotherPool));
    }

    @Reproducible @Test(invocationCount=TEST_TIMES, enabled = true)
    public void executedInAnotherPool_sanityCheck1() {
        assertFalse(commonPool.invoke(new ExecutesInPool(anotherPool)));
    }

    @Reproducible @Test(invocationCount=TEST_TIMES, enabled = true)
    public void executesInAnotherPool_sanityCheck2() {
        assertFalse(anotherPool.invoke(new ExecutesInPool(commonPool)));
    }

    @NotReproducible @Test(invocationCount=TEST_TIMES, enabled = true)
    public void executesInAnotherPool_sanityCheck3() {
        assertTrue(commonPool.invoke(new ExecutesInPool(commonPool)));
    }
    //Sanity checks: those don't test what we really want to make sure, only internal consistency ^^^^^^^^^^^^^^

    static class ExecutesInPool extends RecursiveTask<Boolean> {
        private final ForkJoinPool pool;
        ExecutesInPool(ForkJoinPool pool) { this.pool = pool; }
        @Override protected Boolean compute() { return executesInPool(pool); }
    }

    static boolean executesInPool(ForkJoinPool pool) {
        //The framework will use the caller thread (here the test thread) instead of
        // the pool, when the remaining work is small enough (Spliterator.trySplit()==null).
        //That's why we use a big stream, and also assert that
        // the lambda is called "at least once" in the pool (not "every time").
        AtomicBoolean neverInUnexpectedThread = new AtomicBoolean(true),
                      atLeastOnceInPool = new AtomicBoolean(false);
        Thread testThread = Thread.currentThread();

        long sum = LongStream.rangeClosed(1L, STREAM_SIZE).parallel().reduce(0L, (s, i) -> {
            //out.println("toto"); //?More likely to use the FJP with this, for a given STREAM_SIZE
            Thread lambdaThread = Thread.currentThread();

            //Using lazySet() because set() is SLOW in this context (which is annoying for big STREAM_SIZEs).
            // We will have to write to a volatile field before reading to ensure visibility, 
            // see comment on the lazySetFence field.
            if(!isPoolOrTestThread(lambdaThread, testThread, pool)) neverInUnexpectedThread.lazySet(false);

            if(isPoolThread(lambdaThread, pool)) atLeastOnceInPool.lazySet(true);

            return s + i;
        });
        assertEquals(EXPECTED_SUM, sum, "sum bug");//Make sure computation is really done

        //lazySetFence = true; //Need to force visibility now
        return neverInUnexpectedThread.get() && atLeastOnceInPool.get();
    }

    /**
     * lazySet() javadoc is poor, see Doug Lea's explanation instead:
     * http://bugs.java.com/bugdatabase/view_bug.do?bug_id=6275329
     *
     * "As probably the last little JSR166 follow-up for Mustang,
     * we added a "lazySet" method to the Atomic classes
     * (AtomicInteger, AtomicReference, etc). This is a niche
     * method that is sometimes useful when fine-tuning code using
     * non-blocking data structures. The semantics are
     * that the write is guaranteed not to be re-ordered with any
     * previous write, but may be reordered with subsequent operations
     * (or equivalently, might not be visible to other threads)
     * ____until some other volatile write or synchronizing action occurs____
     *
     * Therefore, i can write to this field to make sure the lazySet is visible to subsequent gets.
     */
    static volatile boolean lazySetFence;

    static boolean isPoolThread(Thread lambdaThread, ForkJoinPool pool) {
        if(!(lambdaThread instanceof ForkJoinWorkerThread)) return false;
        return ((ForkJoinWorkerThread) lambdaThread).getPool() == pool;
    }

    static boolean isPoolOrTestThread(Thread lambdaThread, Thread testThread, ForkJoinPool pool) {
        //Divide and conquer's bottom sequential iterations may execute in caller thread instead of FJP
        return lambdaThread == testThread || isPoolThread(lambdaThread, pool);
    }

    //Test support stuff
    private Instant whenTestMethodStarted;
    private long testMethodNumber = 0;
    static final int LOG_EVERY = TEST_TIMES/100 == 0 ? 1 : TEST_TIMES/100;
    @BeforeMethod public void before() {
        this.whenTestMethodStarted = Instant.now();
    }
    @AfterMethod public void after(ITestResult result) {
        if(++testMethodNumber % LOG_EVERY != 0) return; //Avoid OOME(GC overhead limit exceeded) @surefire.StreamPumper
        out.printf(
                "%s #%d took: %s%n",
                result.getMethod().getMethodName(),
                testMethodNumber,
                Duration.between(whenTestMethodStarted, Instant.now())
        );
    }

    private static void sleep(int i, TimeUnit u) {
        try {
            u.sleep(i);
        } catch (InterruptedException e) {
            return;
        }
    }
}
