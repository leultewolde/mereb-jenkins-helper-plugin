package org.mereb.intellij.mjc

import java.io.File
import java.util.concurrent.TimeUnit

internal object MerebJenkinsGitSupport {
    fun currentBranch(projectRootPath: String): String? {
        val root = File(projectRootPath)
        if (!root.exists()) return null
        return runCatching {
            val process = ProcessBuilder("git", "-C", root.absolutePath, "rev-parse", "--abbrev-ref", "HEAD")
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0) return null
            process.inputStream.bufferedReader().use { it.readText() }
                .trim()
                .takeUnless { it.isBlank() || it == "HEAD" }
        }.getOrNull()
    }
}
