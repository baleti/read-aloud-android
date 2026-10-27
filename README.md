# Read Aloud

Reads shared content aloud. Pick "Read Aloud" in Android's Share sheet (or "Open with") for text,
a link (fetched and reduced to its article text), a PDF, DOCX or PPTX; a Reddit thread link is
read from its `.rss` feed. Audio comes from the shared TTS server, with a local fallback.
Built with Gradle.

**Removed 2026-10-27:** the accessibility-service "read whatever is on screen" feature (per-app
profiles for Gmail, Outlook, WhatsApp, Reddit, browsers, the scroll reader, the highlight overlay
and the Digital Assistant trigger) never worked reliably. It lives in git history (the commit
before "Remove screen reading"); the docs below describing it are historical.

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
4. In digital-assistant-android's own action menu (long-press-power, or however
   your device's Digital Assistant gesture is bound), tap "Read Aloud" -
   or, for Reddit specifically, tap Share on a post/comment and pick
   "Read Aloud" from the share sheet instead.

Defaults to the same `newsdigest-server` instance (`10.10.0.2:8792`,
WireGuard-only) News Digest and claude-agents already use for TTS - no
separate server, no setup needed if that's already running on your
network. Change it from the app's own settings screen if yours differs.
