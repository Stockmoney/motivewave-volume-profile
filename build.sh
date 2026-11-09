#!/usr/bin/env bash
# Builds build/VolumeProfile.jar from the sources and runs the tests.
#
#   bash build.sh            build + tests
#   bash build.sh install    build + tests, then copy into your "MotiveWave Extensions" folder
#
# Needs a JDK (17+; compiled for Java 21 bytecode). Stage 1 (the calculation core) does not need MotiveWave;
# the study itself needs the MotiveWave SDK jar and the JavaFX jars of your installation. Override with:
#   MW_SDK   path to mwave_sdk.jar   (default: macOS app bundle)
#   MW_EXT   extensions folder       (default: ~/MotiveWave Extensions)
#   JAVA_HOME
set -euo pipefail
cd "$(dirname "$0")"

SDK="${MW_SDK:-/Applications/MotiveWave.app/Contents/Java/mwave_sdk.jar}"
EXT="${MW_EXT:-$HOME/MotiveWave Extensions}"

JAVAC=""; JAR=""; JAVA=""
candidates=()
[[ -n "${JAVA_HOME:-}" ]] && candidates+=("$JAVA_HOME/bin")
if [[ -x /usr/libexec/java_home ]]; then
  jh="$(/usr/libexec/java_home 2>/dev/null || true)"; [[ -n "$jh" ]] && candidates+=("$jh/bin")
fi
candidates+=(/opt/homebrew/opt/openjdk/bin /usr/local/opt/openjdk/bin)
if command -v javac >/dev/null 2>&1; then candidates+=("$(dirname "$(command -v javac)")"); fi
for d in "${candidates[@]}"; do
  if [[ -x "$d/javac" && -x "$d/jar" && -x "$d/java" ]] && "$d/javac" -version >/dev/null 2>&1; then
    JAVAC="$d/javac"; JAR="$d/jar"; JAVA="$d/java"; break
  fi
done
[[ -n "$JAVAC" ]] || { echo "No working JDK found. Install one (e.g. 'brew install openjdk') or set JAVA_HOME." >&2; exit 1; }

rm -rf build && mkdir -p build/classes build/test-classes

# The core is plain Java; the study (volume_profile/*.java outside core/) needs the SDK and JavaFX.
CORE_SRC=$(find volume_profile/core -name '*.java')
STUDY_SRC=$(find volume_profile -maxdepth 1 -name '*.java' || true)
OTHER_SRC=$(find volume_profile -mindepth 2 -name '*.java' -not -path 'volume_profile/core/*' || true)

# shellcheck disable=SC2086
"$JAVAC" --release 21 -encoding UTF-8 -Xlint:all -d build/classes $CORE_SRC

if [[ -n "$STUDY_SRC$OTHER_SRC" ]]; then
  [[ -f "$SDK" ]] || { echo "MotiveWave SDK jar not found: $SDK (set MW_SDK)" >&2; exit 1; }
  FX="$(dirname "$SDK")/../javafx"
  CP="build/classes:$SDK"; for j in "$FX"/javafx.*.jar; do [[ -f "$j" ]] && CP="$CP:$j"; done
  # shellcheck disable=SC2086
  "$JAVAC" --release 21 -encoding UTF-8 -Xlint:all,-auxiliaryclass -cp "$CP" -d build/classes $STUDY_SRC $OTHER_SRC
  [[ -d volume_profile/nls ]] && { mkdir -p build/classes/volume_profile/nls; cp volume_profile/nls/*.properties build/classes/volume_profile/nls/; }
fi

# shellcheck disable=SC2046
"$JAVAC" --release 21 -encoding UTF-8 -Xlint:all -cp build/classes -d build/test-classes $(find test -name '*.java')
"$JAVA" -cp build/classes:build/test-classes volume_profile.Tests

"$JAR" cf build/VolumeProfile.jar -C build/classes .
echo "built build/VolumeProfile.jar"

if [[ "${1:-}" == "install" ]]; then
  mkdir -p "$EXT"
  # A new file name each time: a running MotiveWave caches an open jar by path.
  rm -f "$EXT"/VolumeProfile*.jar
  cp build/VolumeProfile.jar "$EXT/VolumeProfile-$(date +%s).jar"
  touch "$EXT/.last_updated"   # MotiveWave rescans the folder when this file changes
  echo "installed into: $EXT"
fi
