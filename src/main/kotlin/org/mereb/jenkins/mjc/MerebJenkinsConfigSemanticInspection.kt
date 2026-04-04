package org.mereb.jenkins.mjc

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.psi.PsiFile

class MerebJenkinsConfigSemanticInspection : LocalInspectionTool() {
    override fun checkFile(
        file: PsiFile,
        manager: InspectionManager,
        isOnTheFly: Boolean,
    ): Array<ProblemDescriptor> {
        if (!MerebJenkinsConfigPaths.isSchemaTarget(file)) {
            return ProblemDescriptor.EMPTY_ARRAY
        }

        val analysis = MerebJenkinsAnalysisCache.forFile(file)
        return analysis.findings.map { finding ->
            val psiElement = MerebJenkinsPsiUtils.findBestProblemElement(file, finding.path, finding.anchorPath) ?: file
            manager.createProblemDescriptor(
                psiElement,
                finding.message,
                isOnTheFly,
                MerebJenkinsQuickFixFactory.createQuickFixes(finding, file, analysis),
                highlightTypeFor(finding)
            )
        }.toTypedArray()
    }

    private fun highlightTypeFor(finding: MerebJenkinsFinding): ProblemHighlightType = when {
        finding.severity == MerebJenkinsSeverity.ERROR -> ProblemHighlightType.GENERIC_ERROR_OR_WARNING
        finding.id in advisoryFindingIds -> ProblemHighlightType.WEAK_WARNING
        else -> ProblemHighlightType.GENERIC_ERROR_OR_WARNING
    }

    private companion object {
        val advisoryFindingIds = setOf(
            "recipe-missing",
            "build-empty",
            "release-autoTag-bump-default",
        )
    }
}
