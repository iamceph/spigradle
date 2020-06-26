package kr.entree.spigradle.util

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import kotlin.test.assertNotEquals

/**
 * Created by JunHyung Lim on 2020-05-23
 */
fun Any.testGradleScript(path: String, taskName: String) {
    val result = GradleRunner.create()
            .withProjectDir(File(javaClass.getResource(path)!!.file))
            .withPluginClasspath()
            .withArguments("build", "--stacktrace")
            .withGradleVersion("5.4.1") // This is the minimal version required to use Spigradle.
            .build()
    println(result.output)
    assertNotEquals(TaskOutcome.FAILED, result.task(":$taskName")!!.outcome)
}