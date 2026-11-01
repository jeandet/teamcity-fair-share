package io.github.jeandet.fairshare;

import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.summingDouble;
import static java.util.stream.Collectors.toCollection;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure fair-share logic: no TeamCity types, so it is testable on its own. */
public final class FairShare {

    /** Who a build is charged to: a top-level project, then a user inside it. */
    public record Owner(String project, String user) {}

    public record QueueItem(String id, Owner owner, long estimateSeconds) {}

    public record Charge(Owner owner, double seconds, Instant at) {}

    private FairShare() {}

    /**
     * Two-level weighted fair queuing: repeatedly take the next build of the least-served
     * project, and inside it of the least-served user, then charge both the build's estimate.
     * Ties go to the earliest build in the current queue, so equal owners keep TeamCity's order.
     */
    public static List<String> order(List<QueueItem> queue, Map<Owner, Double> usage) {
        var pending = queue.stream()
                .collect(groupingBy(QueueItem::owner, LinkedHashMap::new, toCollection(ArrayDeque::new)));
        var position = new HashMap<String, Integer>();
        for (int i = 0; i < queue.size(); i++) position.put(queue.get(i).id(), i);
        var ownerUsage = new HashMap<>(usage);
        var projectUsage = new HashMap<String, Double>();
        usage.forEach((owner, seconds) -> projectUsage.merge(owner.project(), seconds, Double::sum));

        Comparator<Owner> leastServed = Comparator
                .<Owner>comparingDouble(o -> projectUsage.getOrDefault(o.project(), 0.0))
                .thenComparingDouble(o -> ownerUsage.getOrDefault(o, 0.0))
                .thenComparingInt(o -> position.get(pending.get(o).peek().id()));
        var result = new ArrayList<String>(queue.size());
        while (!pending.isEmpty()) {
            var owner = pending.keySet().stream().min(leastServed).orElseThrow();
            var next = pending.get(owner).poll();
            result.add(next.id());
            ownerUsage.merge(owner, (double) next.estimateSeconds(), Double::sum);
            projectUsage.merge(owner.project(), (double) next.estimateSeconds(), Double::sum);
            if (pending.get(owner).isEmpty()) pending.remove(owner);
        }
        return result;
    }

    /** Agent-seconds per owner, each charge halved for every half-life since it happened. */
    public static Map<Owner, Double> usage(List<Charge> charges, Instant now, Duration halfLife) {
        return charges.stream().collect(groupingBy(Charge::owner,
                summingDouble(c -> c.seconds() * decay(Duration.between(c.at(), now), halfLife))));
    }

    public static Map<String, Double> byProject(Map<Owner, Double> usage) {
        return usage.entrySet().stream()
                .collect(groupingBy(e -> e.getKey().project(), summingDouble(Map.Entry::getValue)));
    }

    public static Map<String, Long> queuedPerProject(List<QueueItem> queue) {
        return queue.stream().collect(groupingBy(i -> i.owner().project(), counting()));
    }

    private static double decay(Duration age, Duration halfLife) {
        return Math.pow(0.5, (double) age.toSeconds() / halfLife.toSeconds());
    }
}
