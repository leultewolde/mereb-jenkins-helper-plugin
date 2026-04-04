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
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val file = CommonDataKeys.VIRTUAL_FILE.getData(event.dataContext)
        event.presentation.isEnabledAndVisible = file != null && MerebJenkinsConfigPaths.isSchemaTarget(file)
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val virtualFile = CommonDataKeys.VIRTUAL_FILE.getData(event.dataContext) ?: return
        MerebJenkinsWorkbench.runMigrationAssistant(project, virtualFile)
    }
}
