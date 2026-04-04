package org.mereb.intellij.mjc

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.psi.PsiFile

class MerebJenkinsConfigSemanticInspection : LocalInspectionTool() {
    private val analyzer = MerebJenkinsConfigAnalyzer()

    override fun checkFile(
        file: PsiFile,
        manager: InspectionManager,
        isOnTheFly: Boolean,
    ): Array<ProblemDescriptor> {
        val virtualFile = file.virtualFile ?: return ProblemDescriptor.EMPTY_ARRAY
        if (!MerebJenkinsConfigPaths.isSchemaTarget(virtualFile)) {
            return ProblemDescriptor.EMPTY_ARRAY
        }

        val analysis = analyzer.analyzeDetailed(file.text, virtualFile.path)
        return analysis.findings.map { finding ->
            val psiElement = MerebJenkinsPsiUtils.findBestElement(file, finding.path, finding.anchorPath) ?: file
            manager.createProblemDescriptor(
                psiElement,
                finding.message,
                isOnTheFly,
                MerebJenkinsQuickFixFactory.createQuickFixes(finding, file, analysis),
                when (finding.severity) {
                    MerebJenkinsSeverity.ERROR -> ProblemHighlightType.GENERIC_ERROR_OR_WARNING
                    MerebJenkinsSeverity.WARNING -> ProblemHighlightType.WEAK_WARNING
                }
            )
        }.toTypedArray()
    }
}

