package io.github.jeandet.fairshare;

import com.intellij.openapi.diagnostic.Logger;
import io.github.jeandet.fairshare.FairShare.Charge;
import io.github.jeandet.fairshare.FairShare.QueueItem;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import jetbrains.buildServer.serverSide.BuildEstimates;
import jetbrains.buildServer.serverSide.BuildServerAdapter;
import jetbrains.buildServer.serverSide.BuildServerListener;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.serverSide.SFinishedBuild;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.SQueuedBuild;
import jetbrains.buildServer.serverSide.SRunningBuild;
import jetbrains.buildServer.serverSide.TeamCityProperties;
import jetbrains.buildServer.util.EventDispatcher;
import org.jetbrains.annotations.NotNull;

/** Re-sorts the build queue by fair share each time a build is queued or finishes. */
public final class FairShareQueue extends BuildServerAdapter {

    private static final Logger LOG = Logger.getInstance("jetbrains.buildServer.fairShare");
    private static final long UNKNOWN_ESTIMATE_SECONDS = 600;
    private static final int HISTORY_HALF_LIVES = 5;

    private final SBuildServer server;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "fair-share-queue");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean scheduled = new AtomicBoolean();

    public FairShareQueue(EventDispatcher<BuildServerListener> events, SBuildServer server) {
        this.server = server;
        events.addListener(this);
    }

    @Override
    public void buildTypeAddedToQueue(@NotNull SQueuedBuild build) {
        schedule();
    }

    @Override
    public void buildFinished(@NotNull SRunningBuild build) {
        schedule();
    }

    @Override
    public void serverShutdown() {
        worker.shutdownNow();
    }

    // Reordering runs off TeamCity's event thread; a burst of events collapses into one pass.
    private void schedule() {
        if (TeamCityProperties.getBoolean("teamcity.fairShare.enabled", true) && scheduled.compareAndSet(false, true)) {
            worker.execute(this::reorder);
        }
    }

    private void reorder() {
        scheduled.set(false);
        try {
            var now = Instant.now();
            var halfLife = Duration.ofHours(TeamCityProperties.getInteger("teamcity.fairShare.halfLifeHours", 12));
            var usage = FairShare.usage(charges(now, halfLife), now, halfLife);
            var queue = server.getQueue();
            var items = queue.getItems().stream().map(this::toQueueItem).toList();
            var order = FairShare.order(items, usage);
            if (!order.equals(items.stream().map(QueueItem::id).toList())) {
                queue.applyOrder(order.toArray(String[]::new));
                LOG.info("Reordered " + order.size() + " queued builds, usage: " + usage);
            }
        } catch (RuntimeException e) {
            LOG.warn("Fair-share reorder failed, queue left as is", e);
        }
    }

    private List<Charge> charges(Instant now, Duration halfLife) {
        var charges = new ArrayList<Charge>();
        server.getRunningBuilds().forEach(b -> charges.add(new Charge(projectOf(b), runningCost(b), now)));
        var since = now.minus(halfLife.multipliedBy(HISTORY_HALF_LIVES));
        server.getHistory().processEntries(b -> {
            var finished = b.getFinishDate().toInstant();
            charges.add(new Charge(projectOf(b), agentSeconds(b, b.getDuration()), finished));
            return finished.isAfter(since);
        });
        return charges;
    }

    // A running build is charged what it is expected to occupy, so a burst of starts counts at once.
    private static double runningCost(SRunningBuild build) {
        return agentSeconds(build, Math.max(build.getElapsedTime(), build.getDurationEstimate()));
    }

    private static double agentSeconds(SBuild build, long seconds) {
        return build.isCompositeBuild() ? 0 : seconds;
    }

    private QueueItem toQueueItem(SQueuedBuild build) {
        var estimate = build.getBuildPromotion().isCompositeBuild() ? 0 : estimateSeconds(build);
        return new QueueItem(build.getItemId(), topLevelProject(build.getBuildType().getProject()), estimate);
    }

    private static long estimateSeconds(SQueuedBuild build) {
        return Optional.ofNullable(build.getBuildEstimates())
                .map(BuildEstimates::getTimeInterval)
                .map(t -> t.getDurationSeconds())
                .filter(s -> s > 0)
                .orElse(UNKNOWN_ESTIMATE_SECONDS);
    }

    private String projectOf(SBuild build) {
        return Optional.ofNullable(server.getProjectManager().findProjectById(build.getProjectId()))
                .map(FairShareQueue::topLevelProject)
                .orElse(build.getProjectId());
    }

    // Shares are per top-level project, so a project's subprojects share one budget.
    private static String topLevelProject(SProject project) {
        var path = project.getProjectPath();
        return path.get(Math.min(1, path.size() - 1)).getProjectId();
    }
}
