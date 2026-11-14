import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.FileTree
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** Copies every module's shared contract JSON into the app assets as `contracts/<module>/`; `contracts/` stays the single source. */
@CacheableTask
abstract class CopyContractsTask : DefaultTask() {
    /** Root of the shared contracts; only the JSON files one level down are inputs (READMEs are ignored). */
    @get:Internal
    abstract val contractsDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val contractFiles: FileTree
        get() = contractsDir.asFileTree.matching { include("*/*.json") }

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun copy() {
        fs.sync {
            from(contractsDir) { include("*/*.json") }
            into(output.dir("contracts"))
        }
    }
}

val copyContracts = tasks.register<CopyContractsTask>("copyContracts") {
    contractsDir.set(rootProject.layout.projectDirectory.dir("contracts"))
}

android {
    namespace = "es.sebas1705.ilabs.demo"
    compileSdk = 37

    defaultConfig {
        applicationId = "es.sebas1705.ilabs.demo"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { compose = true }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyContracts, CopyContractsTask::output)
    }
}

dependencies {
    implementation(project(":modules:providers-kotlin"))
    implementation(project(":modules:structured-kotlin"))
    implementation(project(":modules:context-kotlin"))
    implementation(project(":modules:evals-kotlin"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
