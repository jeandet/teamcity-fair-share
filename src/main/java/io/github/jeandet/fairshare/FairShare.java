package io.github.jeandet.fairshare;

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

    public record QueueItem(String id, String project, long estimateSeconds) {}

    public record Charge(String project, double seconds, Instant at) {}

    private FairShare() {}

    /**
     * Weighted fair queuing: repeatedly take the next build of the project with the least
     * usage, then charge that project the build's estimate. Ties go to the project whose next
     * build is earliest in the current queue, so equal projects keep TeamCity's order.
     */
    public static List<String> order(List<QueueItem> queue, Map<String, Double> usage) {
        var pending = queue.stream()
                .collect(groupingBy(QueueItem::project, LinkedHashMap::new, toCollection(ArrayDeque::new)));
        var position = new HashMap<String, Integer>();
        for (int i = 0; i < queue.size(); i++) position.put(queue.get(i).id(), i);
        var virtualUsage = new HashMap<String, Double>();
        pending.keySet().forEach(p -> virtualUsage.put(p, usage.getOrDefault(p, 0.0)));

        Comparator<String> leastServed = Comparator.<String>comparingDouble(virtualUsage::get)
                .thenComparingInt(p -> position.get(pending.get(p).peek().id()));
        var result = new ArrayList<String>(queue.size());
        while (!pending.isEmpty()) {
            var project = pending.keySet().stream().min(leastServed).orElseThrow();
            var next = pending.get(project).poll();
            result.add(next.id());
            virtualUsage.merge(project, (double) next.estimateSeconds(), Double::sum);
            if (pending.get(project).isEmpty()) pending.remove(project);
        }
        return result;
    }

    /** Agent-seconds per project, each charge halved for every half-life since it happened. */
    public static Map<String, Double> usage(List<Charge> charges, Instant now, Duration halfLife) {
        return charges.stream().collect(groupingBy(Charge::project,
                summingDouble(c -> c.seconds() * decay(Duration.between(c.at(), now), halfLife))));
    }

    private static double decay(Duration age, Duration halfLife) {
        return Math.pow(0.5, (double) age.toSeconds() / halfLife.toSeconds());
    }
}
