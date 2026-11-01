# teamcity-fair-share

A TeamCity server plugin that orders the build queue by fair share between projects.

TeamCity's queue is first-in first-out, adjusted by static priority classes. When one project
floods the queue, everyone else waits behind it. This plugin re-sorts the queue so that the
projects that used the CI least recently go first. There is no quota: when nobody else is
waiting, the busy project still gets every agent.

## How it works

1. Each time a build is queued or finishes, the plugin computes every top-level project's
   recent usage, in agent-seconds. Finished builds count for their duration, halved every
   half-life (12 hours by default). Running builds count for their estimated duration.
2. It builds the new queue one build at a time: take the next build of the project with the
   least usage, then add that build's estimated duration to the project's usage.
3. Builds of the same project keep their relative order. Ties keep TeamCity's order.
4. It applies the new order only if it differs from the current one.

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

Upload the zip in Administration → Plugins, then restart the server.

Built against TeamCity 2026.2, Java 17 bytecode.
