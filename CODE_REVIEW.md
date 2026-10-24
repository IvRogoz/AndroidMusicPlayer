# AudioBookPlayer code review — 2026-10-24

## Local implementation follow-up

The review below records the **pre-patch** state. Each of its eight findings now has a corresponding source change in local build **1.0.10 (version code 11)**. This is implementation status, not device verification.

- **VERIFIED:** Debug APK compilation, all 15 JVM regression tests (0 failures/errors), and lint (0 errors, 59 warnings).
- **VERIFIED:** APK v2 signature and packaged version. The copied release APK matches the build output byte for byte.
- Output: `releases/AudioBookPlayer-v1.0.10-debug-sideload.apk` (8,827,601 bytes).
- SHA-256: `3d0a73641253ec1a3c31ba3983a0016af5160a7f59ad62a6e5fd1325dc5ec534`.
- **OBSERVED:** The new Android artwork-cache regression test compiles into the test APK. It was not executed.
- At the user's subsequent explicit request, the APK was installed as an update on the connected phone. **VERIFIED:** installed version 1.0.10 (code 11), with the selected audiobook, saved playback position, and playback preferences unchanged after installation. The Android artwork regression test and interactive runtime checks were not run.
- **Runtime verification: UNVERIFIED — NOT FINISHED.** Cover display and the other Android interaction flows still require execution on a device. Build/test success does not establish those outcomes.

## Original review

Scope: current workspace, prioritizing the intermittent cover display for **Overpowered Level 1 Mage**, then playback, restoration, and Android Auto. Application source was not changed. Existing staged and unstaged changes were preserved.

## Evidence and limits

- **VERIFIED:** A standalone probe on the connected phone read embedded artwork from the exact M4B three times using Android's `MediaMetadataRetriever`. Every read returned the same 469,884-byte JPEG; decoding succeeded at the sizes used by the app. The image is 2400 × 2400 and visibly identifies this book.
- **VERIFIED:** Those bytes match the app's existing persistent cover cache for that track: SHA-256 `3ec3934738af07f65d40164ba578060e3ba3f652fb9837d21e051971aa65dfec`.
- **OBSERVED:** The installed APK is version 1.0.9, but its DEX hashes differ from the workspace's existing APK. This report reviews current source; it does not assume every workspace change is installed.
- **OBSERVED:** At inspection the phone was dozing, and this app had no running process or active media session. Playback was not started or stopped for this review.
- **UNVERIFIED:** The intermittent disappearance has not been reproduced in the visible player, notification/lock screen, or car. The successful probe used the file path, so it does not verify the app's content-URI access or UI lifecycle. Earlier standalone content-URI probes encountered harness context/provider errors and were not treated as evidence of an application defect.

The findings below are **OBSERVED in source**. Their user-visible consequences are derived from the identified control flow, except for the separately verified Android Auto document-wrapper exception. They are not presented as completed device regression tests.

## Findings

### 1. [P1] External track changes leave artwork, bookmarks, and navigation attached to the previous track

Location: `app/src/main/java/com/example/audiobookplayer/MainActivity.kt:152–164`.

`onMetadataChanged()` updates only title and duration. It does not reconcile `METADATA_KEY_MEDIA_URI`, `currentFile`, `currentFileIndex`, selection, or artwork. The service supports changing tracks through media IDs and search independently of this activity. If a car/controller switches from A to B while the activity remains alive, its title and timeline can describe B while its cover still belongs to A. `saveBookmark()` uses `currentFile` with the service position, so it can also save B's timestamp against A's URI.

Suggested correction: reconcile the activity's selected file from service metadata and update artwork and navigation together before allowing bookmarks.

### 2. [P1] Pause and headphone-disconnect handling skip pausing a buffering player

Locations: `PlaybackService.kt:244–257`, `PlaybackService.kt:545–561`.

Both paths call `player.pause()` only if `player.isPlaying` is true. The installed Media3 implementation defines `isPlaying` as false while buffering, even when `playWhenReady` is true. During rebuffering after playback has been prepared, these handlers therefore leave playback requested, publish PAUSED, and remove the noisy receiver/focus handling. Audio can subsequently resume when buffering finishes despite the pause or headphone disconnect.

Suggested correction: clear playback intent unconditionally when a player exists; distinguish playback readiness from whether audio is currently flowing.

### 3. [P1] The main Play button becomes inaccessible after Stop or a loading failure

Location: `MainActivity.kt:1344–1350`; related paths: `PlaybackService.kt:260–267`, `MainActivity.kt:990–1003`.

