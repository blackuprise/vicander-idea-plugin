#!/usr/bin/env bash
# Runs the dependency-free logic tests in test/ against the classes built by build.sh (which it
# runs first). Same environment as build.sh:
#
#   IDE=/path/to/pycharm ELIXIR=/path/to/intellij-elixir/lib ./test.sh
set -euo pipefail

cd "$(dirname "$0")"
IDE="${IDE:-$HOME/apps/pycharm-2026.2.0.1}"
ELIXIR="${ELIXIR:-$HOME/.local/share/JetBrains/PyCharm2026.2/intellij-elixir/lib}"
JBR="$IDE/jbr"

./build.sh

CLASSPATH="$(find "$IDE/lib" "$ELIXIR" -name '*.jar' | tr '\n' ':')"

rm -rf build/test-classes
mkdir -p build/test-classes

"$JBR/bin/javac" -proc:none -nowarn --release 21 \
  -cp "$CLASSPATH:build/classes" -d build/test-classes $(find src/test/java -name '*.java')

"$JBR/bin/java" -cp "build/test-classes:build/classes:$CLASSPATH" io.vicander.idea.HandlerLogicTest
