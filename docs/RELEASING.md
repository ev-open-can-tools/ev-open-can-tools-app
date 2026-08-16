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

The fingerprint that releases are signed with, so a published APK can be checked
against something written down:

| Key | SHA-256 |
|---|---|
| `evcan-release` (created 2026-08-16, RSA 4096, valid 10000 days) | `26:FC:83:7C:A9:DA:70:3A:F7:DD:4B:8F:32:E7:77:80:4A:1D:C6:00:20:70:5E:FC:58:86:67:5B:7E:E4:9C:AD` |

Only the fingerprint is written down — it is public information, printed by
`apksigner` out of every APK. The keystore and its password are not in this
repository and must not be.

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

Do this once. Everything below runs in **your own terminal** — the passwords must
not pass through anyone else's hands, including a shell someone else is driving.

### 1. Create the release keystore

Do this even if an older key was registered at some point: adding a new key to an
existing package name is supported, and it is far less trouble than hunting down
a keystore whose whereabouts are unclear. A key you cannot locate is a key you
cannot ship with.

Keep it **outside the repository**. `.gitignore` would stop it being committed,
but a `git clean -xdf` deletes ignored files too, and that would be unrecoverable:

```bash
mkdir -p ~/keys && chmod 700 ~/keys

~/.jdks/jdk-21.0.12+8/bin/keytool -genkeypair -v \
  -keystore ~/keys/evcan-release.keystore \
  -storetype PKCS12 \
  -alias evcan-release \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=ev-open-can-tools, O=ev-open-can-tools, C=DE"
```

`keytool` prompts for the password. **Do not** pass `-storepass` on the command
line — it would be written to your shell history in plain text.

PKCS12 uses one password for both the store and the key, so `EVCAN_KEY_PASSWORD`
and `EVCAN_KEYSTORE_PASSWORD` below are the same value.

Lock the file down and read its fingerprint:

```bash
chmod 600 ~/keys/evcan-release.keystore

~/.jdks/jdk-21.0.12+8/bin/keytool -list -v \
  -keystore ~/keys/evcan-release.keystore \
  -alias evcan-release | grep -A1 SHA256
```

**Back up the file and the password now**, before going further — a password
manager entry with the keystore attached is the right home for both. Losing
either means losing the ability to ship an update that installs over an existing
version. There is no recovery path; the only remedy is a new package name.

### 2. Register the key with Google

In the Play / Android Developer Console, open the registration for
`org.ev_open_can_tools.ev_can_app` and choose **Add key**, using the SHA-256 from
the previous step. If a key from an earlier attempt is already listed, leave it —
a package name may hold several, and the one you actually sign with is what
counts.

Proving control of an already-registered package name means uploading an APK
signed with the new key. The release workflow produces exactly that, so it is
fine to do step 4 first and take the APK from the resulting GitHub Release.

This step needs a Google account and a government-issued ID. It cannot be
automated.

### 3. Store the key in GitHub

Four repository secrets. Run these in your own terminal, from the repo:

```bash
cd /home/alex/Games/claude/ev-open-can-tools-app

# The keystore itself, base64-encoded, straight from the file — never printed.
base64 -w0 ~/keys/evcan-release.keystore | gh secret set EVCAN_KEYSTORE_BASE64

# The alias is not secret, but keeping it here means the workflow needs no edit.
printf 'evcan-release' | gh secret set EVCAN_KEY_ALIAS

# These two prompt for the password; nothing lands in your shell history.
gh secret set EVCAN_KEYSTORE_PASSWORD
gh secret set EVCAN_KEY_PASSWORD
```

Prefer the web UI? *Settings → Secrets and variables → Actions → New repository
secret*, same four names. For the base64 value, generate it with
`base64 -w0 ~/keys/evcan-release.keystore` and paste the output.

Confirm all four exist:

```bash
gh secret list
```

GitHub stores secrets encrypted and will not show them again, so they survive
indefinitely — but they are also not a backup. Keep step 1's copy.

Secrets are not exposed to workflows triggered by pull requests from forks, which
is fine here: releases are cut from tags on this repository.

### 4. Cut the first release

See below. Afterwards, check the workflow's job summary: it prints the package
name and certificate fingerprint of the APK it just built. Both must match what
you registered.

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

The printed SHA-256 and package name must match what you registered.

**Note the formatting difference:** `keytool` prints the fingerprint as
colon-separated uppercase (`2F:F0:34:…`), `apksigner` as unbroken lowercase
(`2ff034…`). Same value. To compare them directly:

```bash
apksigner verify --print-certs app.apk \\
  | grep -i 'SHA-256 digest' \\
  | tr -d ' ' | cut -d: -f2 \\
  | sed 's/../&:/g;s/:$//' | tr 'a-f' 'A-F'
```

## Sources

- [Register on Android Developer Console](https://developer.android.com/developer-verification/guides/android-developer-console)
- [Registering Android package names](https://support.google.com/googleplay/android-developer/answer/16761053)
- [Select key for existing package name](https://support.google.com/googleplay/android-developer/answer/16762143)
