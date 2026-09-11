buildscript {
    dependencies {
        classpath(libs.kotlin.gradle)
    }
}

plugins {
    alias(libs.plugins.aboutLibraries) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineProfile) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.kotlin.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.moko.resources) apply false
    alias(libs.plugins.sqldelight) apply false

    alias(mihonx.plugins.spotless)
}

// The archived arkon/FlexibleAdapter fork is no longer reliably available from
// JitPack. The upstream project republished its maintained 5.1.0 artifact to
// Maven Central in 2026. Mihon only consumes the core flexible-adapter module,
// while the arkon fork's final commit only adds a missing dependency to the
// separate livedata module, so substitute the stable Central artifact here.
subprojects {
    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("com.github.arkon.FlexibleAdapter:flexible-adapter"))
                .using(module("eu.davidea:flexible-adapter:5.1.0"))
                .because("the archived JitPack fork is no longer resolvable")
        }
    }
}

val buildLogic: IncludedBuild = gradle.includedBuild("build-logic")
tasks {
    listOf("clean", "spotlessApply", "spotlessCheck").forEach { task ->
        named(task) {
            dependsOn(buildLogic.task(":$task"))
        }
    }
}
