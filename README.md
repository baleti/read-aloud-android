# Read Aloud

Reads whatever app is currently on screen, aloud - invoked from
[dictate-android](https://github.com/baleti/dictate-android)'s long-press-power
action menu (the "Complete action using" overlay Android shows for its
Digital Assistant app slot), not from an icon you tap. Built with Gradle
(the only app in this family that needs it - see below); everything else
here is zero-dependency, same as the sibling repos.

A generic engine reads any app's accessibility tree in on-screen order;
a small per-app profile registry (`AppProfile.kt`) overrides that for
apps that need real work - Gmail's open-email footer noise, Reddit's
opaque Compose UI. Add a profile object, register it, nothing else
changes. Full design rationale, the real bugs found getting this working
at all, and per-app findings are in [docs/design.md](docs/design.md).

## Status (2026-09-13)

- **Generic fallback** and **Gmail** - working, confirmed live end to
  end (long-press-power -> tap "Read Aloud" -> reads the open screen ->
  plays over the existing `newsdigest-server` TTS pipeline). Gmail's open
  emails now announce "Subject: ..." / "From: ..." explicitly rather than
  just reading the raw text.
- **Reddit** - two independent working paths. Sharing a specific post
  (Reddit's own Share button -> "Read Aloud") fetches its `.rss` feed and
  reads real comment text with author attribution (structured, but capped
  at ~10 flat comments, no reply nesting). Invoking Read Aloud via the
  corner-swipe gesture while looking at a thread falls through to
  on-device OCR (`play-services-mlkit-text-recognition` - confirmed by
  reading TalkBack's own open-source code that this is the exact library
  and mechanism it uses for its own equivalent case) and reads whatever's
  genuinely on screen - confirmed live, 1969 characters recognized off a
  real opaque post screen and read aloud correctly.
- **Outlook** - written, **completely untested** (not installed on the
  phone this was built against).

## Why this one app needs Gradle

Every sibling app here is intentionally dependency-free with a hand-rolled
`aapt2`/`kotlinc`/`d8` `build.sh`. This one broke that convention on
purpose: `play-services-mlkit-text-recognition`'s real transitive
dependency graph turned out to be Firebase + AndroidX, 15-25+ AARs once
fully resolved - genuine multi-package dependency resolution and manifest/
resource merging, exactly what Gradle's Android plugin exists to automate
and a hand-rolled pipeline has no equivalent for. See docs/design.md's
"Build system: Gradle" section for the full migration (SDK license
bootstrapping, JDK version requirement, flat layout preserved rather than
restructured).

## Setup

1. `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug`
   (JDK 17 specifically - this host's default JDK 11 is too old for the
   Android Gradle Plugin version this uses).
2. `adb install -r build/outputs/apk/debug/read-aloud-android-debug.apk`
3. Settings -> Accessibility -> enable "Read Aloud".
4. In dictate-android's own action menu (long-press-power, or however
   your device's Digital Assistant gesture is bound), tap "Read Aloud" -
   or, for Reddit specifically, tap Share on a post/comment and pick
   "Read Aloud" from the share sheet instead.

Defaults to the same `newsdigest-server` instance (`10.10.0.2:8792`,
WireGuard-only) News Digest and claude-agents already use for TTS - no
separate server, no setup needed if that's already running on your
network. Change it from the app's own settings screen if yours differs.
