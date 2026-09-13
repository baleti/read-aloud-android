# Read Aloud - design notes (2026-09-12)

## The idea

A "digital assistant" style action - invoked from dictate-android's
long-press-power action menu, which already holds this device's Digital
Assistant app slot - that reads aloud whatever app is currently on
screen. Two layers, deliberately modeled on Firejail's per-app sandbox
profiles and Full-Text-RSS's per-site scraping configs: a generic engine
that does something reasonable for any app via the accessibility tree,
plus a small registry of per-app profile overrides for apps that need
real work (`AppProfile.kt` / `AppProfileRegistry`). Add a profile object,
register it, nothing else changes.

## Why a separate app/repo, and how it talks to dictate-android

dictate-android already owns Android's "Digital assistant app" slot via
`AssistActivity`'s `ACTION_ASSIST` intent-filter - only one app can hold
that slot, so Read Aloud can't register its own. Its `actions` list is
explicitly built to be extended ("Add a new entry to `actions` below for
anything else that should hang off this same long-press-power slot
later" - already in that file's own doc before this project started).

So: dictate-android gained one new `MenuItem` ("Read Aloud") that sends an
explicit broadcast to this app; this app does the actual reading. This
needed two real bugs found live before it worked at all:

1. **Implicit broadcast never arrives.** `sendBroadcast(Intent(ACTION))`
   with no target package does not reach a manifest-declared
   `<receiver>` in another app on Android 8+ (implicit-broadcast
   background restrictions). Fixed by `.setPackage("dev.local.readaloud")`
   on the Intent dictate-android sends.
2. **`startForegroundService()` from a `BroadcastReceiver` gets silently
   blocked.** Confirmed live: a `ReadAloudService` component started this
   way from `TriggerReceiver.onReceive()` got a `ServiceRecord` that never
   attached a process (`app=null`, `startForegroundCount=0`, for minutes) -
   Android's foreground-service background-start restriction, with no
   crash or log to point at it. Fixed by removing that separate service
   entirely: `ReadAloudAccessibilityService` is already alive and
   system-bound the whole time it's enabled (same as TalkBack, same as
   DictateAccessibilityService), so `TriggerReceiver` just calls a plain
   Kotlin method on it directly (`startReading()`) - no new component
   startup needed. Only the final handoff to `TtsPlaybackService` (real
   foreground service, for legitimate continuous media playback) still
   calls `startForegroundService()`, and confirmed live that succeeds when
   called from this already-running process (`uidState: BFGS`).

## The accessibility service

`ReadAloudAccessibilityService` requests `canRetrieveWindowContent`,
`canPerformGestures`, `canRequestFilterKeyEvents`, `canTakeScreenshot`,
and `canRequestTouchExplorationMode` - but does NOT request touch
exploration mode by default the way TalkBack does. That's deliberate:
enabling it is what turns every tap system-wide into "focus and
describe, double-tap to activate" for as long as the service runs -
confirmed live to be genuinely disruptive when tested by hand, and
explicitly rejected for this project ("I only need it to read current
screen when I ask... not this different style of navigation"). Instead
`AppProfile.needsTouchExploration` lets a specific profile (RedditProfile)
ask for it toggled on only for the duration of its own `extract()` call,
via `setServiceInfo()` at runtime - a real, narrowly-scoped tradeoff, not
a permanent mode switch.

## Per-app findings

**Gmail** (`com.google.android.gm`) - fully solved. The inbox list needs
no special profile at all: each row's `contentDescription` is already
the complete "Unread, Sender, Subject, Snippet" string, which
`AccessibilityTree.collectText()`'s generic rule (a labeled node is one
atomic announcement, don't also descend into its children) picks up
whole. `GmailProfile` exists only for an *open* email's body - a WebView
bridged into the tree as a flat sequence of per-line nodes with no
structure, where a sender's own marketing footer (social icons, legal
text) lands in that same sequence right after the real signature with no
boundary marker. `GmailProfile.STOP_MARKERS` is a known-imperfect
heuristic for that, not a real boundary detector - refine it here as new
false negatives turn up, same as a Firejail profile gets tightened over
time.

