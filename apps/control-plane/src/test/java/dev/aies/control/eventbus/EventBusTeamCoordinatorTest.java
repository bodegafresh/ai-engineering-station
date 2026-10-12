package dev.aies.control.eventbus;

import dev.aies.control.team.TeamCoordinator;
import dev.aies.control.team.TeamPlan;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class EventBusTeamCoordinatorTest {
    private Vertx vertx;

    @BeforeEach
    void setUp() { vertx = Vertx.vertx(); }

    @AfterEach
    void tearDown() throws Exception {
        if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static TeamPlan.Step step(String id, String... dependencies) {
        return new TeamPlan.Step(id, "backend-developer", "Execute " + id, Set.of(dependencies), Set.of("qa-engineer"));
    }

    private static TeamCoordinator.Result result(String id) {
        return new TeamCoordinator.Result("Output " + id, List.of("Evidence " + id));
    }

    @Test
    void independentStepsExecuteConcurrentlyAndDependentStepWaitsForApproval() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicBoolean dependentStartedBeforeReview = new AtomicBoolean();
        AtomicInteger reviews = new AtomicInteger();
        TeamPlan plan = new TeamPlan("DAG execution", List.of(step("a"), step("b"), step("c", "a", "b")));

        TeamCoordinator.AgentExecutor executor = (s, deps) -> {
            if (s.id().equals("c")) {
                if (reviews.get() != 2) dependentStartedBeforeReview.set(true);
                assertEquals(Set.of("a", "b"), deps.keySet());
                return result("c");
            }
            int active = concurrent.incrementAndGet();
            peak.accumulateAndGet(active, Math::max);
            bothStarted.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Agents were not released");
                return result(s.id());
            } finally {
                concurrent.decrementAndGet();
            }
        };

        try (EventBusTeamCoordinator coordinator = new EventBusTeamCoordinator(
                vertx, "parallel-test", 2, Duration.ofSeconds(8), executor)) {
            Thread releaser = new Thread(() -> {
                try {
                    if (bothStarted.await(5, TimeUnit.SECONDS)) release.countDown();
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            releaser.start();
            try {
                TeamCoordinator.Report report = coordinator.run(plan, (s, r) -> {
                    if (!s.id().equals("c")) reviews.incrementAndGet();
                    return true;
                });
                assertEquals("COMPLETED", report.status());
                assertEquals(Set.of("a", "b", "c"), report.approved().keySet());
                assertTrue(report.failures().isEmpty());
                assertEquals(2, peak.get(), "Independent agents must overlap");
                assertFalse(dependentStartedBeforeReview.get(), "Dependencies must be approved first");
            } finally {
                release.countDown();
                releaser.join(5000);
            }
        }
    }

    @Test
    void failureBlocksDependentStepsButNotIndependentSteps() throws Exception {
        TeamPlan plan = new TeamPlan("Partial DAG", List.of(step("bad"), step("good"), step("dependent", "bad")));
        try (EventBusTeamCoordinator coordinator = new EventBusTeamCoordinator(
                vertx, "failure-test", 2, Duration.ofSeconds(5), (s, deps) -> {
                    if (s.id().equals("bad")) throw new IllegalStateException("Simulated failure");
                    return result(s.id());
                })) {
            TeamCoordinator.Report report = coordinator.run(plan, (s, r) -> true);
            assertEquals("PARTIAL_OR_BLOCKED", report.status());
            assertTrue(report.approved().containsKey("good"));
            assertFalse(report.approved().containsKey("dependent"));
            assertTrue(report.failures().containsKey("bad"));
            assertTrue(report.failures().containsKey("dependent"));
        }
    }

    @Test
    void rejectedReviewBlocksDependents() throws Exception {
        TeamPlan plan = new TeamPlan("Review gate", List.of(step("first"), step("second", "first")));
        try (EventBusTeamCoordinator coordinator = new EventBusTeamCoordinator(
                vertx, "review-test", 2, Duration.ofSeconds(5), (s, deps) -> result(s.id()))) {
            TeamCoordinator.Report report = coordinator.run(plan, (s, r) -> false);
            assertEquals("PARTIAL_OR_BLOCKED", report.status());
            assertTrue(report.approved().isEmpty());
            assertEquals(2, report.failures().size());
            assertTrue(report.failures().get("first").contains("Rejected"));
            assertTrue(report.failures().get("second").contains("Blocked"));
        }
    }

    @Test
    void timeoutIsReported() throws Exception {
        TeamPlan plan = new TeamPlan("Timeout", List.of(step("slow")));
        CountDownLatch neverReleased = new CountDownLatch(1);
        try (EventBusTeamCoordinator coordinator = new EventBusTeamCoordinator(
                vertx, "timeout-test", 1, Duration.ofMillis(200), (s, deps) -> {
                    neverReleased.await(3, TimeUnit.SECONDS);
                    return result(s.id());
                })) {
            TeamCoordinator.Report report = coordinator.run(plan, (s, r) -> true);
            assertEquals("PARTIAL_OR_BLOCKED", report.status());
            assertTrue(report.failures().containsKey("slow"));
        } finally {
            neverReleased.countDown();
        }
    }
}
