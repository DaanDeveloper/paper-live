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