**Reddit** (`com.reddit.frontpage`) - two separate paths now, because it
needed both an explanation and a workaround.

*Why the accessibility tree is really empty, confirmed not guessed:* its
Compose content lives entirely behind an
`androidx.compose.ui.viewinterop.ViewFactoryHolder` node reporting
`childCount=0`. Every equivalent an app can use was tried against a real
open thread and every one came back completely empty: a `dispatchGesture()`
tap at real coordinates, toggling system touch-exploration mode on via
`setServiceInfo()`, `flagIncludeNotImportantViews` added to the service
config, and a direct `ACTION_ACCESSIBILITY_FOCUS` call on the deepest
node. The user pushed back hard here (correctly) - TalkBack really did
read a tapped paragraph aloud, so the content is clearly reachable
*somehow*. Resolved by reading TalkBack's own source (`google/talkback`,
Apache-2.0, actively maintained): it has a dedicated
`CaptionNodeType.UNLABELLED_VIEW` case in `ImageCaptioner.java` for
exactly this situation - a focused node with no real accessible text -
which crops a screenshot of that node's bounds and runs it through
`OcrController.java`, a ~443-line wrapper around
`com.google.android.gms:play-services-mlkit-text-recognition`. TalkBack
wasn't reading the accessibility tree at all for that content; it was
silently doing OCR on a screenshot and speaking the result. That fully
reconciles every measurement above - it doesn't mean this project is
missing an accessibility capability, it means Reddit's content genuinely
isn't there and OCR is the documented, Google-sanctioned answer even
inside Android's own tooling.

**Decision: use the same library (`play-services-mlkit-text-recognition`),
not TalkBack's code and not a host3 endpoint.** It's Apache-2.0 (copying
is fine) but there's barely anything to copy - `OcrController.java` is a
thin wrapper around a public SDK any app can depend on directly, so using
it ourselves is the same amount of work as adapting TalkBack's version.
"Orchestrating" the real TalkBack app instead (e.g. becoming the system
TTS engine to intercept what it decides to speak) was considered and
rejected - it would still require enabling TalkBack itself, bringing back
the exact double-tap-hijack UX problem this project exists to avoid.

**Integrated and confirmed live 2026-09-13** (`MlKitOcr.kt`,
`ReadAloudAccessibilityService.ocrScreenshot()`) - see the Gradle section
below for why this needed a real build-system migration first, not just a
dependency line. Against a genuinely opaque real post screen (same
`ViewFactoryHolder` wall as above): tree extraction came back empty as
expected, `MlKitOcr.recognize()` returned 1969 characters of real
recognized text, and it read aloud correctly end to end (confirmed via a
real `AudioTrack` reaching `PLAYING`). Logcat also showed Play Services
dynamically fetching the recognition module on first use
(`dl-MlkitOcrCommon.optional_*.apk`) - the unbundled variant works exactly
as documented, no model weights shipped in this app's own APK (9MB total,
up from ~815KB pre-ML-Kit, entirely from the dependency graph's classes/
resources, not any bundled model).

*The other path, actually working today:* Reddit's per-post `.rss` feed
(NOT `.json`, which is blocked outright - confirmed live, 403 even with a
real browser User-Agent, on both listings and individual posts, hours
apart, so it's a structural block on that path, not a transient rate
limit) is NOT blocked and returns real comment bodies with author
attribution. `RedditRssParser.kt` parses it (`android.util.Xml`'s
built-in `XmlPullParser`, zero new dependencies) and `RedditShareActivity`
is a second entry point into this app - necessary because there's no way
to read a post's URL off Reddit's empty accessibility tree, so the
generic corner-swipe trigger can never know what to fetch. Share a post
from Reddit's own Share button, pick "Read Aloud", and this fetches and
reads it instead. Confirmed live end to end: real multi-comment audio
playback (`description=Reddit thread`, position past 8 seconds and
climbing). Real, tested limitation: capped at roughly the top ~10
comments regardless of `?limit=`/`?sort=` query params (both tested,
identical entry count either way), and it's a flat list - no reply-
nesting/depth info the way the real (blocked) JSON tree would have given.
Good enough for "read me the gist of this thread," not the full nested
conversation.

