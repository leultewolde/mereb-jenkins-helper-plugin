package org.mereb.intellij.mjc

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

class MerebJenkinsScalarAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val containingFile = element.containingFile ?: return
        if (!MerebJenkinsConfigPaths.isSchemaTarget(containingFile)) return

        when (element) {
            is YAMLKeyValue -> annotateKey(element, holder)
            is YAMLScalar -> annotateScalar(element, holder)
            is PsiComment -> annotateComment(element, holder)
        }

        annotateSemanticFinding(element, holder)
    }

    private fun annotateKey(element: YAMLKeyValue, holder: AnnotationHolder) {
        val key = element.key ?: return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(key.textRange)
            .textAttributes(MerebJenkinsHighlighting.KEY)
            .create()
    }

    private fun annotateScalar(element: YAMLScalar, holder: AnnotationHolder) {
        val text = element.text.trim()
        if (text.isBlank()) return
        val key = if (BOOLEAN_OR_NUMBER.matches(text)) MerebJenkinsHighlighting.LITERAL else MerebJenkinsHighlighting.VALUE
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(element.textRange)
            .textAttributes(key)
            .create()
    }

    private fun annotateComment(element: PsiComment, holder: AnnotationHolder) {
        if (!element.text.trimStart().startsWith("#")) return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(element.textRange)
            .textAttributes(MerebJenkinsHighlighting.COMMENT)
            .create()
    }

    private fun annotateSemanticFinding(element: PsiElement, holder: AnnotationHolder) {
        val containingFile = element.containingFile ?: return
        val analysis = MerebJenkinsAnalysisCache.forFile(containingFile)
        val problemElementPath = MerebJenkinsPsiUtils.elementPathString(element)
            ?: MerebJenkinsPsiUtils.elementPathString(element.parent)
            ?: return

        analysis.findings
            .filter { it.path?.toString() == problemElementPath || it.anchorPath?.toString() == problemElementPath }
            .forEach { finding ->
                val expected = MerebJenkinsPsiUtils.findBestProblemElement(containingFile, finding.path, finding.anchorPath)
                if (expected == null || expected.textRange != element.textRange) return@forEach
                holder.newAnnotation(highlightSeverityFor(finding), finding.message)
                    .range(expected.textRange)
                    .highlightType(problemHighlightTypeFor(finding))
                    .create()
            }
    }

    private fun highlightSeverityFor(finding: MerebJenkinsFinding): HighlightSeverity = when {
        finding.severity == MerebJenkinsSeverity.ERROR -> HighlightSeverity.ERROR
        finding.id in advisoryFindingIds -> HighlightSeverity.WEAK_WARNING
        else -> HighlightSeverity.WARNING
    }

    private fun problemHighlightTypeFor(finding: MerebJenkinsFinding): ProblemHighlightType = when {
        finding.severity == MerebJenkinsSeverity.ERROR -> ProblemHighlightType.GENERIC_ERROR
        finding.id in advisoryFindingIds -> ProblemHighlightType.WEAK_WARNING
        else -> ProblemHighlightType.GENERIC_ERROR_OR_WARNING
    }

    private companion object {
        val BOOLEAN_OR_NUMBER = Regex("""^(?:true|false|null|-?\d+(?:\.\d+)?)$""")
        val advisoryFindingIds = setOf(
            "recipe-missing",
            "build-empty",
            "release-autoTag-bump-default",
        )
    }
}
