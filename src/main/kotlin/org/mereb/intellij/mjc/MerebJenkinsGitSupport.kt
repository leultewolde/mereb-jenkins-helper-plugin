package org.mereb.intellij.mjc

import java.io.File
import java.util.concurrent.TimeUnit

internal object MerebJenkinsGitSupport {
    fun currentBranch(projectRootPath: String): String? {
        val root = File(projectRootPath)
        if (!root.exists()) return null
        return gitOutput(root, 2, "rev-parse", "--abbrev-ref", "HEAD")
            ?.takeUnless { it.isBlank() || it == "HEAD" }
    }

    fun remoteRepositoryName(projectRootPath: String): String? {
        val root = File(projectRootPath)
        if (!root.exists()) return null
        val remote = gitOutput(root, 2, "remote", "get-url", "origin") ?: return null
        val normalized = remote.trim().removeSuffix(".git").trimEnd('/')
        if (normalized.isBlank()) return null
        return normalized.substringAfterLast(':').substringAfterLast('/').takeIf { it.isNotBlank() }
    }

    private fun gitOutput(root: File, timeoutSeconds: Long, vararg args: String): String? {
        return runCatching {
            val process = ProcessBuilder(listOf("git", "-C", root.absolutePath) + args)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0) return null
            process.inputStream.bufferedReader().use { it.readText() }.trim().ifBlank { null }
        }.getOrNull()
    }
}
