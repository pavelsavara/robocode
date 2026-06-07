plugins {
    id("net.sf.robocode.java-conventions")
    `java-library`
}

dependencies {
    implementation(project(":robocode.api"))
    implementation(project(":robocode.battle"))
    implementation(project(":robocode.core"))

    testImplementation(testLibs.junit)

    // Phase 0 ground-truth capture harness drives a live battle through the
    // engine and loads the sample robots from ../.sandbox/test-robots.
    testImplementation(project(":robocode.core"))
    testImplementation(project(":robocode.host"))
    testImplementation(project(":robocode.battle"))
    testImplementation(project(":robocode.tests.robots"))
}

description = "Robocode Déjàvu"

java {
    withJavadocJar()
    withSourcesJar()
}

// Stage the external packaged competitive robots (the rumble top-50, newer Java
// bytecode) into the same ../.sandbox/test-robots directory the sample robots
// are staged into, so the fidelity gate can drive each one as a hero. The jars
// live outside the repo; the location is overridable via the `battleStageDir`
// project/system property and the task no-ops when the directory is absent so it
// never breaks a build that lacks it.
val battleStageDir = (findProperty("battleStageDir") as String?)
    ?: System.getProperty("battleStageDir")
    ?: "d:/robocode-autopilot/pipeline/build/battle-stage"

val stageTopRobots by tasks.registering(Copy::class) {
    // Run after the sample robots are staged so the shared sandbox is populated
    // first; deleting robot.database forces the engine to rescan and pick up the
    // newly added jars.
    dependsOn(":robocode.tests.robots:jar")
    val dir = file(battleStageDir)
    onlyIf { dir.isDirectory }
    from(dir) { include("*.jar") }
    into("../.sandbox/test-robots")
    doFirst {
        delete("../.sandbox/test-robots/robot.database")
    }
}

tasks.test {
    dependsOn(stageTopRobots)

    // Run the engine on JDK 21 so robots compiled for newer class-file versions
    // (e.g. Java 12+) can be loaded. The engine compiles to the Java 8 baseline
    // but runs on the modern JVM; see URLJarCollector for the JDK 9+ strong-
    // encapsulation fallback that keeps engine start-up working without
    // --add-opens.
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })

    // Run the engine in debugging mode so robot threads are given an unbounded
    // per-turn budget instead of the real-time CPU-constant budget. This removes
    // skipped-turn races (which otherwise make end-of-round event capture
    // non-deterministic) and lets the dying hero always read out its death-turn
    // event queue, so the captured ground truth is reproducible.
    systemProperty("debug", "true")
}
