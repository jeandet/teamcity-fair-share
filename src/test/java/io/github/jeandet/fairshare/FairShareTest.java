package io.github.jeandet.fairshare;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.jeandet.fairshare.FairShare.Charge;
import io.github.jeandet.fairshare.FairShare.QueueItem;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FairShareTest {

    private static QueueItem item(String id, String project) {
        return new QueueItem(id, project, 600);
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
        assertEquals(List.of("o1", "p1", "p2"), FairShare.order(queue, Map.of("phare", 10_000.0)));
    }

    @Test
    void projectKeepsItsOwnOrder() {
        var queue = List.of(item("o2", "other"), item("p1", "phare"), item("o1", "other"));
        assertEquals(List.of("o2", "p1", "o1"), FairShare.order(queue, Map.of()));
    }

    @Test
    void longBuildsCountMoreThanShortOnes() {
        var queue = List.of(
                new QueueItem("long", "a", 3600),
                new QueueItem("a2", "a", 60),
                new QueueItem("s1", "b", 60),
                new QueueItem("s2", "b", 60));
        assertEquals(List.of("long", "s1", "s2", "a2"), FairShare.order(queue, Map.of()));
    }

    @Test
    void usageDecaysByHalfLife() {
        var now = Instant.parse("2026-11-01T12:00:00Z");
        var halfLife = Duration.ofHours(12);
        var charges = List.of(
                new Charge("a", 100, now.minus(halfLife)),
                new Charge("a", 10, now),
                new Charge("b", 40, now.minus(halfLife.multipliedBy(2))));
        assertEquals(Map.of("a", 60.0, "b", 10.0), FairShare.usage(charges, now, halfLife));
    }
}
