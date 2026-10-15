#!/usr/bin/env bash
# Builds dist/dev.pollinations.ai2.aix.
#
# MIT App Inventor's extension template and its dependency jars are pulled from
# the official repositories, so nothing of theirs is vendored here.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
work="${TMPDIR:-/tmp}/pollinations-ai2-build"
ant="${ANT_HOME:-}"

for tool in git java ant; do
    command -v "$tool" >/dev/null 2>&1 || {
        echo "missing: $tool" >&2
        exit 1
    }
done

rm -rf "$work"
mkdir -p "$work"
git clone --depth 1 https://github.com/mit-cml/extension-template.git "$work/template"
git clone --depth 1 https://github.com/mit-cml/extension-deps.git "$work/template/lib/appinventor"

rm -rf "$work/template/src"
cp -r "$here/src" "$work/template/src"

cd "$work/template"
# The template builds with source level 7, so a JDK that still accepts it.
echo "sdk.dir=${ANDROID_HOME:-/opt/android-sdk}" > local.properties
ant extensions

cp out/dev.pollinations.ai2.aix "$here/dist/"
echo "built $here/dist/dev.pollinations.ai2.aix"
