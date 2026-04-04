package org.mereb.jenkins.mjc

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

class MerebJenkinsCompletionTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        val virtualFile = file.virtualFile ?: return Result.CONTINUE
        if (!MerebJenkinsConfigPaths.isSchemaTarget(virtualFile)) return Result.CONTINUE
        if (!shouldTrigger(charTyped)) return Result.CONTINUE

        AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.STOP
    }

    private fun shouldTrigger(charTyped: Char): Boolean {
        return charTyped.isLetterOrDigit() || charTyped == '-' || charTyped == ':'
    }
}
