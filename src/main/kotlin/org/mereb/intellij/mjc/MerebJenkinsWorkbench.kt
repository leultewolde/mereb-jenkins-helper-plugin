package org.mereb.intellij.mjc

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import java.nio.file.Paths
import org.jetbrains.yaml.psi.YAMLFile

object MerebJenkinsWorkbench {
    fun runMigrationAssistant(project: Project, virtualFile: VirtualFile) {
        val psiFile = findPsiFile(project, virtualFile) ?: return
        val analysis = readAction { MerebJenkinsConfigAnalyzer().analyzeDetailed(psiFile.text, virtualFile.path) }
        val plan = readAction { MerebJenkinsMigrationPlanner.plan(psiFile, analysis) }
        if (plan.isEmpty()) {
            Messages.showInfoMessage(project, "No conservative migration changes are needed for this project.", "Mereb Jenkins")
            return
        }

        val dialog = MerebJenkinsPreviewDialog(plan)
        if (!dialog.showAndGet()) {
            return
        }

        applyMigration(project, plan)
    }

    fun applyMigration(project: Project, plan: MerebJenkinsMigrationPlan) {
        WriteCommandAction.runWriteCommandAction(project) {
            plan.changes.forEach { change ->
                val currentVirtualFile = VfsUtil.findFile(Paths.get(change.currentPath), true) ?: return@forEach
                val psiFile = PsiManager.getInstance(project).findFile(currentVirtualFile)
                val document = psiFile?.let { PsiDocumentManager.getInstance(project).getDocument(it) }

                if (document != null) {
                    document.setText(change.afterText)
                    PsiDocumentManager.getInstance(project).commitDocument(document)
                } else {
                    VfsUtil.saveText(currentVirtualFile, change.afterText)
                }

                if (change.targetPath != change.currentPath) {
                    val targetPath = Paths.get(change.targetPath)
                    val targetDir = VfsUtil.createDirectoryIfMissing(targetPath.parent.toString()) ?: return@forEach
                    currentVirtualFile.move(this, targetDir)
                    currentVirtualFile.rename(this, targetPath.fileName.toString())
                }
            }

            plan.changes.firstOrNull()?.let { firstChange ->
                val newFile = VfsUtil.findFile(Paths.get(firstChange.targetPath), true)
                if (newFile != null) {
                    FileEditorManager.getInstance(project).openFile(newFile, true)
                }
            }
        }

        if (plan.warnings.isNotEmpty()) {
            Messages.showInfoMessage(project, plan.warnings.joinToString("\n"), "Mereb Jenkins Migration Notes")
        }
    }

    fun navigateToPath(project: Project, virtualFile: VirtualFile, path: MerebJenkinsPath?): Boolean {
        if (path == null) return false
        val targetOffset = readAction {
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? YAMLFile ?: return@readAction null
            MerebJenkinsPsiUtils.findBestElement(psiFile, path)?.textRange?.startOffset
        } ?: return false
        OpenFileDescriptor(project, virtualFile, targetOffset).navigate(true)
        return true
    }

    fun openJenkinsfile(project: Project, analysis: MerebJenkinsAnalysisResult): Boolean {
        val jenkinsfilePath = analysis.projectScan.jenkinsfilePath ?: return false
        val file = VfsUtil.findFile(Paths.get(jenkinsfilePath), true) ?: return false
        FileEditorManager.getInstance(project).openFile(file, true)
        return true
    }

    fun applySafeFixes(project: Project, virtualFile: VirtualFile, analysis: MerebJenkinsAnalysisResult) {
        applySuggestions(project, virtualFile, analysis, analysis.summary.safeFixes)
    }

    fun applySuggestions(
        project: Project,
        virtualFile: VirtualFile,
        analysis: MerebJenkinsAnalysisResult,
        suggestions: List<MerebJenkinsFixSuggestion>,
    ) {
        var currentFile = virtualFile
        suggestions.forEach { suggestion ->
            currentFile = applySuggestion(project, currentFile, analysis, suggestion) ?: currentFile
        }
        FileEditorManager.getInstance(project).openFile(currentFile, true)
    }

