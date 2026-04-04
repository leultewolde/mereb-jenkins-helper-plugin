#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
build_file="$repo_root/build.gradle.kts"

current_version="$(sed -n 's/^version = "\([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\)"$/\1/p' "$build_file")"

if [[ -z "$current_version" ]]; then
  echo "Unable to find version = \"X.Y.Z\" in $build_file" >&2
  exit 1
fi

IFS='.' read -r major minor patch <<< "$current_version"
next_version="${major}.${minor}.$((patch + 1))"

perl -0pi -e 's/^version = "\Q'"$current_version"'\E"$/version = "'"$next_version"'"/m' "$build_file"

echo "$next_version"
