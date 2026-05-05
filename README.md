# OpenRealm Native Client

### Native Java desktop client for [OpenRealm](http://openrealm.net/) — built with LibGDX.

<div>
    <img src="https://github.com/ruusey/openrealm/blob/main/banner.png" width="100%">
</div>

---

## About

This repository ships the **native Java desktop client** for OpenRealm — a real-time multiplayer bullet hell dungeon crawler. The client connects to a running OpenRealm game server (the `openrealm` repo) over a binary TCP/WebSocket protocol and to a data service (the `openrealm-data` repo) over HTTP for account, character, and game-content APIs.

The browser-based PixiJS client (in `openrealm-data`) is the canonical reference for what the game looks like and which features are available; this client is being brought up to feature parity with it.

### Companion repositories

| Repo | Role |
|------|------|
| **openrealm-native-client** (this repo) | Native Java desktop client (LibGDX) |
| **[openrealm](https://github.com/ruusey/openrealm)** | Game server (Java, TCP + WebSocket) |
| **[openrealm-data](https://github.com/ruusey/openrealm-data)** | Data service + browser web client (Spring Boot, MongoDB, PixiJS) |

The three projects are deliberately kept as separate Maven artifacts. Shared protocol classes (`net.*`, `account.dto.*`, `game.contants.*`, `game.math.*`) are duplicated across the server and native client repos rather than shared via a library, so each can be released and maintained independently. When the wire protocol changes on the server, copy the affected classes from `openrealm/` into this repo.

### Key features

- 13 character classes with class-specific sprites and abilities
- Real-time multiplayer with client-side prediction and server reconciliation
- Tiered loot system, soulbound bag drops, and account-wide vault storage
- Player-to-player trading with confirmation UI
- Pixel-painting forge for item enchantment
- Cosmetic dye system (account fame currency)
- Procedural overworld and dungeons fed by the data service

---

## Getting started

### Prerequisites

- Java JDK 17+
- Apache Maven 3.8.3+
- A running `openrealm-data` instance (provides REST APIs and game content)
- A running `openrealm` game server (handles real-time gameplay)

### Build

```bash
mvn clean package
```

Produces `target/openrealm-native-client.jar` — a single fat jar containing all dependencies.

### Run

```bash
# Prompt for login, connect to a local data service
./run-openrealm.sh

# Connect to a remote data service
./run-openrealm.sh openrealm.net

# Skip the login prompt entirely (useful for repeated test runs)
./run-openrealm.sh openrealm.net player@example.com mypassword 146cdcbd-4266-4148-baef-4381eb22f4ad
```

On Windows, use `run-openrealm.bat` with the same arguments.

The host argument points at the **data service**. The client fetches the account, characters, and game content from there, then opens a TCP socket to the game server (host derived from the data service config).

---

## Controls

| Key | Action |
|-----|--------|
| **W/A/S/D** | Move Up/Left/Down/Right |
| **Left click** | Shoot |
| **Right click** | Use ability |
| **Z** | Drink HP potion |
| **X** | Drink MP potion |
| **F / Space** | Use nearest portal / pick up loot |
| **Q / E** | Rotate camera |
| **C** | Reset camera north |
| **Enter** | Open chat / send message |
| **Escape** | Open in-game menu |

Keybinds are remappable in the in-game options menu and persisted to `~/.openrealm/settings.json`.

---

## Architecture

This client is a thin presentation layer over a thick game server. All authoritative game state — combat resolution, inventory mutations, trade execution, forge enchantment validation, fame purchases — lives on the server. The client predicts movement and own-projectile firing locally for responsiveness, and reconciles against authoritative server snapshots delivered at 64 Hz.

The packet/serialization layer (`com.openrealm.net.core`) uses reflection-based binary encoding driven by `@SerializableField` annotations. Packet definitions in `net/client/packet` (server → client) and `net/server/packet` (client → server) match the server byte-for-byte. The `RealmManagerClient` glues incoming packets to game state mutations.

LibGDX handles rendering, input, audio, and the main loop. The Lwjgl3 backend is used (desktop-only); there is no Android/iOS target.

---

## License

Copyright (c) 2024-2026 Robert Usey. All rights reserved.

This software and associated documentation files (the "Software") are the exclusive property of Robert Usey. No part of this Software may be copied, modified, merged, published, distributed, sublicensed, sold, or otherwise made available to any third party, in whole or in part, in any form or by any means, without the prior express written permission of the copyright holder.

For licensing inquiries, contact: **ruusey@gmail.com**
