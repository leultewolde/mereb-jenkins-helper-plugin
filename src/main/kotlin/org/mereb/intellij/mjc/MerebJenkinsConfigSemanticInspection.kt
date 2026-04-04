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
        isOnTheFly: Boolean
    ): Array<ProblemDescriptor> {
        val virtualFile = file.virtualFile ?: return ProblemDescriptor.EMPTY_ARRAY
        if (!MerebJenkinsConfigPaths.isSchemaTarget(virtualFile)) {
            return ProblemDescriptor.EMPTY_ARRAY
        }

        return analyzer.analyze(file.text).map { issue ->
            manager.createProblemDescriptor(
                file,
                issue.message,
                isOnTheFly,
                emptyArray(),
                issue.toHighlightType()
            )
        }.toTypedArray()
    }

    private fun MerebJenkinsConfigAnalyzer.Issue.toHighlightType(): ProblemHighlightType {
        return when (severity) {
            MerebJenkinsConfigAnalyzer.Severity.ERROR -> ProblemHighlightType.GENERIC_ERROR_OR_WARNING
            MerebJenkinsConfigAnalyzer.Severity.WARNING -> ProblemHighlightType.WEAK_WARNING
        }
    }
}