So the practical state: **both paths work now.** Invoking Read Aloud on
Reddit via the corner-swipe gesture falls through to OCR and reads
whatever's genuinely on screen (no comment-tree structure, no reply
navigation, just what a screenshot shows); sharing a specific post/thread
gets real comment bodies with author attribution via the `.rss` path
instead (structured, but capped at ~10 flat comments). Neither replaces
the other - OCR works on anything currently visible regardless of app,
`.rss` gives cleaner structure but only for Reddit and only for what you
explicitly share.

**Outlook** (`com.microsoft.office.outlook`) - `OutlookProfile` is a port
of GmailProfile's footer-trim approach, but **completely untested** -
Outlook isn't installed on this phone. First thing to do before trusting
any of it: dump its accessibility tree the same way Reddit's and Gmail's
were dumped here (`adb shell uiautomator dump`) - a corporate mail app
built on Office's own cross-platform UI toolkit could turn out closer to
Reddit's opaque-canvas case than Gmail's clean one.

**Everything else** - `GenericProfile`, no app-specific code, no
scrolling or clicking. Reads whatever the tree exposes in on-screen
order, trusting whatever the app itself chose to expose. An app that
needs more than that earns its own profile rather than every unknown app
risking unbounded automated interaction it was never tested against.

## Build system: Gradle (2026-09-13)

Every sibling app in this family (dictate-android, claude-agents-android,
newsdigest-android, peeragent-android) deliberately has zero dependencies
and a hand-rolled `aapt2 -> kotlinc -> d8 -> apksigner` `build.sh` instead
of Gradle. This project broke that convention on purpose, and only for
this one reason: `play-services-mlkit-text-recognition`'s real transitive
graph turned out to be Firebase + AndroidX, not the 4 artifacts its own
POM alone suggested - fully mapped before deciding anything:

```
play-services-mlkit-text-recognition
+- play-services-mlkit-text-recognition-common
|  +- play-services-base, play-services-basement, play-services-tasks
|  +- com.google.android.odml:image
|  +- com.google.firebase: firebase-components, firebase-encoders, firebase-encoders-json
|  +- com.google.android.datatransport: transport-api, transport-backend-cct, transport-runtime
|  +- com.google.mlkit: common, vision-common, vision-interfaces
+- com.google.mlkit:common
   +- androidx.appcompat:appcompat (its own large closure: .activity, .fragment, .lifecycle, .drawerlayout, .viewpager, .customview, .savedstate, real resources)
```

15-25+ AARs once fully resolved, each with its own resources and
manifest requirements (Firebase components in particular self-register
via manifest-declared `ContentProvider`s). Hand-merging that many
AARs' classpaths/resources/manifests with zero tooling for version
reconciliation or manifest merging - the two things Gradle's Android
plugin automates - was assessed as genuine risk of a subtly broken build
(resource ID collisions, a missing Firebase manifest entry causing a
hard-to-trace runtime crash), not just more typing. The user made the
call explicitly rather than this being assumed.

What changed, concretely:

- `settings.gradle`, `build.gradle`, `gradle.properties` added at the
  repo root; `build.sh` kept only as a historical reference for how the
  pre-Gradle MVP built (it can no longer build this project - it has no
  way to resolve the dependency above at all).
- **Flat layout preserved, not restructured** - `AndroidManifest.xml`,
  `src/`, `res/` stayed exactly where they were (via `sourceSets` in
  `build.gradle` pointing at them directly) rather than moving everything
  under the conventional `app/src/main/` - kept git history/paths intact
  across the migration.
- `package="..."` removed from `AndroidManifest.xml` - AGP 8+ wants
  `namespace`/`applicationId` in `build.gradle` instead; having both
  conflicts.
- Needed JDK 17 explicitly (`JAVA_HOME=/usr/lib/jvm/java-17-openjdk`) -
  this host's default `java` is JDK 11, too old for AGP 8.5.
