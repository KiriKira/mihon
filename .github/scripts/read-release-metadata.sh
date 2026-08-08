#!/usr/bin/env bash

set -euo pipefail

: "${GITHUB_OUTPUT:?GITHUB_OUTPUT must point to a writable output file}"

version_name="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' app/build.gradle.kts | head -n 1)"
version_code="$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' app/build.gradle.kts | head -n 1)"

if [[ -z "$version_name" || -z "$version_code" ]]; then
  echo "Could not find app versionName/versionCode in app/build.gradle.kts." >&2
  exit 1
fi

if [[ ! "$version_name" =~ ^[0-9]+(\.[0-9]+)+([A-Za-z][0-9A-Za-z.-]*)?$ ]]; then
  echo "Unsupported upstream versionName: $version_name" >&2
  exit 1
fi

commit_count="$(git rev-list --count HEAD)"
fork_version_code=$((version_code * 100000 + commit_count))
if (( fork_version_code > 2100000000 )); then
  echo "Computed Android versionCode $fork_version_code exceeds the Android limit." >&2
  exit 1
fi

fork_version_name="${version_name}-kiri.${commit_count}"
tag="v${fork_version_name}"
source_commit="$(git rev-parse HEAD)"

{
  echo "base_version=$version_name"
  echo "base_version_code=$version_code"
  echo "fork_revision=$commit_count"
  echo "fork_version_name=$fork_version_name"
  echo "fork_version_code=$fork_version_code"
  echo "tag=$tag"
  echo "source_commit=$source_commit"
} >> "$GITHUB_OUTPUT"

cat <<EOF >> "$GITHUB_STEP_SUMMARY"
### Fork release metadata

| Field | Value |
| --- | --- |
| Upstream version | \`$version_name\` |
| Upstream versionCode | \`$version_code\` |
| Fork version | \`$fork_version_name\` |
| Fork versionCode | \`$fork_version_code\` |
| Source commit | \`$source_commit\` |
| Release tag | \`$tag\` |
EOF
