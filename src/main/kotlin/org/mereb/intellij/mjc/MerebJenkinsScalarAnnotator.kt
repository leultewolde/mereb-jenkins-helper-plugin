package org.mereb.intellij.mjc

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import org.jetbrains.yaml.psi.YAMLScalar

class MerebJenkinsScalarAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val virtualFile = element.containingFile?.virtualFile ?: return
        if (!MerebJenkinsConfigPaths.isSchemaTarget(virtualFile)) return

        val scalar = element as? YAMLScalar ?: return
        val text = scalar.text.trim()
        if (text.isBlank()) return

        if (BOOLEAN_OR_NUMBER.matches(text)) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(scalar.textRange)
                .textAttributes(MerebJenkinsHighlighting.LITERAL)
                .create()
        }
    }

    private companion object {
        val BOOLEAN_OR_NUMBER = Regex("""^(?:true|false|null|-?\d+(?:\.\d+)?)$""")
    }
}
