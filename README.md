# teamcity-fair-share

A TeamCity server plugin that orders the build queue by fair share: between projects, then
between users inside each project.

TeamCity's queue is first-in first-out, adjusted by static priority classes. When one project
floods the queue, everyone else waits behind it. This plugin re-sorts the queue so that the
projects that used the CI least recently go first. There is no quota: when nobody else is
waiting, the busy project still gets every agent.

## How it works

1. Each build is charged to an owner: its top-level project, and a user inside that project.
   The user is whoever clicked Run. Otherwise it is the TeamCity account matched to the
   author of the build's change (through the e-mails and VCS usernames set on accounts), or
   the raw VCS author when no account matches. Scheduled builds without changes go to
   `automatic`.
2. Each time a build is queued or finishes, the plugin computes every owner's recent usage,
   in agent-seconds. Finished builds count for their duration, halved every half-life
   (12 hours by default). Running builds count for their estimated duration.
3. It builds the new queue one build at a time: take the next build of the least-served
   project, and inside it of the least-served user, then add that build's estimated duration
   to both.
4. Builds of the same owner keep their relative order. Ties keep TeamCity's order.
5. It applies the new order only if it differs from the current one.

This is weighted fair queuing, the same idea as fair-share schedulers in batch systems. Running
builds are never interrupted, so a newly queued build still waits for the next free agent.

The plugin overrides manual drag-and-drop reordering on its next pass.

## Settings

Internal properties (Administration → Server Administration → Diagnostics → Internal Properties),
read on every pass, no restart needed:

| Property | Default | Meaning |
|---|---|---|
| `teamcity.fairShare.enabled` | `true` | `false` stops all reordering |
| `teamcity.fairShare.halfLifeHours` | `12` | How fast past usage is forgotten |

## Build and install

```sh
gradle build    # → build/distributions/fair-share.zip
```

Upload the zip in Administration → Plugins and enable it. No restart is needed: the plugin
supports runtime reload, so updates and removal are live too.

Built against TeamCity 2026.2, Java 17 bytecode.

## Live check

Tested on TeamCity 2026.2.1 in a throwaway container with no agents, so builds stay queued.
`scripts/queue-order-check.sh <super-user-token>` creates two projects and two users, queues
builds and prints the resulting order next to the expected one. The super user token is
printed in `teamcity-server.log`.
