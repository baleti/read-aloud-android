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

**Reddit** (`com.reddit.frontpage`) - the one genuinely unsolved gap.
Confirmed live: its Compose content lives entirely behind an
`androidx.compose.ui.viewinterop.ViewFactoryHolder` node reporting
`childCount=0` - not hidden or unlabeled, genuinely absent from the
standard `AccessibilityNodeInfo` tree. Manually enabling TalkBack and
tapping a paragraph made it speak, which looked like "lazy semantics
attach on touch" - but every equivalent available to an app was tried and
none reproduced it: a `dispatchGesture()` tap at the same coordinates,
toggling system touch-exploration mode on first via `setServiceInfo()`,
and a direct `ACTION_ACCESSIBILITY_FOCUS` call on the deepest node all
still came back completely empty. Whatever real touch-exploration does to
make Compose populate happens at an input-interception layer this app has
no access to reproduce.

`RedditProfile`'s expand-and-scroll loop (click "N more replies", scroll
the largest scrollable container, dedupe into an order-preserving set) is
built and should work correctly for apps where the tree is readable at
all - it has just never had real content on Reddit itself to prove it end
to end. When every fallback still comes back empty, it calls
`ReadAloudAccessibilityService.captureScreenshot()` - confirmed live to
succeed (1080x2400, matching the device) even on this exact screen - and
returns nothing further. **No OCR/vision step is wired up yet.** That's
the real open question below.

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

## TTS

Streams to the *existing* `newsdigest-server` (`10.10.0.2:8792`,
WireGuard-only, Kokoro/Chatterbox) that News Digest and claude-agents
already use - deliberately not a new host3 service, reusing
`WebSocketClient.kt`/`TtsPlaybackService.kt` copied near-verbatim from
newsdigest-android. Confirmed live end to end: a real `AudioTrack`
(`CONTENT_TYPE_SPEECH`) reached `PLAYING` state via the actual
long-press-power -> tap "Read Aloud" -> reads Gmail's open email path,
not just a direct-trigger test.

## Open questions for next session

1. **Reddit's vision-fallback step.** The capture works; nothing reads
   text out of the bitmap yet. Two real options, not decided here on
   purpose: an on-device OCR/vision model, or a new host3 endpoint over
   the same WireGuard tunnel (there's no vision-capable inference running
   on host3 currently - newsdigest-server only has Kokoro/Chatterbox TTS
   and Whisper STT loaded). Latency and cost tradeoffs are real and
   worth deciding deliberately rather than guessing at 2am.
2. **Alternative for Reddit specifically:** this conversation also looked
   at reusing the already-authenticated Chromium CDP profile (the one
   `reddit-architecture-bot` drives) to fetch `old.reddit.com`'s
   server-rendered HTML instead of fighting the app's accessibility tree
   at all - a completely different, non-accessibility-based data path.
   Worth weighing against the vision-fallback option above.
3. **Outlook** needs a real device/emulator with it installed before
   `OutlookProfile` can be trusted at all.
4. **Volume-key skip controls** - `canRequestFilterKeyEvents` is
   declared but nothing uses it yet.
5. Reddit's OAuth API is NOT a viable alternative to either of the above
   - confirmed via the user's own 2026-08-31 journal entry that new app
   registration on Reddit's developer console has been silently closed.
