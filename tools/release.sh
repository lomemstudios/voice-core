#!/usr/bin/env bash
# release.sh: builds the release of this checkout as a file-Maven zip and prints its SHA-256. Nothing is uploaded.
#
#   tools/release.sh            → build/release/voice-core-<version>-maven.zip
#
# The zip holds a Maven repository: com.lomemstudios.voice:core (AAR, POM, sources), with LICENSE and NOTICE at the
# top. A consumer unzips it into a folder and adds that folder as a
# maven { url } repository.
#
# Publishing is a separate step the owner takes (CLAUDE.md, "Releases and pinning"): tag v<version> on a clean main,
# then `gh release create v<version> build/release/voice-core-<version>-maven.zip --notes-file <notes>`. Consumers
# pin the version and this SHA-256.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "$here"
version="$(sed -n 's/^voiceCoreVersion=//p' gradle.properties)"
out="build/release"
repo="$out/maven"

rm -rf "$out"
./gradlew --quiet test publishAllPublicationsToReleaseRepository
cp LICENSE NOTICE "$repo/"
# maven-metadata.xml only serves version ranges and carries the build time; consumers pin one version.
find "$repo" -name 'maven-metadata.xml*' -delete
zip="voice-core-$version-maven.zip"
# Fixed times and order, so the same tree gives the same zip.
find "$repo" -exec touch -h -d '2026-01-01T00:00:00Z' {} +
(cd "$repo" && find . -type f | LC_ALL=C sort | zip -q -X -D "../$zip" -@)
sha="$(sha256sum "$out/$zip" | cut -d' ' -f1)"
echo "$out/$zip"
echo "sha256 $sha"
