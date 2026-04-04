package org.mereb.jenkins.mjc

data class MerebJenkinsMetadata(
    val title: String,
    val inlineSummary: String,
    val description: String,
    val validValues: List<String> = emptyList(),
    val defaultValue: String? = null,
    val runtimeNotes: List<String> = emptyList(),
    val jenkinsImpact: List<String> = emptyList(),
    val improvementSuggestions: List<String> = emptyList(),
) {
    fun toHtml(): String = buildString {
        append("<h2>").append(title).append("</h2>")
        append("<p>").append(description).append("</p>")
        if (validValues.isNotEmpty()) {
            append("<p>Valid values: <code>")
            append(validValues.joinToString("</code>, <code>"))
            append("</code>.</p>")
        }
        defaultValue?.let {
            append("<p>Default: <code>").append(it).append("</code>.</p>")
        }
        if (runtimeNotes.isNotEmpty()) {
            append("<h3>Runtime Notes</h3><ul>")
            runtimeNotes.forEach { append("<li>").append(it).append("</li>") }
            append("</ul>")
        }
        if (jenkinsImpact.isNotEmpty()) {
            append("<h3>Jenkins Impact</h3><ul>")
            jenkinsImpact.forEach { append("<li>").append(it).append("</li>") }
            append("</ul>")
        }
        if (improvementSuggestions.isNotEmpty()) {
            append("<h3>What To Improve</h3><ul>")
            improvementSuggestions.forEach { append("<li>").append(it).append("</li>") }
            append("</ul>")
        }
    }
}

object MerebJenkinsMetadataCatalog {
    private val recipeValues = listOf("build", "package", "image", "service", "microfrontend", "terraform")
    private val deliveryModes = listOf("staged", "custom")
    private val bumpValues = listOf("patch", "minor", "major")
    private val topLevelInlinePaths = setOf("delivery", "build", "image", "release", "deploy", "microfrontend", "terraform")

