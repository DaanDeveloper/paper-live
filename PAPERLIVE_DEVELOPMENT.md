# PaperLive development projects

PaperLive lets you develop a normal Paper plugin from source while the PaperLive Server prepares its JAR during server startup.

## Project location

Place each project below the server's plugin folder:

```text
plugins/
└── PaperLive/
    └── projects/
        └── testplugin/
            ├── build.gradle.kts
            ├── gradlew.bat
            ├── gradlew
            ├── gradle/
            └── src/
                └── main/
                    ├── java/
                    │   └── nl/testplugin/plugin/TestPlugin.java
                    └── resources/
                        └── plugin.yml
```

The project remains a conventional plugin. For example, its main class can still use `extends JavaPlugin`, register listeners with `getServer().getPluginManager()`, and declare commands in `plugin.yml`.

## Build requirements

- Gradle projects need `build.gradle` or `build.gradle.kts` and their own Gradle wrapper (`gradlew.bat` on Windows).
- Maven projects need `pom.xml` and their own Maven wrapper (`mvnw.cmd` on Windows).
- The build output must contain a JAR with a root-level `plugin.yml`.

When the server starts, PaperLive builds each project, stores the build log in `plugins/.paperlive-runtime/`, and loads the generated JAR from that isolated directory.

## Refreshing projects

Automatic source refresh waits 30 seconds after the most recent source change, so editor autosave does not rebuild the server while you are typing. Use `/refresh` whenever you want to compile and reload a project immediately. The original `/paperlive refresh` and `/plive refresh` forms remain available.

Configure the watcher in `plugins/PaperLive/config.yml` and restart the server:

```yml
auto-refresh: true
auto-refresh-debounce-seconds: 30
```

Set `auto-refresh` to `false` to use manual refreshes only. Increase or decrease `auto-refresh-debounce-seconds` to change how long PaperLive waits after the final source change.

Do not also copy that plugin's JAR directly into `plugins/`; that would cause the same plugin to be discovered twice.

PaperLive watches relevant project files after server startup. Changes to Java/Kotlin source, resources, and Gradle or Maven configuration are debounced, compiled in the background, and refreshed automatically. Build output directories are ignored to prevent refresh loops.

## Development commands

Only operators may use PaperLive's commands:

- `/refresh` manually rebuilds every source project and reloads plugins without restarting the server process.
- `/load <plugin>` and `/unload <plugin>` load or unload a plugin.
- `/projects` shows source projects that PaperLive can build.
- `/help` shows the command overview.

Compilation and refresh feedback is sent to the console and to online players with the `paperlive.command` permission. The permission defaults to operators. If a plugin-owned thread does not stop after interruption, PaperLive aborts the refresh, restores the existing plugins, and reports the blocking thread instead of loading a second plugin instance.

## Testing with the Dev Tool

Use **Config Guard** before the first refresh to catch malformed YAML and descriptors. After a successful refresh it records a last-known-good configuration baseline.

For repeatable manual tests:

1. Create or select a non-primary disposable world on **Setup**.
2. Save a named dev snapshot. This includes the world/playerdata and PaperLive source-plugin data folders.
3. Start a scenario recording for an online player.
4. Perform the commands and interactions once, then stop and save the scenario.
5. Restore the snapshot and replay the scenario after later refreshes.

Server-side replay executes recorded commands and verifies the final player state. Event and inventory observations that require real client input remain visible as manual steps. This boundary is intentional: calling a Bukkit event directly is not equivalent to a client packet and vanilla game processing.

When a scenario reliably fails only with a particular source-plugin combination, **Bisect plugin conflict** can isolate one suspect. It only cycles PaperLive source plugins, follows dependent unloads, restores the snapshot before every iteration, and performs a final verification.
