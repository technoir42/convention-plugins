package io.technoirlab.conventions.kotlin.multiplatform.configuration

import io.technoirlab.conventions.common.configuration.KotlinConfig
import io.technoirlab.conventions.common.configuration.KotlinLibraries
import io.technoirlab.conventions.kotlin.multiplatform.api.KotlinMultiplatformExtension
import io.technoirlab.core.capitalized
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.invoke
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.jetbrains.kotlin.gradle.dsl.HasConfigurableKotlinCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinNativeTargetConfigurator.Companion.RUN_GROUP
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.Executable
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinAndroidTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinWasmJsTargetDsl
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.HostManager
import kotlin.io.path.Path
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension as KmpExtension

internal fun Project.configureKotlinMultiplatform(
    config: KotlinMultiplatformExtension,
    kotlinConfig: Provider<KotlinConfig> = provider { KotlinConfig.DEFAULT },
    executable: Boolean = false,
) {
    extensions.configure(KmpExtension::class) {
        applyDefaultHierarchyTemplate()

        compilerOptions {
            apiVersion.set(kotlinConfig.map { it.apiVersion })
            languageVersion.set(kotlinConfig.map { it.languageVersion })
            freeCompilerArgs.addAll(
                "-Xconsistent-data-class-copy-visibility",
                "-Xexpect-actual-classes",
                "-Xwarning-level=NOTHING_TO_INLINE:disabled",
            )
        }

        targets.configureEach {
            when (this) {
                is KotlinAndroidTarget -> configureJvmTarget()
                is KotlinJvmTarget -> configureJvmTarget()
                is KotlinNativeTarget -> configureNativeTarget(config.packageName, config.buildFeatures.cinterop, executable)
                is KotlinWasmJsTargetDsl -> configureJsTarget(executable)
            }
        }

        sourceSets {
            commonMain.dependencies {
                val kotlinLibraries = kotlinConfig.map { KotlinLibraries(it.coreLibrariesVersion) }
                implementation(kotlinLibraries.map { dependencies.platform(it.kotlinBom) })
                implementation(kotlinLibraries.map { dependencies.platform(it.kotlinCoroutinesBom) })
                implementation(kotlinLibraries.map { dependencies.platform(it.kotlinSerializationBom) })
            }
        }
    }

    afterEvaluate {
        extensions.configure(KmpExtension::class) {
            coreLibrariesVersion = kotlinConfig.get().coreLibrariesVersion

            if (config.buildFeatures.abiValidation.get()) {
                @OptIn(ExperimentalAbiValidation::class)
                abiValidation {
                    tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME).configure {
                        dependsOn(checkTaskProvider)
                    }
                }
            }
        }
    }
}

private fun <T> T.configureJvmTarget() where T : KotlinTarget, T : HasConfigurableKotlinCompilerOptions<KotlinJvmCompilerOptions> {
    compilerOptions {
        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
    }
}

private fun KotlinNativeTarget.configureNativeTarget(
    packageName: Provider<String>,
    enableCInterop: Property<Boolean>,
    executable: Boolean,
) {
    if (enableCInterop.get()) {
        compilations.named(KotlinCompilation.MAIN_COMPILATION_NAME) {
            cinterops.register(project.name) {
                packageName(packageName.get())
                val srcPath = Path("src", "nativeInterop", "cinterop")
                compilerOpts("-I$srcPath")
            }
        }
    }

    binaries {
        if (konanTarget.family in setOf(Family.IOS, Family.TVOS, Family.WATCHOS)) {
            framework {
                isStatic = true
                baseName = project.name
            }
        } else if (executable) {
            executable {
                if (packageName.isPresent) {
                    entryPoint = "${packageName.get()}.main"
                }
            }
        }
        configureEach {
            if (buildType == NativeBuildType.DEBUG) {
                freeCompilerArgs = freeCompilerArgs + "-ea"
            }
            if (konanTarget.family == Family.ANDROID) {
                linkerOpts("-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384")
            }
            if (this is Executable && HostManager.host == konanTarget) {
                val executableName = name
                project.tasks.register("run${executableName.capitalized()}") {
                    group = RUN_GROUP
                    description = "Executes Kotlin/Native executable $executableName for target ${target.name}"
                    dependsOn(runTaskName!!)
                }
            }
        }
    }
}

private fun KotlinWasmJsTargetDsl.configureJsTarget(executable: Boolean) {
    if (executable) {
        binaries.executable()
    }
}
