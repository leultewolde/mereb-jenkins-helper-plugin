package org.mereb.jenkins.mjc

import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class MerebJenkinsInspectionSuppressor : InspectionSuppressor {
    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
        return MerebJenkinsInspectionSuppressionPolicy.shouldSuppress(element.containingFile, toolId)
    }

    override fun getSuppressActions(element: PsiElement?, toolId: String): Array<SuppressQuickFix> = emptyArray()
}

object MerebJenkinsInspectionSuppressionPolicy {
    private val allowedInspectionIds = setOf(
        "LegacyMerebJenkinsConfigFilename",
        "MerebJenkinsConfigSemantics",
    )

    fun shouldSuppress(file: PsiFile?, toolId: String): Boolean {
        val path = file?.virtualFile?.path ?: file?.viewProvider?.virtualFile?.path
        return shouldSuppressPath(path, toolId)
    }

    fun shouldSuppressPath(path: String?, toolId: String): Boolean {
        if (path.isNullOrBlank() || !MerebJenkinsConfigPaths.isSchemaTargetPath(path)) return false
        if (toolId in allowedInspectionIds) return false
        if (toolId.startsWith("MerebJenkins")) return false
        if (toolId.startsWith("JsonSchema")) return false
        return true
    }
}
