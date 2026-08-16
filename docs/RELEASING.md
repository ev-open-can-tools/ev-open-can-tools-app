# Releasing

The app is distributed as a sideloadable APK from GitHub Releases, not through
the Play Store. From **30 September 2026** Google requires every app installed on
a certified Android device to be tied to a verified developer — including
sideloaded ones. Enforcement starts in Brazil, Indonesia, Singapore and Thailand
and goes global from 2027.

Being covered by that verification comes down to two values matching what is
registered in the Android Developer Console:

| | Value |
|---|---|
| `applicationId` | `org.ev_open_can_tools.ev_can_app` |
| Release key SHA-256 | `2F:F0:34:E7:67:F2:6A:DC:D9:7C:34:A3:B1:DE:73:55:4C:3C:2D:B7:BA:64:D1:C2:D9:69:B8:DE:F3:F6:BD:3A` |

If either differs, the APK is not covered and certified devices will refuse it
once enforcement reaches them.

> The Kotlin/R namespace is still `com.evcantools.app`. That is deliberate — only
> the `applicationId` is the app's identity; renaming every source package would
> be churn for nothing.

## The two keys, and why they are not the same

**`app/debug.keystore` is committed on purpose.** It is a throwaway with the
password `android`, and it exists so every CI debug build carries the same
signature and sideloaded updates install over each other instead of being
rejected for a signature mismatch.

**It must never be the release key.** It is public — anyone who clones the repo
could sign an APK that appears to come from this developer. Its fingerprint is
`3B:C9:71:20:…`, which is *not* the registered one.

The release key is never committed. `.gitignore` blocks `*.keystore` and `*.jks`
(with an exception for the debug one), and CI materialises it from a secret.

## One-time setup

### 1. Create the release keystore (if you do not have one yet)

Run this yourself — choose and keep the passwords, they must not be shared:

```bash
keytool -genkeypair -v \
  -keystore release.keystore \
  -alias evcan-release \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=ev-open-can-tools, O=ev-open-can-tools, C=DE"
```

Read its fingerprint and confirm it is the registered one:

```bash
keytool -list -v -keystore release.keystore -alias evcan-release | grep -A1 SHA256
```

**Back this file up somewhere safe.** Losing it means losing the ability to ship
updates that install over existing ones — there is no recovery.

### 2. Register it with Google

In the Android Developer Console, register the package name
`org.ev_open_can_tools.ev_can_app` and add this key's SHA-256 fingerprint. A
package name can hold several keys, and further keys can be added later.

This step needs a Google account and a government-issued ID; it cannot be
automated.

### 3. Add the GitHub secrets

*Settings → Secrets and variables → Actions*:

| Secret | Value |
|---|---|
| `EVCAN_KEYSTORE_BASE64` | output of `base64 -w0 release.keystore` |
| `EVCAN_KEYSTORE_PASSWORD` | keystore password |
| `EVCAN_KEY_ALIAS` | `evcan-release` |
| `EVCAN_KEY_PASSWORD` | key password |

## Cutting a release

1. Update `CHANGELOG.md`: move `[Unreleased]` into a dated version heading.
2. Bump `versionName` and `versionCode` in `app/build.gradle.kts`. `versionCode`
   must increase on every published build or Android refuses the update.
3. Tag and push:

```bash
git tag v0.2.0-beta.1
git push origin v0.2.0-beta.1
```

`.github/workflows/release.yml` then runs the protocol tests, builds a
release-signed APK, prints the package name and certificate fingerprint into the
job summary, and publishes the APK plus its `.sha256` as a GitHub Release. Tags
containing `beta` are marked as pre-releases.

The workflow **fails early and publishes nothing** if the signing secrets are
missing. An unsigned or debug-signed APK would not match the registration, so
shipping one is worse than shipping nothing.

## Checking a published APK

```bash
sha256sum -c ev-can-tools-v0.2.0-beta.1.apk.sha256
apksigner verify --print-certs ev-can-tools-v0.2.0-beta.1.apk
aapt2 dump packagename ev-can-tools-v0.2.0-beta.1.apk
```

The printed SHA-256 and package name must match the table at the top.

## Sources

- [Register on Android Developer Console](https://developer.android.com/developer-verification/guides/android-developer-console)
- [Registering Android package names](https://support.google.com/googleplay/android-developer/answer/16761053)
- [Select key for existing package name](https://support.google.com/googleplay/android-developer/answer/16762143)
