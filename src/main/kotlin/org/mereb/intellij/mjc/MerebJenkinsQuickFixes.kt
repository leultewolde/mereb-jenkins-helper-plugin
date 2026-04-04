package org.mereb.intellij.mjc

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import java.nio.file.Paths
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLValue

private const val PREVIEW_PLACEHOLDER = "__preview__"

object MerebJenkinsQuickFixFactory {
    fun createQuickFixes(
        finding: MerebJenkinsFinding,
        file: PsiFile,
        analysis: MerebJenkinsAnalysisResult,
    ): Array<LocalQuickFix> {
        return finding.quickFixes.mapNotNull { suggestion ->
            when (suggestion.kind) {
                MerebJenkinsFixKind.ADD_RECIPE -> AddRecipeQuickFix(suggestion.data["recipe"].orEmpty())
                MerebJenkinsFixKind.REPLACE_RECIPE -> ReplaceRecipeQuickFix(suggestion.data["recipe"].orEmpty())
                MerebJenkinsFixKind.RENAME_CONFIG_FILE -> RenameConfigFileQuickFix
                MerebJenkinsFixKind.UPDATE_JENKINSFILE_CONFIG_PATH -> UpdateJenkinsfileConfigPathQuickFix(suggestion.data["path"].orEmpty())
                MerebJenkinsFixKind.REMOVE_KEY -> RemoveYamlKeyQuickFix(suggestion.label)
                MerebJenkinsFixKind.FIX_ORDER -> FixOrderQuickFix(
                    pathString = suggestion.data["path"].orEmpty(),
                    validNames = suggestion.data["validNames"]?.split(',')?.filter(String::isNotBlank).orEmpty(),
                )
                MerebJenkinsFixKind.ADD_IMAGE_REPOSITORY -> AddImageRepositoryQuickFix(suggestion.data["repository"].orEmpty())
            }
        }.toTypedArray()
    }

    fun legacyQuickFixes(file: PsiFile, analysis: MerebJenkinsAnalysisResult): Array<LocalQuickFix> {
        val fixes = mutableListOf<LocalQuickFix>(RenameConfigFileQuickFix)
        val jenkinsPath = analysis.projectScan.jenkinsfileConfigPath
        if (jenkinsPath != null && MerebJenkinsConfigPaths.isLegacyPath(jenkinsPath)) {
            fixes += UpdateJenkinsfileConfigPathQuickFix(MerebJenkinsConfigPaths.preferredPath())
        }
        return fixes.toTypedArray()
    }
}

private class AddRecipeQuickFix(private val recipe: String) : LocalQuickFix {
    override fun getFamilyName(): String = "Add recipe: $recipe"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val file = descriptor.psiElement.containingFile ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        if (recipe.isBlank()) return
        WriteCommandAction.runWriteCommandAction(project, familyName, null, Runnable {
            val text = document.text
            if (Regex("^recipe:\\s*", RegexOption.MULTILINE).containsMatchIn(text)) {
                return@Runnable
            }
            val insertOffset = Regex("^version:.*$", RegexOption.MULTILINE)
                .find(text)
                ?.range
                ?.last
                ?.plus(1)
                ?: 0
            val prefix = if (insertOffset == 0) "" else "\n"
            document.insertString(insertOffset, "${prefix}recipe: $recipe\n")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }, file)
    }
}

private class ReplaceRecipeQuickFix(private val recipe: String) : LocalQuickFix {
    override fun getFamilyName(): String = "Replace recipe with $recipe"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val keyValue = descriptor.psiElement.parent as? YAMLKeyValue ?: descriptor.psiElement as? YAMLKeyValue
        val file = descriptor.psiElement.containingFile ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        if (recipe.isBlank()) return
        WriteCommandAction.runWriteCommandAction(project, familyName, null, Runnable {
            if (keyValue != null && keyValue.keyText == "recipe" && keyValue.value != null) {
                document.replaceString(keyValue.value!!.textRange.startOffset, keyValue.value!!.textRange.endOffset, recipe)
            } else {
                val text = document.text
                val match = Regex("^recipe:\\s*.*$", RegexOption.MULTILINE).find(text)
                if (match != null) {
                    document.replaceString(match.range.first, match.range.last + 1, "recipe: $recipe")
                } else {
                    document.insertString(0, "recipe: $recipe\n")
                }
            }
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }, file)
    }
}

private object RenameConfigFileQuickFix : LocalQuickFix {
    override fun getFamilyName(): String = "Rename config to ${MerebJenkinsConfigPaths.preferredPath()}"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val file = descriptor.psiElement.containingFile?.virtualFile ?: return
        val preferred = MerebJenkinsConfigPaths.preferredPath()
        WriteCommandAction.runWriteCommandAction(project, familyName, null, Runnable {
            when {
                file.path.endsWith("/.ci/ci.yml") -> file.rename(this, "ci.mjc")
                file.name == "ci.yml" -> {
                    val parent = file.parent ?: return@Runnable
                    val ciDir = parent.findChild(".ci") ?: parent.createChildDirectory(this, ".ci")
                    file.move(this, ciDir)
                    file.rename(this, "ci.mjc")
                }
                file.path.endsWith("/.ci/ci.mjc") -> return@Runnable
                else -> Messages.showErrorDialog(project, "Cannot safely rename ${file.path} to $preferred", "Rename Mereb Config")
            }
        }, descriptor.psiElement.containingFile)
    }
}

