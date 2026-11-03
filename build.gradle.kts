plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.vicander"
version = "0.1.0"

// Same inputs as build.sh: the locally installed IDE and the locally installed Elixir plugin.
//   IDE=/path/to/pycharm ELIXIR=/path/to/intellij-elixir ./gradlew buildPlugin
val ideDir = providers.environmentVariable("IDE")
    .orElse("${System.getProperty("user.home")}/apps/pycharm-2026.2.0.1")
val elixirDir = providers.environmentVariable("ELIXIR")
    .orElse("${System.getProperty("user.home")}/.local/share/JetBrains/PyCharm2026.2/intellij-elixir")

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        local(ideDir)
        localPlugin(elixirDir)
    }
}

tasks.withType<JavaCompile> {
    options.release = 21
    options.compilerArgs.addAll(listOf("-proc:none", "-nowarn"))
}

intellijPlatform {
    buildSearchableOptions = false // needs a headless IDE run; the plugin has no settings pages
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
}

// HandlerLogicTest is a dependency-free main() program (no JUnit), so run it as a JavaExec.
tasks.named("test") { enabled = false }
tasks.register<JavaExec>("logicTest") {
    group = "verification"
    description = "Runs the dependency-free HandlerLogicTest."
    // Handlers reference IDE classes, so (like test.sh) put the IDE and Elixir jars on the classpath.
    classpath = sourceSets["test"].runtimeClasspath +
        files(ideDir.map { fileTree("$it/lib") { include("**/*.jar") } }) +
        files(elixirDir.map { fileTree("$it/lib") { include("*.jar") } })
    mainClass = "io.vicander.idea.HandlerLogicTest"
}
tasks.named("check") { dependsOn("logicTest") }
