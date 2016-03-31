package ru.yandex.qatools.allure.cucumberallure;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cucumber.runtime.StepDefinitionMatch;
import gherkin.formatter.Formatter;
import gherkin.formatter.Reporter;
import gherkin.formatter.model.Background;
import gherkin.formatter.model.Examples;
import gherkin.formatter.model.Feature;
import gherkin.formatter.model.Match;
import gherkin.formatter.model.Result;
import gherkin.formatter.model.Scenario;
import gherkin.formatter.model.ScenarioOutline;
import gherkin.formatter.model.Step;
import gherkin.formatter.model.Tag;
import ru.yandex.qatools.allure.Allure;
import ru.yandex.qatools.allure.annotations.Description;
import ru.yandex.qatools.allure.annotations.Features;
import ru.yandex.qatools.allure.annotations.Issue;
import ru.yandex.qatools.allure.annotations.Issues;
import ru.yandex.qatools.allure.annotations.Severity;
import ru.yandex.qatools.allure.annotations.Stories;
import ru.yandex.qatools.allure.annotations.TestCaseId;
import ru.yandex.qatools.allure.config.AllureModelUtils;
import ru.yandex.qatools.allure.events.MakeAttachmentEvent;
import ru.yandex.qatools.allure.events.StepCanceledEvent;
import ru.yandex.qatools.allure.events.StepFailureEvent;
import ru.yandex.qatools.allure.events.StepFinishedEvent;
import ru.yandex.qatools.allure.events.StepStartedEvent;
import ru.yandex.qatools.allure.events.TestCaseCanceledEvent;
import ru.yandex.qatools.allure.events.TestCaseFailureEvent;
import ru.yandex.qatools.allure.events.TestCaseFinishedEvent;
import ru.yandex.qatools.allure.events.TestCaseStartedEvent;
import ru.yandex.qatools.allure.events.TestSuiteFinishedEvent;
import ru.yandex.qatools.allure.events.TestSuiteStartedEvent;
import ru.yandex.qatools.allure.model.DescriptionType;
import ru.yandex.qatools.allure.model.SeverityLevel;
import ru.yandex.qatools.allure.utils.AnnotationManager;

public class AllureReporter implements Reporter, Formatter {

    private static final String FAILED = "failed";
    private static final String SKIPPED = "skipped";

    private static final Logger LOGGER = LoggerFactory.getLogger(AllureReporter.class);

    private final Allure lifecycle = Allure.LIFECYCLE;

    private final Pattern severityPattern = Pattern.compile("@SeverityLevel\\.(.+)");
    private final Pattern issuePattern = Pattern.compile("@Issue\\(\"+?([^\"]+)\"+?\\)");
    private final Pattern testCaseIdPattern = Pattern.compile("@TestCaseId\\(\"+?([^\"]+)\"+?\\)");

    private Feature feature;
    private StepDefinitionMatch match;

    private Queue<Step> gherkinSteps = new LinkedList<>();
    private List<Step> accessedSteps = new LinkedList<>();

    private String uid;

    //to avoid duplicate names of attachments and messages
    private long counter = 0;

    @Override
    public void syntaxError(String state, String event, List<String> legalEvents, String uri, Integer line) {

    }

    @Override
    public void uri(String uri) {

    }

    @Override
    public void feature(Feature feature) {
        this.feature = feature;

        uid = UUID.randomUUID().toString();

        TestSuiteStartedEvent event = new TestSuiteStartedEvent(uid, feature.getName());

        Collection<Annotation> annotations = new ArrayList<>();
        annotations.add(getDescriptionAnnotation(feature.getDescription()));
        annotations.add(getFeaturesAnnotation(feature.getName()));

        AnnotationManager am = new AnnotationManager(annotations);
        am.update(event);
        lifecycle.fire(event);
    }

    @Override
    public void scenarioOutline(ScenarioOutline scenarioOutline) {

    }

