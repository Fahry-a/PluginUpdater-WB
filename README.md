# PluginUpdater-WB

A fully asynchronous plugin manager and updater for Paper-compatible Minecraft servers.

PluginUpdater-WB automatically checks, downloads, stages, and manages plugin updates from multiple sources, including Modrinth, GitHub Releases, Hangar, SpigotMC, and custom URLs.

This repository is a maintained fork of [web-beck/PluginUpdater-WB](https://github.com/web-beck/PluginUpdater-WB). The original project and its author are credited below.

## Overview

PluginUpdater-WB is designed to keep a Paper server's plugin collection up to date without blocking the server's main thread.

It provides:

- Multi-source update checking
- Asynchronous network and file operations
- Per-plugin update channels
- Automatic plugin discovery
- Backup and rollback support
- Plugin enable/disable tracking
- Geyser ecosystem management
- Dedicated self-update support from GitHub Releases
- Paper, Purpur, Folia, Spigot, and Bukkit loader awareness

### Supported update sources

| Source | Use |
|---|---|
| Modrinth | Versioned plugin releases and builds |
| GitHub Releases | GitHub-hosted plugin releases |
| Hangar | PaperMC Hangar projects |
| SpigotMC | Spigot resources |
| Custom URL | Direct JAR downloads |

## Requirements

- **Minecraft:** 26.2
- **Server:** Paper or a compatible fork
- **Java:** 25
- **Folia:** Supported

The exact server and Java requirements are determined by the version of PluginUpdater-WB you install.

## Installation

1. Download the latest `PluginUpdater-WB` JAR from the [GitHub Releases](https://github.com/zalfafa/PluginUpdater-WB/releases) page.
2. Put the JAR into your server's `plugins/` directory.
3. Start or restart the server.
4. PluginUpdater-WB will create its configuration and discover installed plugins.
5. Review `plugins/PluginUpdater-WB/config.yml` and the generated plugin tracking configuration.
6. Run `/upd reload` after making configuration changes.

## Commands

The main command is `/updater`. The shorter alias `/upd` is recommended.

### General commands

| Command | Description |
|---|---|
| `/upd help` | Show the command help |
| `/upd -v` | Show the installed PluginUpdater-WB version |
| `/upd check` | Check tracked plugins for available updates |
| `/upd run` | Download and stage pending plugin updates |
| `/upd run <plugin>` | Stage a specific pending update |
| `/upd list` | Show tracked plugins |
| `/upd list all` | Show all tracked plugins |
| `/upd list versions` | Check physical/latest versions without the normal game-version filter |
| `/upd list enabled` | Show enabled plugin tracking entries |
| `/upd list disabled` | Show disabled plugin tracking entries |
| `/upd list pending` | Show pending plugin deletions |
| `/upd staged` | Show updates currently staged for the next restart |
| `/upd errors` | Show plugin sources that failed to update |
| `/upd reload` | Reload and synchronize configuration |
| `/upd confirm` | Confirm a pending plugin deletion |

### Self-update

PluginUpdater-WB can update itself from the project's official GitHub Releases.

| Command | Description |
|---|---|
| `/upd self` | Check for a PluginUpdater-WB update |
| `/upd self check` | Check for a PluginUpdater-WB update |
| `/upd self update` | Check and stage the latest self-update |
| `/upd self status` | Show self-update configuration and status |

Self-updates are staged through Paper's update mechanism. The currently running JAR is not replaced while the plugin is loaded; Paper applies the staged JAR on the next server restart.

### Plugin management

| Command | Description |
|---|---|
| `/upd plugin <name>` | Manage a specific plugin |
| `/upd plugin track <plugin> <channel>` | Set the update channel |
| `/upd plugin track <plugin> server <type>` | Set the server/loader target |
| `/upd plugin toggle <plugin> <true\|false>` | Enable or disable tracking |
| `/upd plugin redownload <plugin>` | Force a plugin download |
| `/upd plugin rollback <plugin> <backup>` | Stage a rollback from a backup |
| `/upd plugin info <plugin>` | Show plugin tracking information |
| `/upd plugin id <plugin> auto` | Resolve a plugin on Modrinth |
| `/upd plugin id <plugin> <source> <id>` | Manually lock a plugin to a source |
| `/upd plugin <name> delete` | Schedule a plugin for deletion |
| `/upd confirm` | Confirm the scheduled deletion |

Supported tracking channels:

- `release`
- `beta`
- `alpha`
- `all`

Supported server types:

- `auto`
- `paper`
- `purpur`
- `folia`
- `spigot`
- `bukkit`

### Geyser management

Geyser is handled as a regular plugin and is **locked to Modrinth**.

Floodgate and MCXboxBroadcast are handled separately because they use different distribution mechanisms.

| Command | Description |
|---|---|
| `/upd plugin geyser` | Show Geyser management options |
| `/upd plugin geyser update Geyser` | Check and stage a Geyser update |
| `/upd plugin geyser update Floodgate` | Check and stage a Floodgate update |
| `/upd plugin geyser update MCXboxBroadcast` | Check and stage an MCXboxBroadcast update |
| `/upd plugin geyser update all` | Update Geyser and its managed addons |
| `/upd plugin geyser download <name>` | Download/stage a specific Geyser component |
| `/upd plugin geyser toggle <addon> <true\|false>` | Enable or disable addon management |

Geyser itself uses the official Modrinth project as its update source. Legacy/custom Geyser source settings are normalized automatically.

## Configuration

The main configuration file is:

`plugins/PluginUpdater-WB/config.yml`

### Basic configuration

```yaml
minecraft-version: ''

allowed-players:
  - 'YourUsernameHere'

server-type-override: 'paper'

tracking-type: 'all'

github-token: ''
```

### Minecraft version

`minecraft-version` controls the Minecraft version sent to Modrinth.

Leave it empty to let PluginUpdater-WB detect the running server version automatically.

```yaml
minecraft-version: ''
```

### Server type

`server-type-override` controls the loader used for Modrinth queries.

```yaml
server-type-override: 'paper'
```

Valid values:

`auto`, `paper`, `purpur`, `folia`, `spigot`, `bukkit`

### Default tracking channel

`tracking-type` is used when new plugins are discovered.

```yaml
tracking-type: 'all'
```

Valid values:

`release`, `beta`, `alpha`, `all`

Per-plugin settings can be changed with `/upd plugin track`.

### GitHub API token

An optional GitHub token can increase the unauthenticated API rate limit.

```yaml
github-token: ''
```

A token is not required for normal GitHub Release checks.

## Self-update configuration

Self-update is enabled by default.

```yaml
self-update:
  enabled: true
  auto-download: true
  check-interval-minutes: 360
  github-repo: 'Fahry-a/PluginUpdater-WB'
```

| Option | Description |
|---|---|
| `enabled` | Enables or disables self-update checks |
| `auto-download` | Automatically stages an available update |
| `check-interval-minutes` | Periodic check interval; `0` disables periodic checks |
| `github-repo` | Official GitHub repository used for self-update |

A startup check still runs when periodic checking is disabled.

## Plugin tracking configuration

Plugin tracking data is stored separately from the main configuration.

The generated plugin configuration contains entries such as:

### Modrinth

```yaml
MyPlugin:
  enabled: true
  type: MODRINTH
  project-id: osLrA9oB
  allowed-release-types:
    - release
  current-version: 1.0.0
```

### GitHub Releases

```yaml
AnotherPlugin:
  enabled: true
  type: GITHUB
  github-repo: owner/repository
  allowed-release-types:
    - release
  current-version: 2.0.0
```

### Hangar

```yaml
HangarPlugin:
  enabled: true
  type: HANGAR
  project-id: ViaVersion
  allowed-release-types:
    - release
  current-version: 5.0.0
```

### SpigotMC

```yaml
SpigotPlugin:
  enabled: true
  type: SPIGOT
  project-id: '19254'
  allowed-release-types:
    - release
  current-version: 1.0.0
```

### Custom URL

```yaml
CustomPlugin:
  enabled: true
  type: CUSTOM
  custom-url: 'https://example.com/downloads/CustomPlugin-latest.jar'
  current-version: 1.0.0
```

Custom URLs are useful for plugins that do not provide a supported release API.

## Geyser addons

Geyser addon management is configured under `geyser-addons`.

```yaml
geyser-addons:
  enabled: false
  Floodgate: true
  MCXboxBroadcast: true
  Floodgate-url: 'https://download.geysermc.org/v2/projects/floodgate/versions/latest/builds/latest/downloads/spigot'
  Floodgate-file: 'floodgate.jar'
  MCXboxBroadcast-repo: 'MCXboxBroadcast/Broadcaster'
  MCXboxBroadcast-asset: 'MCXboxBroadcastExtension.jar'
  MCXboxBroadcast-file: 'MCXboxBroadcastExtension.jar'
```

Set `enabled: true` to allow addon management through `/upd plugin geyser`.

### Floodgate

Floodgate is downloaded from the GeyserMC distribution endpoint.

The updater uses HTTP metadata such as ETag and Last-Modified to avoid unnecessary downloads when possible.

### MCXboxBroadcast

MCXboxBroadcast is retrieved from GitHub Releases using an explicit release asset name.

The downloaded artifact is also verified using its expected SHA-256 when the source provides one.

### Geyser

Geyser is not configured as a Geyser addon.

It is tracked as a normal plugin entry and its source is automatically locked to Modrinth.

## Backups and rollback

Before replacing an installed plugin JAR, PluginUpdater-WB can create a backup.

Backups are stored under:

`plugins/PluginUpdater-WB/backups/`

A backup can be staged for restoration with:

```
/upd plugin rollback <plugin> <backup-file>
```

This makes it possible to recover from an update without manually replacing files.

## Permissions

| Permission | Description | Default |
|---|---|---|
| `pluginupdater.admin` | Full access to PluginUpdater-WB commands | OP |

Players can also be granted access through `allowed-players` in `config.yml`.

## How updates are applied

PluginUpdater-WB separates checking, downloading, and applying updates.

```text
Server startup
     |
     v
Discover / load plugin configuration
     |
     v
Check supported update sources
     |
     v
Download available update
     |
     v
Validate JAR
     |
     v
Backup current JAR
     |
     v
Stage update
     |
     v
Server restart
     |
     v
Paper loads the new JAR
```

Network requests and file operations are performed asynchronously where possible so update checks do not unnecessarily block the server's main thread.

## Automatic releases

The repository uses GitHub Actions for automated releases.

The release workflow builds and publishes the plugin after successful verification on `main`.

Commits follow [Conventional Commits](https://www.conventionalcommits.org/):

| Commit | Release impact |
|---|---|
| `fix:` | Patch |
| `feat:` | Minor |
| `feat!:` | Major |
| `BREAKING CHANGE:` | Major |
| `docs:`, `chore:`, `test:` | No release |

Release configuration is maintained in [`.github/workflows/release.yml`](.github/workflows/release.yml).

## Development

Clone the repository and build it with Maven:

```bash
git clone https://github.com/Fahry-a/PluginUpdater-WB.git
cd PluginUpdater-WB
mvn clean verify
```

The generated JAR is placed in:

```
target/
```

### Project structure

```text
PluginUpdater-WB/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── me/webbeck/pluginUpdater/
│   │   └── resources/
│   │       ├── config.yml
│   │       └── plugin.yml
├── .github/
│   └── workflows/
├── pom.xml
├── CHANGELOG.md
├── LICENSE
└── README.md
```

## Support

For bugs or feature requests, open an issue in the [zalfafa/PluginUpdater-WB](https://github.com/zalfafa/PluginUpdater-WB) repository.

For issues specific to the original upstream project, see [web-beck/PluginUpdater-WB](https://github.com/web-beck/PluginUpdater-WB).

## Credits

PluginUpdater-WB is based on the original work by [WebBeck](https://github.com/web-beck).

- Original project: [web-beck/PluginUpdater-WB](https://github.com/web-beck/PluginUpdater-WB)
- Original author: **WebBeck**
- Current fork maintainer: **zalfafa**

Changes in this fork include asynchronous update handling, improved source management, Geyser integration, Geyser source locking, addon download fixes, and dedicated self-update support.

## Contributing

Contributions are welcome.

Before submitting a pull request:

1. Keep changes focused.
2. Follow the existing code style.
3. Use Conventional Commits where practical.
4. Update the README or configuration documentation when behavior changes.
5. Run `mvn clean verify` before submitting the change.

## License

See [LICENSE](LICENSE) for the license terms.
