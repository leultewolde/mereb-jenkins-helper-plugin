package org.mereb.intellij.mjc

import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiElement

class MerebJenkinsRelationAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val containingFile = element.containingFile ?: return
        if (!MerebJenkinsConfigPaths.isSchemaTarget(containingFile)) return

        val path = MerebJenkinsPsiUtils.elementPathString(element) ?: return
        val analysis = MerebJenkinsAnalysisCache.forFile(containingFile)
        val range = MerebJenkinsPsiUtils.rangeForElement(element)

        analysis.summary.relations
            .filter { it.sourcePath?.toString() == path || it.targetPath?.toString() == path }
            .firstOrNull()
            ?.let { relation ->
                val attributes = when (relation.status) {
                    MerebJenkinsRelationStatus.OK -> RELATION_OK
                    MerebJenkinsRelationStatus.MISSING -> RELATION_MISSING
                    MerebJenkinsRelationStatus.UNUSED -> RELATION_UNUSED
                    MerebJenkinsRelationStatus.IGNORED, MerebJenkinsRelationStatus.INACTIVE -> RELATION_IGNORED
                }
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(range)
                    .textAttributes(attributes)
                    .tooltip(relation.detail ?: relation.label)
                    .create()
                return
            }

        analysis.summary.sections.firstOrNull { it.path?.toString() == path }?.let { section ->
            val attributes = when (section.status) {
                MerebJenkinsRelationStatus.UNUSED -> RELATION_UNUSED
                MerebJenkinsRelationStatus.IGNORED, MerebJenkinsRelationStatus.INACTIVE -> RELATION_IGNORED
                MerebJenkinsRelationStatus.MISSING -> RELATION_MISSING
                MerebJenkinsRelationStatus.OK -> return
            }
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(range)
                .textAttributes(attributes)
                .tooltip(section.detail ?: section.label)
                .create()
        }
    }

    companion object {
        private val RELATION_OK = TextAttributesKey.createTextAttributesKey(
            "MEREB_JENKINS_RELATION_OK",
            DefaultLanguageHighlighterColors.CONSTANT
        )
        private val RELATION_MISSING = TextAttributesKey.createTextAttributesKey(
            "MEREB_JENKINS_RELATION_MISSING",
            CodeInsightColors.ERRORS_ATTRIBUTES
        )
        private val RELATION_UNUSED = TextAttributesKey.createTextAttributesKey(
            "MEREB_JENKINS_RELATION_UNUSED",
            CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES
        )
        private val RELATION_IGNORED = TextAttributesKey.createTextAttributesKey(
            "MEREB_JENKINS_RELATION_IGNORED",
            CodeInsightColors.WEAK_WARNING_ATTRIBUTES
        )
    }
}
