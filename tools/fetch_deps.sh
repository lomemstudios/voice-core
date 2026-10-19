#!/usr/bin/env bash
# fetch_deps.sh: puts the third-party libraries that are on no public Maven repository into a local Maven directory,
# pinned by SHA-256: VOICE_CORE_DEPS, else ~/.cache/voice-core/deps. Never into the repo.
#
#   sherpa-onnx 1.13.8 (k2-fsa, Apache 2.0), the Android AAR with ONNX Runtime (MIT) linked in, republished unchanged
#   as com.lomemstudios.voice.deps:sherpa-onnx-static-link-onnxruntime:1.13.8
#
# A consumer that builds voice-core from a checkout (Claude Voice Next with VOICE_CORE_DIR) reads the same directory.
set -euo pipefail
dest="${VOICE_CORE_DEPS:-$HOME/.cache/voice-core/deps}"
group=com/lomemstudios/voice/deps
name=sherpa-onnx-static-link-onnxruntime
version=1.13.8
sha=b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471
url="https://github.com/k2-fsa/sherpa-onnx/releases/download/v$version/$name-$version.aar"

dir="$dest/$group/$name/$version"
aar="$dir/$name-$version.aar"
mkdir -p "$dir"
if ! { [[ -f "$aar" ]] && echo "$sha  $aar" | sha256sum -c --status; }; then
  # The same file Claude Voice Next's fetch_voiceprint.sh keeps, when it is there and intact.
  cached="$HOME/.cache/claude-voice/voiceprint/$name-$version.aar"
  if [[ -f "$cached" ]] && echo "$sha  $cached" | sha256sum -c --status; then
    cp "$cached" "$aar.part"
  else
    curl -fsSL -o "$aar.part" "$url"
  fi
  echo "$sha  $aar.part" | sha256sum -c --quiet
  mv "$aar.part" "$aar"
fi
cat >"$dir/$name-$version.pom" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.lomemstudios.voice.deps</groupId>
  <artifactId>$name</artifactId>
  <version>$version</version>
  <packaging>aar</packaging>
  <name>sherpa-onnx $version (k2-fsa), republished unchanged</name>
  <url>$url</url>
  <licenses>
    <license><name>Apache-2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0</url></license>
  </licenses>
</project>
POM
echo "sherpa-onnx $version in $dir"