`setPlaybackControlsEnabled(false)` disables Play together with seek controls. Stop releases the player, so the service no longer advertises SEEK_TO; `PlaybackReadiness` then makes the activity unprepared and disables Play. Loading failures similarly leave it unprepared. The retry/restart branch in `togglePlayback()` exists but cannot be reached through the disabled button. Selecting the file again or external controls can take another path, but the main Play control cannot recover.

Suggested correction: enable Play when a restorable track exists, independently of seek/readiness controls.

### 4. [P1] Android Auto cannot enumerate selected subfolders

Location: `PlaybackService.kt:308–314`; exception handling: `PlaybackService.kt:148–150`.

The root uses `DocumentFile.fromTreeUri()`, but nested folders use `fromSingleUri()`. Calling `listFiles()` on the latter throws `UnsupportedOperationException`; the browse worker catches it and returns an empty result. This affects books stored inside folders, including the reported book's location.

**VERIFIED, narrow reproduction:** invoking `listFiles()` on that wrapper from the installed APK's DocumentFile dependency on the phone produced `UnsupportedOperationException`. The cached dependency source independently contains the unconditional throw. This did not exercise Android Auto's rendered UI.

Suggested correction: retain the tree-backed document wrapper for nested folder browsing.

### 5. [P1] A rejected foreground-service start escapes the playback callback

Locations: `PlaybackService.kt:525–530`, `PlaybackService.kt:920–929`.

`startPlayback()` calls `ensureServiceStartedForPlayback()` without handling failure. That helper invokes `startForegroundService()`, which can throw when a start is disallowed. The catch around `startForeground()` at lines 828–833 handles a later, different operation and does not cover this call. In the asynchronous READY callback and `onPlay()` path, that exception can escape the main-thread callback. This contradicts the changelog's claim that denial is handled.

Suggested correction: handle the actual service-start refusal where it occurs and keep player/session state consistent with the chosen recovery. A refusal was not deliberately induced on the phone.

### 6. [P2] Recreation can discard the track needed to restore the cover

Location: `MainActivity.kt:471–479`; connection path: `MainActivity.kt:175–209`.

After recreation, `skipAutoRestoreTrack` is true. If the library scan finishes before the controller connects, `hasActiveServiceSession()` is false and the early return discards `lastTrack` without setting `pendingRestoreTrack`. The later connection callback has no pending track to resolve and does not independently restore selection/artwork from metadata. This ordering can leave a placeholder while the service retains its track.

Suggested correction: defer reconciliation until controller connection rather than dropping the candidate. This event ordering has not been reproduced on the visible activity.

### 7. [P2] A single failed artwork read leaves no recovery path on that selection

Locations: `MainActivity.kt:903–930`, `AudioArtLoader.kt:69–90`, `PlaybackService.kt:646–669`.

The activity clears the cover before its own read and leaves the placeholder on a null result. There is no retry, persistent-cache fallback, or adoption of service artwork when metadata arrives. The service independently reads artwork and likewise does not reuse the provider's persistent cached image. Consequently a read failure can leave one surface blank even though another surface, or the existing persistent cache, already has the cover. The shelf and activity also share a two-worker executor without cancellation of obsolete queued requests, so pending thumbnail work can delay a main-cover request.

Suggested correction: use a common per-track artwork result/cache, consume successful service updates, and provide bounded recovery for transient reads. The probe proves the book's cover is readable, not that a transient read failure caused the reported episodes.

### 8. [P2] Autoplay-next depends on the activity surviving

Locations: `PlaybackService.kt:446–453`, `MainActivity.kt:1370–1377`, `MainActivity.kt:418–423`.

The service ends the current item and sends a session event. Only the activity handles that event and chooses the next file. If the activity is destroyed while listening in the background, it unregisters the callback, and no service-side queue/completion handler advances playback even when autoplay is enabled.

Suggested correction: make the service own the queue and autoplay decision; let the activity reflect service state.

## Validation

- **VERIFIED:** `gradlew.bat :app:testDebugUnitTest --offline` completed; 9 tests, 0 failures, 0 errors. They cover time parsing, focus-policy booleans, and readiness flags, not artwork or these end-to-end flows.
- **VERIFIED:** `gradlew.bat :app:lintDebug --console=plain` completed with 0 errors and 59 warnings. Its first offline attempt lacked a cached lint dependency; the online retry completed.
- **VERIFIED:** Native artwork extraction/decoding and the document-wrapper exception, scoped as described above.
- No APK was installed and no application fix was authored. Temporary probe DEX files were removed from the phone.

**Cover diagnosis: UNVERIFIED — NOT FINISHED.** The file contains a decodable cover and the code has concrete restoration/recovery defects, but the exact trigger of the reported intermittent disappearance still needs a captured failing session on the affected display surfaces.