    fun metadataForPath(path: String, analysis: MerebJenkinsAnalysisResult? = null): MerebJenkinsMetadata? {
        val metadata = when {
            path == "recipe" -> MerebJenkinsMetadata(
                title = "recipe",
                inlineSummary = "Explicit pipeline recipe${analysis?.summary?.resolvedRecipe?.let { ": $it" } ?: ""}.",
                description = "Declares the primary pipeline executor that Mereb Jenkins should use for this project.",
                validValues = recipeValues,
                runtimeNotes = listOf("Set this explicitly even when the runtime can infer it."),
                jenkinsImpact = listOf("Changes which major Jenkins stage families are expected for this project."),
            )
            path == "delivery" || path == "delivery.mode" -> MerebJenkinsMetadata(
                title = "delivery",
                inlineSummary = deliveryInlineSummary(analysis),
                description = "Controls how promotion rules are interpreted across rollout environments.",
                validValues = if (path == "delivery.mode") deliveryModes else emptyList(),
                runtimeNotes = listOf("In staged mode, per-environment keys like when, autoPromote, and approval can be ignored."),
                jenkinsImpact = listOf("Changes how deploy and promotion stages behave across rollout environments."),
            )
            path == "build" -> MerebJenkinsMetadata(
                title = "build",
                inlineSummary = buildInlineSummary(analysis),
                description = "Defines the build, test, and custom commands that run before release or deploy phases.",
                jenkinsImpact = listOf("Maps to bootstrap, build, lint, typecheck, and test Jenkins stage families."),
            )
            path == "image" || path.startsWith("image.") -> MerebJenkinsMetadata(
                title = "image",
                inlineSummary = imageInlineSummary(analysis),
                description = "Controls container image repository, build context, and Dockerfile settings used by service and image recipes.",
                jenkinsImpact = listOf("Maps to Docker build, push, and image verification Jenkins stages."),
            )
            path == "release" || path == "release.autoTag" || path == "release.autoTag.bump" -> MerebJenkinsMetadata(
                title = "release",
                inlineSummary = releaseInlineSummary(analysis),
                description = "Configures release stages and auto-tag behavior for package, image, service, microfrontend, and terraform recipes.",
                validValues = if (path == "release.autoTag.bump") bumpValues else emptyList(),
                defaultValue = if (path == "release.autoTag.bump") "patch" else null,
                jenkinsImpact = listOf("Maps to release-tag and GitHub release Jenkins stages."),
            )
            path == "deploy" -> MerebJenkinsMetadata(
                title = "deploy",
                inlineSummary = deployInlineSummary(analysis),
                description = "Defines deploy environments and the order in which Mereb Jenkins promotes a service.",
                runtimeNotes = listOf("In staged mode, environment-level when, autoPromote, and approval keys are ignored."),
                jenkinsImpact = listOf("Maps deploy and smoke Jenkins stages for each environment in deploy.order."),
            )
            isDeployEnvironment(path) -> MerebJenkinsMetadata(
                title = "deploy environment",
                inlineSummary = deployEnvironmentInlineSummary(path, analysis),
                description = "Configures an individual service deployment target.",
                runtimeNotes = listOf("Staged delivery ignores environment-level when, autoPromote, and approval keys."),
                jenkinsImpact = listOf("Maps to deploy and smoke Jenkins stages for ${path.substringAfter("deploy.").substringBefore('.')}."),
            )
            path == "microfrontend" -> MerebJenkinsMetadata(
                title = "microfrontend",
                inlineSummary = microfrontendInlineSummary(analysis),
                description = "Configures remote publish targets, order, and public asset settings for microfrontend recipes.",
                runtimeNotes = listOf("In staged mode, environment-level when and approval keys are ignored."),
                jenkinsImpact = listOf("Maps publish stages for configured microfrontend environments."),
            )
            isMicrofrontendEnvironment(path) -> MerebJenkinsMetadata(
                title = "microfrontend environment",
                inlineSummary = "One publish target in the current rollout order.",
                description = "Defines a specific microfrontend publish environment.",
                runtimeNotes = listOf("Staged delivery ignores when and approval keys for environment entries."),
                jenkinsImpact = listOf("Maps to publish stages for ${path.substringAfterLast('.')} in Jenkins."),
            )
            path == "terraform" -> MerebJenkinsMetadata(
                title = "terraform",
                inlineSummary = terraformInlineSummary(analysis),
                description = "Controls Terraform path, environment order, and apply/plan sequencing for infrastructure changes.",
                jenkinsImpact = listOf("Maps plan and apply Jenkins stages for configured Terraform environments."),
            )
            isTerraformEnvironment(path) -> MerebJenkinsMetadata(
                title = "terraform environment",
                inlineSummary = "One Terraform execution target in the current environment order.",
                description = "Defines a specific Terraform environment and its apply/plan settings.",
                jenkinsImpact = listOf("Maps to Terraform plan/apply stages for ${path.substringAfterLast('.')} in Jenkins."),
            )
            else -> null
        }

        return metadata
            ?.withDynamicNotes(path, analysis)
            ?.withSuggestions(path, analysis)
    }

    fun shouldRenderInlineHint(path: String): Boolean {
        return path in topLevelInlinePaths
    }

    private fun MerebJenkinsMetadata.withDynamicNotes(path: String, analysis: MerebJenkinsAnalysisResult?): MerebJenkinsMetadata {
        if (analysis == null) return this

        val sectionId = when {
            path == "build" -> "build"
            path == "image" || path.startsWith("image.") -> "image"
            path == "release" || path.startsWith("release.") -> "release"
            path == "deploy" || path.startsWith("deploy.") -> "deploy"
            path == "microfrontend" || path.startsWith("microfrontend.") -> "microfrontend"
            path == "terraform" || path.startsWith("terraform.") -> "terraform"
            else -> null
        }

        val sectionDetail = sectionId
            ?.let { id -> analysis.summary.sections.firstOrNull { it.id == id && it.detail?.isNotBlank() == true }?.detail }

        if (sectionDetail == null) return this
        return copy(runtimeNotes = runtimeNotes + sectionDetail)
    }

