## Changelog

### v1.0.5 - 2026-02-25

- Added sideload artifact: `releases/AudioBookPlayer-v1.0.5-debug-sideload.apk`.
- Added audio focus request/abandon handling to pause cleanly on external audio changes.
- Added `ACTION_AUDIO_BECOMING_NOISY` handling to pause when output becomes noisy (for example headphone disconnect).
- Improved playback service lifecycle cleanup for focus and receiver state across pause/stop/error/completion.
- Updated README latest update and sideload APK reference.

### v1.0.4 - 2026-02-25

- Added sideload artifact: `releases/AudioBookPlayer-v1.0.4-debug-sideload.apk`.
- Fixed waveform scrub behavior so drawer interactions cannot change playback position.
- Updated scrub touch handling to seek only on `ACTION_UP` and ignore `ACTION_CANCEL` seeks.
- Updated README latest update and sideload APK reference.

### v1.0.3 - 2026-02-24

- Added sideload artifact: `releases/AudioBookPlayer-v1.0.3-debug-sideload.apk`.
- Fixed playback stop behavior to preserve current position instead of resetting to start.
- Fixed resume after seek + app close by restoring explicit saved position on startup/play actions.
- Updated README latest update and sideload APK reference.

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
