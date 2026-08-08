# KiriKira fork automation

This fork tracks `mihonapp/mihon` `main` from the scheduled
`.github/workflows/sync-upstream-release.yml` workflow. It fetches the complete
upstream branch, stops on a merge conflict, preserves this fork's workflows,
and pushes only a successful merge to `main`.

Every successful sync builds both the regular and FOSS APKs. The upstream
`versionName` and `versionCode` are used as the base, while fork builds add the
monotonic suffix `-kiri.<git-commit-count>` and use
`upstreamVersionCode * 100000 + commitCount` as the Android `versionCode`.
Therefore a local feature commit can keep the same upstream major/minor/patch
version and still install as an upgrade. A changed upstream `versionName`
creates a stable release; a sync with the same upstream version creates a
prerelease. The push workflow also creates a same-core prerelease for local
changes.

## Actions and signing setup

The repository must allow GitHub Actions to write repository contents and
releases. Configure an `UPSTREAM_SYNC_TOKEN` Actions secret containing a
fine-scoped PAT with repository Contents and Workflows read/write permission.
The sync checkout uses this workflow-capable token because GitHub blocks a
regular `GITHUB_TOKEN` from pushing workflow-file changes. Branch protection
rules must also allow that bot to update `main`, or synchronization will stop
before the build job.

For a private production key, configure all four preferred repository secrets:

- `SIGNING_KEY`: base64-encoded PKCS#12 keystore
- `ALIAS`
- `KEY_STORE_PASSWORD`
- `KEY_PASSWORD`

The legacy `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEYSTORE_PASSWORD`, and `ANDROID_KEY_PASSWORD` names are also accepted
for compatibility. The workflow rejects partial secret sets and checks the
certificate against `.github/signing-cert.sha256` before signing and again
after signing each APK.

If no secrets are configured, the committed `.github/keystore/ci.p12` is used.
It is a fixed CI distribution key, not a confidential production key. Its
certificate is intentionally checked into the repository so every build uses
the same identity. Replacing or rotating the key changes the Android signing
certificate and existing installations cannot be upgraded in place; users must
uninstall before installing the new key's APK.

The generated APK embeds the fork repository and exact release tag, so its
in-app updater checks this fork rather than `mihonapp/mihon`. Fork builds query
the release list (including prereleases), so a newer same-core `-kiri.*` build
can be offered by the updater. Release assets include `SHA256SUMS` and the
certificate fingerprint in the release notes.