    private fun applySuggestion(
        project: Project,
        virtualFile: VirtualFile,
        analysis: MerebJenkinsAnalysisResult,
        suggestion: MerebJenkinsFixSuggestion,
    ): VirtualFile? {
        val psiFile = findPsiFile(project, virtualFile)
        when (suggestion.kind) {
            MerebJenkinsFixKind.ADD_RECIPE -> {
                val recipe = suggestion.data["recipe"].orEmpty()
                mutateYamlDocument(project, psiFile) { document, _ ->
                    if (recipe.isBlank()) return@mutateYamlDocument
                    if (Regex("^recipe:\\s*", RegexOption.MULTILINE).containsMatchIn(document.text)) return@mutateYamlDocument
                    val insertOffset = Regex("^version:.*$", RegexOption.MULTILINE).find(document.text)?.range?.last?.plus(1) ?: 0
                    val prefix = if (insertOffset == 0) "" else "\n"
                    document.insertString(insertOffset, "${prefix}recipe: $recipe\n")
                }
            }
            MerebJenkinsFixKind.REPLACE_RECIPE -> {
                val recipe = suggestion.data["recipe"].orEmpty()
                mutateYamlDocument(project, psiFile) { document, _ ->
                    if (recipe.isBlank()) return@mutateYamlDocument
                    val match = Regex("^recipe:\\s*.*$", RegexOption.MULTILINE).find(document.text)
                    if (match != null) {
                        document.replaceString(match.range.first, match.range.last + 1, "recipe: $recipe")
                    }
                }
            }
            MerebJenkinsFixKind.RENAME_CONFIG_FILE -> {
                return renameConfigFile(project, virtualFile)
            }
            MerebJenkinsFixKind.UPDATE_JENKINSFILE_CONFIG_PATH -> {
                updateJenkinsfileConfigPath(project, analysis.projectScan, suggestion.data["path"].orEmpty())
            }
            MerebJenkinsFixKind.REMOVE_KEY -> {
                val path = suggestion.data["path"].orEmpty()
                WriteCommandAction.runWriteCommandAction(project) {
                    val psiYaml = PsiManager.getInstance(project).findFile(virtualFile) as? YAMLFile ?: return@runWriteCommandAction
                    val keyValue = MerebJenkinsPsiUtils.findKeyValue(psiYaml, MerebJenkinsPsiUtils.parsePathString(path))
                    keyValue?.delete()
                }
            }
            MerebJenkinsFixKind.FIX_ORDER -> {
                val path = suggestion.data["path"].orEmpty()
                val validNames = suggestion.data["validNames"]?.split(',')?.filter(String::isNotBlank).orEmpty()
                mutateYamlDocument(project, psiFile) { document, currentPsiFile ->
                    val currentYaml = currentPsiFile as? YAMLFile ?: return@mutateYamlDocument
                    val keyValue = MerebJenkinsPsiUtils.findKeyValue(currentYaml, MerebJenkinsPsiUtils.parsePathString(path))
                    val value = keyValue?.value ?: return@mutateYamlDocument
                    document.replaceString(value.textRange.startOffset, value.textRange.endOffset, "[${validNames.joinToString(", ")}]")
                }
            }
            MerebJenkinsFixKind.ADD_IMAGE_REPOSITORY -> {
                val placeholder = suggestion.data["repository"].orEmpty().ifBlank { "registry.example.com/app" }
                mutateYamlDocument(project, psiFile) { document, currentPsiFile ->
                    val currentYaml = currentPsiFile as? YAMLFile
                    val imageKey = currentYaml?.let { MerebJenkinsPsiUtils.findKeyValue(it, MerebJenkinsPsiUtils.parsePathString("image")) }
                    when (val value = imageKey?.value) {
                        null -> {
                            val anchorMatch = Regex("^build:.*$", setOf(RegexOption.MULTILINE)).find(document.text)
                            val offset = anchorMatch?.range?.last?.plus(1) ?: document.text.length
                            document.insertString(offset, "\nimage:\n  repository: $placeholder\n")
                        }
                        else -> {
                            val text = value.text
                            when {
                                text == "false" || text == "true" -> {
                                    document.replaceString(value.textRange.startOffset, value.textRange.endOffset, "\n  repository: $placeholder")
                                }
                                text.contains("repository:") -> Unit
                                else -> document.insertString(value.textRange.endOffset, "\n  repository: $placeholder")
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun mutateYamlDocument(
        project: Project,
        psiFile: PsiFile?,
        mutator: (com.intellij.openapi.editor.Document, PsiFile?) -> Unit,
    ) {
        val file = psiFile ?: return
        val document = readAction { PsiDocumentManager.getInstance(project).getDocument(file) } ?: return
        WriteCommandAction.runWriteCommandAction(project) {
            mutator(document, file)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }

    private fun renameConfigFile(project: Project, virtualFile: VirtualFile): VirtualFile? {
        var renamed: VirtualFile? = null
        WriteCommandAction.runWriteCommandAction(project) {
            when {
                virtualFile.path.endsWith("/.ci/ci.yml") -> {
                    virtualFile.rename(this, "ci.mjc")
                    renamed = virtualFile
                }
                virtualFile.name == "ci.yml" -> {
                    val parent = virtualFile.parent ?: return@runWriteCommandAction
                    val ciDir = parent.findChild(".ci") ?: parent.createChildDirectory(this, ".ci")
                    virtualFile.move(this, ciDir)
                    virtualFile.rename(this, "ci.mjc")
                    renamed = virtualFile
                }
                else -> renamed = virtualFile
            }
        }
        return renamed
    }

    private fun updateJenkinsfileConfigPath(project: Project, scan: MerebJenkinsProjectScan, targetPath: String) {
        if (targetPath.isBlank()) return
        val jenkinsfilePath = scan.jenkinsfilePath ?: return
        val jenkinsVirtualFile = VfsUtil.findFile(Paths.get(jenkinsfilePath), true) ?: return
        val psiFile = findPsiFile(project, jenkinsVirtualFile) ?: return
        val document = readAction { PsiDocumentManager.getInstance(project).getDocument(psiFile) } ?: return
        WriteCommandAction.runWriteCommandAction(project) {
            val updated = document.text
                .replace("configPath: '.ci/ci.yml'", "configPath: '$targetPath'")
                .replace("configPath: \".ci/ci.yml\"", "configPath: \"$targetPath\"")
                .replace("configPath: 'ci.yml'", "configPath: '$targetPath'")
                .replace("configPath: \"ci.yml\"", "configPath: \"$targetPath\"")
            document.setText(updated)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }

    private fun findPsiFile(project: Project, virtualFile: VirtualFile): PsiFile? =
        readAction { PsiManager.getInstance(project).findFile(virtualFile) }

    private fun <T> readAction(action: () -> T): T = ReadAction.compute<T, RuntimeException> { action() }
}
