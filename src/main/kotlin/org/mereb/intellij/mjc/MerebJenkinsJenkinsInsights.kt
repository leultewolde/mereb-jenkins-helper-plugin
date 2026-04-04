package org.mereb.intellij.mjc

enum class MerebJenkinsStageMappingStatus {
    MATCHED,
    MISSING,
    EXTRA,
    AMBIGUOUS,
}

enum class MerebJenkinsDriftKind {
    LOCAL,
    RUNTIME,
    BRANCH,
}

data class MerebJenkinsConfigStageMapping(
    val id: String,
    val label: String,
    val configPath: MerebJenkinsPath?,
    val group: String,
    val expectedTokens: Set<String>,
    val status: MerebJenkinsStageMappingStatus,
    val liveStageName: String? = null,
    val detail: String? = null,
)

data class MerebJenkinsDriftFinding(
    val id: String,
    val kind: MerebJenkinsDriftKind,
    val message: String,
    val path: MerebJenkinsPath? = null,
    val detail: String? = null,
)

data class MerebJenkinsDeploymentTimelineEntry(
    val environment: String,
    val runName: String? = null,
    val runUrl: String? = null,
    val timestampMillis: Long? = null,
    val status: MerebJenkinsStageMappingStatus = MerebJenkinsStageMappingStatus.MISSING,
    val detail: String? = null,
)

data class MerebJenkinsImpactPreview(
    val title: String,
    val details: List<String>,
)

data class MerebJenkinsDiagnostics(
    val title: String,
    val detail: String,
    val requestUrl: String? = null,
    val redirectTarget: String? = null,
    val redirectRelation: String? = null,
)

data class MerebJenkinsQueueState(
    val inQueue: Boolean = false,
    val reason: String? = null,
    val blocked: Boolean = false,
    val stuck: Boolean = false,
)

data class MerebJenkinsConsoleExcerpt(
    val runId: String,
    val stageName: String? = null,
    val excerpt: String,
    val anchored: Boolean = false,
    val logUrl: String,
)

data class MerebJenkinsFailedTest(
    val suiteName: String,
    val caseName: String,
    val status: String,
    val className: String? = null,
    val durationSeconds: Double? = null,
    val errorDetails: String? = null,
) {
    override fun toString(): String = "$suiteName :: $caseName"
}

data class MerebJenkinsTestSummary(
    val totalCount: Int = 0,
    val failedCount: Int = 0,
    val skippedCount: Int = 0,
    val passedCount: Int = 0,
    val durationSeconds: Double? = null,
    val reportUrl: String? = null,
    val failedTests: List<MerebJenkinsFailedTest> = emptyList(),
)

data class MerebJenkinsStageTrend(
    val stageName: String,
    val appearanceCount: Int,
    val successCount: Int,
    val failureCount: Int,
    val unstableCount: Int,
    val averageDurationMillis: Long? = null,
    val lastFailureRunName: String? = null,
    val lastFailureTimestampMillis: Long? = null,
) {
    val flaky: Boolean
        get() = successCount > 0 && (failureCount > 0 || unstableCount > 0)
}

data class MerebJenkinsTrendSummary(
    val sampleSize: Int = 0,
    val flakyStageCount: Int = 0,
    val stages: List<MerebJenkinsStageTrend> = emptyList(),
)

data class MerebJenkinsActionAvailability(
    val canRebuild: Boolean = false,
    val rebuildUrl: String? = null,
    val rebuildDetail: String? = null,
    val approvalUrl: String? = null,
    val approvalDetail: String? = null,
    val failingLogUrl: String? = null,
    val failingLogDetail: String? = null,
)

data class MerebJenkinsOpsSnapshot(
    val headline: String,
    val buildStatus: String,
    val selectedVariantLabel: String,
    val pendingApprovalCount: Int,
    val artifactCount: Int,
    val flakyStageCount: Int,
    val testHeadline: String,
)

data class MerebJenkinsRunChoice(
    val id: String? = null,
    val label: String = "Latest",
) {
    val isLatest: Boolean
        get() = id == null

    override fun toString(): String = label
}

