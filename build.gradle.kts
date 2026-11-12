plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.application) apply false
}

// Aggregate: runs the JVM tests of every Kotlin module (:modules:<name>-kotlin).
// Convention: every :modules:*-kotlin project with a JVM target is included.
// Projects without a jvmTest task are skipped, and new modules are picked up automatically.
// Task paths are plain strings resolved when the task graph is built,
// so no other project's tasks are realized eagerly.
tasks.register("jvmContractTests") {
    group = "verification"
    description = "Runs the JVM tests of every Kotlin module under :modules."
    dependsOn(
        provider {
            subprojects
                .filter { it.path.startsWith(":modules:") && it.name.endsWith("-kotlin") }
                .filter { "jvmTest" in it.tasks.names }
                .map { "${it.path}:jvmTest" }
        },
    )
}