    @Override
    public void examples(Examples examples) {

    }

    @Override
    public void startOfScenarioLifeCycle(Scenario scenario) {

        TestCaseStartedEvent event = new TestCaseStartedEvent(uid, scenario.getName());
        event.setTitle(scenario.getName());

        Collection<Annotation> annotations = new ArrayList<>();

        SeverityLevel level = getSeverityLevel(scenario);

        if(level != null){
            annotations.add(getSeverityAnnotation(level));
        }

        Issues issues = getIssuesAnnotation(scenario);
        if(issues != null){
            annotations.add(issues);
        }

        TestCaseId testCaseId = getTestCaseIdAnnotation(scenario);
        if(testCaseId != null){
            annotations.add(testCaseId);
        }



        annotations.add(getFeaturesAnnotation(feature.getName()));
        annotations.add(getStoriesAnnotation(scenario.getName()));
        annotations.add(getDescriptionAnnotation(scenario.getDescription()));

        AnnotationManager am = new AnnotationManager(annotations);
        am.update(event);

        event.withLabels(AllureModelUtils.createTestFrameworkLabel("CucumberJVM"));

        lifecycle.fire(event);
    }

    @Override
    public void background(Background background) {

    }

    @Override
    public void scenario(Scenario scenario) {

    }

    @Override
    public void step(Step step) {
        gherkinSteps.add(step);
    }

    @Override
    public void endOfScenarioLifeCycle(Scenario scenario) {
        lifecycle.fire(new TestCaseFinishedEvent());
        this.gherkinSteps.clear();
        this.accessedSteps.clear();
    }

    @Override
    public void done() {

    }

    @Override
    public void close() {

    }

    @Override
    public void eof() {
        lifecycle.fire(new TestSuiteFinishedEvent(uid));
        uid = null;
    }

    @Override
    public void before(Match match, Result result) {

    }

    @Override
    public void result(Result result) {
        if (match != null) {
            if (FAILED.equals(result.getStatus())) {
                lifecycle.fire(new StepFailureEvent().withThrowable(result.getError()));
                lifecycle.fire(new TestCaseFailureEvent().withThrowable(result.getError()));
            } else if(SKIPPED.equals(result.getStatus())){
                lifecycle.fire(new StepCanceledEvent());
            }
            lifecycle.fire(new StepFinishedEvent());
            match = null;
        }
    }

    @Override
    public void after(Match match, Result result) {

    }

    @Override
    public void match(Match match) {

        if (match instanceof StepDefinitionMatch) {
            this.match = (StepDefinitionMatch) match;

            Step step = extractStep(this.match);

            if (isEqualSteps(step, gherkinSteps.peek())) {
                accessedSteps.add(gherkinSteps.remove());
            } else if (!accessedSteps.contains(step) && gherkinSteps.peek() != null && !isEqualSteps(step, gherkinSteps.peek())) {
                String name = gherkinSteps.remove().getName();
                lifecycle.fire(new StepStartedEvent(name).withTitle(name));
                lifecycle.fire(new StepCanceledEvent());
                lifecycle.fire(new StepFinishedEvent());
                lifecycle.fire(new TestCaseCanceledEvent());
            }

            String name = this.match.getStepLocation().getMethodName();
            lifecycle.fire(new StepStartedEvent(name).withTitle(name));
        }
    }



    @Override
    public void embedding(String mimeType, byte[] data) {
        lifecycle.fire(new MakeAttachmentEvent(data, "attachment" + counter++, mimeType));
    }

    @Override
    public void write(String text) {
        lifecycle.fire(new MakeAttachmentEvent(text.getBytes(), "message" + counter++, "text/plain"));
    }

    private Step extractStep(StepDefinitionMatch match) {
        try {
            Field step = match.getClass().getDeclaredField("step");
            step.setAccessible(true);
            return (Step) step.get(match);
        } catch (ReflectiveOperationException e) {
            //shouldn't ever happen
            throw new RuntimeException(e);
        }
    }