- The Android SDK checkout at `~/.local/share/android-sdk` only had
  `platforms/android-34`, no `build-tools` - AGP auto-installs
  `build-tools;34.0.0` itself given accepted SDK licenses
  (`~/.local/share/android-sdk/licenses/`, the standard published hash
  files - same effect as running `sdkmanager --licenses` interactively).
- `debug.keystore` reused as-is (same file `build.sh` already generated,
  gitignored same as every sibling app) - a Gradle task
  (`ensureDebugKeystore`) regenerates it with the identical `keytool`
  invocation on a clean checkout that doesn't have one yet.

Build now: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug`,
output at `build/outputs/apk/debug/read-aloud-android-debug.apk` (~9MB,
up from ~815KB pre-ML-Kit - entirely the dependency graph's classes/
resources, no native libraries, no bundled OCR model weights).

## Chrome filtering and other Gmail refinements (2026-09-13)

Reported live: reading an open Gmail email spoke toolbar button labels
("emoji reaction", "forward", "share") right alongside the message.
`AccessibilityTree.isChrome()` now skips a node when either is true
(confirmed against a real dump, no false positives on 71 real content
nodes checked): its resource-id contains "button" or "badge" (NOT gated
on `isClickable` - confirmed live that "Reply all"/"Forward" are
`clickable="false"` TextView labels sitting inside a separately-clickable
container, so requiring the label itself to be clickable missed them),
or it's clickable with no visible `.text` and only a short (<=4 word)
content-desc on a common icon-control class (catches "Navigate up",
"More options", "Add star", "Mark unread", "Add emoji reaction").

Real bug found applying this: it was only added to `walk()`
(`collectText()`'s implementation), not `walkLabeled()`
(`collectTextWithLabels()`'s, separate function) - GmailProfile calls the
latter, so the fix silently didn't apply to Gmail at all until the two
walks were unified into one. Worth remembering: this project's `AppProfile`
model means every fix needs checking against which actual extraction path
a given profile uses, not just "did I add it to AccessibilityTree.kt".

The "badge" id-pattern was added after a second live report: Gmail's
contact-badge icon has content-desc "Show contact information for
<sender name>" - a multi-word name pushes it past the 4-word cutoff, so
word-count alone couldn't catch it; the resource-id (`contact_badge`) can.

Separately, "please skip badges/tags" also turned up a real Gmail quirk
`AccessibilityTree`'s node-level filtering can't fix at all:
`subject_and_folder_view`'s own `.text` bakes the folder/category chips
directly onto the end of the subject string with no separator -
`"...vps-af1b0c30.vps.ovh.net Inbox primary"` for an email that's both in
the Inbox and the Primary tab. Not a separate node - it's the same string
as the real subject. `GmailProfile.stripTrailingLabels()` strips a
trailing run of Gmail's known category/location words
(inbox/primary/social/promotions/updates/forums/starred/...) from
whatever `subject_and_folder_view` produces before it gets the "Subject:"
label. Known-imperfect (a subject genuinely ending in one of these exact
words would get over-trimmed) - same tradeoff class as `STOP_MARKERS`.

## Generating-audio overlay + streaming position reporting (2026-09-13)

Reported live: the first sentence plays almost instantly, but a later
one can take ~10s to synthesize (worse with Chatterbox than Kokoro - see
server.py), with nothing indicating the app hasn't just frozen.

Two fixes, one of which was a real bug rather than a missing feature:
`TtsSpeaker` never sent `{"type":"position","played_ms":...}` back to the
server at all - the position-feedback loop `newsdigest-android`'s
`ReadAloudController` (this protocol's original implementation) has
always needed to tell the server how far playback has actually gotten,
so it knows how far ahead it's safe to keep synthesizing
(`TTS_LOOKAHEAD_CAP_MS` in server.py). Without it the server likely saw
playback as stuck at position 0 indefinitely and capped its own lookahead
accordingly - a strong candidate for exactly the "second sentence stalls"
symptom, not something any client-side buffering trick could paper over
since the server itself was the one holding back. `TtsSpeaker` now runs
the identical 500ms reporting loop `ReadAloudController.streamText()`
already has.

`OverlayIndicator` is a small floating banner ("Read Aloud: generating
audio...") shown whenever nothing is queued to play - driven by
`TtsPlaybackService.HighlightListener`'s existing `onSentenceEnd()`/
`onSentenceStart()`/`onQueueIdle()` callbacks (already there for
newsdigest-android's own word-highlighting; not previously used by this
project at all). Uses `TYPE_ACCESSIBILITY_OVERLAY`, which needs a context
that IS a live, bound `AccessibilityService` - routed through
`ReadAloudAccessibilityService.instance` specifically so it works
identically whether the read was triggered from the corner-swipe path or
`RedditShareActivity`'s Share path, since the accessibility service
singleton is available either way.

**Not built yet, discussed:** an explicit contextual menu (e.g. Gmail:
"read this email" vs "read the whole inbox") - noted that the inbox-vs-
single-email distinction may already happen implicitly today, purely
based on which Gmail screen you're on when Read Aloud is invoked (the
inbox list already reads as a rundown of every visible row). A real
menu would need an interactive overlay Activity (the same translucent-
overlay pattern dictate-android's own AssistActivity already uses, not
OverlayIndicator's passive, untouchable banner), shown only for a profile
that declares more than one mode.

## TTS

Streams to the *existing* `newsdigest-server` (`10.10.0.2:8792`,
WireGuard-only, Kokoro/Chatterbox) that News Digest and claude-agents
already use - deliberately not a new host3 service, reusing
`WebSocketClient.kt`/`TtsPlaybackService.kt` copied near-verbatim from
newsdigest-android. Confirmed live end to end: a real `AudioTrack`
(`CONTENT_TYPE_SPEECH`) reached `PLAYING` state via the actual
long-press-power -> tap "Read Aloud" -> reads Gmail's open email path,
not just a direct-trigger test.

## Emulator (2026-09-13)

Live phone testing kept losing races against the user's own real-time
phone use (focus-stealing mid-test, once disrupting an active Monzo
banking session) - set up a local Android emulator on host3 instead so
Gmail UI-interaction development no longer touches the phone at all.

- `~/.local/share/android-sdk`, installed via Google's own
  `cmdline-tools` (downloaded directly from
  `https://dl.google.com/android/repository/` - NOT `/android/repo/`,
  which 404s for current filenames; the right current filename/path
  comes from fetching `repository2-3.xml` first).
