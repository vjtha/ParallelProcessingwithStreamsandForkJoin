# Parallel streams execution

All parallel stream executions use the same (singleton) thread pool: ForkJoinPool.commonPool().
That's why it's very bad to do IO (more generally blocking calls) in a parallel stream:
the blocked thread is unusable by ALL parallel streams in the JVM.
For that, you must use CompletableFuture instead (or ManagedBlocker in some cases). 
That's for another article though.

The goal is to show a few concurrent testing techniques (including running LOTS of them, we'll see it's not so easy)
, to show a few concurrency tricks.


There is a trick to use different ForkJoinPool:
execute the parallel stream while already within that FJP
(this doesn't solve all performance issues with parallel streams, just the one mentioned).
This works because of this:
http://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ForkJoinTask.html#fork--    
"Arranges to asynchronously execute this task in the pool the current task is running in, if applicable, or using the ForkJoinPool.commonPool() if not "

This test class tests this trick:
   * executesInCommonPool illustrates the default common pool execution;
    it is the experiment's "control sample"
    We will see that it is NOT guaranteed.
   * executesInAnotherPool asserts that the trick works
   * tests ending with _sanityCheck test the internal consistency of test results:
    they're not what we really want to test, but if they fail it means the "real tests" contain a bug.

The results are as follow:
   * Tests that assert that work is done in the default common pool are NOT reproducible.
    This illustrates that no guarantee is given by the API that a parallel stream will even execute in parallel.
    This is the case of executesInCommonPool() and executesInAnotherPool_sanityCheck3()
    For some reason including println in the work seem to increase the probability that the common FJP will be used.
    The implementation code is hard to read, i wasn't able to find out where the decision is made.
    Increasing desired parallelism by using -Djava.util.concurrent.ForkJoinPool.common.parallelism=N
     does not make those tests reproducible
   * Tests that assert that work is done in another pool ARE reproducible.
    This illustrates that XXXXXXXXXX

A concurrency trick is used to make the tests faster when TEST_TIMES is big:
using AtomicBoolean.lazySet() instead of set or a volatile field.
lazySet has weaker visibility semantincs, an explanation by Doug Lea is linked to in the comments.

(Another unrelated minor trick shown is how to make the build fail on warnings with maven)

******************************************************************
**********************OOME ISSUES**********************
******************************************************************
This became more time-consuming than the test itself..
I created 2 Issues at surefire and testng:
    https://github.com/cbeust/testng/issues/614
    http://jira.codehaus.org/browse/SUREFIRE-1147             


-->huge surefire leak prevents running a lot of repetitions
       (when setting TEST_TIMES = 100_000)
       (failed tests don't matter, 2 of them are SUPPOSED to be non-reproducible)
-->i'm already using the latest java, maven, etc.. so updating is a non-starter
    (except the jdk, i'm at 8u25 because 8u40 on win7 gives the lovely "access is denied" even with admin and owner status, total control for everybody, UAC off, etc..)
   anyway those leaks have been marked as fixed years ago, but are still there (and they don't come from the jdk)


------------------THE FORK IS AT https://github.com/vandekeiser/maven-surefire-------------------
------------------ALL CHANGES ARE IN maven-surefire-common----------------------------

*****************PATCH SUREFIRE VVVVVVVVVVVVVV*****************      
C:\Users\User\.m2\repository\org\apache\maven\plugins\maven-surefire-plugin\2.18.1\maven-surefire-plugin-2.18.1.jar
cd C:\Users\User\.m2\repository\org\apache\maven\plugins\maven-surefire-plugin\2.18.1\
jar uf maven-surefire-plugin-2.18.1.jar

TARGET: org.apache.maven.surefire.shade.org.apache.maven.shared.utils.cli.StreamPumper#run à vider
* fork https://github.com/apache/maven-shared/tree/trunk/maven-shared-utils
* G:\projets\blog\parallel-stream\patch\maven-shared\maven-shared-utils
* clone, edit org.apache.maven.shared.utils.cli.Stream.Pumper commenting content of run(), 
* mvn -DskipTests clean install
* -->C:\Users\User\.m2\repository\org\apache\maven\shared\maven-shared-utils\0.8-SNAPSHOT\maven-shared-utils-0.8-SNAPSHOT.jar
* run() is commented in org.apache.maven.shared.utils.cli.StreamPumper 
    --> patch is OK

* G:\projets\blog\parallel-stream\patch2\maven-surefire\maven-surefire-common
 pom parent OK:
    <dependency>
    <groupId>org.apache.maven.shared</groupId>
    <artifactId>maven-shared-utils</artifactId>
    <version>0.8-SNAPSHOT</version>
  </dependency>
* mvn clean install
* -->C:\Users\User\.m2\repository\org\apache\maven\surefire\maven-surefire-common\2.19-SNAPSHOT\maven-surefire-common-2.19-SNAPSHOT.jar
* OK, run() is commented in org.apache.maven.surefire.shade.org.apache.maven.shared.utils.cli.StreamPumper 
    --> patch is OK

* clean install maven-surefire\surefire-providers\surefire-testng
* (no changes but need version 2.19-SNAPSHOT)
*****************PATCH SUREFIRE ^^^^^^^^^^^^^^*****************



*****************1st PATCH that avoids OOME VVVVVVVVVVVVVVV*****************
*************APRES NullOutputStream*************
*****org.apache.maven.plugin.surefire.report.Utf8RecodingDeferredFileOutputStream
*****    .deferredFileOutputStream=new NullOutputStream()*****
*****breaking links to the many byte[]***********
____ParallelStreamFjpTest done(600000 tests, 479610passed, 120390failed), OK.____
Tests that are supposed to be reproducible
        [executesInAnotherPool, executesInCommonPool_sanityCheck, executedInAnot
herPool_sanityCheck1, executesInAnotherPool_sanityCheck2]
        but failed:
        <NONE>
Tests that are known to be non-reproducible
        [executesInAnotherPool_sanityCheck3, executesInCommonPool]
        and did fail:
        {executesInAnotherPool_sanityCheck3=57657, executesInCommonPool=62733}
-->Can complete the 100000*6 tests without OOME, but there is still a big leak..
*****************1st PATCH that avoids OOME ^^^^^^^^^^^^^^^*****************


*****************2nd PATCH that avoids OOME VVVVVVVVVVVVVVV*****************
*************APRES NO_ENTRY*************
*****(org.apache.maven.plugin.surefire.report.WrappedReportEntry.original=NO_ENTRY)*****
*****breaking links to the many char[]***********
____ParallelStreamFjpTest done(600000 tests, 480179passed, 119821failed), OK.____
Tests that are supposed to be reproducible
        [executesInAnotherPool, executesInCommonPool_sanityCheck, executedInAnot
herPool_sanityCheck1, executesInAnotherPool_sanityCheck2]
        but failed:
        <NONE>
Tests that are known to be non-reproducible
        [executesInAnotherPool_sanityCheck3, executesInCommonPool]
        and did fail:
        {executesInAnotherPool_sanityCheck3=55880, executesInCommonPool=63941}


Tests run: 0, Failures: 0, Errors: 0, Skipped: 0

[INFO] ------------------------------------------------------------------------
[INFO] BUILD FAILURE
[INFO] ------------------------------------------------------------------------
[INFO] Total time: 02:27 min
[INFO] Finished at: 2015-03-08T19:49:26+01:00
[INFO] Final Memory: 15M/684M
[INFO] ------------------------------------------------------------------------
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:2.
19-SNAPSHOT:test (default-test) on project parallel-stream: Execution default-te
st of goal org.apache.maven.plugins:maven-surefire-plugin:2.19-SNAPSHOT:test fai
led: There was an error in the forked process
[ERROR] java.lang.OutOfMemoryError: Java heap space
[ERROR] at java.util.Arrays.copyOf(Arrays.java:3332)
[ERROR] at java.lang.AbstractStringBuilder.expandCapacity(AbstractStringBuilder.
java:137)
[ERROR] at java.lang.AbstractStringBuilder.ensureCapacityInternal(AbstractString
Builder.java:121)
[ERROR] at java.lang.AbstractStringBuilder.append(AbstractStringBuilder.java:421
)
[ERROR] at java.lang.StringBuffer.append(StringBuffer.java:272)
[ERROR] at org.testng.reporters.TestHTMLReporter.generateTable(TestHTMLReporter.
java:161)
[ERROR] at org.testng.reporters.TestHTMLReporter.generateLog(TestHTMLReporter.ja
va:305)
[ERROR] at org.testng.reporters.TestHTMLReporter.onFinish(TestHTMLReporter.java:
40)
[ERROR] at org.testng.TestRunner.fireEvent(TestRunner.java:1244)
[ERROR] at org.testng.TestRunner.afterRun(TestRunner.java:1035)
*****************2nd PATCH that avoids OOME ^^^^^^^^^^^^^^^*****************



-->Now it's no longer a surefire leak but a TestNG leak


-->
My adventures:
    were supposed to be fixed:
        -surefire leak
        -testng leak
        -visualVM JVM crash on memory dump with allocation stacks

    other bad stuff:
        -maven/surefire crazy inconsistency in passing JVM options (hell on earth)
         see comment in pom:
             <!--1. org.codehaus.plexus.classworlds.launcher.Launcher-->
                <!--Launched first-->
                <!--Gets OOME'd-->
                <!--Does NOT see argLine-->
                <!--Does NOT see systemPropertyVariables/classworlds.conf-->
                <!--Sees ONLY MAVEN_OPTS-->
             <!--2. org.apache.maven.surefire.booter.ForkedBooter-->
                 <!--Not OOME'd-->
                 <!--Sees ONLY argLine-->
                 <!--Does NOT see MAVEN_OPTS-->
        -MAVEN_OPTS has to be an env variable (so can't be set differently on 2 builds)
        -the leaks come from the report module, even though i have "disabled" reports
        -tool issues:
             VVM-->memory profiler snapshot crash with allocation stacks enabled
             JMC-->flight recorder: after one OOME becomes unresponsive and recording is lost



