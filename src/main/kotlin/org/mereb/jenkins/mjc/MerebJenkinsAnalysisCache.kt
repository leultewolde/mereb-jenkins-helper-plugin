package org.mereb.jenkins.mjc

import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

object MerebJenkinsAnalysisCache {
    private val analyzer = MerebJenkinsConfigAnalyzer()

    fun forFile(file: PsiFile): MerebJenkinsAnalysisResult {
        val virtualFile = file.virtualFile ?: file.viewProvider.virtualFile
        if (virtualFile == null || !MerebJenkinsConfigPaths.isSchemaTargetPath(virtualFile.path)) {
            return analyzer.analyzeDetailed(file.text)
        }

        return CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(
                analyzer.analyzeDetailed(file.text, virtualFile.path),
                file,
            )
        }
    }
}