- AVD `readaloud_test`: `system-images;android-34;google_apis_playstore;x86_64`,
  Pixel 6 device profile. Play Store present but its own sign-in got
  stuck on a forced password step - irrelevant, since apps are installed
  directly via `adb install`/`install-multiple`, never through the Play
  Store UI.
- Gmail itself: no APK download needed - pulled the real, already-signed
  split APKs straight off the phone (`adb pull` each
  `pm path com.google.android.gm` split) and `install-multiple`d them
  onto the emulator. The `arm64_v8a` split installs and runs fine on the
  x86_64 image, confirming this Play system image ships ARM translation.
  Signed into the real `baleti3266@gmail.com` account via `scrcpy`
  (`pacman -S scrcpy`, mirrors+controls any adb device) for one real,
  interactive Google login - not automated, deliberately, since it can
  hit CAPTCHA/2FA.
- **CPU/thermal**: the emulator's default launch (visible Qt window +
  `-gpu swiftshader_indirect` software rendering) pinned 2 cores at
  200%+ and pushed package temps to 88°C, even though nothing was
  visually watching it - the guest fully composites its UI internally
  regardless of whether a host window shows it. Fixed by launching
  `-no-window -no-audio -no-boot-anim -camera-back none -camera-front
  none -gpu host -cores 2 -memory 2048` plus disabling animation scales
  (`window_animation_scale`/`transition_animation_scale`/
  `animator_duration_scale` = 0) - steady-state dropped to ~27% CPU, 72°C.
  `-gpu host` (real hardware-accelerated rendering via the already-
  confirmed KVM/VT-x path) instead of software rasterization was the
  actual fix; headless/no-audio/no-animation are secondary savings.
