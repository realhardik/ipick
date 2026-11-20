#!/usr/bin/env bash
# Build the shell-side capture daemon to a classes.dex jar.
#
# ANDROID_PLATFORM must match the platform the DEVICE runs, not the app's
# minSdk. This code links @hide framework internals, so it has to be compiled
# against the same API level it will execute on. The app's minSdk 31 is a
# separate decision that happens to be a different number on purpose.
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-36}"
BUILD_TOOLS="${BUILD_TOOLS:-36.1.0}"

# Pin the compiler major for reproducible dex output.
JDK_MAJOR="${IPICK_JDK_MAJOR:-21}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_JAR="$SDK/platforms/android-$ANDROID_PLATFORM/android.jar"
D8="$SDK/build-tools/$BUILD_TOOLS/d8"
OUT="$HERE/build"
JAR="$HERE/ipick-capture.jar"

[ -f "$ANDROID_JAR" ] || { echo "missing $ANDROID_JAR" >&2; exit 1; }
[ -x "$D8" ] || { echo "missing $D8" >&2; exit 1; }

JAVAC=""
for candidate in "${IPICK_JAVA_HOME:+$IPICK_JAVA_HOME/bin/javac}" \
                 "${JAVA_HOME:+$JAVA_HOME/bin/javac}" \
                 "$(command -v javac || true)"; do
    [ -n "$candidate" ] && [ -x "$candidate" ] || continue
    if [[ "$("$candidate" --version 2>/dev/null)" == "javac $JDK_MAJOR."* ]]; then
        JAVAC="$candidate"
        break
    fi
    echo "skipping $candidate: $("$candidate" --version 2>/dev/null || echo unknown)" >&2
done
[ -n "$JAVAC" ] || {
    echo "no javac $JDK_MAJOR found. The daemon must be compiled by JDK $JDK_MAJOR - the" >&2
    echo "same major F-Droid and CI use - or the APK is not reproducible. Install one and" >&2
    echo "point IPICK_JAVA_HOME or JAVA_HOME at it." >&2
    exit 1
}

rm -rf "$OUT" "$JAR"
mkdir -p "$OUT/classes" "$OUT/gen/com/genymobile/scrcpy"


cat > "$OUT/gen/com/genymobile/scrcpy/BuildConfig.java" <<'EOF'
package com.genymobile.scrcpy;

public final class BuildConfig {
    public static final boolean DEBUG = false;

    private BuildConfig() {
        // not instantiable
    }
}
EOF

find "$HERE/src" "$OUT/gen" "$HERE/../app/src/main/java/app/ipick/voicemail/answering/GreetingCue.java" -name '*.java' > "$OUT/sources.txt"
echo "compiling $(wc -l < "$OUT/sources.txt" | tr -d ' ') files against android-$ANDROID_PLATFORM"

# The helper calls deprecated Android APIs for platform compatibility.
echo "javac: $JAVAC ($("$JAVAC" --version))"
"$JAVAC" --release 17 -nowarn -cp "$ANDROID_JAR" -d "$OUT/classes" "@$OUT/sources.txt"

# Let d8 write the jar so its timestamps are reproducible.
echo "dexing with build-tools $BUILD_TOOLS"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$D8" --lib "$ANDROID_JAR" --min-api 31 --output "$JAR" @"$OUT/classes.txt"

echo "built $JAR ($(wc -c < "$JAR" | tr -d ' ') bytes, sha256 $(shasum -a 256 "$JAR" | cut -c1-16)...)"