    private fun MerebJenkinsMetadata.withSuggestions(path: String, analysis: MerebJenkinsAnalysisResult?): MerebJenkinsMetadata {
        if (analysis == null) return this
        val suggestions = linkedSetOf<String>()
        suggestions += analysis.findings
            .filter { matchesPath(path, it.path) || matchesPath(path, it.anchorPath) }
            .map { it.message }
            .take(3)

        when {
            path == "build" -> suggestions += "Keep step names stable so Jenkins stage mapping stays readable."
            path == "image" -> suggestions += "Keep repository, Dockerfile, and push behavior explicit to reduce drift between config and Jenkins."
            path == "deploy" -> suggestions += deploySuggestions(analysis)
            isDeployEnvironment(path) -> suggestions += deployEnvironmentSuggestions(path, analysis)
            path == "release" || path.startsWith("release.") ->
                suggestions += "Keep release tagging and GitHub release settings aligned with the stages emitted by Jenkins."
        }

        return copy(improvementSuggestions = suggestions.filter(String::isNotBlank).take(4))
    }

    private fun deliveryInlineSummary(analysis: MerebJenkinsAnalysisResult?): String {
        val mode = analysis?.rawConfig?.let(::deliveryMode).orEmpty().ifBlank { "custom" }
        return if (mode == "staged") {
            "Staged promotion rules with environment-level overrides reduced."
        } else {
            "Custom promotion rules and explicit environment behavior."
        }
    }

    private fun buildInlineSummary(analysis: MerebJenkinsAnalysisResult?): String {
        val steps = countBuildSteps(analysis?.rawConfig.orEmpty())
        return when {
            steps > 0 -> "$steps named build or test step${if (steps == 1) "" else "s"} before image, deploy, or release."
            else -> "Build and test stages for the pipeline."
        }
    }

    private fun imageInlineSummary(analysis: MerebJenkinsAnalysisResult?): String {
        val repository = imageRepository(analysis?.rawConfig.orEmpty())
        return repository?.let { "Build and publish image to $it." }
            ?: "Docker image build and publish settings."
    }

    private fun releaseInlineSummary(analysis: MerebJenkinsAnalysisResult?): String {
        val release = (analysis?.rawConfig?.get("release") as? Map<*, *>)?.normalizeMap().orEmpty()
        val autoTag = (release["autoTag"] as? Map<*, *>)?.normalizeMap().orEmpty()
        if (autoTag["enabled"] == true) {
            val bump = autoTag["bump"]?.asString()?.ifBlank { "patch" } ?: "patch"
            val whenExpr = autoTag["when"]?.asString()?.takeIf(String::isNotBlank)
            return buildString {
                append("Auto-tag ")
                append(bump)
                whenExpr?.let { append(" when ").append(it) }
                append('.')
            }
        }
        return "Release automation and semantic version tagging."
    }

    private fun deployInlineSummary(analysis: MerebJenkinsAnalysisResult?): String {
        val order = analysis?.summary?.deployOrder.orEmpty()
        val smokeCount = countDeploySmokeChecks(analysis?.rawConfig.orEmpty())
        return buildString {
            append(order.size)
            append(" environment")
            if (order.size != 1) append('s')
            if (order.isNotEmpty()) {
                append(" in order ")
                append(order.joinToString(" -> "))
            }
            if (smokeCount > 0) {
                append(" • ")
                append(smokeCount)
                append(" smoke check")
                if (smokeCount != 1) append('s')
            }
            append('.')
        }
    }

    private fun deployEnvironmentInlineSummary(path: String, analysis: MerebJenkinsAnalysisResult?): String {
        val environment = path.removePrefix("deploy.").substringBefore('.')
        val inOrder = environment in analysis?.summary?.deployOrder.orEmpty()
        return if (inOrder) {
            "$environment deploy target is included in deploy.order."
        } else {
            "$environment deploy target is defined but not referenced in deploy.order."
        }
    }

    private fun microfrontendInlineSummary(analysis: MerebJenkinsAnalysisResult?): String {
        val order = analysis?.summary?.microfrontendOrder.orEmpty()
        return if (order.isNotEmpty()) {
            "${order.size} publish environment${if (order.size == 1) "" else "s"} in order ${order.joinToString(" -> ")}."
        } else {
            "Remote publish environments and public asset settings."
        }
    }

