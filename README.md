# TeamCity Finish Build Trigger (Plus)

A TeamCity server plugin that enhances the built-in **Finish Build Trigger** with additional capabilities.

## Features

- **Trigger on all compatible agents** — Queues one build per enabled compatible agent, identical to the Schedule Trigger option.
- **Time to wait (minutes)** — Delays the triggered build by N minutes after the watched build finishes.
- **Build Customization** — Supports clean sources, snapshot dependency rebuild, and custom build parameters via the standard TeamCity tab.
- **Triggering build information** — Injects watched build metadata (`buildTypeId`, `buildTypeName`, `BuildNumber`, `BuildId`) as configuration parameters into the triggered build, and passes through the triggering user so `triggeredBy.username` reflects who started the original build.

## Requirements

| Component | Version |
|-----------|---------|
| TeamCity Server | 2025.11+ |
| Java (build only) | 11+ |
| Maven (build only) | 3.6+ |

## Installation

1. Download the latest `finish-build-trigger-plus.zip` from [Releases](https://github.com/xwoojin/finish-build-trigger-plus/releases), or build from source.
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
2. Build:
   ```bash
   mvn verify
   ```
3. The deployable ZIP is produced at `dist/finish-build-trigger-plus.zip`.

## Usage

1. Open **Build Configuration Settings > Triggers**.
2. Click **Add new trigger** and select **Finish Build Trigger (Plus)**.

### Triggering Settings

| Setting | Description |
|---------|-------------|
| **Build configuration** | The build configuration to watch for completion. |
| **Trigger after successful build only** | Only trigger when the watched build finishes with SUCCESS status. |
| **Trigger build on all enabled and compatible agents** | Queue a separate build for each enabled compatible agent. |
| **Time to wait (minutes)** | Minutes to wait after the watched build finishes before queuing. Leave empty or `0` for immediate. |

### Build Customization

The standard TeamCity **Build Customization** tab is available, allowing you to configure clean checkout and custom build parameters — the same as the built-in Finish Build Trigger.

## Injected Build Parameters

When the trigger fires, the following **configuration parameters** are automatically injected into the triggered build:

| Parameter | Example | Description |
|-----------|---------|-------------|
| `teamcity.build.triggered.BuildTypeId` | `Dev_BuildA` | External ID of the watched build configuration |
| `teamcity.build.triggered.BuildConfName` | `Build A` | Name of the watched build configuration |
| `teamcity.build.triggered.ProjectConfName` | `ProjectName / SubProjectName / Android` | Full project path of the watched build configuration |
| `teamcity.build.triggered.BuildNumber` | `42` | Build number of the specific watched build |
| `teamcity.build.triggered.BuildId` | `801` | Internal numeric build ID |

These can be referenced in build steps:
```
echo "Triggered by: %teamcity.build.triggered.ProjectConfName% / %teamcity.build.triggered.BuildConfName% #%teamcity.build.triggered.BuildNumber%"
```

## Triggering User Passthrough

If the watched build was manually run by a user, the triggered build inherits that user as `teamcity.build.triggeredBy.username`.

| Watched build started by | Triggered build `triggeredBy.username` |
|--------------------------|----------------------------------------|
| User (e.g. `admin`) | `admin` |
| Trigger / Schedule / VCS | `n/a` |

## Logging

All log messages are prefixed with `[FinishBuildTriggerPlus]` and written to `teamcity-server.log`.

To enable DEBUG level logging, add via **Administration > Diagnostics > Logging Presets**:
```xml
<Logger name="xwoojin.teamcity.trigger" level="DEBUG"/>
```

## Version Format

Versions follow `YYMMDD.N` (e.g. `260402.1`):

| Part | Meaning |
|------|---------|
| `YY` | Year (last 2 digits) |
| `MM` | Month |
| `DD` | Day |
| `N` | Sequential build number, starting at `1` |

When releasing, update the version in three places: `pom.xml`, `server/pom.xml`, and `teamcity-plugin.xml`.

## License

[MIT License](LICENSE)
