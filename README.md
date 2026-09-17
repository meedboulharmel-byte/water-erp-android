# WATER ERP — Android

Native Android wrapper for the Google Apps Script WATER ERP of **جمعية سيدي لحسن امخشون**.

- **Package:** `com.sidilahcen.watererp`
- **Min SDK:** 24 (Android 7.0)
- **Target / compile SDK:** 34 (Android 14)
- **Language:** Kotlin, Gradle Kotlin DSL, Version Catalogs

The app is a full-screen WebView pointed at:

```
https://script.google.com/macros/s/AKfycbwbCbsoPcFVBPfWjVgQbaGmDqmCrmqq_8lMrN4P7ANlG6eVUNN-eMEas1NQQMN0F_8p/exec
```

## Open in Android Studio

1. Install [Android Studio](https://developer.android.com/studio) (Hedgehog / Koala or newer) with SDK 34 and JDK 17.
2. **File → Open** and select this `android/` folder.
3. Let Gradle sync. If the Gradle wrapper JAR is missing, Android Studio offers to generate it — accept.
4. Connect a device or start an emulator (API 24+).
5. Run the `app` configuration.

To change the Apps Script URL after a redeploy, edit `AppConfig.TARGET_URL` in:

`app/src/main/java/com/sidilahcen/watererp/AppConfig.kt`

## What the wrapper does

- JavaScript, DOM storage, cookies (including third-party) so `google.script.run` sessions persist
- Mobile Chrome user-agent so the ERP serves its mobile CSS
- All Apps Script / Google account redirects stay inside the WebView
- Native JS `alert` / `confirm` / `prompt`
- Horizontal load progress + pull-to-refresh
- System back navigates WebView history, then exits
- Custom offline and error screens with retry
- Camera + file picker (`onShowFileChooser`) for meter photos
- Geolocation prompt for field readings
- WhatsApp, `tel:`, `mailto:` open the matching system app
- Edge-to-edge, splash screen (Android 12+ API via AndroidX)

## Build a release APK / AAB

1. **Build → Generate Signed App Bundle or APK**
2. Create a keystore (keep it private; never commit it)
3. Select `release`, finish

ProGuard is enabled for release. WebView JS interfaces are kept in `app/proguard-rules.pro`.

## Debug

Chrome DevTools: `chrome://inspect` against a debug build (`WebView.setWebContentsDebuggingEnabled(true)`). Console messages from the ERP are forwarded to Logcat under the tag `WaterERP-JS`.