data class MerebJenkinsViewContext(
    val mapping: MerebJenkinsJobMapping,
    val branchName: String?,
    val variantSelection: MerebJenkinsJobVariantSelection,
    val liveData: MerebJenkinsLiveJobData,
    val diagnostics: MerebJenkinsDiagnostics? = null,
    val stageMappings: List<MerebJenkinsConfigStageMapping> = emptyList(),
    val driftFindings: List<MerebJenkinsDriftFinding> = emptyList(),
    val deploymentTimeline: List<MerebJenkinsDeploymentTimelineEntry> = emptyList(),
    val impactPreview: MerebJenkinsImpactPreview? = null,
)

data class MerebJenkinsCompareContext(
    val enabled: Boolean = false,
    val selection: MerebJenkinsJobCandidate? = null,
    val liveData: MerebJenkinsLiveJobData? = null,
)

data class MerebJenkinsViewSelection(
    val projectRootPath: String,
    val compareEnabled: Boolean = false,
    val primaryVariantJobPath: String? = null,
    val compareVariantJobPath: String? = null,
    val primaryRunId: String? = null,
    val compareRunId: String? = null,
)

internal object MerebJenkinsInsights {
    fun buildStageMappings(
        analysis: MerebJenkinsAnalysisResult,
        liveData: MerebJenkinsLiveJobData?,
    ): List<MerebJenkinsConfigStageMapping> {
        val expected = expectedMappings(analysis)
        if (liveData == null) {
            return expected
        }
        val stages = liveData.selectedRun?.stages.orEmpty()
        val matchedStageIds = mutableSetOf<String>()
        val resolved = expected.map { mapping ->
            val scored = stages
                .map { stage -> stage to mappingScore(mapping.expectedTokens, stage.name) }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
            when {
                scored.isEmpty() -> mapping.copy(
                    status = MerebJenkinsStageMappingStatus.MISSING,
                    detail = "Expected stage missing from the selected Jenkins run.",
                )
                scored.size > 1 && scored.first().second == scored[1].second -> mapping.copy(
                    status = MerebJenkinsStageMappingStatus.AMBIGUOUS,
                    liveStageName = scored.first().first.name,
                    detail = "Multiple Jenkins stages match this config step.",
                )
                else -> {
                    val matched = scored.first().first
                    matchedStageIds += matched.id
                    mapping.copy(
                        status = MerebJenkinsStageMappingStatus.MATCHED,
                        liveStageName = matched.name,
                        detail = "Matched Jenkins stage ${matched.name}.",
                    )
                }
            }
        }

        val extraStages = stages
            .filterNot { it.id in matchedStageIds }
            .filterNot { shouldIgnoreExtraStage(it.name) }
            .map { stage ->
                MerebJenkinsConfigStageMapping(
                    id = "extra-${normalizeStageText(stage.name)}",
                    label = stage.name,
                    configPath = null,
                    group = "Live Jenkins",
                    expectedTokens = emptySet(),
                    status = MerebJenkinsStageMappingStatus.EXTRA,
                    liveStageName = stage.name,
                    detail = "Live Jenkins stage not explained by the current Mereb config.",
                )
            }

        return resolved + extraStages
    }

