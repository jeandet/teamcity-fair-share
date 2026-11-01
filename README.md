# teamcity-fair-share

A TeamCity server plugin that orders the build queue by fair share: between projects, then
between users inside each project.

TeamCity's queue is first-in first-out, adjusted by static priority classes. When one project
floods the queue, everyone else waits behind it. This plugin re-sorts the queue so that the
projects, and the users, that used the CI least recently go first. There is no quota: when
nobody else is waiting, a busy project still gets every agent.

## Install

Download `fair-share.zip` from the [releases](../../releases), then in TeamCity:
Administration → Plugins → Upload plugin zip → Enable. No restart is needed. Updating or
removing the plugin is live too.

Built against TeamCity 2026.2, Java 17 bytecode. Tested on TeamCity 2026.2.1.

## Settings

Internal properties (Administration → Diagnostics → Internal Properties), read on every pass:

| Property | Default | Meaning |
|---|---|---|
| `teamcity.fairShare.enabled` | `true` | `false` stops all reordering, immediately |
| `teamcity.fairShare.halfLifeHours` | `12` | How fast past usage is forgotten |

## The algorithm

### 1. Who a build belongs to

Each build has an **owner**: a project and a user.

- **Project**: the build configuration's top-level project, the one directly under `<Root>`.
  Subprojects share their top-level project's budget.
- **User**, the first that applies:
  1. whoever started the build by hand (clicked Run, or called the REST API);
  2. the TeamCity account matched to the author of the build's change. TeamCity does the
     matching with the e-mails and VCS usernames set on each account;
  3. the raw VCS author (`Name <email>`), when no account matches;
  4. `automatic`, for builds with no change and no user, such as schedule triggers.

### 2. How much each owner used the CI

Usage is counted in agent-seconds. Past builds fade with an exponential decay of half-life
*H* (12 hours by default):

```
usage(owner) =   Σ  duration(b) × 0.5 ^ ((now − finish(b)) / H)     over finished builds
               + Σ  max(elapsed(r), estimate(r))                      over running builds
```

- A build that finished *H* ago counts half, 2*H* ago a quarter, and so on. The plugin reads
  the history back 5 half-lives (60 hours); anything older weighs less than 3%.
- A running build counts its full estimated duration from the moment it starts. Without this,
  a project that just started ten builds would look idle until they finish.
- Composite builds count zero: they occupy no agent.

A project's usage is the sum of its owners' usage.

### 3. How the queue is ordered

This is weighted fair queuing, in two levels:

```
pending   = queued builds, grouped by owner, each group in its current queue order
projectU  = usage per project      (from step 2)
ownerU    = usage per owner        (from step 2)
result    = []

while pending is not empty:
    pick the owner o with the smallest (projectU[o.project], ownerU[o], queue position of o's next build)
    move o's next build from pending to result
    projectU[o.project] += estimate(that build)
    ownerU[o]           += estimate(that build)
```

- **Projects first, then users.** The least-served project wins. Inside it, the least-served user wins.
- **Each pick is charged its estimate.** That is what interleaves projects: a project that has just
  been given a one-hour build in the new order must let the others catch up by an hour.
  A plain sort by usage would instead let a quiet project with 50 queued builds jump ahead all at once.
- **Ties keep TeamCity's order**, and an owner's own builds never overtake each other.
- The estimate is TeamCity's own build duration estimate, or 10 minutes when it has none.

### Worked example

The queue holds, in this order: Phare `p1 p2 p3` (10 minutes each) then SciQLop `s1`.
Phare used 2 agent-hours recently, SciQLop nothing.

| Step | Phare usage | SciQLop usage | Pick |
|---|---|---|---|
| 1 | 7200 s | 0 s | `s1` (SciQLop is lower) |
| 2 | 7200 s | 600 s | `p1` (only Phare left) |
| 3 | 7800 s | 600 s | `p2` |
| 4 | 8400 s | 600 s | `p3` |

New queue: `s1 p1 p2 p3`. With no history at all, the same queue becomes `p1 s1 p2 p3`:
the tie goes to `p1`, which charges Phare 600 s, so SciQLop's turn comes next.

### 4. When it runs

The plugin recomputes the order each time a build is queued or finishes, on its own thread.
A burst of events collapses into one pass. It writes the order back only if it changed.

### Properties and limits

- **Work-conserving.** The order only says who goes first. TeamCity still starts any queued
  build that a free agent can run, so an idle CI never holds builds back.
- **No starvation.** A waiting project's usage keeps decaying while the others are charged for
  what they run, so its turn always comes.
- **No preemption.** Running builds are never stopped. A build that jumps to the front still
  waits for the next free agent.
- **It replaces TeamCity's ordering.** Priority classes and manual drag-and-drop still place a
  build when it is queued, but the plugin re-sorts everything on its next pass. TeamCity itself
  never re-sorts builds already in the queue, so the two do not fight.
- **One person, two git identities** (say, work and personal e-mail) counts as two users unless
  both are set on the person's TeamCity account.

## Build

```sh
gradle build    # → build/distributions/fair-share.zip, runs the unit tests
```

No local JDK? Run Gradle in a container:

```sh
podman run --rm -v "$PWD":/w:Z -w /w docker.io/library/gradle:8.14-jdk21 gradle --no-daemon build
```

Local builds are versioned `dev`. GitHub Actions builds and tests every push and pull request.

To release, push a tag: `git tag v0.3.0 && git push origin v0.3.0`. The workflow builds the
plugin with version `0.3.0` and publishes the GitHub release with `fair-share.zip` attached.

The ordering logic (`FairShare.java`) has no TeamCity types and is unit-tested on its own.
`FairShareQueue.java` connects it to TeamCity.

## Live check

`scripts/queue-order-check.sh <super-user-token>` runs against a throwaway TeamCity on
`127.0.0.1:18111` with no agents, so builds stay queued. It creates two projects and two users,
queues builds and prints the resulting order next to the expected one. The super user token is
printed in `teamcity-server.log`.

## License

[MIT](LICENSE)
