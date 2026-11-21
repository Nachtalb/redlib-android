# Redlib for Android

[Redlib](https://github.com/Nachtalb/redlib) as an Android app: it runs Redlib on the phone itself and shows it in a WebView, so there is no instance to host and every request to Reddit comes from the phone.

- Opens `reddit.com` and `redd.it` links, once "Open supported links" is enabled for the app in Android's settings (Apps > Redlib > Open by default).
- Everything that isn't a Redlib page opens in the browser, such as other sites and "open in Reddit".
- Long-press a link, image or video to open, copy, share or save it.

Needs Android 11 or newer on ARM (64 or 32 bit).

## Building

```
./gradlew assembleRelease   # build/outputs/apk/release/redlib-android-release.apk
```

The build downloads Redlib's Android binaries from the [`latest` release](https://github.com/Nachtalb/redlib/releases/latest) of the fork; `./gradlew clean` makes the next build fetch them again.