    fun buildDriftFindings(
        analysis: MerebJenkinsAnalysisResult,
        branchName: String?,
        variantSelection: MerebJenkinsJobVariantSelection?,
        liveData: MerebJenkinsLiveJobData?,
        stageMappings: List<MerebJenkinsConfigStageMapping>,
    ): List<MerebJenkinsDriftFinding> {
        val findings = mutableListOf<MerebJenkinsDriftFinding>()
        val scan = analysis.projectScan
        val configRelativePath = currentConfigRelativePath(scan)
        if (scan.jenkinsfilePath == null) {
            findings += MerebJenkinsDriftFinding(
                id = "local-missing-jenkinsfile",
                kind = MerebJenkinsDriftKind.LOCAL,
                message = "No sibling Jenkinsfile was found for this Mereb config.",
            )
        } else if (!scan.jenkinsfileConfigPath.isNullOrBlank() && scan.jenkinsfileConfigPath != configRelativePath) {
            findings += MerebJenkinsDriftFinding(
                id = "local-jenkinsfile-config-path",
                kind = MerebJenkinsDriftKind.LOCAL,
                message = "Jenkinsfile points to ${scan.jenkinsfileConfigPath}, but the active config is $configRelativePath.",
                detail = "Update Jenkinsfile configPath or migrate the config path so local wiring matches runtime intent.",
            )
        }
        if (analysis.parseError != null) {
            findings += MerebJenkinsDriftFinding(
                id = "local-parse-error",
                kind = MerebJenkinsDriftKind.LOCAL,
                message = "The Mereb config has a YAML parse error, so runtime intent may be unreliable.",
            )
        }

        stageMappings
            .filter { it.status in setOf(MerebJenkinsStageMappingStatus.MISSING, MerebJenkinsStageMappingStatus.AMBIGUOUS, MerebJenkinsStageMappingStatus.EXTRA) }
            .forEach { mapping ->
                findings += MerebJenkinsDriftFinding(
                    id = "runtime-${mapping.id}",
                    kind = MerebJenkinsDriftKind.RUNTIME,
                    message = when (mapping.status) {
                        MerebJenkinsStageMappingStatus.MISSING -> "${mapping.label} is expected from the config but missing in the selected Jenkins run."
                        MerebJenkinsStageMappingStatus.AMBIGUOUS -> "${mapping.label} maps to multiple Jenkins stages."
                        MerebJenkinsStageMappingStatus.EXTRA -> "Jenkins exposes stage ${mapping.liveStageName ?: mapping.label} that is not explained by the current config."
                        MerebJenkinsStageMappingStatus.MATCHED -> mapping.label
                    },
                    path = mapping.configPath,
                    detail = mapping.detail,
                )
            }

        if (liveData?.summary?.jobClass?.contains("WorkflowMultiBranchProject") == true && liveData.summary.branchCount == 0) {
            findings += MerebJenkinsDriftFinding(
                id = "runtime-empty-multibranch",
                kind = MerebJenkinsDriftKind.RUNTIME,
                message = "The matching multibranch Jenkins job exists, but it has no indexed branch jobs yet.",
                detail = "Run “Scan Multibranch Pipeline Now” or fix the SCM source on the Jenkins job.",
            )
        }

        if (!branchName.isNullOrBlank() && !isMainBranch(branchName) && variantSelection != null) {
            when (variantSelection.mode) {
                MerebJenkinsJobVariantSelectionMode.MAIN_FALLBACK -> findings += MerebJenkinsDriftFinding(
                    id = "branch-fallback-main",
                    kind = MerebJenkinsDriftKind.BRANCH,
                    message = "The current branch '$branchName' is showing the main Jenkins job because no branch-specific job matched.",
                )
                MerebJenkinsJobVariantSelectionMode.MANUAL -> findings += MerebJenkinsDriftFinding(
                    id = "branch-manual-selection",
                    kind = MerebJenkinsDriftKind.BRANCH,
                    message = "A manual Jenkins job selection overrides the default branch-aware target for '$branchName'.",
                )
                else -> Unit
            }
        }

        return findings.distinctBy { it.id }
    }

