package org.mereb.intellij.mjc

import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiFile

data class MerebJenkinsMigrationFileChange(
    val title: String,
    val currentPath: String,
    val targetPath: String,
    val beforeText: String,
    val afterText: String,
)

data class MerebJenkinsMigrationPlan(
    val changes: List<MerebJenkinsMigrationFileChange>,
    val warnings: List<String>,
) {
    fun isEmpty(): Boolean = changes.isEmpty() && warnings.isEmpty()
}

object MerebJenkinsMigrationPlanner {
    fun plan(file: PsiFile, analysis: MerebJenkinsAnalysisResult): MerebJenkinsMigrationPlan {
        val changes = mutableListOf<MerebJenkinsMigrationFileChange>()
        val warnings = mutableListOf<String>()
        val virtualFile = file.virtualFile ?: return MerebJenkinsMigrationPlan(emptyList(), emptyList())
        val projectScan = analysis.projectScan

        val preferredRecipe = analysis.summary.explicitRecipe ?: analysis.summary.resolvedRecipe
        val migratedConfigText = migratedConfigText(file.text, preferredRecipe, analysis)
        val targetConfigPath = migratedConfigPath(virtualFile.path)
        if (migratedConfigText != file.text || targetConfigPath != virtualFile.path) {
            changes += MerebJenkinsMigrationFileChange(
                title = "Config",
                currentPath = virtualFile.path,
                targetPath = targetConfigPath,
                beforeText = file.text,
                afterText = migratedConfigText,
            )
        }

        val jenkinsPath = projectScan.jenkinsfilePath
        val jenkinsfileVirtualFile = jenkinsPath?.let { VfsUtil.findFile(java.nio.file.Paths.get(it), true) }
        val jenkinsText = jenkinsfileVirtualFile?.inputStream?.bufferedReader()?.use { it.readText() }
        if (jenkinsText != null && projectScan.jenkinsfileConfigPath != null && MerebJenkinsConfigPaths.isLegacyPath(projectScan.jenkinsfileConfigPath)) {
            val updatedJenkins = updateJenkinsfileText(jenkinsText)
            if (updatedJenkins != jenkinsText) {
                changes += MerebJenkinsMigrationFileChange(
                    title = "Jenkinsfile",
                    currentPath = jenkinsfileVirtualFile.path,
                    targetPath = jenkinsfileVirtualFile.path,
                    beforeText = jenkinsText,
                    afterText = updatedJenkins,
                )
            }
        }

        warnings += analysis.findings.filter { it.id.startsWith("ignored-") }.map { "Ignored in staged mode: ${it.message}" }
        return MerebJenkinsMigrationPlan(changes, warnings.distinct())
    }

    private fun migratedConfigText(text: String, recipe: String, analysis: MerebJenkinsAnalysisResult): String {
        var updated = text
        if (!Regex("^recipe:\\s*", RegexOption.MULTILINE).containsMatchIn(updated)) {
            val versionMatch = Regex("^version:.*$", RegexOption.MULTILINE).find(updated)
            val insertOffset = versionMatch?.range?.last?.plus(1) ?: 0
            val prefix = if (insertOffset == 0) "" else "\n"
            updated = updated.substring(0, insertOffset) + "$prefix" + "recipe: $recipe\n" + updated.substring(insertOffset)
        }

        val invalidRecipeFinding = analysis.findings.firstOrNull { it.id == "recipe-invalid" }
        val replacement = invalidRecipeFinding
            ?.quickFixes
            ?.firstOrNull { it.kind == MerebJenkinsFixKind.REPLACE_RECIPE }
            ?.data
            ?.get("recipe")
        if (!replacement.isNullOrBlank()) {
            updated = updated.replace(Regex("^recipe:\\s*.*$", RegexOption.MULTILINE), "recipe: $replacement")
        }
        return updated
    }

    private fun migratedConfigPath(currentPath: String): String {
        return when {
            currentPath.endsWith("/.ci/ci.yml") -> currentPath.removeSuffix("ci.yml") + "ci.mjc"
            currentPath.endsWith("/ci.yml") -> currentPath.removeSuffix("ci.yml") + ".ci/ci.mjc"
            else -> currentPath
        }
    }

    fun updateJenkinsfileText(text: String): String {
        return text
            .replace("configPath: '.ci/ci.yml'", "configPath: '.ci/ci.mjc'")
            .replace("configPath: \".ci/ci.yml\"", "configPath: \".ci/ci.mjc\"")
            .replace("configPath: 'ci.yml'", "configPath: '.ci/ci.mjc'")
            .replace("configPath: \"ci.yml\"", "configPath: \".ci/ci.mjc\"")
    }
}
