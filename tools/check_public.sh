#!/usr/bin/env bash
# check_public.sh: this repository is public. Fails when the tracked tree holds a recording, a model, a token, a
# private address or path, or anything of REAL-TSE (CLAUDE.md, "Never in this repository"). Run before every commit
# that adds files, and in CI.
set -euo pipefail
cd "$(dirname "$0")/.."
fail=0
files="$(git ls-files)"
media="$(grep -i -E '\.(wav|mp3|m4a|ogg|flac|pcm|raw|onnx|pt|pth|ckpt|bin|npy|npz|zip|aar|jks|keystore)$|token' <<<"$files" \
  | grep -v -x 'gradle/wrapper/gradle-wrapper.jar' || true)"
if [[ -n "$media" ]]; then echo "recordings, models, archives or tokens tracked:"; echo "$media"; fail=1; fi
# Private values: the tailnet, home paths, secrets.
private='100\.82\.|100\.[0-9]+\.[0-9]+\.[0-9]+:[0-9]|/home/[a-z]|/Users/[a-z]|@secrets|okimotus'
found="$(git grep -n -i -E "$private" -- . ':!tools/check_public.sh' || true)"
if [[ -n "$found" ]]; then echo "private values in the tree:"; echo "$found"; fail=1; fi
# REAL-TSE: its checkpoint, its source, its fetch. Only CLAUDE.md names it, to forbid it.
realtse='tfmap_context|drive\.google|realtse|real-tse|real_tse'
found="$(git grep -n -i -E "$realtse" -- . ':!tools/check_public.sh' ':!CLAUDE.md' || true)"
if [[ -n "$found" ]]; then echo "REAL-TSE in the tree:"; echo "$found"; fail=1; fi
if (( fail )); then echo "check_public: not clean" >&2; exit 1; fi
echo "check_public: clean ($(wc -l <<<"$files") tracked files)"
