# Canali Italia Pro — AI Agent & Developer Guide

This file provides architectural context, commands, code style guidelines, and design principles for working in the **Canali Italia Pro** codebase (`mich-de/italyitvpro`).

---

## 1. Project Overview

**Canali Italia Pro** is an IPTV player for Italian television optimized for Android TV (Leanback / D-pad), tablets, and phones (portrait & landscape). It provides live channels, an XMLTV-based EPG (now & next programme guide), multi-source logo resolution, category filtering, search, favorites, and a resilient dual-engine player.

- **Package**: `com.michde.italyitv`
- **Application ID**: `com.michde.italyitv`
- **Min SDK**: 24 (Android 7.0+) | **Compile/Target SDK**: 36
- **Language**: Kotlin 2.3+
- **UI Toolkit**: Jetpack Compose with Material 3
- **Media Engine**: AndroidX Media3 ExoPlayer 1.6.1 + AndroidX WebKit (WebView)

---

## 2. Common Commands

### Build & Run
```bash
# Assemble debug APK
./gradlew :app:assembleDebug

# Assemble release APK
./gradlew :app:assembleRelease

# Install debug APK on connected device/emulator
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Compile Kotlin classes
./gradlew :app:compileDebugKotlin
```

### Testing & Verification
```bash
# Run local unit tests (JUnit)
./gradlew testDebugUnitTest

# Run Android lint
./gradlew :app:lintDebug

# Clean build artifacts
./gradlew clean
```

---

## 3. Architecture & Code Structure

The project follows a single-activity architecture with Jetpack Compose, manual dependency injection via `AppContainer`, and reactive state management using Kotlin Coroutines and `StateFlow`.

```
app/src/main/java/com/michde/italyitv/
├── App.kt                  # Application class; installs permissive TLS; AppContainer (DI)
├── MainActivity.kt         # Single activity; Screen back-stack; PiP handling; engine routing
├── core/
│   ├── Http.kt             # OkHttp/URLConnection client; permissive TLS; UA spoofing
│   └── NameTools.kt        # Channel name sanitizer; category classification; match keys
├── data/
│   ├── IptvRepository.kt   # Core sync orchestrator (catalog -> inject -> EPG -> logos -> dedupe)
│   ├── SettingsStore.kt    # SharedPreferences persistence (favorites, settings)
│   ├── epg/
│   │   ├── EpgIndex.kt     # In-memory EPG lookup for now/next programmes
│   │   └── LogoResolver.kt # 3-tier logo resolution (EPG icon -> Free-TV list -> tv-logos repo)
│   ├── model/
│   │   └── Models.kt       # Channel, ParsedChannel, NowNext, Programme, Category, SyncState
│   ├── parser/
│   │   └── XmltvParser.kt  # Memory-efficient streaming XmlPullParser for large XMLTV guides
│   └── remote/
│       ├── DliveResolver.kt    # Scrapes dlive.sx / Daddy Live dynamic player endpoints
│       ├── HuhuApi.kt          # huhu.to JSON catalogue fetcher & stream resolver
│       └── InjectedChannels.kt # Curated channel catalogue (DAZN, Sky, Rai)
└── ui/
    ├── AppViewModel.kt     # Shared ViewModel; combines streams; 60s ticker for now/next
    ├── TvFocus.kt          # D-pad focus extensions & indicators for Android TV
    ├── channels/
    │   └── ChannelsScreen.kt   # Main guide: search, category chips, channel list cards
    ├── player/
    │   ├── PlayerScreen.kt     # ExoPlayer engine: HLS, buffer tuning, OSD stats, aspect ratio
    │   ├── WebPlayerScreen.kt  # WebView engine: Chromium for anti-bot / Clappr / dlive streams
    │   └── PlayerHelpers.kt    # Player UI overlays, stats HUD, aspect ratios
    ├── settings/
    │   └── SettingsScreen.kt   # Settings screen (cache clear, backend status)
    ├── splash/
    │   └── SplashScreen.kt     # Initial loading screen with live sync pipeline log
    └── theme/
        └── Theme.kt        # Material 3 Dark theme optimized for TV and OLED screens
```

---

## 4. Key Architectural Patterns & Constraints

### 1. Dual Playback Engine Strategy
Channels require different playback engines depending on CDN anti-scraping and delivery mechanics:
- **ExoPlayer (`PlayerScreen`)**: Used for direct HLS/DASH streams and resolved `huhu.to` handles. Tuned for fast startup (~1.5s initial buffer, 60s max buffer), auto `https -> http` fallback for expired SSL origins, and automatic audio track selection preferring Italian (`"ita"`, `"it"`).
- **WebView (`WebPlayerScreen`)**: Used for `dlive.sx` / "Daddy Live" channels (`DliveResolver.isDlive(url)`). Upstream media segments are Cloudflare-worker gated and served disguised as `.zst`/`.pdf` chunks that fail outside a real browser. The app runs a headless/clean Chromium WebView that injects custom JS/CSS to strip ads, bypass overlays, remove the `X-Requested-With` header, and hook fullscreen/PiP.

### 2. Permissive TLS Requirement
Many IPTV CDN origins operate with expired, misconfigured, or mismatched SSL certificates.
`Http.installPermissiveTls()` is called in `App.onCreate()` to install a trust-all `X509TrustManager` and disable hostname verification for media streams. **Do not remove or restrict this**, as doing so breaks playback on numerous Italian community streams.

### 3. Streaming XMLTV Parsing
EPG files (`epgshare01.online`) contain ~20,000+ programmes and can exceed 30MB uncompressed.
- Always use `XmlPullParser` via `XmltvParser.kt`.
- **Never** read the entire XML into memory as a DOM tree, as low-end Android TV devices (e.g. Fire TV Sticks) have strict heap limits and will crash with `OutOfMemoryError`.

### 4. TV D-Pad & Leanback Compatibility
The app must work seamlessly on both touchscreens and Android TV D-pads:
- Use `TvFocus.kt` utilities for focus border drawing and highlight animations.
- Ensure all interactive elements have explicit `.focusable()` states and support key events (`DPAD_CENTER`, `ENTER`, `BACK`).
- Handle `onUserLeaveHint()` in `MainActivity` for automatic Picture-in-Picture (PiP) transitions.

### 5. Deduplication & Backend Tagging
- When channels are retrieved from multiple backends (e.g. `huhu` and curated `Daddy`), they are deduplicated using normalized name keys (`NameTools.matchKey`).
- Curated channels are kept, and names are tagged with `(Daddy)` or `(huhu)` so users can choose the preferred backend if one experiences server downtime.

---

## 5. Coding Style & Conventions

- **Language**: Kotlin idioms (expression bodies, data classes, sealed interfaces, extension functions).
- **Concurrency**: Coroutines and structured concurrency. Network/IO operations must run on `Dispatchers.IO` with proper `runCatching` or try/catch blocks.
- **State Management**: Prefer `StateFlow` and unidirectional data flow in Compose (`vm.ui.collectAsStateWithLifecycle()`).
- **Compose UI**: Reusable composables, state hoisting, passing callbacks instead of ViewModels to child composables where possible.
- **Dependencies**: Managed centrally in `gradle/libs.versions.toml`.
