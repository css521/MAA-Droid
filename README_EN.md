<div align="center">
<img alt="LOGO" src="/docs/en-us/develop/Logo.png" width="256" height="256" />

# MAA Droid

**Run game automation tasks on Android**

The app integrates Arknights MaaCore and a Limbus Company engine derived from LALC. Screen capture and input use Shizuku or Root.

[![GitHub Release](https://img.shields.io/github/v/release/Aliothmoon/MAA-Meow?style=flat-square&label=Latest)](https://github.com/Aliothmoon/MAA-Meow/releases/latest)
[![License](https://img.shields.io/github/license/Aliothmoon/MAA-Meow?style=flat-square)](LICENSE)
[![GitHub Stars](https://img.shields.io/github/stars/Aliothmoon/MAA-Meow?style=flat-square)](https://github.com/Aliothmoon/MAA-Meow)
[![GitHub Downloads](https://img.shields.io/github/downloads/Aliothmoon/MAA-Meow/total?style=flat-square&label=Downloads)](https://github.com/Aliothmoon/MAA-Meow/releases)

[Download](https://github.com/Aliothmoon/MAA-Meow/releases/latest) · [FAQ](https://docs.maameow.com/faq/getting-started/) · [Issues](https://github.com/Aliothmoon/MAA-Meow/issues) · [QQ Group](https://join.maameow.com/)

**English** | **[中文](README.md)**

</div>

---

> An authorized Shizuku service can be used without Root. The Limbus engine is under development. Generic engine sessions currently use a background virtual display; foreground mode remains part of the existing Arknights path.

## Games

| Game | Module | Upstream | Status |
|---|---|---|---|
| Arknights | `:engine:arknights` | [MaaAssistantArknights](https://github.com/MaaAssistantArknights/MaaAssistantArknights) | Existing task and UI path |
| Limbus Company | `:engine:limbus` | [LixAssistantLimbusCompany](https://github.com/HSLix/LixAssistantLimbusCompany) | In development |

Arknights keeps its existing MaaResource updater. Limbus downloads task definitions, language tables, images and models from a pinned LALC source archive. Its UI catalog and artwork use those installed files; bulk PNGs and a catalog snapshot are no longer bundled in the APK. Missing resources show a download prompt while saved teams, weights and preferences remain intact.

Resource updates can change data supported by the current engine. New native logic, action types or incompatible protocols still require an app update. Limbus validates an update before activation and retains the previous installation on failure.

Adding a game requires an engine module, contracts, host registration and lifecycle checks. Arknights core bindings and `MaaCoreSession` have moved into its module, but business orchestration and UI still use host-owned legacy paths. Its complete generic provider is not yet implemented. See the [architecture](docs/zh-cn/develop/ARCHITECTURE.md) and [adding a game guide](docs/zh-cn/develop/ADDING_A_GAME.md) (Chinese).

<p align="center">
  <img src="docs/zh-cn/manual/screenshots/home.png" width="200" />
  <img src="docs/zh-cn/manual/screenshots/background_task.png" width="200" />
  <img src="docs/zh-cn/manual/screenshots/schedule.png" width="200" />
  <img src="docs/zh-cn/manual/screenshots/auto_controls.png" width="200" />
</p>

## Features

|  | Feature | Description |
|---|---|---|
| 🧠 | **Native execution** | Run automation on Android without a PC or emulator |
| 🎮 | **Game selection** | Switch games in the background task page, with separate task configuration |
| 🪟 | **Dual Mode** | Arknights supports foreground and background modes; generic engines currently use background mode |
| 📦 | **Arknights tasks** | Sanity battles, recruitment, infrastructure, copilot, roguelike, and more |
| ⏱️ | **Scheduled Tasks** | Existing Arknights scheduling; new engines need their scheduling entry points wired and verified |
| 🔄 | **Auto Update** | Existing app/Arknights update paths; Limbus offers resource download and update controls in its task page |

## Requirements

| Item | Requirement |
|---|---|
| OS | Android 9+ (API 28) |
| Permissions | [Shizuku](https://shizuku.rikka.app/) running & authorized, or rooted device |
| Architecture | arm64-v8a or x86_64 |

## Documentation

| Document | Description |
|---|---|
| [Architecture (Chinese)](docs/zh-cn/develop/ARCHITECTURE.md) | Current module boundaries, lifecycle and migration limits |
| [Adding a game (Chinese)](docs/zh-cn/develop/ADDING_A_GAME.md) | Contracts, host wiring, task UI, resources and verification |
| [Build Guide](docs/en-us/develop/BUILDING.md) | Build APK from source |
| [External Automation](docs/en-us/develop/AUTOMATION.md) | Launch profiles via Intent / am with MacroDroid or Tasker |
| [Roadmap](docs/en-us/develop/ROADMAP.md) | Feature plans & progress |
| [PR Guidelines](docs/en-us/develop/PULL_REQUEST_GUIDELINES.md) | Pull request title, description, verification, and review conventions |
| [Third-Party Notices (current inventory, Chinese)](docs/zh-cn/develop/THIRD_PARTY_NOTICES.md) | Open-source components & licenses |

## Contributing

Pull requests are welcome! Whether it's a bug fix, UX improvement, or a new feature — we appreciate every contribution.

1. Fork this repository
2. Create your branch (`git checkout -b feat/your-feature`)
3. Commit your changes (`git commit -m 'feat: add some feature'`)
4. Push to remote (`git push origin feat/your-feature`)
5. Open a Pull Request

> Please follow the [Conventional Commits](https://www.conventionalcommits.org/) specification (`feat:`, `fix:`, `docs:`, etc.).
> See the [Build Guide](docs/en-us/develop/BUILDING.md) for first-time setup.
> Read the [PR Guidelines](docs/en-us/develop/PULL_REQUEST_GUIDELINES.md) before opening a pull request.

If you find this project useful, consider giving it a Star ⭐ to help others discover it!

## Relationship to upstream projects

- This is an independent project, not an official Android client of MaaAssistantArknights. Arknights uses its prebuilt `libMaaCore.so` through JNA. Please report integration issues in this repository first.
- The Limbus engine ports LALC action and configuration semantics to Kotlin and uses its resource files. Both upstream projects publish under AGPL v3; third-party libraries retain their own licenses.
- AALC is a documented design reference, not the source of the Limbus execution resource pack.
- Game images, icons and trademarks remain subject to their owners' rights; a code license does not grant a separate license to those assets.

## Acknowledgements

- [MaaAssistantArknights](https://github.com/MaaAssistantArknights/MaaAssistantArknights) — Arknights assistant based on image recognition
- [LixAssistantLimbusCompany](https://github.com/HSLix/LixAssistantLimbusCompany) — source of the Limbus engine port and resources
- [AhabAssistantLimbusCompany](https://github.com/KIYI671/AhabAssistantLimbusCompany) — resource manifest and Android input design references
- [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy) — Display and control your Android device.
- [Shizuku](https://github.com/RikkaApps/Shizuku) — Using system APIs directly with adb/root privileges from normal apps through a Java process started with app_process.

## License

This project is licensed under [AGPL-3.0](LICENSE). Third-party code retains its original license — see [Third-Party Notices (current inventory, Chinese)](docs/zh-cn/develop/THIRD_PARTY_NOTICES.md).
