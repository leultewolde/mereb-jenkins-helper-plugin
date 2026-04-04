package org.mereb.intellij.mjc

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import java.nio.file.Paths

class MerebJenkinsNewConfigAction : DumbAwareAction("New Mereb Jenkins Config") {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val targetRoot = resolveTargetRoot(project, event) ?: return
        val recipe = Messages.showEditableChooseDialog(
            "Choose the pipeline recipe for the new config.",
            "New Mereb Jenkins Config",
            null,
            MerebJenkinsTemplates.supportedRecipes().toTypedArray(),
            "service",
            null,
        ) ?: return

        WriteCommandAction.runWriteCommandAction(project) {
            val ciDir = targetRoot.findChild(".ci") ?: targetRoot.createChildDirectory(this, ".ci")
            val existing = ciDir.findChild("ci.mjc")
            if (existing != null) {
                Messages.showInfoMessage(project, "A .ci/ci.mjc file already exists in ${targetRoot.path}", "Mereb Jenkins")
                FileEditorManager.getInstance(project).openFile(existing, true)
                return@runWriteCommandAction
            }
            val file = ciDir.createChildData(this, "ci.mjc")
            VfsUtil.saveText(file, MerebJenkinsTemplates.configTemplate(recipe))
            FileEditorManager.getInstance(project).openFile(file, true)
        }
    }

    private fun resolveTargetRoot(project: Project, event: AnActionEvent): VirtualFile? {
        val selected = CommonDataKeys.VIRTUAL_FILE.getData(event.dataContext)
        return when {
            selected == null -> project.basePath?.let { VfsUtil.findFile(Paths.get(it), true) }
            selected.isDirectory -> selected
            MerebJenkinsConfigPaths.isSchemaTarget(selected) -> MerebJenkinsProjectScanner.detectProjectRoot(Paths.get(selected.path))?.let { VfsUtil.findFile(it, true) }
            else -> selected.parent
        }
    }
}

class MerebJenkinsMigrationAssistantAction : DumbAwareAction("Migrate Mereb Jenkins Config") {
    private val analyzer = MerebJenkinsConfigAnalyzer()

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val file = CommonDataKeys.VIRTUAL_FILE.getData(event.dataContext)
        event.presentation.isEnabledAndVisible = file != null && MerebJenkinsConfigPaths.isSchemaTarget(file)
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val virtualFile = CommonDataKeys.VIRTUAL_FILE.getData(event.dataContext) ?: return
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return
        val analysis = analyzer.analyzeDetailed(psiFile.text, virtualFile.path)
        val plan = MerebJenkinsMigrationPlanner.plan(psiFile, analysis)
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

    private fun applyMigration(project: Project, plan: MerebJenkinsMigrationPlan) {
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
}