    private boolean isEqualSteps(Step step, Step gherkinStep) {
        return Objects.equals(step.getLine(), gherkinStep.getLine());
    }

    private SeverityLevel getSeverityLevel(Scenario scenario) {
        SeverityLevel level = null;
        List<SeverityLevel> severityLevels = Arrays.asList(
                SeverityLevel.BLOCKER,
                SeverityLevel.CRITICAL,
                SeverityLevel.NORMAL,
                SeverityLevel.MINOR,
                SeverityLevel.TRIVIAL);
        for (Tag tag : scenario.getTags()) {
            Matcher matcher = severityPattern.matcher(tag.getName());
            if(matcher.matches()){
                SeverityLevel levelTmp;
                String levelString = matcher.group(1);
                try {
                    levelTmp = SeverityLevel.fromValue(levelString.toLowerCase());
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("Unexpected Severity level {}. SeverityLevel.NORMAL will be used instead", levelString);
                    levelTmp = SeverityLevel.NORMAL;
                }

                if(level == null || severityLevels.indexOf(levelTmp) < severityLevels.indexOf(level)){
                    level = levelTmp;
                }
            }
        }
        return level;
    }

    private Description getDescriptionAnnotation(final String description){
        return new Description(){
            @Override
            public String value() {
                return description;
            }

            @Override
            public DescriptionType type() {
                return DescriptionType.TEXT;
            }

            @Override
            public Class<? extends Annotation> annotationType() {
                return Description.class;
            }
        };
    }

    private Features getFeaturesAnnotation(final String value) {
        return new Features() {

            @Override
            public String[] value() {
                return new String[]{value};
            }

            @Override
            public Class<Features> annotationType() {
                return Features.class;
            }
        };
    }

    private Stories getStoriesAnnotation(final String value) {
        return new Stories() {

            @Override
            public String[] value() {
                return new String[]{value};
            }

            @Override
            public Class<Stories> annotationType() {
                return Stories.class;
            }
        };
    }

    private Severity getSeverityAnnotation(final SeverityLevel value) {
        return new Severity() {

            @Override
            public SeverityLevel value() {
                return value;
            }

            @Override
            public Class<Severity> annotationType() {
                return Severity.class;
            }
        };
    }

    private Issues getIssuesAnnotation(Scenario scenario) {
        List<String> issues = new ArrayList<>();
        for (Tag tag : scenario.getTags()) {
            Matcher matcher = issuePattern.matcher(tag.getName());
            if(matcher.matches()){
                issues.add(matcher.group(1));
            }
        }
        return issues.size() > 0 ? getIssuesAnnotation(issues): null;
    }

    private Issues getIssuesAnnotation(List<String> issues){
        final Issue[] values = createIssuesArray(issues);
        return new Issues() {
            @Override
            public Issue[] value() {
                return values;
            }

            @Override
            public Class<Issues> annotationType() {
                return Issues.class;
            }
        };
    }

    private Issue[] createIssuesArray(List<String> issues) {
        ArrayList<Issue> values = new ArrayList<>();
        for (final String issue : issues) {
            values.add(new Issue() {
                @Override
                public Class<Issue> annotationType() {
                    return Issue.class;
                }

                @Override
                public String value() {
                    return issue;
                }
            });
        }

        return values.toArray(new Issue[values.size()]);
    }

    private TestCaseId getTestCaseIdAnnotation(Scenario scenario) {
        for (Tag tag : scenario.getTags()) {
            Matcher matcher = testCaseIdPattern.matcher(tag.getName());
            if(matcher.matches()){
                final String testCaseId = matcher.group(1);
                return new TestCaseId() {
                    @Override
                    public String value() {
                        return testCaseId;
                    }

                    @Override
                    public Class<TestCaseId> annotationType() {
                        return TestCaseId.class;
                    }
                };
            }
        }

        return null;
    }
}