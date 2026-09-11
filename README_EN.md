<div align="center">

# MAA Droid

**Run game automation tasks on Android**

Integrates Arknights MaaCore and a Limbus Company engine based on LALC. Frame capture and input injection via Shizuku or Root.

[![License](https://img.shields.io/github/license/css521/MAA-Droid?style=flat-square)](LICENSE)

**English** | **[中文](README.md)**

</div>

---

> Works with authorized Shizuku — no Root required. The Limbus engine is still in development.

## Supported Games

| Game | Engine | Upstream | Status |
|---|---|---|---|
| Arknights | `:engine:arknights` | [MaaAssistantArknights](https://github.com/MaaAssistantArknights/MaaAssistantArknights) | Available |
| Limbus Company | `:engine:limbus` | [LixAssistantLimbusCompany](https://github.com/HSLix/LixAssistantLimbusCompany) | In Development |

## Features

|  | Feature | Description |
|---|---|---|
| 🧠 | **Native Execution** | Runs automation directly on Android — no PC or emulator needed |
| 🎮 | **Multi-Game** | Switch between games in the background task panel |
| 🪟 | **Background Mode** | Runs via virtual display without interrupting phone usage |
| 📦 | **Arknights Tasks** | Sanity battles, recruitment, infrastructure, copilot, auto-roguelike |
| 🎲 | **Limbus Tasks** | EXP/Thread luxcavation, Mirror Dungeon automation, mail collection |
| ⏱️ | **Scheduled Tasks** | Auto-start tasks at preset times |
| 🔄 | **Auto Update** | App and resources update independently; resource hot-updates don't require reinstall |

## Requirements

| Item | Requirement |
|---|---|
| Android | 9+ (API 28) |
| Permissions | [Shizuku](https://shizuku.rikka.app/) running and authorized, or Root |
| Architecture | arm64-v8a or x86_64 |

## Upstream Relationship

- **Not an official Android client of MaaAssistantArknights** — drives Arknights via JNA-loaded `libMaaCore.so`
- **Limbus engine ported from LixAssistantLimbusCompany** — an AGPL-3.0 derivative work
- Both upstream projects are AGPL-3.0; this project uses the same license

## Acknowledgements

- [MaaAssistantArknights](https://github.com/MaaAssistantArknights/MaaAssistantArknights)
- [LixAssistantLimbusCompany](https://github.com/HSLix/LixAssistantLimbusCompany)
- [AhabAssistantLimbusCompany](https://github.com/KIYI671/AhabAssistantLimbusCompany)
- [Shizuku](https://github.com/RikkaApps/Shizuku)

## License

[AGPL-3.0](LICENSE)
