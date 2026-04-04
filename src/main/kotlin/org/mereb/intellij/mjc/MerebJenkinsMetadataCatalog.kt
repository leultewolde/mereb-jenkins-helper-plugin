package org.mereb.intellij.mjc

data class MerebJenkinsMetadata(
    val title: String,
    val inlineSummary: String,
    val description: String,
    val validValues: List<String> = emptyList(),
    val defaultValue: String? = null,
    val runtimeNotes: List<String> = emptyList(),
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
            append("<p>")
            append(runtimeNotes.joinToString(" "))
            append("</p>")
        }
    }
}

object MerebJenkinsMetadataCatalog {
    private val recipeValues = listOf("build", "package", "image", "service", "microfrontend", "terraform")
    private val deliveryModes = listOf("staged", "custom")
    private val bumpValues = listOf("patch", "minor", "major")
    private val topLevelInlinePaths = setOf("recipe", "delivery", "build", "image", "release", "release.autoTag", "deploy", "microfrontend", "terraform")

    fun metadataForPath(path: String, analysis: MerebJenkinsAnalysisResult? = null): MerebJenkinsMetadata? {
        val metadata = when {
        path == "recipe" -> MerebJenkinsMetadata(
            title = "recipe",
            inlineSummary = "Pipeline executor${analysis?.summary?.resolvedRecipe?.let { ": $it" } ?: ""}.",
            description = "Declares the primary pipeline executor that Mereb Jenkins should use for this project.",
            validValues = recipeValues,
            runtimeNotes = listOf("Set this explicitly even when the runtime can infer it."),
        )
        path == "delivery" || path == "delivery.mode" -> MerebJenkinsMetadata(
            title = "delivery",
            inlineSummary = "Promotion rules and staged vs custom behavior.",
            description = "Controls how promotion rules are interpreted across rollout environments.",
            validValues = if (path == "delivery.mode") deliveryModes else emptyList(),
            runtimeNotes = listOf("In staged mode, per-environment keys like when, autoPromote, and approval can be ignored."),
        )
        path == "build" -> MerebJenkinsMetadata(
            title = "build",
            inlineSummary = "Build and test stages for the pipeline.",
            description = "Defines the build/test/custom commands that run before release or deploy phases.",
        )
        path == "image" || path.startsWith("image.") -> MerebJenkinsMetadata(
            title = "image",
            inlineSummary = "Docker image build and publish settings.",
            description = "Controls container image repository, build context, and Dockerfile settings used by service and image recipes.",
        )
        path == "release" || path == "release.autoTag" || path == "release.autoTag.bump" -> MerebJenkinsMetadata(
            title = "release",
            inlineSummary = "Release automation and semantic version tagging.",
            description = "Configures release stages and auto-tag behavior for package, image, service, microfrontend, and terraform recipes.",
            validValues = if (path == "release.autoTag.bump") bumpValues else emptyList(),
            defaultValue = if (path == "release.autoTag.bump") "patch" else null,
        )
        path == "deploy" -> MerebJenkinsMetadata(
            title = "deploy",
            inlineSummary = "Service rollout environments${analysis?.summary?.deployOrder?.takeIf { it.isNotEmpty() }?.let { ": ${it.joinToString(" -> ")}" } ?: ""}.",
            description = "Defines deploy environments and the order in which Mereb Jenkins promotes a service.",
            runtimeNotes = listOf("In staged mode, environment-level when, autoPromote, and approval keys are ignored."),
        )
        isDeployEnvironment(path) -> MerebJenkinsMetadata(
            title = "deploy environment",
            inlineSummary = "One deploy target in the service rollout.",
            description = "Configures an individual service deployment target.",
            runtimeNotes = listOf("Staged delivery ignores environment-level when, autoPromote, and approval keys."),
        )
        path == "microfrontend" -> MerebJenkinsMetadata(
            title = "microfrontend",
            inlineSummary = "Remote publish environments${analysis?.summary?.microfrontendOrder?.takeIf { it.isNotEmpty() }?.let { ": ${it.joinToString(" -> ")}" } ?: ""}.",
            description = "Configures remote publish targets, order, and public asset settings for microfrontend recipes.",
            runtimeNotes = listOf("In staged mode, environment-level when and approval keys are ignored."),
        )
        isMicrofrontendEnvironment(path) -> MerebJenkinsMetadata(
            title = "microfrontend environment",
            inlineSummary = "One microfrontend publish target.",
            description = "Defines a specific microfrontend publish environment.",
            runtimeNotes = listOf("Staged delivery ignores when and approval keys for environment entries."),
        )
        path == "terraform" -> MerebJenkinsMetadata(
            title = "terraform",
            inlineSummary = "Terraform execution environments${analysis?.summary?.terraformOrder?.takeIf { it.isNotEmpty() }?.let { ": ${it.joinToString(" -> ")}" } ?: ""}.",
            description = "Controls Terraform path, environment order, and apply/plan sequencing for infrastructure changes.",
        )
        isTerraformEnvironment(path) -> MerebJenkinsMetadata(
            title = "terraform environment",
            inlineSummary = "One Terraform environment target.",
            description = "Defines a specific Terraform environment and its apply/plan settings.",
        )
        else -> null
        }

        return metadata?.withDynamicNotes(path, analysis)
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