    private fun terraformInlineSummary(analysis: MerebJenkinsAnalysisResult?): String {
        val order = analysis?.summary?.terraformOrder.orEmpty()
        return if (order.isNotEmpty()) {
            "${order.size} Terraform environment${if (order.size == 1) "" else "s"} in order ${order.joinToString(" -> ")}."
        } else {
            "Terraform execution environments and apply/plan sequencing."
        }
    }

    private fun deploySuggestions(analysis: MerebJenkinsAnalysisResult): List<String> {
        val suggestions = mutableListOf<String>()
        val envNames = deployEnvironmentNames(analysis.rawConfig)
        if (analysis.summary.deployOrder.isEmpty() && envNames.isNotEmpty()) {
            suggestions += "Add deploy.order so rollout order is explicit."
        }
        if (envNames.any { it !in analysis.summary.deployOrder }) {
            suggestions += "Keep deploy.order aligned with the deploy environment blocks that are actually defined."
        }
        if (analysis.summary.ignoredFields.any { it.startsWith("deploy.") }) {
            suggestions += "Staged delivery is ignoring some environment-level deploy keys. Remove them or switch to custom delivery."
        }
        return suggestions
    }

    private fun deployEnvironmentSuggestions(path: String, analysis: MerebJenkinsAnalysisResult): List<String> {
        val environment = path.removePrefix("deploy.").substringBefore('.')
        val suggestions = mutableListOf<String>()
        if (environment !in analysis.summary.deployOrder) {
            suggestions += "Add $environment to deploy.order or remove the unused deploy target."
        }
        if (analysis.summary.ignoredFields.any { it.startsWith("deploy.$environment.") }) {
            suggestions += "Staged delivery is ignoring some keys under deploy.$environment."
        }
        return suggestions
    }

    private fun countBuildSteps(cfg: Map<String, Any?>): Int {
        val build = (cfg["build"] as? Map<*, *>)?.normalizeMap().orEmpty()
        val pnpm = (build["pnpm"] as? Map<*, *>)?.normalizeMap().orEmpty()
        return (pnpm["steps"] as? List<*>)?.size ?: 0
    }

    private fun countDeploySmokeChecks(cfg: Map<String, Any?>): Int {
        val deploy = (cfg["deploy"] as? Map<*, *>)?.normalizeMap().orEmpty()
        return deploy.values.count { value ->
            val env = (value as? Map<*, *>)?.normalizeMap().orEmpty()
            env["smoke"] is Map<*, *>
        }
    }

    private fun deployEnvironmentNames(cfg: Map<String, Any?>): List<String> {
        val deploy = (cfg["deploy"] as? Map<*, *>)?.normalizeMap().orEmpty()
        return deploy.keys.filter { it != "order" }
    }

    private fun deliveryMode(cfg: Map<String, Any?>): String {
        val delivery = (cfg["delivery"] as? Map<*, *>)?.normalizeMap().orEmpty()
        return delivery["mode"]?.asString()?.trim()?.lowercase().orEmpty().ifBlank { "custom" }
    }

    private fun imageRepository(cfg: Map<String, Any?>): String? {
        val image = (cfg["image"] as? Map<*, *>)?.normalizeMap().orEmpty()
        return image["repository"]?.asString()?.takeIf(String::isNotBlank)
    }

    private fun matchesPath(path: String, candidate: MerebJenkinsPath?): Boolean {
        val candidatePath = candidate?.toString() ?: return false
        return candidatePath == path || candidatePath.startsWith("$path.") || candidatePath.startsWith("$path[")
    }

    private fun isDeployEnvironment(path: String): Boolean {
        val segments = path.split('.')
        return segments.size == 2 && segments.first() == "deploy" && segments.last() != "order"
    }

    private fun isMicrofrontendEnvironment(path: String): Boolean {
        val segments = path.split('.')
        return segments.size == 3 && segments.take(2) == listOf("microfrontend", "environments")
    }

    private fun isTerraformEnvironment(path: String): Boolean {
        val segments = path.split('.')
        return segments.size == 3 && segments.take(2) == listOf("terraform", "environments")
    }
}
