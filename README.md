# TeamCity Finish Build Trigger (Plus)

A TeamCity server plugin that enhances the built-in **Finish Build Trigger** with additional capabilities.

## Features

| Feature | Description |
|---------|-------------|
| **Conditional multi-build trigger (AND)** | Watch multiple build configurations and trigger only when ALL of them have completed. e.g. Build A + Build B + Build C must all finish before Build D starts. Self-referencing and duplicate selections are prevented. |
| **Trigger on all compatible agents** | Queues one build per enabled compatible agent, identical to the Schedule Trigger option. |
| **Run build on the same agent** | Queues the triggered build on the same agent that ran the watched build. In multi-build mode, the agent from the most recently finished build is used. Falls back to unassigned queue if the agent is unavailable. |
| **Time to wait (minutes)** | Delays the triggered build by N minutes after the watched build finishes. |
| **Build Customization** | Supports clean sources, snapshot dependency rebuild, and custom build parameters via the standard TeamCity tab. |
| **Triggering build information** | Injects watched build metadata as configuration parameters and passes through the triggering user. |
| **Deleted-build auto-cleanup** | When a watched build configuration is deleted, its ID is automatically removed from every trigger that referenced it — no stale entries, no "Build configuration not found" errors on save. |
| **Trigger Chain Viewer integration** | The trigger edit dialog links out to the companion [Trigger Chain Viewer](https://github.com/xwoojin/teamcity-trigger-chain-viewer) plugin (when installed), so you can inspect the full downstream chain or reverse usage without leaving the settings page. |

## Requirements

| Component | Version |
|-----------|---------|
| TeamCity Server | 2025.11+ |
| Java (build only) | 11+ |
| Maven (build only) | 3.6+ |

## Installation

1. Download the latest `finish-build-trigger-plus.zip` from [Releases](https://github.com/xwoojin/teamcity-finish-build-trigger-plus/releases), or build from source.
2. Copy the ZIP to your TeamCity data directory:
   ```
   <TeamCity data dir>/plugins/finish-build-trigger-plus.zip
   ```
3. Restart the TeamCity server.

## Building from Source

1. Update `teamcity.home` in the root `pom.xml` to point to your local TeamCity server installation:
   ```xml
   <teamcity.home>/path/to/your/TeamCity</teamcity.home>
   ```
2. Build (auto-increments version):
   ```bash
   ./build.sh
   ```
   Or manually:
   ```bash
   mvn clean verify
   ```
3. The deployable ZIP is produced at `dist/finish-build-trigger-plus.zip`.

## Usage

1. Open **Build Configuration Settings > Triggers**.
2. Click **Add new trigger** and select **Finish Build Trigger (Plus)**.

### Finish Build Trigger (Plus) Settings

| Setting | Description |
|---------|-------------|
| **Build configuration** | The build configuration(s) to watch. Use **+ Add build configuration** to add multiple — trigger fires when ALL complete (AND condition). Stored IDs are auto-refreshed if a watched build configuration is renamed. |

### Additional Options

| Setting | Description |
|---------|-------------|
| **Trigger after successful build only** | Only trigger when the watched build finishes with SUCCESS status. |
| **Trigger build on all enabled and compatible agents** | Queue a separate build for each enabled compatible agent. Mutually exclusive with **Run build on the same agent**. |
| **Run build on the same agent** | Queue the triggered build on the agent that ran the watched build. In multi-build mode, the agent from the most recently finished watched build is used. If the agent is unavailable, falls back to the default queue. |

### Delay Trigger Options

| Setting | Description |
|---------|-------------|
| **Time to wait (minutes)** | Minutes to wait after the watched build finishes before queuing. Leave empty or `0` for immediate. In multi-build mode, the delay starts from the last build to finish. |

### Build Customization

The standard TeamCity **Build Customization** tab is available for configuring clean checkout and custom build parameters.

In addition, a **Time Settings** section is injected into the Build Customization tab when multiple watched builds are configured:

| Setting | Description |
|---------|-------------|
| **Watched Time Frame (Hours)** | *(Multi-build only)* Maximum hours between the earliest and latest watched build finish times. If the span exceeds this window, the trigger will not fire. Default: **3 hours**. |

## Injected Build Parameters

### Single-Build Mode

When the trigger watches a single build configuration, the following **configuration parameters** are injected into the triggered build:

| Parameter | Example | Description |
|-----------|---------|-------------|
| `teamcity.build.triggered.BuildTypeId` | `Dev_BuildA` | External ID of the watched build configuration |
| `teamcity.build.triggered.BuildConfName` | `Build A` | Name of the watched build configuration |
| `teamcity.build.triggered.ProjectConfName` | `Project / SubProject / Android` | Full project path of the watched build configuration |
| `teamcity.build.triggered.BuildNumber` | `42` | Build number of the specific watched build |
| `teamcity.build.triggered.BuildId` | `801` | Internal numeric build ID |

Usage in build steps:
```
echo "Triggered by: %teamcity.build.triggered.ProjectConfName% / %teamcity.build.triggered.BuildConfName% #%teamcity.build.triggered.BuildNumber%"
```

### Multi-Build Mode (AND)

When multiple build configurations are watched, indexed parameters are injected:

| Parameter | Example | Description |
|-----------|---------|-------------|
| `teamcity.build.triggered.BuildCount` | `3` | Number of watched builds |
| `teamcity.build.triggered.1.BuildTypeId` | `Dev_BuildA` | External ID of the 1st watched build |
| `teamcity.build.triggered.1.BuildConfName` | `Build A` | Name of the 1st watched build |
| `teamcity.build.triggered.1.ProjectConfName` | `Project / Android` | Full project path of the 1st watched build |
| `teamcity.build.triggered.1.BuildNumber` | `42` | Build number of the 1st watched build |
| `teamcity.build.triggered.1.BuildId` | `801` | Internal build ID of the 1st watched build |
| `teamcity.build.triggered.2.*` | ... | Same fields for the 2nd watched build |
| `teamcity.build.triggered.N.*` | ... | Same fields for the Nth watched build |

Usage in build steps:
```
echo "Build count: %teamcity.build.triggered.BuildCount%"
echo "First: %teamcity.build.triggered.1.BuildConfName% #%teamcity.build.triggered.1.BuildNumber%"
echo "Second: %teamcity.build.triggered.2.BuildConfName% #%teamcity.build.triggered.2.BuildNumber%"
```

## Triggering User Passthrough

If the watched build was manually run by a user, the triggered build inherits that user as `teamcity.build.triggeredBy.username`.

| Watched build started by | Triggered build `triggeredBy.username` |
|--------------------------|----------------------------------------|
| User (e.g. `admin`) | `admin` |
| Trigger / Schedule / VCS | `n/a` |

In multi-build mode, the user from the most recently finished watched build is used.

## Validation

The plugin prevents invalid configurations:

- **Self-reference** — A build configuration cannot watch itself (prevents cyclic triggers).
- **Duplicates** — The same build configuration cannot be added more than once.
- **Deleted references** — If a watched build configuration is deleted, its ID is silently removed from every trigger referencing it (via a `ProjectsModelListener`), the edit dialog filters unresolvable IDs out of its model, and `describeTrigger` falls back to `<non-existent (deleted) build configuration>` to mirror TeamCity's built-in trigger.

These checks are enforced both in the UI (client-side) and at the server level.

## Logging

All log messages are prefixed with `[FinishBuildTriggerPlus]` and written to `teamcity-server.log`.

To enable DEBUG level logging, add via **Administration > Diagnostics > Logging Presets**:
```xml
<Logger name="xwoojin.teamcity.trigger" level="DEBUG"/>
```

## Version Format

Versions follow `YYMMDD.N` (e.g. `260414.1`). Use `./build.sh` to auto-increment.

| Part | Meaning |
|------|---------|
| `YYMMDD` | Build date |
| `N` | Sequential build number for the day, starting at `1` |

## License

[MIT License](LICENSE)
