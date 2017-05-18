package io.qameta.allure.ios;

import io.qameta.allure.Reader;
import io.qameta.allure.core.Configuration;
import io.qameta.allure.core.ResultsVisitor;
import io.qameta.allure.entity.Status;
import io.qameta.allure.entity.StatusDetails;
import io.qameta.allure.entity.Step;
import io.qameta.allure.entity.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xmlwise.Plist;
import xmlwise.XmlParseException;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static java.nio.file.Files.newDirectoryStream;
import static java.util.Collections.emptyList;

/**
 * @author charlie (Dmitry Baev).
 */
public class IosPlugin implements Reader {

    private static final Logger LOGGER = LoggerFactory.getLogger(IosPlugin.class);

    private static final String TESTABLE_SUMMARIES = "TestableSummaries";
    private static final String TESTS = "Tests";
    private static final String SUB_TESTS = "Subtests";
    private static final String TITLE = "Title";
    private static final String SUB_ACTIVITIES = "SubActivities";
    private static final String ACTIVITY_SUMMARIES = "ActivitySummaries";
    private static final String HAS_SCREENSHOT = "HasScreenshotData";


    @Override
    public void readResults(final Configuration configuration,
                            final ResultsVisitor visitor,
                            final Path directory) {
        final List<Path> files = listResults(directory);
        for (Path file : files) {
            try {
                LOGGER.info("Parse file {}", file);
                final Map<String, Object> loaded = Plist.load(file.toFile());
                final List<?> summaries = asList(loaded.getOrDefault(TESTABLE_SUMMARIES, emptyList()));
                summaries.forEach(summary -> parseTestRun(directory, summary, visitor));
            } catch (XmlParseException | IOException e) {
                LOGGER.error("Could not parse file {}: {}", file, e);
            }
        }
    }

    private void parseTestRun(final Path directory, final Object summary, final ResultsVisitor visitor) {
        final Map<String, Object> props = asMap(summary);
        final List<Object> tests = asList(props.getOrDefault(TESTS, emptyList()));
        tests.forEach(test -> parseTestSuite(directory, test, visitor));
    }

    @SuppressWarnings("unchecked")
    private void parseTestSuite(final Path directory, final Object testSuite, final ResultsVisitor visitor) {
        Map<String, Object> props = asMap(testSuite);
        if (ResultsUtils.isTest(props)) {
            parseTest(directory, testSuite, visitor);
            return;
        }

        final Object tests = props.get(SUB_TESTS);
        if (Objects.nonNull(tests)) {
            final List<?> subTests = List.class.cast(tests);
            subTests.forEach(subTest -> parseTestSuite(directory, subTest, visitor));
        }
    }

    private void parseTest(final Path directory, final Object test, final ResultsVisitor visitor) {
        Map<String, Object> props = asMap(test);
        final TestResult result = ResultsUtils.getTestResult(props);
        asList(props.getOrDefault(ACTIVITY_SUMMARIES, emptyList()))
                .forEach(activity -> parseStep(directory, result, result, activity, visitor));
        visitor.visitTestResult(result);
    }

    private void parseStep(final Path directory, final TestResult testResult, final Object parent,
                           final Object activity, final ResultsVisitor visitor) {
        final Map<String, Object> props = asMap(activity);
        final String activityTitle = (String) props.get(TITLE);

        if (activityTitle.startsWith("Assertion Failure:")) {
            testResult.setStatusDetails(new StatusDetails().withMessage(activityTitle));
            ((Step) parent).setStatus(Status.FAILED);
            ((Step) parent).setStatusDetails(new StatusDetails().withMessage(activityTitle));
            return;
        }

        if (activityTitle.startsWith("Start Test at")) {
            getStartTime(activityTitle).ifPresent(start -> {
                long duration = testResult.getTime().getDuration();
                testResult.getTime().setStart(start);
                testResult.getTime().setStop(start + duration);
            });
            return;
        }

        final Step step = ResultsUtils.getStep(props);

        if (props.containsKey(HAS_SCREENSHOT)) {
            String uuid = props.get("UUID").toString();
            Path attachmentPath = directory.resolve("Attachments").resolve(String.format("Screenshot_%s.png", uuid));
            step.getAttachments().add(visitor.visitAttachmentFile(attachmentPath));
        }

        if (parent instanceof TestResult) {
            ((TestResult) parent).getTestStage().getSteps().add(step);
        }

        if (parent instanceof Step) {
            ((Step) parent).getSteps().add(step);
        }

        asList(props.getOrDefault(SUB_ACTIVITIES, emptyList()))
                .forEach(subActivity -> parseStep(directory, testResult, step, subActivity, visitor));

        step.getSteps().stream()
                .map(Step::getStatus)
                .filter(status -> status.equals(Status.FAILED))
                .findFirst().ifPresent(step::setStatus);
    }

    @SuppressWarnings("unchecked")
    private List<Object> asList(final Object object) {
        return List.class.cast(object);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(final Object object) {
        return Map.class.cast(object);
    }

    private static List<Path> listResults(final Path directory) {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return result;
        }

        try (DirectoryStream<Path> directoryStream = newDirectoryStream(directory, "*.plist")) {
            for (Path path : directoryStream) {
                if (!Files.isDirectory(path)) {
                    result.add(path);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Could not read data from {}: {}", directory, e);
        }
        return result;
    }

    private static Optional<Long> getStartTime(final String stepName) {
        try {
            final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSX", Locale.US);
            final Date date = dateFormat.parse(stepName.substring(14));
            return Optional.of(date.getTime());
        } catch (DateTimeException | ParseException e) {
            return Optional.empty();
        }
    }

}
