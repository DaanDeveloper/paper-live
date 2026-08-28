# PaperLive Dev Tool implementation plan

This plan keeps each capability independently testable and makes later automation build on earlier safety primitives.

## Completed milestones

1. **Plugin dependency visibility**
   - Show hard and optional direct dependencies.
   - Show reverse dependents.
   - Resolve case-insensitive names and `provides` aliases.
   - Read metadata from loaded plugins and unloaded/runtime JARs with a timestamp/size cache.

2. **Config Guard**
   - Validate `plugin.yml`, `paper-plugin.yml`, and source YAML before compilation.
   - Block refresh before unload when an error exists.
   - Report file, line and column where available.
   - Capture the configuration from the last successful refresh and show added, modified, removed and unchanged files.

3. **Scheduler and async inspector**
   - Trace classic Bukkit sync/async tasks and Paper async/global/entity tasks.
   - Retain only serializable task metadata, not plugin task objects.
   - Show owner, registration callsite, thread, duration, repeating state and failure.
   - Attribute AsyncCatcher violations through the active plugin classloader.

4. **Dev snapshots**
   - Archive a selected dev world/playerdata and PaperLive source-plugin data folders.
   - Quiesce source plugins before archive/restore.
   - Reject unsafe/mismatched ZIP contents.
   - Move current data to a recovery directory before restore and roll back partial extraction failures.

5. **Scenario record and replay**
   - Record player commands and relevant player/inventory observations.
   - Persist initial state, steps and final assertions as YAML.
   - Restore initial world/location/inventory, replay commands, and compare final state.
   - Keep client-only interactions explicit as manual observations.

6. **Dependency-aware conflict bisect**
   - Require a reproducibly failing scenario and named snapshot.
   - Restore the baseline for every iteration.
   - Disable candidate source plugins with their dependent closure.
   - Binary-isolate and separately verify the final suspect.
   - Restore the all-plugins-loaded baseline in a `finally` path.

## Verification gates

- Pure metadata, Config Guard, snapshot archive/rollback, scenario persistence and bisect-set logic have unit tests.
- `PaperLiveCommandTestSuite` covers command/world/config/snapshot/bisect helpers.
- `PaperLiveDebuggerTestSuite` covers debugger/task/scenario/dependency models.
- Both suites must pass together after changes to shared runtime code.
- Manual server validation should cover GUI layout, a real Gradle and Maven source project, snapshot restore on Windows, async task attribution, one passing replay, one failing replay and a two-plugin bisect.

## Deliberate next boundary

A real-client runner is an optional later integration. The current recorder format already separates replayable commands from client-only observations so a protocol client can implement those steps without changing stored scenarios.
