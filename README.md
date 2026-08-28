# PaperLive

PaperLive is a development-focused Paper server that builds and reloads ordinary Paper plugins directly from their source projects. Put a Maven or Gradle plugin project in the server's `plugins/PaperLive/projects` folder and PaperLive builds it, prepares an isolated runtime JAR, and loads it like any other plugin.

It is intended for local plugin development: edit source code, wait for the configured quiet period or run `/refresh`, then test the new plugin without manually copying JARs around.

## What PaperLive does

- Builds Maven and Gradle plugin projects at server startup.
- Keeps generated runtime JARs in `plugins/.paperlive-runtime/`, separate from source code.
- Reloads projects with `/refresh` (or `/paperlive refresh` or `/plive refresh`).
- Loads Bukkit-plugin JARs from `plugins/` and fully unloads active Bukkit plugins with `/load <plugin>` and `/unload <plugin>`.
- Optionally watches source changes and refreshes only after a configurable period without edits.
- Stops the refresh when plugin-owned threads cannot be shut down safely, and logs the responsible thread.
- Rejects incomplete build JARs before they can enter the runtime directory.
- Includes a desktop dev tool for plugin dependencies, configuration validation, scheduler inspection, reproducible snapshots, scenario replay, and event debugging.

PaperLive does not replace normal plugin development. Each project remains a conventional Paper plugin with its own build file, wrapper, dependencies, source tree, and `plugin.yml`.

## Quick start

1. Build PaperLive or obtain its Paperclip JAR.
2. Create a normal Paper server directory and run the JAR once.
3. Place plugin source projects in `plugins/PaperLive/projects/`.
4. Start the server. PaperLive compiles every supported project before plugin loading.
5. Edit your plugin and use `/refresh`, or let the source watcher refresh it after the configured quiet period.

Do not copy a PaperLive project's built JAR into `plugins/`. PaperLive already loads its isolated runtime copy; loading both would create duplicate plugins.

## Project layout

Each direct child directory of `plugins/PaperLive/projects/` is treated as one source project.

```text
plugins/
├── PaperLive/
│   ├── config.yml
│   └── projects/
│       └── example-plugin/
│           ├── pom.xml                 # Maven project
│           ├── mvnw
│           ├── mvnw.cmd
│           ├── .mvn/
│           └── src/main/
│               ├── java/
│               └── resources/plugin.yml
└── .paperlive-runtime/                 # managed by PaperLive
```

Gradle projects use `build.gradle` or `build.gradle.kts`, plus their own `gradlew` and `gradlew.bat` wrapper files. Maven projects use `pom.xml`, `mvnw`, `mvnw.cmd`, and `.mvn/wrapper/`.

The built JAR must contain a root-level `plugin.yml` and the class declared by its `main` property.

## Refreshing plugins

Use these commands as an operator:

| Command | Purpose |
| --- | --- |
| `/refresh` | Build all source projects and reload them. |
| `/load <project-or-plugin>` | Build a matching source project, or load a matching JAR from `plugins/`. |
| `/unload <plugin>` | Fully unregister an active Bukkit plugin, including its commands and classloader. |
| `/projects` | List detected PaperLive projects. |
| `/help` | Show PaperLive command help. |

The original `/paperlive <command>` and `/plive <command>` forms remain available for every command.

The default watcher waits 30 seconds after the last relevant file change before refreshing. This works well with IntelliJ autosave: while you are typing, each save restarts the timer instead of repeatedly rebuilding the server.

Configure it in `plugins/PaperLive/config.yml`:

```yml
auto-refresh: true
auto-refresh-debounce-seconds: 30
```

Set `auto-refresh` to `false` to build only through `/refresh`. Restart the server after changing this file.

## PaperLive Dev Tool

When a desktop is available, PaperLive opens an in-process development window. Disable it with
`-Dpaperlive.debugger.enabled=false` or run headless when only the console workflow is needed.

The tool contains these pages:

- **Setup** creates disposable worlds, teleports test players, and saves/restores dev snapshots. A snapshot contains the selected world (including its playerdata) and every PaperLive source plugin data folder. Source plugins are quiesced before files are archived or restored, and the pre-restore state is retained in a recovery directory.
- **Plugins** shows loaded plugins, source projects and JARs, including direct hard/optional dependencies and reverse dependents. `provides` aliases are resolved to the canonical plugin.
- **Config Guard** validates YAML and plugin descriptors before build/unload. Invalid source YAML blocks refresh. The page compares current files with the last successful refresh.
- **Tasks** attributes Bukkit and Paper scheduler executions to a plugin, registration callsite and thread. Executions over 50 ms, task failures and unsafe AsyncCatcher access are highlighted.
- **Scenarios** records one player's commands and event/inventory observations. Replay restores the recorded starting location/inventory, executes commands, and asserts the final world, location and inventory. Observations requiring a real client are shown as manual steps rather than simulated inaccurately.
- **Debugger** traces commands, correlated background activity, event handler order, cancellation changes, build failures and exceptions.

Plugin-conflict bisect uses a saved dev snapshot plus a failing scenario. It repeatedly restores the same baseline and disables dependency-expanded halves of the PaperLive source plugins. This assumes a deterministic scenario and one primary conflicting source plugin; the final suspect is verified separately and the all-plugins-loaded snapshot is restored afterward.

### Scenario commands

Scenario recording and replay also work without the desktop window. Run the recording and replay commands as the online player whose state should be captured or restored:

| Command | Purpose |
| --- | --- |
| `/scenario create <name>` | Start recording a scenario. |
| `/scenario record <name>` | Alias for `create`. |
| `/scenario stop [name]` | Save the recording. The name is optional when it was supplied to `create` or `record`. |
| `/scenario cancel` | Discard the active recording. |
| `/scenario replay <name>` | Restore the recorded initial state, replay the scenario, and check the expected final state. |
| `/scenario list` | List saved scenarios. |
| `/scenario status` | Show the active recording, if any. |
| `/scenario delete <name>` | Delete a saved scenario. |

`/senario` is accepted as an alias for `/scenario`. Scenario names must be 1–64 characters and use only letters, numbers, `.`, `_`, or `-`.

## Build logs and troubleshooting

Every project has a build log in:

```text
plugins/.paperlive-runtime/paperlive-<project-name>.build.log
```

An empty build log usually means PaperLive could not start the build command, such as when a required Maven or Gradle wrapper is missing. Maven and Gradle compiler errors are written to the same log.

If a refresh is blocked, PaperLive leaves the active plugins unchanged and reports the responsible plugin thread in the console. The detailed stack trace is logged as a `Refresh blocker diagnostic` entry.

Avoid running `mvn package` or `gradlew build` manually while PaperLive is building the same project. Let PaperLive own the build during a refresh so it never observes a partially written output JAR.

## Building PaperLive from source

PaperLive is built as a Paper server distribution. You need JDK 25 and an internet connection.

```powershell
.\gradlew.bat :paper-server:createPaperclipJar
```

The runnable server JAR is written to:

```text
paper-server/build/libs/paper-paperclip-26.2.local-SNAPSHOT.jar
```

## Development notes

- The server's own build requires JDK 25.
- A plugin project uses the Java version declared by its own Maven or Gradle build.
- Build output folders such as `target`, `build`, and `out` are ignored by the watcher to avoid refresh loops.
- PaperLive supports Java/Kotlin source, plugin resources, and Maven/Gradle configuration changes.

For a more detailed project walkthrough, see [PAPERLIVE_DEVELOPMENT.md](PAPERLIVE_DEVELOPMENT.md).
