package org.mereb.intellij.mjc

import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

data class MerebJenkinsWorkspaceTarget(
    val projectRootPath: String,
    val configFilePath: String,
    val jenkinsfilePath: String? = null,
    val jenkinsfileConfigPath: String? = null,
    val expectedRecipe: String? = null,
)

object MerebJenkinsProjectScanner {
    private val configPathPattern = Regex("configPath\\s*:\\s*['\"]([^'\"]+)['\"]")
    private val skippedDirectories = setOf(".git", ".idea", ".gradle", "build", "out", "dist", "target", "node_modules", ".next", "coverage")

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

    fun discoverWorkspaceTargets(basePath: String?): List<MerebJenkinsWorkspaceTarget> {
        if (basePath.isNullOrBlank()) return emptyList()
        val workspaceRoot = runCatching { Paths.get(basePath).normalize() }.getOrNull() ?: return emptyList()
        if (!workspaceRoot.exists()) return emptyList()

        val targetsByRoot = linkedMapOf<String, MerebJenkinsWorkspaceTarget>()

        Files.walkFileTree(workspaceRoot, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                return if (dir != workspaceRoot && dir.fileName?.toString() in skippedDirectories) {
                    FileVisitResult.SKIP_SUBTREE
                } else {
                    FileVisitResult.CONTINUE
                }
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!MerebJenkinsConfigPaths.isSchemaTargetPath(file.toString())) {
                    return FileVisitResult.CONTINUE
                }

                val scan = scan(file.toString())
                val root = scan.projectRootPath ?: return FileVisitResult.CONTINUE
                val target = MerebJenkinsWorkspaceTarget(
                    projectRootPath = root,
                    configFilePath = scan.configFilePath ?: file.toString(),
                    jenkinsfilePath = scan.jenkinsfilePath,
                    jenkinsfileConfigPath = scan.jenkinsfileConfigPath,
                    expectedRecipe = scan.expectedRecipe,
                )

                val existing = targetsByRoot[root]
                if (existing == null || configPriority(target.configFilePath) < configPriority(existing.configFilePath)) {
                    targetsByRoot[root] = target
                }

                return FileVisitResult.CONTINUE
            }
        })

        return targetsByRoot.values.sortedWith(
            compareBy<MerebJenkinsWorkspaceTarget> { relativeRootLabel(workspaceRoot, it.projectRootPath) }
                .thenBy { it.configFilePath }
        )
    }

    private fun relativeRootLabel(workspaceRoot: Path, rootPath: String): String {
        val root = runCatching { Paths.get(rootPath) }.getOrNull() ?: return rootPath
        return runCatching { workspaceRoot.relativize(root).toString().ifBlank { root.fileName?.toString().orEmpty() } }
            .getOrDefault(root.fileName?.toString().orEmpty())
    }

    private fun configPriority(path: String): Int = when {
        path.endsWith("/.ci/ci.mjc") || path == ".ci/ci.mjc" -> 0
        path.endsWith("/.ci/ci.yml") || path == ".ci/ci.yml" -> 1
        path.endsWith("/ci.yml") || path == "ci.yml" -> 2
        else -> 99
    }
}
