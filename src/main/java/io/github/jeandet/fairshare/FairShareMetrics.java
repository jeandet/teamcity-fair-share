package io.github.jeandet.fairshare;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import jetbrains.buildServer.metrics.Counter;
import jetbrains.buildServer.metrics.MetricBuilder;
import jetbrains.buildServer.metrics.MetricDataType;
import jetbrains.buildServer.metrics.ServerMetrics;

/**
 * Per-project fair-share numbers, published with TeamCity's own metrics at /app/metrics.
 * Per project only: user names stay out of the metrics, which often end up on public dashboards.
 */
final class FairShareMetrics {

    private record Snapshot(Map<String, Double> usage, Map<String, Long> queued, Instant at, Duration halfLife) {}

    private static final Duration[] WAIT_BUCKETS = {
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofMinutes(30),
            Duration.ofHours(1), Duration.ofHours(2), Duration.ofHours(4), Duration.ofHours(8)};

    private final ServerMetrics metrics;
    private final Set<String> projects = ConcurrentHashMap.newKeySet();
    private final Map<String, Counter> waits = new ConcurrentHashMap<>();
    private final List<MetricBuilder> registered = new CopyOnWriteArrayList<>();
    private volatile Snapshot latest = new Snapshot(Map.of(), Map.of(), Instant.now(), Duration.ofHours(12));

    FairShareMetrics(ServerMetrics metrics) {
        this.metrics = metrics;
    }

    void publish(Map<String, Double> usage, Map<String, Long> queued, Instant at, Duration halfLife) {
        latest = new Snapshot(usage, queued, at, halfLife);
        usage.keySet().forEach(this::registerGauges);
        queued.keySet().forEach(this::registerGauges);
    }

    void recordWait(String project, Duration wait) {
        waits.computeIfAbsent(project, p -> register(metrics.metricBuilder("fairshare.queue.wait")
                .description("Time builds waited in the queue before starting")
                .addTag("project", p)
                .dataType(MetricDataType.MILLISECONDS))
                .buildTimeHistogram(WAIT_BUCKETS))
                .increment(wait.toMillis());
    }

    void dispose() {
        registered.forEach(MetricBuilder::unregister);
    }

    private void registerGauges(String project) {
        if (!projects.add(project)) return;
        register(metrics.metricBuilder("fairshare.usage")
                .description("Recent agent-seconds used, decayed by the fair-share half-life")
                .addTag("project", project))
                .buildGauge(() -> decayedUsage(project));
        register(metrics.metricBuilder("fairshare.queued")
                .description("Builds of the project waiting in the queue")
                .addTag("project", project))
                .buildGauge(() -> latest.queued().getOrDefault(project, 0L));
    }

    // Between passes nothing new is charged, so the last usage just keeps decaying.
    private double decayedUsage(String project) {
        var s = latest;
        var age = Duration.between(s.at(), Instant.now());
        return s.usage().getOrDefault(project, 0.0) * Math.pow(0.5, (double) age.toSeconds() / s.halfLife().toSeconds());
    }

    // Plugin metrics default to "experimental", which /app/metrics hides unless asked.
    private MetricBuilder register(MetricBuilder builder) {
        registered.add(builder);
        return builder.experimental(false);
    }
}
