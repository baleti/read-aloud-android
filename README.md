# Read Aloud

Reads whatever app is currently on screen, aloud - invoked from
[dictate-android](https://github.com/baleti/dictate-android)'s long-press-power
action menu (the "Complete action using" overlay Android shows for its
Digital Assistant app slot), not from an icon you tap. No Gradle, no Play
Services, no third-party dependencies; built with the plain Android SDK
command-line tools, same as the sibling repos.

A generic engine reads any app's accessibility tree in on-screen order;
a small per-app profile registry (`AppProfile.kt`) overrides that for
apps that need real work - Gmail's open-email footer noise, Reddit's
lazy/collapsed comment tree. Add a profile object, register it, nothing
else changes. Full design rationale, the real bugs found getting this
working at all, and per-app findings are in
[docs/design.md](docs/design.md).

## Status (2026-09-12, MVP)

- **Generic fallback** and **Gmail** - working, confirmed live end to
  end (long-press-power -> tap "Read Aloud" -> reads the open screen ->
  plays over the existing `newsdigest-server` TTS pipeline).
- **Reddit** - split into two paths. Sharing a specific post (Reddit's
  own Share button -> "Read Aloud") works today: fetches the post's
  `.rss` feed and reads real comment text with author attribution
  (confirmed live). Invoking Read Aloud via the corner-swipe gesture
  while just looking at a thread still doesn't work - Reddit's Compose UI
  is invisible to the standard accessibility tree entirely (confirmed via
  TalkBack's own open-source code: it OCRs a screenshot for exactly this
  case). Same fix planned here - `play-services-mlkit-text-recognition`,
  the identical library TalkBack uses - not yet integrated because it
  pulls in a real multi-AAR dependency chain this project's no-Gradle
  build can't resolve automatically. See docs/design.md.
- **Outlook** - written, **completely untested** (not installed on the
  phone this was built against).

## Setup

1. `bash build.sh` (needs the Android SDK command-line tools and
   `kotlinc` - see the script's own comments for the exact env vars if
   your layout differs from the default).
2. `adb install -r build/readaloud-signed.apk`
3. Settings -> Accessibility -> enable "Read Aloud".
4. In dictate-android's own action menu (long-press-power, or however
   your device's Digital Assistant gesture is bound), tap "Read Aloud".

Defaults to the same `newsdigest-server` instance (`10.10.0.2:8792`,
WireGuard-only) News Digest and claude-agents already use for TTS - no
separate server, no setup needed if that's already running on your
network. Change it from the app's own settings screen if yours differs.
