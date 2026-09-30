#!/usr/bin/env bash
# One command to build and run the Meridian dispute-intake prototype.
#   ./run.sh          build + start all services, demo console at http://localhost:8080
#   ./run.sh demo     build + scripted end-to-end walkthrough in the terminal
#   ./run.sh test     build + self-checks (exit code 0 = all passed)
# Only requirement: a JDK 17 or newer (no Maven/Gradle, no internet, no third-party libraries).
set -euo pipefail
cd "$(dirname "$0")"

fail_no_jdk() {
  cat >&2 <<'EOF'

  A Java Development Kit (JDK) 17 or newer is required, and none was found.

  macOS (Homebrew):   brew install --cask temurin@17
  macOS / Linux:      curl -s "https://get.sdkman.io" | bash, then: sdk install java   (any 17+)
  Ubuntu / Debian:    sudo apt-get install -y openjdk-17-jdk
  Windows:            winget install EclipseAdoptium.Temurin.17.JDK   (then run from Git Bash or WSL)

  Open a new terminal afterwards and check with:  java -version  &&  javac -version
EOF
  exit 1
}

# Prefer JAVA_HOME if set, otherwise whatever is on PATH.
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/javac" ]]; then
  JAVA="$JAVA_HOME/bin/java"; JAVAC="$JAVA_HOME/bin/javac"
else
  JAVA="$(command -v java || true)"; JAVAC="$(command -v javac || true)"
fi
[[ -z "$JAVA" || -z "$JAVAC" ]] && fail_no_jdk
# On macOS /usr/bin/javac exists as a stub even without a JDK, so actually run it.
JAVAC_VERSION="$("$JAVAC" -version 2>&1 | grep -Eo 'javac [0-9]+' | grep -Eo '[0-9]+' || true)"
[[ -z "$JAVAC_VERSION" ]] && fail_no_jdk
if (( JAVAC_VERSION < 17 )); then
  echo "Found javac $JAVAC_VERSION, but Java 17+ is required." >&2
  fail_no_jdk
fi

echo "› Building with javac $JAVAC_VERSION …"
rm -rf build && mkdir -p build/classes
find src/main/java -name '*.java' > build/sources.txt
"$JAVAC" --release 17 -encoding UTF-8 -d build/classes @build/sources.txt
cp -R src/main/resources/. build/classes/

MODE="${1:-serve}"
echo "› Starting ($MODE) …"
exec "$JAVA" -Dfile.encoding=UTF-8 -cp build/classes com.meridian.poc.App "$MODE"
