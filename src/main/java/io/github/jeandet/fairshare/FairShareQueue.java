package io.github.jeandet.fairshare;

import com.intellij.openapi.diagnostic.Logger;
import io.github.jeandet.fairshare.FairShare.Charge;
import io.github.jeandet.fairshare.FairShare.Owner;
import io.github.jeandet.fairshare.FairShare.QueueItem;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import jetbrains.buildServer.metrics.ServerMetrics;
import jetbrains.buildServer.serverSide.BuildEstimates;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.BuildServerAdapter;
import jetbrains.buildServer.serverSide.BuildServerListener;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.serverSide.SFinishedBuild;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.SQueuedBuild;
import jetbrains.buildServer.serverSide.SRunningBuild;
import jetbrains.buildServer.serverSide.TeamCityProperties;
import jetbrains.buildServer.serverSide.TriggeredBy;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.vcs.SVcsModification;
import jetbrains.buildServer.util.EventDispatcher;
import org.jetbrains.annotations.NotNull;

/** Re-sorts the build queue by fair share each time a build is queued or finishes. */
public final class FairShareQueue extends BuildServerAdapter {

    private static final Logger LOG = Logger.getInstance("jetbrains.buildServer.fairShare");
    private static final long UNKNOWN_ESTIMATE_SECONDS = 600;
    private static final int HISTORY_HALF_LIVES = 5;

    private final SBuildServer server;
    private final EventDispatcher<BuildServerListener> events;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "fair-share-queue");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final FairShareMetrics metrics;

    public FairShareQueue(EventDispatcher<BuildServerListener> events, SBuildServer server, ServerMetrics serverMetrics) {
        this.server = server;
        this.events = events;
        this.metrics = new FairShareMetrics(serverMetrics);
        events.addListener(this);
    }

    // Called by Spring when the plugin is unloaded at runtime, so a reload leaves no stale listener.
    public void dispose() {
        events.removeListener(this);
        worker.shutdownNow();
        metrics.dispose();
    }

    @Override
    public void buildTypeAddedToQueue(@NotNull SQueuedBuild build) {
        schedule();
    }

    @Override
    public void buildStarted(@NotNull SRunningBuild build) {
        metrics.recordWait(projectOf(build), Duration.between(build.getQueuedDate().toInstant(), build.getStartDate().toInstant()));
    }

    @Override
    public void buildFinished(@NotNull SRunningBuild build) {
        schedule();
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
            metrics.publish(FairShare.byProject(usage), FairShare.queuedPerProject(items), now, halfLife);
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
        server.getRunningBuilds().forEach(b -> charges.add(new Charge(ownerOf(b), runningCost(b), now)));
        var since = now.minus(halfLife.multipliedBy(HISTORY_HALF_LIVES));
        server.getHistory().processEntries(b -> {
            var finished = b.getFinishDate().toInstant();
            charges.add(new Charge(ownerOf(b), agentSeconds(b, b.getDuration()), finished));
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
        var owner = new Owner(topLevelProject(build.getBuildType().getProject()),
                userOf(build.getTriggeredBy(), build.getBuildPromotion()));
        return new QueueItem(build.getItemId(), owner, estimate);
    }

    private static long estimateSeconds(SQueuedBuild build) {
        return Optional.ofNullable(build.getBuildEstimates())
                .map(BuildEstimates::getTimeInterval)
                .map(t -> t.getDurationSeconds())
                .filter(s -> s > 0)
                .orElse(UNKNOWN_ESTIMATE_SECONDS);
    }

    private Owner ownerOf(SBuild build) {
        return new Owner(projectOf(build), userOf(build.getTriggeredBy(), build.getBuildPromotion()));
    }

    // Who clicked Run, else the TeamCity account matched to the author of the build's first listed
    // change, else that author's raw VCS name.
    // simplify: looks up the changes of every finished build in the history window on each pass;
    // cache the owner by build id if the history walk shows up in thread dumps.
    private static String userOf(TriggeredBy trigger, BuildPromotion promotion) {
        return Optional.ofNullable(trigger.getUser())
                .map(SUser::getUsername)
                .or(() -> promotion.getContainingChanges().stream().findFirst().map(FairShareQueue::authorOf))
                .orElse("automatic");
    }

    private static String authorOf(SVcsModification change) {
        return change.getCommitters().stream().findFirst()
                .map(SUser::getUsername)
                .orElse(change.getUserName());
    }

    private String projectOf(SBuild build) {
        return Optional.ofNullable(server.getProjectManager().findProjectById(build.getProjectId()))
                .map(FairShareQueue::topLevelProject)
                .orElse(build.getProjectExternalId());
    }

    // Shares are per top-level project, so a project's subprojects share one budget.
    private static String topLevelProject(SProject project) {
        var path = project.getProjectPath();
        return path.get(Math.min(1, path.size() - 1)).getExternalId();
    }
}
