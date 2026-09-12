# Builds

Newest first. Older builds are kept deliberately: each new feature reaches these
before it has been proven on real hardware, so there is always a previous build
to fall back to when the newest one misbehaves.

| APK | Date | What it is |
| --- | --- | --- |
| `meshlink-2026-09-12-voice-calls.apk` | 2026-09-12 | Everything below, plus voice calling. **The calls themselves have never run across two devices** — the code builds and the unit tests pass, but no audio has been heard. Use the build below if you need the messaging to be dependable. |
| `meshlink-2026-09-12.apk` | 2026-09-12 | Messaging, tested. Multi-hop relaying, store-and-forward custody, attachments, stickers, contact cards, group chat, blocking and the emergency allowance. No calling. This is the fallback. |
| `meshlink-aug25.apk` | 2026-08-25 | Earlier snapshot from before the UI rework. |

All are debug-signed, so they install alongside nothing else and can be
side-loaded directly with `adb install -r <file>`. Installing one over another
keeps the message database; the schema migrates forward and is not downgradable,
so going *back* to an older build after running a newer one needs the app's data
cleared first.
