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
**Not yet integrated** - fetched the actual AAR
(`play-services-mlkit-text-recognition:19.0.1`, confirmed live: 78KB, zero
native `.so` files, tiny 2.8KB `classes.jar`) and its POM, which showed a
real transitive dependency chain (`play-services-base`,
`play-services-basement`, `play-services-mlkit-text-recognition-common`,
`com.google.mlkit:common`) - genuine multi-AAR resolution, manifest
merging, and version reconciliation, exactly what Gradle exists to
automate and this project's hand-rolled `aapt2`/`kotlinc`/`d8` pipeline
has no equivalent for. Deliberately not rushed blind at 3am; needs
careful manual assembly with real verification at each step, not a late-
night guess that could leave a broken build. `RedditProfile`'s
`captureScreenshot()` fallback (confirmed live: captures the real
1080x2400 screen even on this exact opaque thread) is exactly the input
this OCR step will consume once it's wired up - the missing piece is
narrowly "run recognition on that bitmap," nothing upstream of it.

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

So the practical state: **invoking Read Aloud on Reddit via the
corner-swipe gesture** still hits the accessibility-tree wall pending the
OCR integration above; **sharing a specific post/thread** already works
today via the RSS path.

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

1. **Wire up `play-services-mlkit-text-recognition` for real.** Decided
   (see Reddit findings above) - same library TalkBack uses, on-device,
   private, fast (TalkBack's own version felt instant because it crops to
   just the focused node's bounds and the model is likely already warm).
   What's left is the actual manual AAR/dependency integration into the
   no-Gradle build: `play-services-mlkit-text-recognition` itself (78KB,
   no native code) plus its real transitive chain
   (`play-services-base`, `play-services-basement`,
   `play-services-mlkit-text-recognition-common`,
   `com.google.mlkit:common`) - each needs its classes/resources merged
   into the build by hand since there's no Gradle here to do it
   automatically. Do this carefully with a real build+install+test cycle
   after each AAR added, not all at once.
2. **`RedditProfile`'s corner-swipe path still needs the OCR step above**
   to stop being a dead end for "just invoke Read Aloud while already
   looking at a Reddit thread" (as opposed to deliberately sharing it) -
   see item 1.
3. Reusing the already-authenticated Chromium CDP profile (the one
   `reddit-architecture-bot` drives) to fetch `old.reddit.com`'s
   server-rendered HTML was also considered as a fully separate,
   non-accessibility data path for Reddit - superseded by the `.rss`
   discovery above (no browser automation needed at all), but worth
   remembering if `.rss` ever also gets locked down the way `.json` was.
4. **Outlook** needs a real device/emulator with it installed before
   `OutlookProfile` can be trusted at all.
5. **Volume-key skip controls** - `canRequestFilterKeyEvents` is
   declared but nothing uses it yet.
6. Reddit's OAuth API is NOT a viable alternative to any of the above -
   confirmed via the user's own 2026-08-31 journal entry that new app
   registration on Reddit's developer console has been silently closed.
