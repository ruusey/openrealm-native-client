# OpenRealm Native Client — Setup

End-to-end setup for building, running, and packaging the native desktop client.

---

## 1. Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| **JDK** | 17+ (Temurin recommended) | Must include `jpackage` (standard in JDK 17+) |
| **Maven** | 3.8.3+ | `mvn -v` to verify |
| **Git** | any | For pulling sources |

Platform-specific extras (only needed when building installers):

| Platform | Extra requirement | Why |
|----------|------------------|-----|
| **Windows** | [WiX Toolset 3.x](https://github.com/wixtoolset/wix3/releases) on `PATH` | `jpackage` shells out to WiX to produce the `.exe` / `.msi` |
| **macOS** | Xcode Command-Line Tools (`xcode-select --install`) | Required by `jpackage` for `.dmg` |
| **Linux** | `fakeroot` + `dpkg` (deb) or `rpm-build` (rpm) | Native packager backends |

---

## 2. Companion services

The client is a presentation layer — it needs both backend services running:

1. **`openrealm-data`** — Spring Boot REST service (account, characters, item content). Default port `8080`.
2. **`openrealm`** — Real-time game server (TCP + WebSocket). Default port `2222`.

Both are separate repos; clone and start them per their own READMEs before launching the client. For local dev, point the client at `localhost`.

---

## 3. Clone & build

```bash
git clone https://github.com/ruusey/openrealm-native-client.git
cd openrealm-native-client
mvn clean package
```

Output: `target/openrealm-native-client.jar` — a shaded fat jar containing all runtime deps.

---

## 4. Run from source

```bash
# Linux/macOS
./run-openrealm.sh                                          # local data service, prompts for login
./run-openrealm.sh openrealm.net                            # remote data service, prompts for login
./run-openrealm.sh openrealm.net user@example.com pw <char> # fully scripted launch

# Windows
run-openrealm.bat openrealm.net
```

The host argument points at the **data service**. The client pulls account/characters/content from there, then opens a TCP socket to the game server using config returned from the data service.

User settings (key bindings, audio, video) are persisted to `~/.openrealm/settings.json`.

---

## 5. Building a native installer

The POM ships per-OS profiles that wrap `jpackage`. Output lands in `target/installer/`.

### Windows — `OpenRealm-1.0.0.exe`

```bash
mvn -Pinstaller-windows package verify
```

Requires WiX 3.x on `PATH`. The installer registers a Start Menu entry and creates a desktop shortcut.

Icon source: `src/main/resources/icon_min.ico` (regenerate from `icon_min.png` with ImageMagick if needed):

```bash
magick icon_min.png -define icon:auto-resize=256,128,64,48,32,16 icon_min.ico
```

### macOS — `OpenRealm-1.0.0.dmg`

```bash
mvn -Pinstaller-mac package verify
```

Requires `src/main/resources/icon_min.icns`.

### Linux — `.deb` / `.rpm`

Add a similar profile if needed (the macOS profile is a good template — swap the icon to `.png` and drop `--mac-package-name`).

> **`jpackage` cannot cross-compile.** Build each installer on the matching OS (or matching CI runner — see §6).

---

## 6. CI release pipeline

`.github/workflows/release.yml` builds the Windows installer on `windows-latest` and uploads the `.exe` to a GitHub release.

**Trigger it by pushing a version tag:**

```bash
git tag v1.0.0
git push origin v1.0.0
```

The workflow:

1. Sets up Temurin JDK 17 (with `jpackage`).
2. Adds the preinstalled WiX 3 to `PATH`.
3. Runs `mvn -Pinstaller-windows package verify`.
4. Creates / updates the GitHub release for the tag and attaches `target/installer/*.exe`.

You can also dispatch the workflow manually from the Actions tab (`workflow_dispatch`) — it'll build against the default branch but skip the release-upload step.

---

## 7. Troubleshooting

| Symptom | Fix |
|---------|-----|
| `jpackage: command not found` | Using a JRE, not a JDK. Install JDK 17+ and re-check `JAVA_HOME`. |
| `Cannot find WiX tools (light.exe)` | WiX 3 not on `PATH`. Reinstall WiX 3.x (NOT 4 — `jpackage` only supports 3). |
| Client connects to data service but never reaches game server | Game server unreachable from the host the data service points at. Check the server config returned by `/api/game/config`. |
| `LinkageError` / native LWJGL crash on startup | Mismatched JDK arch (32- vs 64-bit) or running on an unsupported GPU. Confirm 64-bit JDK and updated graphics drivers. |
| Installer builds but app won't launch | `--main-class` mismatch. Confirm the shaded jar's manifest points at `com.openrealm.game.GameLauncher`. |

---

## 8. Repo layout

```
openrealm-native-client/
├── src/main/java/com/openrealm/    # Game code
│   ├── game/                       # Entities, state machines, UI, scripts
│   ├── net/                        # Packet protocol (mirrors openrealm server)
│   └── account/                    # Data-service client
├── src/main/resources/             # Sprites, fonts, icon, configs
├── dist/                           # Distributable launcher scripts
├── run-openrealm.sh / .bat         # Dev launcher scripts
├── pom.xml                         # Build + installer profiles
└── .github/workflows/release.yml   # CI installer + release pipeline
```
