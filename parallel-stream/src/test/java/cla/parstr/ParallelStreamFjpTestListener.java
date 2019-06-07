package cla.parstr;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.testng.IResultMap;
import org.testng.ITestContext;
import org.testng.ITestNGMethod;
import org.testng.TestListenerAdapter;
import org.testng.internal.ConstructorOrMethod;
import static java.lang.System.out;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;

public class ParallelStreamFjpTestListener extends TestListenerAdapter {

    static final String NONE = "<NONE>";

    @Override public void onFinish(ITestContext testContext) {
        out.printf("%n%nParallelStreamFjpTest done, computing stats..");

        ITestNGMethod[] all = testContext.getAllTestMethods();
        IResultMap failed = testContext.getFailedTests(),
                   passed = testContext.getPassedTests();

        String allReproducible = join(testsAnnotated(all, ParallelStreamFjpTest.Reproducible.class)),
               allNonReproducible = join(testsAnnotated(all, ParallelStreamFjpTest.NotReproducible.class)),
               koFails = joinWithCount(testsAnnotated(failed, ParallelStreamFjpTest.Reproducible.class)),
               okFails = joinWithCount(testsAnnotated(failed, ParallelStreamFjpTest.NotReproducible.class)),
        globalResult = koFails.equals(NONE) ? "OK." : "KO!"  ;

        out.printf(
            "%n%n____ParallelStreamFjpTest done(%d tests, %dpassed, %dfailed), %s____%n" +
            "Tests that are supposed to be reproducible%n\t%s %n\tbut failed: %n\t%s%n" +
            "Tests that are known to be non-reproducible%n\t%s %n\tand did fail: %n\t%s%n%n%n",
            failed.size()+passed.size(), passed.size(), failed.size(), globalResult,
            allReproducible, koFails,
            allNonReproducible, okFails
        );

        //testng seems unable to deal with that many tests,
        // the test output phase takes longer and longer even if disbling XML report generation,
        // and with even more tests, it crashes with OOME.
        //System.exit(0);
    }

    static String join(Stream<Method> testMethods) {
        Set<String> names = testMethods.map(Method::getName).collect(Collectors.toSet());
        return names.isEmpty() ? NONE : names.toString();
    }

    static String joinWithCount(Stream<Method> testMethods) {
        Map<String, Long> names = testMethods.collect(groupingBy(
            Method::getName,
            counting()
        ));
        return names.isEmpty() ? NONE : names.toString();
    }

    static Stream<Method> testsAnnotated(ITestNGMethod[] tests, Class<? extends Annotation> annot) {
        return testsAnnotated(Arrays.stream(tests), annot);
    }

    static Stream<Method> testsAnnotated(IResultMap tests, Class<? extends Annotation> annot) {
        return testsAnnotated(tests.getAllMethods().stream(), annot);
    }

    static Stream<Method> testsAnnotated(Stream<ITestNGMethod> methods, Class<? extends Annotation> annot) {
        return methods
            .map(ITestNGMethod::getConstructorOrMethod)
            .map(ConstructorOrMethod::getMethod)
            .filter(testMethod ->
                testMethod.getAnnotation(annot) != null
            );
    }
}
