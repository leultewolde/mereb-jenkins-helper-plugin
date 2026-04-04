package org.mereb.intellij.mjc

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

object MerebJenkinsProjectScanner {
    private val configPathPattern = Regex("configPath\\s*:\\s*['\"]([^'\"]+)['\"]")

    fun scan(configFilePath: String?): MerebJenkinsProjectScan {
        if (configFilePath.isNullOrBlank()) {
            return MerebJenkinsProjectScan()
        }
        val configPath = runCatching { Paths.get(configFilePath).normalize() }.getOrNull()
            ?: return MerebJenkinsProjectScan(configFilePath = configFilePath)
        val root = detectProjectRoot(configPath)
        val jenkinsfile = root?.resolve("Jenkinsfile")?.takeIf { it.exists() }
        val jenkinsfileText = jenkinsfile?.readText()
        val jenkinsfileConfigPath = jenkinsfileText?.let { configPathPattern.find(it)?.groupValues?.getOrNull(1) }

        return MerebJenkinsProjectScan(
            configFilePath = configPath.toString(),
            projectRootPath = root?.toString(),
            jenkinsfilePath = jenkinsfile?.toString(),
            jenkinsfileConfigPath = jenkinsfileConfigPath,
            expectedRecipe = root?.let(::expectedRecipeForRoot),
        )
    }

    fun detectProjectRoot(configPath: Path): Path? {
        val normalized = configPath.normalize()
        val fileName = normalized.fileName?.toString() ?: return null
        return when {
            normalized.endsWith(Paths.get(".ci", "ci.mjc")) -> normalized.parent?.parent
            normalized.endsWith(Paths.get(".ci", "ci.yml")) -> normalized.parent?.parent
            fileName == "ci.yml" -> normalized.parent
            else -> normalized.parent?.takeIf(Path::isDirectory)
        }
    }

    private fun expectedRecipeForRoot(root: Path): String? {
        val name = root.name
        val parent = root.parent?.name.orEmpty()
        val grandParent = root.parent?.parent?.name.orEmpty()
        return when {
            parent == "services" && name == "shared" -> "package"
            parent == "services" -> "service"
            parent == "packages" -> "package"
            parent == "docker" -> "image"
            parent == "web" && name.startsWith("mfe-") -> "microfrontend"
            parent == "web" && name == "shell" -> "service"
            parent == "web" && name == "runtime" -> "package"
            parent == "web" && name == "e2e" -> "build"
            parent == "infra" && name == "platform" -> "terraform"
            parent == "infra" && name == "charts" -> "build"
            grandParent == "infra" && parent == "charts" -> "build"
            else -> null
        }
    }
}
