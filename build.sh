#!/usr/bin/env bash
# Builds the plugin without Gradle: javac from the IDE's own runtime, against the IDE's jars
# and the Elixir plugin's jar. Output: build/vicander/ (a plugin folder to copy into the IDE's
# plugins directory).
#
#   IDE=/path/to/pycharm ELIXIR=/path/to/intellij-elixir/lib ./build.sh
set -euo pipefail

cd "$(dirname "$0")"
IDE="${IDE:-$HOME/apps/pycharm-2026.2.0.1}"
ELIXIR="${ELIXIR:-$HOME/.local/share/JetBrains/PyCharm2026.2/intellij-elixir/lib}"
JAVA_HOME_JBR="$IDE/jbr"

CLASSPATH="$(find "$IDE/lib" "$ELIXIR" -name '*.jar' | tr '\n' ':')"

rm -rf build
mkdir -p build/classes build/vicander/lib

"$JAVA_HOME_JBR/bin/javac" -proc:none -nowarn --release 21 \
  -cp "$CLASSPATH" -d build/classes $(find src -name '*.java')

cp -r resources/META-INF build/classes/
(cd build/classes && zip -qr ../vicander/lib/vicander.jar .)

echo "built build/vicander"
