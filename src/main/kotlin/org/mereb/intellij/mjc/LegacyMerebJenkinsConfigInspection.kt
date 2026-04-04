package org.mereb.intellij.mjc

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.psi.PsiFile

class LegacyMerebJenkinsConfigInspection : LocalInspectionTool() {
    private val analyzer = MerebJenkinsConfigAnalyzer()

    override fun checkFile(
        file: PsiFile,
        manager: InspectionManager,
        isOnTheFly: Boolean,
    ): Array<ProblemDescriptor> {
        val virtualFile = file.virtualFile ?: return ProblemDescriptor.EMPTY_ARRAY
        if (!MerebJenkinsConfigPaths.isLegacy(virtualFile)) {
            return ProblemDescriptor.EMPTY_ARRAY
        }

        val analysis = analyzer.analyzeDetailed(file.text, virtualFile.path)
        val problem = manager.createProblemDescriptor(
            file,
            MESSAGE,
            isOnTheFly,
            MerebJenkinsQuickFixFactory.legacyQuickFixes(file, analysis),
            ProblemHighlightType.WEAK_WARNING
        )
        return arrayOf(problem)
    }

    companion object {
        const val MESSAGE = "Prefer .ci/ci.mjc for Mereb Jenkins configs. Legacy filenames are still supported."
    }
}
