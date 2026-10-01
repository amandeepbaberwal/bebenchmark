# BE Benchmark - Google Play release preparation

## App configuration

- App name: BE Benchmark
- Package: `com.lapetlo.bebenchmark` (do not change after the first Play submission).
- Version: `1.0.0`, version code `1`.
- Developer name: Amandeep.
- Support email: `help@lapetlo.com`.
- Minimum Android version: Android 7.0 (API 24).
- Target and compile API: Android 16 (API 36).
- Distribution artifact: signed Android App Bundle (`.aab`).
- The older sideloaded package `com.bluebenchmark.cpu` remains a separate app and is not updated by this new package ID.

## Signing key

The local upload key is in `keystore/BE-Benchmark-upload.jks`. Gradle reads its credentials from the ignored root file `keystore.properties`. Keep both files private, make a secure backup of the keystore, and do not send the key or passwords through chat or commit them. Enroll in Play App Signing when creating the Play app.

Build the release bundle with:

```powershell
.\gradlew.bat :app:bundleRelease
```

The bundle is written to `app/build/outputs/bundle/release/app-release.aab`.

## Privacy policy and Play Console

The full policy is in `docs/privacy-policy.html` and is available in the app under Settings > Privacy Policy. GitHub Pages can host this static page from the repository's `/docs` folder. This checkout currently has no GitHub remote, so the page is not public yet; after pushing to GitHub and enabling Pages for `/docs`, add the resulting URL to Play Console.

The app stores benchmark history and preferences on device, does not request the Internet permission, and does not send results to a BE Benchmark server. Android system backup may include app data according to the user's device settings. JSON export is user initiated through Android's document picker; the destination may be a cloud provider selected by the user.

Before submission:

1. Publish the privacy policy and add its public URL in Play Console.
2. Complete the Data safety, Ads, target audience, content rating, and app access forms using the current app behavior and merged release manifest.
3. Declare the benchmark's `specialUse` foreground service in Play Console. Explain the finite user started CPU workload, what happens if interrupted, and provide a demo video. Play may reject this use case after review.
4. Upload the prepared 512 x 512 PNG icon at `play-store/assets/icon-512.png`, the feature graphic at `play-store/assets/feature-graphic.png`, and genuine phone screenshots from `play-store/assets/screenshots/`. The current home screenshot is an empty-history state; capture a fresh one after a completed benchmark before finalizing the listing.
5. Upload to an internal testing track and verify the signed release on Android 9 and a current Android release.

## Listing copy draft

**Title:** BE Benchmark

**Short description:** Repeatable CPU tests for single-core, multicore, memory and AI performance.

**Full description:**

Run a repeatable CPU benchmark covering integer and floating-point arithmetic, SIMD, memory and cache, matrix workloads, cryptography, compression, signal processing, real-world tasks, and on-device AI inference.

Review single-core and all-core scores, category results, per-core clocks and load, available temperature readings, and raw test measurements. Compare results with your own previous runs on the same device and score version, or export measurements as JSON.

The benchmark runs locally and does not send results to a BE Benchmark server. Android device backup may follow your system backup settings. Scores from different benchmark apps are not directly comparable. A run keeps the CPU busy and can warm the device. You can stop it at any time; incomplete runs are not saved.

## Remaining submission work

- Push this repository and enable GitHub Pages for the `/docs` directory; enter the public privacy policy URL in Play Console.
- Capture a completed-run screenshot on the supported devices; the current BE package starts with empty history.
- Complete Play Console declarations and record the foreground service demo video.
- Inspect the signed AAB, then complete internal track testing before production review.
- Keep the upload keystore backed up and private.
