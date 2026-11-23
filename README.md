# Redlib for Android

[Redlib](https://github.com/Nachtalb/redlib) as an Android app: it runs Redlib on the phone itself and shows it in a WebView, so there is no instance to host and every request to Reddit comes from the phone.

- Opens `reddit.com` and `redd.it` links, once "Open supported links" is enabled for the app in Android's settings (Apps > Redlib > Open by default).
- Everything that isn't a Redlib page opens in the browser, such as other sites and "open in Reddit".
- Long-press a link, image or video to open, copy, share or save it.
- Pull down at the top of a page to reload it.

Needs Android 11 or newer on ARM (64 or 32 bit).

## Installing

Download [`redlib-android.apk`](https://github.com/Nachtalb/redlib-android/releases/latest/download/redlib-android.apk) from the [newest release](https://github.com/Nachtalb/redlib-android/releases/latest) and open it on the phone.

After that the app updates itself: once a day, when it starts or comes back to the front, it checks the newest release and offers a newer version, downloads it with Android's download manager and installs it over the old one. Android asks once to allow Redlib to install apps. On Samsung phones, Auto Blocker stops the update; the app then opens Auto Blocker's screen and installs once you come back. Builds from before 0.2.1 were signed with a key that is no longer used, and Android refuses to update across keys: uninstall them and install 0.2.1 or newer by hand once.

## Releases

A release (`<app version>-<Redlib version>`, e.g. `0.2.0-2026.1123.1`) happens when `appVersion` in `build.gradle.kts` is bumped; other pushes only build and test.
A new [Redlib release](https://github.com/Nachtalb/redlib/releases/latest) is picked up by a nightly check, which GitHub pauses after 60 days without activity in the repository (turn it back on under Actions).

## Building

```
./gradlew assembleRelease   # build/outputs/apk/release/redlib-android-release.apk
```

The build downloads Redlib's Android binaries from the [newest release](https://github.com/Nachtalb/redlib/releases/latest) of the fork, and fetches them again when there is a newer one. To build with other binaries, pass `-PredlibDir=<folder>` holding `redlib-aarch64-linux-android` and `redlib-armv7-linux-androideabi` (the version then ends in `-local`). Local builds have versionCode 1, never offer updates, and are signed with your machine's debug key, so they don't install over a published build.

## License

The app's own code is [MIT](LICENSE). The Redlib binary a built APK contains and the Redlib logo are Redlib's, under [AGPL-3.0](https://github.com/Nachtalb/redlib/blob/main/LICENSE); the menu icons are Google's Material icons, under Apache-2.0.
