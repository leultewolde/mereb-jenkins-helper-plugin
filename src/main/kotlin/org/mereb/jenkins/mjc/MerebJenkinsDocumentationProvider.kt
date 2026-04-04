package org.mereb.jenkins.mjc

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.psi.PsiElement

class MerebJenkinsDocumentationProvider : AbstractDocumentationProvider() {
    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val target = element ?: originalElement ?: return null
        val containingFile = target.containingFile ?: return null
        val file = containingFile.virtualFile ?: return null
        if (!MerebJenkinsConfigPaths.isSchemaTarget(file)) return null

        val path = MerebJenkinsPsiUtils.elementPathString(target) ?: return null
        val analysis = MerebJenkinsAnalysisCache.forFile(containingFile)
        return MerebJenkinsMetadataCatalog.metadataForPath(path, analysis)?.toHtml()
    }
}