- Network: the AVD's default NAT can still reach host3's own LAN IP
  (`10.10.0.2:8792`, the TTS server `Settings.DEFAULT_HOST`/`DEFAULT_TTS_PORT`
  point at) directly - confirmed via `ping` from inside the emulator,
  no bridge/port-forward setup needed.

**First live bug the emulator caught** (would have been very hard to
isolate on the phone, where every earlier attempt at this exact
scenario landed on the wrong foreground app instead): right after
`ModeChooserActivity.finish()`, `findForegroundWithRetry()` briefly
accepted a status-bar/notification-panel window as "the foreground
screen" - `GmailProfile.extract()` dutifully read its icon
content-descriptions ("Wifi signal full", "Battery charging, 100
percent") as if they were email content. Root cause:
`foregroundRoot()` only ever excluded *our own* package
(`dev.local.readaloud`) as "not the app we want" - it never checked
that what came back was actually the *expected* app, even in the one
call site (`readWithMode()`) that already knows exactly which package
it's waiting for (`pkg`, passed down from `ModeChooserActivity` itself).
Fixed by adding an optional `expectedPackage` param to
`findForegroundWithRetry()` that keeps retrying until the returned
window's package actually matches, used by `readWithMode()` and by
every `GmailProfile` call site that's mid-navigation within Gmail
(these already implicitly expect `packageName` back). The loops that
poll `foregroundRoot()` directly (waiting for an email to open,
`expandCollapsedMessages()`) were already safe by construction - they
additionally check for Gmail-specific resource IDs before accepting a
result, so a wrong-package root just fails that check and gets retried
naturally.

**First live verification of `expandCollapsedMessages()`**: tested
against a real synced thread with a genuine collapsed-3-messages block
(a GitHub notification thread). Confirmed both the resource IDs the
code was written against (`super_collapsed_block`, `email_snippet`,
`upper_header`) match the real live tree exactly, and that clicking the
block actually expands it on screen (the "3" indicator disappears,
full message bodies appear) - extraction went from a single truncated
message to 1107 chars spanning all previously-collapsed messages.

## Open questions for next session

1. **`ocrScreenshot()` is currently only wired into `RedditProfile`.**
   Given it's a generic `ReadAloudAccessibilityService` method (not
   Reddit-specific), consider making it `GenericProfile`'s own fallback
   too, for whatever next unknown/opaque app comes up - it wouldn't need
   its own profile written first just to stop being a dead end.
2. **Word-highlight + live caption overlay**, discussed but not built:
   for the `.rss`/Share path (full text known upfront, same shape as
   `newsdigest-android`'s existing `ReadAloudController` word-highlighter)
   this is cheap and mostly adapting code that already works. For the
   OCR/corner-swipe path, syncing a highlight to Reddit's *own* scroll
   position would need repeated OCR per scroll step (real, meaningfully
   higher CPU/battery cost than the current one-shot-per-invocation use) -
   preferred approach there is still a caption overlay showing the OCR'd
   text itself, not trying to drive Reddit's real (still-opaque) scroll.
3. Reusing the already-authenticated Chromium CDP profile (the one
   `reddit-architecture-bot` drives) to fetch `old.reddit.com`'s
   server-rendered HTML was considered as a fully separate,
   non-accessibility data path for Reddit - superseded by both the OCR
   and `.rss` paths above, but worth remembering if `.rss` ever also gets
   locked down the way `.json` was.
4. **Outlook** needs a real device/emulator with it installed before
   `OutlookProfile` can be trusted at all.
5. **Volume-key skip controls** - `canRequestFilterKeyEvents` is
   declared but nothing uses it yet.
6. Reddit's OAuth API is NOT a viable alternative to any of the above -
   confirmed via the user's own 2026-08-31 journal entry that new app
   registration on Reddit's developer console has been silently closed.
