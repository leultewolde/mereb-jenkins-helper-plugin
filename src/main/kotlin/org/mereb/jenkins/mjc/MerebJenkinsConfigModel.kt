package org.mereb.jenkins.mjc

enum class MerebJenkinsSeverity {
    ERROR,
    WARNING,
}

enum class MerebJenkinsFixKind {
    ADD_RECIPE,
    REPLACE_RECIPE,
    RENAME_CONFIG_FILE,
    UPDATE_JENKINSFILE_CONFIG_PATH,
    REMOVE_KEY,
    FIX_ORDER,
    ADD_IMAGE_REPOSITORY,
}

enum class MerebJenkinsRelationStatus {
    OK,
    MISSING,
    UNUSED,
    IGNORED,
    INACTIVE,
}

sealed interface MerebJenkinsPathSegment {
    data class Key(val name: String) : MerebJenkinsPathSegment
    data class Index(val index: Int) : MerebJenkinsPathSegment
}

data class MerebJenkinsPath(
    val segments: List<MerebJenkinsPathSegment> = emptyList(),
) {
    fun key(name: String): MerebJenkinsPath = MerebJenkinsPath(segments + MerebJenkinsPathSegment.Key(name))

    fun index(index: Int): MerebJenkinsPath = MerebJenkinsPath(segments + MerebJenkinsPathSegment.Index(index))

    override fun toString(): String {
        if (segments.isEmpty()) return "<root>"
        return buildString {
            segments.forEach { segment ->
                when (segment) {
                    is MerebJenkinsPathSegment.Key -> {
                        if (isNotEmpty()) append('.')
                        append(segment.name)
                    }
                    is MerebJenkinsPathSegment.Index -> append('[').append(segment.index).append(']')
                }
            }
        }
    }

    companion object {
        fun root(): MerebJenkinsPath = MerebJenkinsPath()
    }
}

data class MerebJenkinsFixSuggestion(
    val kind: MerebJenkinsFixKind,
    val label: String,
    val data: Map<String, String> = emptyMap(),
)

data class MerebJenkinsFinding(
    val id: String,
    val severity: MerebJenkinsSeverity,
    val message: String,
    val path: MerebJenkinsPath? = null,
    val anchorPath: MerebJenkinsPath? = null,
    val quickFixes: List<MerebJenkinsFixSuggestion> = emptyList(),
)

data class MerebJenkinsProjectScan(
    val configFilePath: String? = null,
    val projectRootPath: String? = null,
    val jenkinsfilePath: String? = null,
    val jenkinsfileConfigPath: String? = null,
    val expectedRecipe: String? = null,
)

data class MerebJenkinsCapability(
    val id: String,
    val label: String,
    val enabled: Boolean,
    val detail: String? = null,
)

data class MerebJenkinsSectionState(
    val id: String,
    val label: String,
    val path: MerebJenkinsPath? = null,
    val status: MerebJenkinsRelationStatus = MerebJenkinsRelationStatus.OK,
    val detail: String? = null,
)

data class MerebJenkinsRelation(
    val id: String,
    val group: String,
    val label: String,
    val sourcePath: MerebJenkinsPath? = null,
    val targetPath: MerebJenkinsPath? = null,
    val status: MerebJenkinsRelationStatus = MerebJenkinsRelationStatus.OK,
    val detail: String? = null,
)

data class MerebJenkinsFlowStep(
    val label: String,
    val path: MerebJenkinsPath? = null,
    val status: MerebJenkinsRelationStatus = MerebJenkinsRelationStatus.OK,
    val detail: String? = null,
)

data class MerebJenkinsPipelineSummary(
    val explicitRecipe: String? = null,
    val resolvedRecipe: String = "build",
    val imageEnabled: Boolean = false,
    val releaseEnabled: Boolean = false,
    val deployOrder: List<String> = emptyList(),
    val microfrontendOrder: List<String> = emptyList(),
    val terraformOrder: List<String> = emptyList(),
    val ignoredFields: List<String> = emptyList(),
    val repoWarnings: List<String> = emptyList(),
    val referencedButMissing: List<String> = emptyList(),
    val definedButUnused: List<String> = emptyList(),
    val capabilities: List<MerebJenkinsCapability> = emptyList(),
    val sections: List<MerebJenkinsSectionState> = emptyList(),
    val relations: List<MerebJenkinsRelation> = emptyList(),
    val safeFixes: List<MerebJenkinsFixSuggestion> = emptyList(),
    val flowSteps: List<MerebJenkinsFlowStep> = emptyList(),
    val errorCount: Int = 0,
    val warningCount: Int = 0,
)

data class MerebJenkinsAnalysisResult(
    val rawConfig: Map<String, Any?> = emptyMap(),
    val resolvedRecipe: String = "build",
    val findings: List<MerebJenkinsFinding> = emptyList(),
    val summary: MerebJenkinsPipelineSummary = MerebJenkinsPipelineSummary(),
    val projectScan: MerebJenkinsProjectScan = MerebJenkinsProjectScan(),
    val parseError: String? = null,
)
