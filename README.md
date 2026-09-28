# PluginUpdater-WB 🔄

> Highly advanced, fully asynchronous plugin manager and updater for Paper servers.
>
> Fork of [web-beck/PluginUpdater-WB](https://github.com/web-beck/PluginUpdater-WB) — all credit for the original plugin goes to **WebBeck**. See [Credits](#-credits).

**Version:** `26.2-1.2.0` &nbsp;|&nbsp; **Minecraft:** `26.2` &nbsp;|&nbsp; **API Version:** `26.2` &nbsp;|&nbsp; **Java:** 25 &nbsp;|&nbsp; **Original Author:** WebBeck &nbsp;|&nbsp; **Fork Maintainer:** Fahry-a

---

## 📖 What It Does

PluginUpdater-WB automates keeping your Paper server's plugins up to date. It checks for new versions across multiple plugin repositories—Modrinth, Hangar, GitHub Releases, and SpigotMC—then downloads and installs updates asynchronously so your server stays responsive. It also supports direct custom download URLs and manages the Geyser-ecosystem artifacts that cannot be regular plugins (Floodgate, MCXboxBroadcast) natively. Geyser itself is tracked as a regular plugin via Modrinth.

## ✨ Features

- **Multi-source update checking** — Modrinth, Hangar, GitHub Releases, SpigotMC, and custom URLs
- **Fully asynchronous** — all network and I/O operations run off the main thread
- **Per-plugin configuration** — track `release`, `beta`, `alpha`, or `all` channels per plugin
- **Auto-detection** — scans loaded plugins on startup and populates config automatically
- **Geyser addon management** — Floodgate (direct download with ETag change detection) and MCXboxBroadcast (GitHub release with asset pin + sha256 verification); Geyser itself is tracked as a regular Modrinth plugin
- **Backup support** — backs up plugin jars before overwriting
- **Fine-grained permissions** — `pluginupdater.admin` permission node plus an allowlist for specific players
- **Server-type awareness** — supports Paper, Purpur, Folia, Spigot, and Bukkit loader targets

---

## 📋 Requirements

- Paper (or compatible fork) running **API version 26.2**
- Java **25**

---

## 🚀 Installation

1. Download `PluginUpdater-WB-26.2-1.2.0.jar` and drop it into your server's `plugins/` folder.
2. Start (or restart) your server. The plugin will generate `config.yml` and auto-populate it with entries for every plugin currently loaded.
3. Edit `config.yml` to enable or tune tracking for each plugin (see [Configuration](#%EF%B8%8F-configuration) below).
4. Run `/upd reload` to apply changes without restarting.

---

## ⚙️ Configuration

`config.yml` is generated on first run. Key options:

```yaml
# Minecraft version used for Modrinth queries. Blank = auto-detect (recommended).
# If the exact version has no builds, the latest available release is used instead.
minecraft-version: ''

# Players allowed to run commands without OP or the permission node.
allowed-players:
  - 'YourUsernameHere'

# Loader type sent to Modrinth. "auto" detects Paper/Spigot/etc at runtime.
# Valid: "auto", "paper", "purpur", "folia", "spigot", "bukkit"
server-type-override: 'paper'

# Default release channel for newly detected plugins.
# Per-plugin channels set via /upd plugin track are never overwritten.
# Valid: "release", "beta", "alpha", "all"
tracking-type: 'all'

# Optional GitHub token. Raises the API limit from 60 to 5,000 req/hour.
github-token: ''

# Geyser addon management (Floodgate, MCXboxBroadcast).
# Geyser itself is tracked as a regular plugin via Modrinth (channel beta),
# NOT here - legacy geyser-addons.Geyser keys are removed automatically.
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

### Plugin Entries

Each plugin under the `plugins:` key supports one of five source types:

```yaml
plugins:

  # Modrinth
  MyPlugin:
    enabled: true
    type: MODRINTH
    project-id: osLrA9oB
    allowed-release-types:
      - release
    current-version: 1.0.0

  # GitHub Releases
  AnotherPlugin:
    enabled: true
    type: GITHUB
    github-repo: owner/repository
    allowed-release-types:
      - release
    current-version: 2.0.0

  # Hangar (PaperMC)
  HangarPlugin:
    enabled: true
    type: HANGAR
    project-id: ViaVersion
    allowed-release-types:
      - release
    current-version: 5.0.0

  # SpigotMC (numeric resource ID from the URL)
  SpigotPlugin:
    enabled: true
    type: SPIGOT
    project-id: '19254'
    allowed-release-types:
      - release
    current-version: 1.0.0

  # Custom URL (always downloads; skips version-check logic)
  CustomPlugin:
    enabled: true
    type: CUSTOM
    custom-url: 'https://example.com/downloads/CustomPlugin-latest.jar'
    current-version: 1.0.0
```

A reference for the entry format is below. Entries live in `plugins/plugins.yml`
(auto-generated on first run), not in `config.yml`.

---

## 💬 Commands

The main command is `/updater` (alias: `/upd`). Requires the `pluginupdater.admin` permission or OP (configurable).

| Command | Description |
|---|---|
| `/upd help` | Show all available subcommands |
| `/upd -v` | Display the plugin version |
| `/upd check` | Run an async update check across all enabled plugins |
| `/upd run` | Download and install all available updates |
| `/upd staged` | Inspect the update folder without restarting |
| `/upd errors` | List plugins whose source could not be checked, with fix commands |
| `/upd confirm` | Confirm a pending plugin deletion (expires in 60s) |
| `/upd list` | List all tracked plugins and their current status |
| `/upd reload` | Reload `config.yml` without restarting the server |
| `/upd plugin <name>` | Check, update, or manage a specific plugin |
| `/upd plugin track <plugin\|all> <release\|beta\|alpha\|all>` | Set release channel per plugin or globally |
| `/upd plugin track <plugin\|all> server <auto\|paper\|spigot\|folia\|purpur>` | Set loader target per plugin or globally |
| `/upd plugin toggle <plugin> <true\|false>` | Enable/disable tracking for a plugin |
| `/upd plugin <name> delete` + `/upd confirm` | Schedule a plugin jar + data folder for deletion |
| `/upd plugin geyser` | Manage Floodgate & MCXboxBroadcast (requires `geyser-addons.enabled: true`; Geyser itself is a regular Modrinth-tracked plugin) |

---

## 🔒 Permissions

| Node | Description | Default |
|---|---|---|
| `pluginupdater.admin` | Full access to all updater commands | OP |

Non-OP players can also be granted access by adding their username to `allowed-players` in `config.yml`.

---

## 🚀 Auto Release

Releases are fully automated by [semantic-release](https://github.com/semantic-release/semantic-release) running in [`.github/workflows/release.yml`](.github/workflows/release.yml) on every push to `main` (after `mvn test` passes).

- Write commits in [Conventional Commits](https://www.conventionalcommits.org/) style: `fix:` → patch, `feat:` → minor, `feat!:`/`BREAKING CHANGE:` → major. `chore:`, `docs:`, `test:` etc. trigger no release.
- The bot tags pure semver (`v1.0.6`), bumps `pom.xml`/`README.md` to `26.3-1.0.6` (Minecraft prefix is kept), rebuilds the jar, attaches it to the GitHub Release, updates `CHANGELOG.md`, and commits everything back as `chore(release): … [skip ci]`.
- Requirements: the workflow's `GITHUB_TOKEN` must be allowed to push to `main` (repo Settings → Actions → General → Workflow permissions: Read and write). If `main` is branch-protected against the bot, use a `RELEASE_PAT` secret instead.

---

## 🆘 Support

- **Issues & bug reports:** Open an issue on the project repository
- **Upstream (original):** [web-beck/PluginUpdater-WB](https://github.com/web-beck/PluginUpdater-WB)
- **This fork:** [Fahry-a/PluginUpdater-WB](https://github.com/Fahry-a/PluginUpdater-WB)

---

## 🙏 Credits

- **Original plugin** by [**WebBeck**](https://github.com/web-beck) ([web-beck/PluginUpdater-WB](https://github.com/web-beck/PluginUpdater-WB)) — this repository is a fork of that work.
- Fork changes (async overhaul, Geyser hybrid tracking, source registry) are documented in the commit history.

---

## 🤝 Contributing

Pull requests are welcome. Please open an issue first to discuss any significant changes. Make sure new features include appropriate config defaults and that all commands continue to work asynchronously.

---

## 👤 Maintainer

[**WebBeck**](www.webbeck.org)

See the `LICENSE` file for license terms.
