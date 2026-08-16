# Releasing

The app is distributed as a sideloadable APK from GitHub Releases, not through
the Play Store. From **30 September 2026** Google requires every app installed on
a certified Android device to be tied to a verified developer — including
sideloaded ones. Enforcement starts in Brazil, Indonesia, Singapore and Thailand
and goes global from 2027.

Being covered by that verification comes down to two values matching what is
registered in the Android Developer Console:

- **`applicationId`** — `org.ev_open_can_tools.ev_can_app`, set in
  `app/build.gradle.kts`. This is fixed; changing it would mean re-registering
  and would look like a different app to every device.
- **The release key's SHA-256** — whichever key you registered. A package name
  can hold **several** keys and further ones can be added at any time, so there
  is no need to track down an old key: generate a fresh keystore and add its
  fingerprint in the console.

If the APK's package name and signing key are not both registered, it is not
covered, and certified devices will refuse it once enforcement reaches them.

Record the fingerprint you actually ship with here once it is registered, so a
published APK can be checked against something written down:

| Key | SHA-256 | Registered |
|---|---|---|
| _(fill in after step 1)_ | | |

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
`3B:C9:71:20:15:5B:C3:E4:AC:D8:6D:BB:1E:21:35:57:7F:0B:68:9C:FE:10:D6:74:24:43:BC:A3:1C:C9:DD:6A`
— never register that one.

The release key is never committed. `.gitignore` blocks `*.keystore` and `*.jks`
(with an exception for the debug one), and CI materialises it from a secret.

## One-time setup

### 1. Create the release keystore

Do this even if an older key was registered at some point: adding a new key to an
existing package name is supported, and it is far less trouble than hunting down
a keystore whose whereabouts are unclear. A key you cannot locate is a key you
cannot ship with.

Run this yourself — choose and keep the passwords, they must not be shared:

```bash
keytool -genkeypair -v \
  -keystore release.keystore \
  -alias evcan-release \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=ev-open-can-tools, O=ev-open-can-tools, C=DE"
```

Read its fingerprint — this is the value you register in step 2 and note in the
table above:

```bash
keytool -list -v -keystore release.keystore -alias evcan-release | grep -A1 SHA256
```

**Back this file up somewhere safe.** Losing it means losing the ability to ship
updates that install over existing ones — there is no recovery.

### 2. Register it with Google

In the Play / Android Developer Console, open the registration for
`org.ev_open_can_tools.ev_can_app` and **Add key** with this keystore's SHA-256
fingerprint. If a key from an earlier attempt is already listed, leave it — a
package name may hold several, and the one you sign with is what counts.

Adding a key to a package name you already own means proving control of it by
uploading an APK signed with the new key, which the release workflow below
produces.

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
