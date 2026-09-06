package io.technoirlab.conventions.common.configuration

import io.technoirlab.gradle.dependencies.implementation
import org.gradle.api.HasImplicitReceiver
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.dsl.abi.BinariesSource
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.samWithReceiver.gradle.SamWithReceiverExtension

fun Project.configureKotlin(
    kotlinConfig: Provider<KotlinConfig> = provider { KotlinConfig.DEFAULT },
    enableAbiValidation: Provider<Boolean>,
) {
    extensions.configure(KotlinJvmProjectExtension::class) {
        compilerOptions {
            apiVersion.set(kotlinConfig.map { it.apiVersion })
            languageVersion.set(kotlinConfig.map { it.languageVersion })
            jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
            freeCompilerArgs.addAll(
                "-Xconsistent-data-class-copy-visibility",
                "-Xwarning-level=NOTHING_TO_INLINE:disabled",
            )
        }
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.plugin.sam.with.receiver") {
        extensions.configure(SamWithReceiverExtension::class) {
            annotation(checkNotNull(HasImplicitReceiver::class.qualifiedName))
        }
    }

    afterEvaluate {
        extensions.configure(KotlinProjectExtension::class) {
            coreLibrariesVersion = kotlinConfig.get().coreLibrariesVersion

            if (enableAbiValidation.get()) {
                @OptIn(ExperimentalAbiValidation::class)
                abiValidation {
                    binariesSource.set(BinariesSource.NON_TEST_COMPILATIONS)
                    tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME).configure {
                        dependsOn(checkTaskProvider)
                    }
                }
            }
        }
    }

    dependencies {
        val kotlinLibraries = kotlinConfig.map { KotlinLibraries(it.coreLibrariesVersion) }
        implementation(kotlinLibraries.map { platform(it.kotlinBom) })
        implementation(kotlinLibraries.map { platform(it.kotlinCoroutinesBom) })
        implementation(kotlinLibraries.map { platform(it.kotlinSerializationBom) })
    }
}
