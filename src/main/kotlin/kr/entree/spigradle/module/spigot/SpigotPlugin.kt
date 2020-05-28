package kr.entree.spigradle.module.spigot

import com.fasterxml.jackson.module.kotlin.readValue
import kr.entree.spigradle.data.Load
import kr.entree.spigradle.data.SpigotRepositories
import kr.entree.spigradle.internal.Groovies
import kr.entree.spigradle.module.common.Download
import kr.entree.spigradle.module.common.SpigradlePlugin
import kr.entree.spigradle.module.common.applySpigradlePlugin
import kr.entree.spigradle.module.common.setupDescGenTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.*
import java.io.File

/**
 * Created by JunHyung Lim on 2020-04-28
 */
class SpigotPlugin : Plugin<Project> {
    companion object {
        const val DESC_GEN_TASK_NAME = "generateSpigotDescription"
        const val MAIN_DETECTION_TASK_NAME = "detectSpigotMain"
        const val EXTENSION_NAME = "spigot"
        const val DESC_FILE_NAME = "plugin.yml"
        const val PLUGIN_SUPER_CLASS = "org/bukkit/plugin/java/JavaPlugin"
        const val BUILD_TOOLS_URL = "https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar"
    }

    override fun apply(project: Project) {
        with(project) {
            applySpigradlePlugin()
            setupDefaultRepositories()
            setupDescGenTask<SpigotDescription>(
                    EXTENSION_NAME,
                    DESC_GEN_TASK_NAME,
                    MAIN_DETECTION_TASK_NAME,
                    DESC_FILE_NAME,
                    PLUGIN_SUPER_CLASS
            )
            setupGroovyExtensions()
            setupDebugTasks()
        }
    }

    private fun Project.setupDefaultRepositories() {
        SpigotRepositories.run {
            listOf(SPIGOT_MC, PAPER_MC)
        }.forEach {
            repositories.maven(it)
        }
    }

    private fun Project.setupGroovyExtensions() {
        Groovies.getExtensionFrom(extensions.getByName(EXTENSION_NAME)).apply {
            set("POST_WORLD", Load.POST_WORLD)
            set("STARTUP", Load.STARTUP)
        }
    }

    private fun Project.setupDebugTasks() {
        // downloadBuildTools -> buildSpigot -> copySpigot -> runSpigot -> copyPlugins(TO-DO)
        // prepareSpigot, runSpigot
        val debugOption = extensions.getByName<SpigotDescription>("spigot").debug
        val downloadSpigotBuildTools by tasks.creating(Download::class) {
            description = "Download the BuildTools."
            source = BUILD_TOOLS_URL
            destination = debugOption.buildToolJar
            dependsOn(tasks.getByName("idea"))
        }
        val buildSpigot by tasks.creating(JavaExec::class) {
            group = "spigradle"
            description = "Build the spigot.jar using the BuildTools."
            doFirst {
                args(
                        "--rev", debugOption.buildVersion,
                        "--output-dir", debugOption.spigotDirectory
                )
            }
        }
        val prepareSpigot by tasks.creating {
            group = "spigradle"
            description = "Copy the spigot.jar generated by BuildTools into the given path."
            mustRunAfter(downloadSpigotBuildTools, buildSpigot)
            onlyIf {
                !debugOption.spigotJar.isFile
            }
            doFirst {
                val buildVersion = runCatching {
                    SpigradlePlugin.mapper.readValue<Map<String, Any>>(
                            File(debugOption.buildToolDirectory, "BuildData/info.json")
                    )["minecraftVersion"]?.toString()
                }.getOrElse {
                    throw GradleException("Error while reading buildVersion in build info.json.", it)
                }
                val resultJar = File(
                        debugOption.buildToolDirectory,
                        "spigot-$buildVersion.jar"
                ).apply { parentFile.mkdirs() }
                copy {
                    from(resultJar)
                    into(debugOption.spigotDirectory)
                    rename { debugOption.spigotJar.name }
                }
            }
        }
        val runSpigot by tasks.creating(JavaExec::class) {
            group = "spigradle"
            description = "Startup the spigot server."
            standardInput = System.`in`
            dependsOn(prepareSpigot)
            doFirst {
                if (!debugOption.eula) {
                    throw GradleException("""
                        Please set the 'eula' property to true if you agree the Mojang EULA.
                        https://account.mojang.com/documents/minecraft_eula
                    """.trimIndent())
                }
                classpath = files(debugOption.spigotJar)
                workingDir = debugOption.spigotDirectory
                File(debugOption.spigotDirectory, "eula.txt").writeText("eula=true")
            }
        }
        val build by tasks
        val prepareDebug by tasks.creating {
            group = "spigradle"
            description = "Copy the jars into the server."
            dependsOn(build)
            doFirst {
                val pluginJar = tasks.withType<Jar>().asSequence().mapNotNull {
                    it.archiveFile.orNull?.asFile
                }.find { it.isFile } ?: throw GradleException("Couldn't find a plugin.jar")
                copy {
                    from(pluginJar)
                    into(File(debugOption.spigotDirectory, "plugins"))
                }
            }
        }
        val debugSpigot by tasks.creating {
            group = "spigradle"
            description = "Startup the spigot server with the plugin.jar"
            dependsOn(prepareDebug, runSpigot)
            runSpigot.mustRunAfter(prepareDebug)
        }
    }
}