    fun buildDeploymentTimeline(
        analysis: MerebJenkinsAnalysisResult,
        runs: List<MerebJenkinsRun>,
    ): List<MerebJenkinsDeploymentTimelineEntry> {
        val environments = when (analysis.summary.resolvedRecipe) {
            "service" -> analysis.summary.deployOrder
            "microfrontend" -> analysis.summary.microfrontendOrder
            "terraform" -> analysis.summary.terraformOrder
            else -> emptyList()
        }
        if (environments.isEmpty()) return emptyList()

        return environments.map { environment ->
            val normalizedEnvironment = normalizeStageText(environment)
            val matchingRun = runs.firstOrNull { run ->
                run.status.contains("SUCCESS", ignoreCase = true) &&
                    run.stages.any { stage ->
                        stage.status.contains("SUCCESS", ignoreCase = true) &&
                            stageMentionsEnvironment(stage.name, normalizedEnvironment)
                    }
            }
            if (matchingRun != null) {
                MerebJenkinsDeploymentTimelineEntry(
                    environment = environment,
                    runName = matchingRun.name,
                    runUrl = matchingRun.url,
                    timestampMillis = matchingRun.timestampMillis,
                    status = MerebJenkinsStageMappingStatus.MATCHED,
                    detail = "Last successful ${environment} deployment came from ${matchingRun.name}.",
                )
            } else {
                MerebJenkinsDeploymentTimelineEntry(
                    environment = environment,
                    status = MerebJenkinsStageMappingStatus.MISSING,
                    detail = "No recent successful Jenkins run could be mapped to ${environment}.",
                )
            }
        }
    }

    fun buildImpactPreview(
        analysis: MerebJenkinsAnalysisResult,
        selectedPath: MerebJenkinsPath?,
        variantSelection: MerebJenkinsJobVariantSelection?,
    ): MerebJenkinsImpactPreview? {
        val path = selectedPath ?: return null
        val pathString = path.toString()
        val details = mutableListOf<String>()
        when {
            pathString == "recipe" -> {
                details += "Active recipe: ${analysis.summary.resolvedRecipe}."
                details += analysis.summary.flowSteps.map { it.label }
            }
            pathString.startsWith("deploy.") && !pathString.startsWith("deploy.order") -> {
                val environment = pathString.removePrefix("deploy.").substringBefore('.')
                details += "Affects deploy stage for environment '$environment'."
                variantSelection?.selected?.leafName?.takeIf { it.isNotBlank() }?.let { details += "Viewed against Jenkins variant '$it'." }
            }
            pathString.startsWith("microfrontend.environments.") -> {
                val environment = pathString.removePrefix("microfrontend.environments.").substringBefore('.')
                details += "Affects microfrontend publish stage for '$environment'."
            }
            pathString.startsWith("terraform.environments.") -> {
                val environment = pathString.removePrefix("terraform.environments.").substringBefore('.')
                details += "Affects terraform/apply stage for '$environment'."
            }
            pathString.startsWith("image") -> details += "Affects image build and any service deploy flow that depends on the built image."
            pathString.startsWith("release") -> details += "Affects release/tag automation and package or deploy publication stages."
            pathString.startsWith("build") -> details += "Affects build/test stages before image, deploy, or release phases."
            else -> return null
        }
        if (details.isEmpty()) return null
        return MerebJenkinsImpactPreview(
            title = "Impact for $pathString",
            details = details.flattenPreviewDetails(),
        )
    }

