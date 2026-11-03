# Vicander IDEA Plugin

Navigation for the [Vicander](https://github.com/vicander) DDD framework in JetBrains IDEs
(requires the [Elixir plugin](https://github.com/KronicDeth/intellij-elixir)).

## Features

Ctrl+click (go to declaration) on:

- a call to an operation → jumps to the `defop` that defines it
- a repository call → jumps to the repository definition
- `command.field` → jumps to the `field :field` that declares it

## Requirements

- PyCharm / IntelliJ-based IDE, build 262+ (2026.2)
- Elixir plugin (`org.elixir_lang`)
- A JDK 21 to run Gradle (the IDE's bundled JBR works)

## Build

By default the build uses a locally installed IDE and Elixir plugin:

| Variable | Default |
|----------|---------|
| `IDE`    | `~/apps/pycharm-2026.2.0.1` |
| `ELIXIR` | `~/.local/share/JetBrains/PyCharm2026.2/intellij-elixir` |

```bash
export JAVA_HOME=$IDE/jbr          # or any JDK 21
./gradlew buildPlugin              # → build/distributions/vicander-idea-plugin-0.1.0.zip
./gradlew check                    # compile + run HandlerLogicTest
```

Install the zip via **Settings → Plugins → ⚙ → Install Plugin from Disk…**.

### Without Gradle

`./build.sh` compiles with the IDE's `javac` into `build/vicander/` (a plugin folder to copy
into the IDE's plugins directory), and `./test.sh` builds and runs the logic tests.

## Layout

```
src/main/java/       plugin sources (io.vicander.idea)
src/main/resources/  META-INF/plugin.xml
src/test/java/       HandlerLogicTest — dependency-free tests for the pure logic
```

The PSI-driven navigation itself needs a running IDE with the Elixir plugin and is not covered
by the automated tests.
