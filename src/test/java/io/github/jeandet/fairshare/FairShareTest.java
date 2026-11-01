package io.github.jeandet.fairshare;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.jeandet.fairshare.FairShare.Charge;
import io.github.jeandet.fairshare.FairShare.Owner;
import io.github.jeandet.fairshare.FairShare.QueueItem;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FairShareTest {

    private static QueueItem item(String id, String project) {
        return item(id, project, "someone");
    }

    private static QueueItem item(String id, String project, String user) {
        return new QueueItem(id, new Owner(project, user), 600);
    }

    @Test
    void emptyQueueStaysEmpty() {
        assertEquals(List.of(), FairShare.order(List.of(), Map.of()));
    }

    @Test
    void floodingProjectIsInterleavedWithOthers() {
        var queue = List.of(item("p1", "phare"), item("p2", "phare"), item("p3", "phare"), item("o1", "other"));
        assertEquals(List.of("p1", "o1", "p2", "p3"), FairShare.order(queue, Map.of()));
    }

    @Test
    void heavyRecentUsageSendsProjectBehind() {
        var queue = List.of(item("p1", "phare"), item("p2", "phare"), item("o1", "other"));
        assertEquals(List.of("o1", "p1", "p2"), FairShare.order(queue, Map.of(new Owner("phare", "someone"), 10_000.0)));
    }

    @Test
    void projectKeepsItsOwnOrder() {
        var queue = List.of(item("o2", "other"), item("p1", "phare"), item("o1", "other"));
        assertEquals(List.of("o2", "p1", "o1"), FairShare.order(queue, Map.of()));
    }

    @Test
    void longBuildsCountMoreThanShortOnes() {
        var queue = List.of(
                new QueueItem("long", new Owner("a", "u"), 3600),
                new QueueItem("a2", new Owner("a", "u"), 60),
                new QueueItem("s1", new Owner("b", "u"), 60),
                new QueueItem("s2", new Owner("b", "u"), 60));
        assertEquals(List.of("long", "s1", "s2", "a2"), FairShare.order(queue, Map.of()));
    }

    @Test
    void floodingUserIsInterleavedWithinProject() {
        var queue = List.of(
                item("a1", "phare", "alice"), item("a2", "phare", "alice"), item("a3", "phare", "alice"),
                item("b1", "phare", "bob"));
        assertEquals(List.of("a1", "b1", "a2", "a3"), FairShare.order(queue, Map.of()));
    }

    @Test
    void heavyUserGoesBehindColleaguesOfSameProject() {
        var queue = List.of(item("a1", "phare", "alice"), item("b1", "phare", "bob"));
        assertEquals(List.of("b1", "a1"), FairShare.order(queue, Map.of(new Owner("phare", "alice"), 10_000.0)));
    }

    @Test
    void projectsAreBalancedBeforeUsers() {
        // alice's usage is in "phare"; she has no history in "other", but phare as a whole is heavy.
        var queue = List.of(item("p1", "phare", "bob"), item("o1", "other", "carol"));
        var usage = Map.of(new Owner("phare", "alice"), 10_000.0);
        assertEquals(List.of("o1", "p1"), FairShare.order(queue, usage));
    }

    @Test
    void usageDecaysByHalfLife() {
        var now = Instant.parse("2026-11-01T12:00:00Z");
        var halfLife = Duration.ofHours(12);
        var alice = new Owner("a", "alice");
        var bob = new Owner("b", "bob");
        var charges = List.of(
                new Charge(alice, 100, now.minus(halfLife)),
                new Charge(alice, 10, now),
                new Charge(bob, 40, now.minus(halfLife.multipliedBy(2))));
        assertEquals(Map.of(alice, 60.0, bob, 10.0), FairShare.usage(charges, now, halfLife));
    }
}