private class UpdateJenkinsfileConfigPathQuickFix(private val targetPath: String) : LocalQuickFix {
    override fun getFamilyName(): String = "Update Jenkinsfile configPath to $targetPath"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        if (targetPath.isBlank()) return
        val scan = MerebJenkinsProjectScanner.scan(descriptor.psiElement.containingFile?.virtualFile?.path)
        val jenkinsfilePath = scan.jenkinsfilePath ?: return
        val jenkinsVirtualFile = VfsUtil.findFile(Paths.get(jenkinsfilePath), true) ?: return
        val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(jenkinsVirtualFile) ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return
        WriteCommandAction.runWriteCommandAction(project, familyName, null, Runnable {
            val updated = document.text
                .replace("configPath: '.ci/ci.yml'", "configPath: '$targetPath'")
                .replace("configPath: \".ci/ci.yml\"", "configPath: \"$targetPath\"")
                .replace("configPath: 'ci.yml'", "configPath: '$targetPath'")
                .replace("configPath: \"ci.yml\"", "configPath: \"$targetPath\"")
            document.setText(updated)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }, psiFile)
    }
}

private class RemoveYamlKeyQuickFix(private val label: String) : LocalQuickFix {
    override fun getFamilyName(): String = label.ifBlank { "Remove ignored key" }

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val keyValue = descriptor.psiElement.parent as? YAMLKeyValue ?: descriptor.psiElement as? YAMLKeyValue ?: return
        WriteCommandAction.runWriteCommandAction(project, familyName, null, Runnable {
            keyValue.delete()
        }, descriptor.psiElement.containingFile)
    }
}

private class FixOrderQuickFix(
    private val pathString: String,
    private val validNames: List<String>,
) : LocalQuickFix {
    override fun getFamilyName(): String = "Remove unknown values from $pathString"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        if (pathString.isBlank()) return
        val file = descriptor.psiElement.containingFile as? YAMLFile ?: return
        val keyValue = findKeyValueByPath(file, pathString) ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        WriteCommandAction.runWriteCommandAction(project, familyName, null, Runnable {
            val replacement = "[${validNames.joinToString(", ")}]"
            val value = keyValue.value ?: return@Runnable
            document.replaceString(value.textRange.startOffset, value.textRange.endOffset, replacement)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }, file)
    }
}

private class AddImageRepositoryQuickFix(private val repository: String) : LocalQuickFix {
    override fun getFamilyName(): String = "Add image.repository placeholder"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val file = descriptor.psiElement.containingFile as? YAMLFile ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        val placeholder = repository.ifBlank { "registry.example.com/app" }

        WriteCommandAction.runWriteCommandAction(project, familyName, null, Runnable {
            val imageKey = findKeyValueByPath(file, "image")
            when (val value = imageKey?.value) {
                is YAMLValue -> {
                    if (value.text == "false" || value.text == "true") {
                        document.replaceString(value.textRange.startOffset, value.textRange.endOffset, "\n  repository: $placeholder")
                    } else if (value.text.contains("repository:")) {
                        return@Runnable
                    } else {
                        val insertOffset = value.textRange.startOffset + value.textLength
                        document.insertString(insertOffset, "\n  repository: $placeholder")
                    }
                }
                else -> {
                    val text = document.text
                    val anchorMatch = Regex("^build:.*$", setOf(RegexOption.MULTILINE)).find(text)
                    val offset = anchorMatch?.range?.last?.plus(1) ?: text.length
                    document.insertString(offset, "\nimage:\n  repository: $placeholder\n")
                }
            }
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }, file)
    }
}

private fun findKeyValueByPath(file: YAMLFile, pathString: String): YAMLKeyValue? {
    val path = parsePath(pathString) ?: return null
    val element = MerebJenkinsPsiUtils.findBestElement(file, path)
    return element?.parent as? YAMLKeyValue ?: element as? YAMLKeyValue
}

private fun parsePath(pathString: String): MerebJenkinsPath? {
    if (pathString.isBlank()) return null
    var current = MerebJenkinsPath.root()
    pathString.split('.').forEach { token ->
        val match = Regex("([A-Za-z0-9_-]+)(\\[(\\d+)])?").matchEntire(token) ?: return@forEach
        current = current.key(match.groupValues[1])
        val indexValue = match.groupValues.getOrNull(3)?.takeIf(String::isNotBlank)?.toIntOrNull()
        if (indexValue != null) {
            current = current.index(indexValue)
        }
    }
    return current
}
