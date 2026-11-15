# EchoSnap — Music Share Card Generator

EchoSnap is an Android Jetpack Compose application that automatically reads active music playback notifications and media sessions from any music player (Spotify, YouTube Music, Apple Music, Samsung Music, SoundCloud, etc.) and turns the currently playing song into a sleek, customizable 9:16 Share Card ready to save or share anywhere.

---

## Direct APK Download

- **Latest Debug APK (`app-debug.apk`):**
  [`https://github.com/y4hya3/Music-share-card/releases/latest/download/app-debug.apk`](https://github.com/y4hya3/Music-share-card/releases/latest/download/app-debug.apk)
- **All Releases & Changelogs:**
  [`https://github.com/y4hya3/Music-share-card/releases`](https://github.com/y4hya3/Music-share-card/releases)

> All previous releases remain archived under GitHub Releases so you can inspect past builds or roll back anytime.

---

## Key Features

### 1. Automatic Live Cover Art & Track Sync
- Listens to Android `NotificationListenerService` and `MediaSessionManager` active sessions (`MediaController.Callback`) in real time.
- Extracts track title, artist, album, and cover artwork from `MediaMetadata` bitmaps, `content://` / `https://` artwork URIs, and notification large-icon fallbacks.
- Automatically updates the card artwork, blurred background, and adaptive colors whenever the playing song changes—plus instant manual **Refresh** and auto-sync on app resume.
- Opens with a pure `#000000` splash screen showcasing the transparent **EchoSnap** mark with zero background box or clutter.

### 2. Full-Screen Blurred Background & Live Blur Slider
- Displays the active album cover as a full-screen `ContentScale.Crop` background behind a `25%` dark contrast overlay (`rgba(0, 0, 0, 0.25)`).
- Interactive **Background Blur** slider (`0px` sharp to `40px` heavily blurred) placed cleanly outside the share card container so exported images and screenshots remain clutter-free.
- Hardware `RenderEffect` blur on Android 12+ and optimized zero-table StackBlur fallback on Android 7.0–11.

### 3. Floating Glassmorphic Share Card
- Crisp, unblurred album art at the top inside a `16dp` rounded floating card with soft drop shadow and adaptive dominant-color dark glassmorphism extracted via AndroidX `Palette`.
- Clean card footer featuring the selected brand logo and wordmark.

### 4. Customizable Branding, Layouts & Metadata (Edit Sheet)
- **Card Logo & Name Switcher:** Switch the card footer anytime between:
  - **Bitchord** (signature squircle soundwave badge + `"Bitchord"`)
  - **Spotify** (circular 3-arc soundwave badge + `"Spotify"`)
  - **Apple Music** (rounded squircle + beamed eighth-note badge + `"Apple Music"`)
- **Logo Badge Colors:** Red, Template Dynamic (matches album art), Green, or Classic White—changing only the icon badge while keeping the brand wordmark crisp white.
- **Background Modes:** Blurred Album Cover (`0px–40px`) or Palette Gradient presets.
- **Card Layout Styles:** Floating Glass Card, Floating Glow Art, Minimal Story, or **Spotify-Style Lyrics Card** (with an inline manual multi-line lyrics editor, automatic light/dark ink contrast, and a **Palette Color / Song Cover Art** background toggle).
- **Card Size Control (`60%–120%`):** Adjust card scale consistently across the live screen, 9:16 Export Preview, Gallery Save, and Social Shares—with synchronous persistence across app restarts.
- **High-Quality Cover Art & Online Search:** Auto-fetch or manually search 1000×1000 HD covers (iTunes HD & Deezer HD) and choose between online results or the original player cover art.
- **Spotify Account Login & Smart Animated Canvas:** Built-in **Login to Spotify** flow automatically captures and persists your session Bearer token (with optional OAuth PKCE & manual token fallback), paired with strict artist/title-verified matching for looping Spotify Canvas MP4s and HD music clips.
- **Enhanced Adaptive Palette Gradients:** `clearFilters()` + HSV-harmonized multi-stop gradients (`Adaptive Harmony`, `Cinematic Aurora`, `Studio Glow`, etc.) that adapt cleanly to every cover including monochrome, ultra-dark, and bright art.
- **Persistent Customizations:** All branding, size, blur, gradient, layout, Lyrics background mode, Spotify login token, and canvas-search preferences save synchronously and stay active across song changes, player switches, and app restarts.
- **Track Editor & Sample Presets:** Edit track title/artist manually or preview with built-in demo tracks.

### 5. Export Preview, Save & Direct Social Sharing
- **Copy link:** Copies track info cleanly to the clipboard.
- **Save:** Exports a high-resolution `1080×1920` PNG directly to `Pictures/EchoSnap` via `MediaStore`.
- **Preview:** Inspect the exact clutter-free `9:16` export (reflecting your current card size and edits) before saving or sharing.
- **Circular Social Logos:** Direct sharing via real circular app logos for **WhatsApp** (standard chat/status share), **Instagram Story** (Stories only), **Snapchat Story** (Stories only), **Discord**, and **Other** (Android share sheet).

---

## Performance & Device Compatibility

- **Supported Android Versions:** Android 7.0 (API 24) through Android 15 (targetSdk 35).
- **Phone & Tablet Adaptive UI:**
  - **Compact Phones (e.g., Redmi 9C — Helio G35, PowerVR GE8320, 2–3GB RAM):** Zero-table StackBlur, `inSampleSize` sampled artwork decoding (`<= 720px`), `HARDWARE` bitmap to `ARGB_8888` normalization, immediate export bitmap recycling, and 16ms slider coalescing to prevent memory spikes or GC stutters.
  - **Tablets Portrait & Landscape (e.g., Samsung Galaxy Tab A7 LTE — 10.4" 2000×1200, Snapdragon 662, Adreno 610):** Pre-scaled GPU blur textures and an automatic **two-pane landscape layout** (floating card on the left; controls, blur slider, and 3×2 share grid on the right) with zero vertical clipping.

---

## Setup & Permissions

1. Install `app-debug.apk` on your Android device (**EchoSnap**).
2. Open **EchoSnap** and tap the **Notification Access Required** banner (or **Enable**).
3. Allow Notification Access for **EchoSnap Media Listener** in Android Settings.
4. Play any song in your favorite music app—EchoSnap automatically pulls the cover art, title, and artist.

- **Privacy:** EchoSnap reads active media notifications locally on-device solely to render your share card. No tracking, analytics, or external servers are used.

---

## Build & Automated CI/CD

- **Android Gradle Plugin:** `9.1.1`
- **Gradle:** `9.3.1`
- **Java:** Temurin `17`
- **Automated Workflow:** Every push to `main` (and manual dispatch) runs `.github/workflows/build-apk.yml`, builds `app/build/outputs/apk/debug/app-debug.apk`, uploads the backup Actions artifact `app-debug-apk`, and publishes a new GitHub Release preserving all prior releases while marking the newest build as `latest`.
