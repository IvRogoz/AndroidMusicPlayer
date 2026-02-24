## Changelog

### v1.0.2 - 2026-02-24

- Added sideload artifact: `releases/AudioBookPlayer-v1.0.2-debug-sideload.apk`.
- Fixed playback progress persistence by saving position periodically during playback.
- Fixed resume reliability after app/service shutdown by persisting position on lifecycle exit.
- Improved last-track restore with URI, filename, and title fallback matching.
- Added explicit resume error reporting when Bluetooth/car resume cannot reopen the last track.
- Updated README with features and latest update notes.

### v1.0.1 - 2026-02-24

- Added bookmark playback and bookmark tree improvements.
- Added/updated media playback service integration and playback preferences.
- Added README app overview and top screenshot.
