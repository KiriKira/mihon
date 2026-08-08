# KiriKira fork automation

This fork keeps its own commits on `main` and periodically merges
`mihonapp/mihon:main`. The merge is prepared on a temporary branch, checked by
format/tests/a release build, and then fast-forwarded into `main`. A conflict or
a concurrent change to `main` stops the run without overwriting either branch.

## Versioning

The upstream `app/build.gradle.kts` `versionName` and `versionCode` are the
source version. The workflow gives every fork build a deterministic revision
based on the full commit count:

```text
upstream: 0.20.4 / 29
fork:     0.20.4-kiri.<commit-count> / (29 * 100000 + <commit-count>)
tag:      v0.20.4-kiri.<commit-count>
```

The injected values are used only by CI, so upstream version lines remain easy
to merge. A larger upstream version gets a stable GitHub Release when the sync
workflow dispatches `fork-release.yml` in `auto` mode. A local feature release
can keep the same upstream version and still be a strictly newer Android
`versionCode`: run `Fork build and release` manually with `release_mode=local`.
Use `prerelease` only for a build that should not be returned by GitHub's
`/releases/latest` endpoint. `build` uploads an artifact without publishing a
Release.

The updater is compiled with the fork repository and exact fork tag, so a
published fork Release is checked instead of `mihonapp/mihon`. Pre-releases are
not considered by the upstream app updater.

## Signing and upgrade compatibility

Every release APK is verified with the certificate fingerprint in
`.github/signing-cert.sha256` before it can be uploaded. The current fingerprint
is:

```text
AB:AF:13:5B:C4:93:6B:3C:B4:1E:93:09:A8:7D:D7:F2:68:91:6B:D7:63:76:0A:E2:8D:C4:7C:2C:04:46:E0:0E
```

The repository contains the existing CI PKCS#12 keystore at
`.github/keystore/ci.p12` as a continuity fallback (`mihon-ci` / `android`).
This key is public because it is in Git history and must not be treated as a
production secret. To use a private copy while preserving upgrade ability,
configure all four preferred Actions secrets with the *same certificate*:

| Secret | Value |
| --- | --- |
| `SIGNING_KEY` | Base64 of the PKCS#12 keystore |
| `ALIAS` | Keystore key alias |
| `KEY_STORE_PASSWORD` | Keystore password |
| `KEY_PASSWORD` | Key password |

The legacy names `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEYSTORE_PASSWORD`, and `ANDROID_KEY_PASSWORD` are also accepted as a
complete set. A partial set fails, and a certificate different from the
fingerprint file fails. This is intentional: Android cannot install an APK
over an installed APK signed by a different certificate. Rotating the key
requires deliberately changing the fingerprint and accepting that users of
the old certificate must uninstall/migrate first.

The workflow publishes `SHA256SUMS` and `SIGNING_CERT_SHA256` beside every APK.
Passwords and keystore material are never printed to the log.

## Repository settings

Enable Actions with read/write workflow permissions for the repository. The
sync workflow needs to push a validated fast-forward to `main` and dispatch the
signed build workflow. If `main` is protected, its rules must allow the Actions
token to perform that fast-forward, or the `promote` job must be adapted to the
repository's required review/merge queue.

The schedule is daily at 03:17 UTC and can be started with `workflow_dispatch`.
If an upstream merge conflicts, resolve it in a normal branch, merge it into
`main`, and rerun the workflow. The temporary `automation/upstream-sync-*`
branch is only created after a clean merge and is deleted after promotion.
