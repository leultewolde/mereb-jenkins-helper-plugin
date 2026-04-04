package org.mereb.intellij.mjc

class MerebJenkinsConfigAnalyzer {
    private val engine = MerebJenkinsConfigEngine()

    fun analyze(rawText: String): List<Issue> {
        return engine.analyze(rawText).findings.map { finding ->
            when (finding.severity) {
                MerebJenkinsSeverity.ERROR -> Issue.error(finding.message)
                MerebJenkinsSeverity.WARNING -> Issue.warning(finding.message)
            }
        }
    }

    fun analyzeDetailed(rawText: String, configFilePath: String? = null): MerebJenkinsAnalysisResult {
        return engine.analyze(rawText, configFilePath)
    }

    sealed class Severity {
        data object ERROR : Severity()
        data object WARNING : Severity()
    }

    data class Issue(
        val severity: Severity,
        val message: String,
    ) {
        companion object {
            fun error(message: String): Issue = Issue(Severity.ERROR, message)
            fun warning(message: String): Issue = Issue(Severity.WARNING, message)
        }
    }
}
