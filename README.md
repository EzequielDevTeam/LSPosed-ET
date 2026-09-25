<div align="center">

# LSPosed ET

**An ART-level hook framework providing a complete Xposed implementation for modern Android**

[![Release](https://img.shields.io/github/v/release/EzequielDevTeam/LSPosed-ET?style=for-the-badge&logo=git&label=Release)](https://github.com/EzequielDevTeam/LSPosed-ET/releases)
[![CI](https://img.shields.io/github/actions/workflow/status/EzequielDevTeam/LSPosed-ET/core.yml?branch=master&style=for-the-badge&label=Build)](https://github.com/EzequielDevTeam/LSPosed-ET/actions/workflows/core.yml)
[![Xposed API](https://img.shields.io/badge/Xposed_API-102-blue?style=for-the-badge)](https://github.com/libxposed/api)
[![Min Android](https://img.shields.io/badge/Android_8.1+-27+-34A853?style=for-the-badge&logo=android&logoColor=white)](#supported-versions)
[![Target Android](https://img.shields.io/badge/Android_17_(API_37)-Supported-brightgreen?style=for-the-badge&logo=android&logoColor=white)](#supported-versions)
[![License](https://img.shields.io/badge/License-GPL--3.0-red?style=for-the-badge)](LICENSE)
[![Platform](https://img.shields.io/badge/Loader-Zygisk_|_Riru-black?style=for-the-badge&logo=magisk)](https://github.com/topjohnwu/Magisk)

</div>

---

## Introduction

**LSPosed ET** is a maintained fork of [LSPosed](https://github.com/LSPosed/LSPosed) by the **EzequielDevTeam Technology**, focused on keeping the framework fully functional on the newest Android releases — including **Android 17 (API 37)**, which the upstream project no longer officially supports.

LSPosed itself is an evolution of the classic [Xposed Framework](https://github.com/rovo89/Xposed) by rovo89: a module system that allows small apps ("modules") to alter the behavior of the operating system and of other applications **without modifying any APK file**. Instead of decompiling and repacking apps, modules inject logic into running processes through method hooking at the ART virtual machine level.

Where the original Xposed required installing a modified `app_process` directly into `/system` (breaking OTA updates and requiring full system modification), LSPosed achieves the same result entirely from userspace through [Magisk](https://github.com/topjohnwu/Magisk)'s systemless infrastructure, using either the **Zygisk** or **Riru** injection mechanism.

---

## How it works

The boot sequence performs the following steps, typically within the first seconds after power-on:

1. **Magisk** starts and injects its Zygisk companion library (`libzygisk.so`) into **Zygote**, the parent process from which every Android application is forked.
2. Zygisk loads the **`zygisk_lsposed`** module into the Zygote address space.
3. The module spawns the **daemon (`lspd`)**, which runs as the `system` user with elevated privileges and stays alive for the entire uptime.
4. The daemon applies its own **SELinux policy rules** (`sepolicy.rule`) so that regular applications are allowed to communicate with it over Binder IPC.
5. From this point on, **every newly forked application process inherits the injected runtime**: when a module declares scope over an app, that specific process gets the module's code loaded before the application's own classes run.

Hooking itself is delegated to **[LSPlant](https://github.com/LSPosed/LSPlant)**, an ART-native engine that rewrites method entry points at runtime (trampoline-based inline hooking backed by a JIT-aware code cache strategy). When a module registers a callback on, say, `WindowManager.LayoutParams.flags`, every call made by the hooked process executes the module's Java code first — effectively "replacing circuit boards while the machine keeps running".

### Components

| Component | Role |
|-----------|------|
| `zygisk_lsposed` / `riru_lsposed` | Magisk module: entry point injected during boot, hands off control to the daemon |
| Daemon (`lspd`) | The brain: manages installed modules, scopes, per-app resource redirection, applies hooks, publishes the manager binder service |
| `manager.apk` | User interface: lists modules, toggles them, configures scopes, shows framework status and logs |
| libxposed **API 102.0.0** | Stable ABI consumed by modules, compiled against `io.github.libxposed:api:102.0.0` |

---

## Features

- **Systemless**: nothing outside the Magisk module directory is modified
- **Per-app scoping**: modules only run inside processes they explicitly target — zero overhead everywhere else
- **Modern API surface**: implements the next-generation [libxposed API](https://github.com/libxposed/api) alongside legacy compatibility paths
- **Resource hooks**: modules can replace strings, layouts and drawables of target apps
- **Detection resistance**: integrates with the Magisk denylist so hooked apps cannot trivially observe the framework
- **Self-updating**: the module ships an `update.json` consumed by the Magisk app for seamless upgrades

---

## Supported versions

| Android version | Status |
|-----------------|--------|
| 8.1 – 13 (API 27 – 34) | ✅ Supported |
| 14 – 15 (API 34 – 35) | ✅ Supported |
| **16 (API 36)** | ✅ **Fully supported** (upgraded LSPlant v6.4 + `IUserManager.getUsers` fix for API 36) |
| **17 (API 37)** | ✅ **Fully supported** (v2.0.0 — targetSdk 37 + libxposed API 102.0.0) |

---

## Installation

1. Ensure **Magisk 24.0+** is installed with **Zygisk enabled**
2. Flash the appropriate zip from the [Releases](https://github.com/EzequielDevTeam/LSPosed-ET/releases) page:
   - `LSPosed-*-zygisk-release.zip` for Magisk with Zygisk (recommended)
   - `LSPosed-*-riru-release.zip` for setups still using Riru
3. Reboot
4. Install the bundled `manager.apk` (manually or through the notification shortcut)
5. Install modules, enable them, assign scopes, force-stop target apps

---

## Differences from upstream

| Area | This fork |
|------|-----------|
| Android 16 | Works out of the box (LSPlant v6.4, daemon fixes) |
| Android 17 | Works out of the box (v2.0.0 — compileSdk/targetSdk 37, build-tools 37.0.0) |
| Identity | `LSPosed ET` / EzequielDevTeam Technology |
| Updater | Points to this repository's `update.json` |
| CI | Self-hosted GitHub Actions pipeline producing signed artifacts |
| libxposed API | `api:102.0.0` + `interface:102.0.0` from Maven Central |

---

## Credits

This project stands on the shoulders of giants. All credit for the original engineering belongs to:

- **[LSPosed Contributors](https://github.com/LSPosed)** — the original framework: *Mygod*, *vvbbnn00*, *teble*, *keta1*, *LoveSy*, *yujincheng08*, *canyie* and many others
- **[rovo89](https://github.com/rovo89/Xposed)** — creator of the original Xposed Framework, the foundation of everything above
- **[topjohnwu](https://github.com/topjohnwu)** — creator of [Magisk](https://github.com/topjohnwu/Magisk) and Zygisk
- **[RikkaApps](https://github.com/RikkaApps/Riru)** — the Riru injection mechanism
- **[LSPosed/LSPlant](https://github.com/LSPosed/LSPlant)** and **[DexBuilder](https://github.com/LSPosed/DexBuilder)** — the ART hooking engine and DEX generation toolkit
- The countless module developers who kept the ecosystem alive

---

## License

LSPosed ET is licensed under the **GNU General Public License v3.0**, as the original project.

> **Disclaimer:** using hook frameworks may violate terms of service of some applications. Use responsibly and at your own risk.