    private fun expectedMappings(analysis: MerebJenkinsAnalysisResult): List<MerebJenkinsConfigStageMapping> {
        val mappings = mutableListOf<MerebJenkinsConfigStageMapping>()
        mappings += MerebJenkinsConfigStageMapping(
            id = "build",
            label = "Build",
            configPath = MerebJenkinsPath.root().key("build"),
            group = "Build",
            expectedTokens = setOf("build", "test", "compile"),
            status = MerebJenkinsStageMappingStatus.MISSING,
        )
        when (analysis.summary.resolvedRecipe) {
            "service" -> {
                mappings += MerebJenkinsConfigStageMapping(
                    id = "image",
                    label = "Build Image",
                    configPath = MerebJenkinsPath.root().key("image"),
                    group = "Image",
                    expectedTokens = setOf("image", "docker", "container"),
                    status = MerebJenkinsStageMappingStatus.MISSING,
                )
                analysis.summary.deployOrder.forEach { environment ->
                    mappings += MerebJenkinsConfigStageMapping(
                        id = "deploy-$environment",
                        label = "Deploy $environment",
                        configPath = MerebJenkinsPath.root().key("deploy").key(environment),
                        group = "Deploy",
                        expectedTokens = setOf("deploy", normalizeStageText(environment)),
                        status = MerebJenkinsStageMappingStatus.MISSING,
                    )
                }
            }
            "image" -> mappings += MerebJenkinsConfigStageMapping(
                id = "image",
                label = "Build Image",
                configPath = MerebJenkinsPath.root().key("image"),
                group = "Image",
                expectedTokens = setOf("image", "docker", "container"),
                status = MerebJenkinsStageMappingStatus.MISSING,
            )
            "package" -> mappings += MerebJenkinsConfigStageMapping(
                id = "release-stages",
                label = "Release Stages",
                configPath = MerebJenkinsPath.root().key("releaseStages"),
                group = "Release",
                expectedTokens = setOf("release", "publish", "tag"),
                status = MerebJenkinsStageMappingStatus.MISSING,
            )
            "microfrontend" -> analysis.summary.microfrontendOrder.forEach { environment ->
                mappings += MerebJenkinsConfigStageMapping(
                    id = "microfrontend-$environment",
                    label = "Publish $environment",
                    configPath = MerebJenkinsPath.root().key("microfrontend").key("environments").key(environment),
                    group = "Microfrontend",
                    expectedTokens = setOf("publish", normalizeStageText(environment)),
                    status = MerebJenkinsStageMappingStatus.MISSING,
                )
            }
            "terraform" -> analysis.summary.terraformOrder.forEach { environment ->
                mappings += MerebJenkinsConfigStageMapping(
                    id = "terraform-$environment",
                    label = "Terraform $environment",
                    configPath = MerebJenkinsPath.root().key("terraform").key("environments").key(environment),
                    group = "Terraform",
                    expectedTokens = setOf("terraform", "apply", normalizeStageText(environment)),
                    status = MerebJenkinsStageMappingStatus.MISSING,
                )
            }
        }
        if (analysis.summary.releaseEnabled) {
            mappings += MerebJenkinsConfigStageMapping(
                id = "release",
                label = "Release",
                configPath = MerebJenkinsPath.root().key("release"),
                group = "Release",
                expectedTokens = setOf("release", "tag", "publish"),
                status = MerebJenkinsStageMappingStatus.MISSING,
            )
        }
        return mappings
    }

    private fun mappingScore(expectedTokens: Set<String>, stageName: String): Int {
        if (expectedTokens.isEmpty()) return 0
        val normalizedStage = normalizeStageText(stageName)
        return expectedTokens.count { token ->
            normalizedStage == token || normalizedStage.contains(token)
        }
    }

    private fun shouldIgnoreExtraStage(stageName: String): Boolean {
        val normalized = normalizeStageText(stageName)
        return normalized in setOf("checkout-scm", "declarative-checkout-scm", "declarative-post-actions", "post-actions")
    }

    private fun normalizeStageText(value: String): String {
        return value
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }

    private fun currentConfigRelativePath(scan: MerebJenkinsProjectScan): String? {
        val projectRoot = scan.projectRootPath ?: return null
        val configPath = scan.configFilePath ?: return null
        val root = runCatching { java.nio.file.Paths.get(projectRoot) }.getOrNull() ?: return null
        val config = runCatching { java.nio.file.Paths.get(configPath) }.getOrNull() ?: return null
        return runCatching { root.relativize(config).toString() }.getOrNull()
    }

    private fun stageMentionsEnvironment(stageName: String, normalizedEnvironment: String): Boolean {
        val normalizedStage = normalizeStageText(stageName)
        return normalizedStage.contains(normalizedEnvironment)
    }

    private fun isMainBranch(branchName: String): Boolean {
        val normalized = branchName
            .trim()
            .lowercase()
            .replace(Regex("^refs/heads/"), "")
            .replace(Regex("^origin/"), "")
        return normalized in setOf("main", "master", "trunk")
    }

    private fun List<String>.flattenPreviewDetails(): List<String> {
        return flatMap { detail -> detail.split('\n').map(String::trim).filter(String::isNotBlank) }
            .distinct()
    }
}
