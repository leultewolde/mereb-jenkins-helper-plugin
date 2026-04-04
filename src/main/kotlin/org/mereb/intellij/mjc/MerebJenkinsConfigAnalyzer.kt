package org.mereb.intellij.mjc

import org.yaml.snakeyaml.Yaml

class MerebJenkinsConfigAnalyzer {

    fun analyze(rawText: String): List<Issue> {
        val parsed = try {
            Yaml().load<Any?>(rawText)
        } catch (_: Throwable) {
            return emptyList()
        }
        val root = parsed as? Map<*, *> ?: return emptyList()
        val cfg = root.normalizeMap()
        val issues = mutableListOf<Issue>()

        val version = cfg["version"]
        if (version != null && version.asInt() != 1) {
            issues += Issue.error("version must be 1")
        }

        val build = cfg["build"]
        if (build != null && build !is Map<*, *>) {
            issues += Issue.error("build must be a map")
        }
        val buildMap = (build as? Map<*, *>)?.normalizeMap().orEmpty()
        val buildStages = buildMap["stages"]
        if (buildStages != null && buildStages !is List<*>) {
            issues += Issue.error("build.stages must be a list")
        }

        val pnpm = cfg["pnpm"]
        if (pnpm != null && pnpm !is Map<*, *>) {
            issues += Issue.error("pnpm section must be a map")
        }

        val delivery = cfg["delivery"]
        if (delivery != null && delivery !is Map<*, *>) {
            issues += Issue.error("delivery must be a map")
        }
        val deliveryMap = (delivery as? Map<*, *>)?.normalizeMap().orEmpty()
        val deliveryMode = deliveryMap["mode"]?.asString()?.trim()?.lowercase().orEmpty().ifEmpty { "custom" }
        if (deliveryMode !in setOf("staged", "custom")) {
            issues += Issue.error("delivery.mode must be one of: staged, custom")
        }

        val recipe = cfg["recipe"]?.asString()?.trim().orEmpty()
        val supportedRecipes = setOf("build", "package", "image", "service", "microfrontend", "terraform")
        if (recipe.isBlank()) {
            issues += Issue.warning("Set recipe explicitly to improve readability and editor validation.")
        } else if (recipe !in supportedRecipes) {
            issues += Issue.error("recipe must be one of: build, package, image, service, microfrontend, terraform")
        }

        val imageEnabled = isImageEnabled(cfg)
        if (imageEnabled) {
            val imageMap = (cfg["image"] as? Map<*, *>)?.normalizeMap().orEmpty()
            val appMap = (cfg["app"] as? Map<*, *>)?.normalizeMap().orEmpty()
            val hasRepo = imageMap["repository"]?.asString()?.isNotBlank() == true
            val hasAppName = appMap["name"]?.asString()?.isNotBlank() == true
            if (!hasRepo && !hasAppName) {
                issues += Issue.error("image.repository or app.name must be provided when docker image builds are enabled")
            }
        }

        analyzeDeploy(cfg, deliveryMode, issues)
        analyzeMicrofrontend(cfg, deliveryMode, issues)
        analyzeRelease(cfg, issues)
        analyzeRecipeCompatibility(cfg, recipe, issues)

        if (cfg["pnpm"] == null && buildMap.isEmpty() && cfg["preset"]?.asString()?.isBlank() != false) {
            issues += Issue.warning("No build stages defined. The pipeline will skip build/test unless custom stages are added.")
        }

        return issues.distinct()
    }

    private fun analyzeDeploy(cfg: Map<String, Any?>, deliveryMode: String, issues: MutableList<Issue>) {
        val deploySection = when (val deploy = cfg["deploy"]) {
            is Map<*, *> -> deploy.normalizeMap()
            else -> when (val legacy = cfg["environments"]) {
                is Map<*, *> -> legacy.normalizeMap()
                else -> emptyMap()
            }
        }
        if (deploySection.isEmpty()) {
            return
        }

        val envNodes = deploySection.filterKeys { it != "order" }
        val envNames = envNodes.keys
        val order = deploySection["order"] as? List<*>
        if (order != null) {
            val invalid = order.mapNotNull { it?.asString() }.filter { it !in envNames }
            if (invalid.isNotEmpty()) {
                issues += Issue.error("deploy.order references unknown environments: ${invalid.joinToString(", ")}")
            }
        }

        envNodes.forEach { (name, value) ->
            val envCfg = value as? Map<*, *>
            if (envCfg == null) {
                issues += Issue.error("deploy.$name must be a map")
                return@forEach
            }
            val normalized = envCfg.normalizeMap()
            if (deliveryMode == "staged") {
                if (normalized.containsKey("when")) {
                    issues += Issue.warning("deploy.$name.when is ignored in staged mode")
                }
                if (normalized.containsKey("autoPromote")) {
                    issues += Issue.warning("deploy.$name.autoPromote is ignored in staged mode")
                }
                if (normalized.containsKey("approval") || normalized.containsKey("approve")) {
                    issues += Issue.warning("deploy.$name.approval is ignored in staged mode")
                }
            }
        }
    }

    private fun analyzeMicrofrontend(cfg: Map<String, Any?>, deliveryMode: String, issues: MutableList<Issue>) {
        val mfeSection = (cfg["microfrontend"] as? Map<*, *>)?.normalizeMap().orEmpty()
        if (cfg["microfrontend"] != null && mfeSection.isEmpty() && cfg["microfrontend"] !is Map<*, *>) {
            issues += Issue.error("microfrontend must be a map")
            return
        }
        val envs = (mfeSection["environments"] as? Map<*, *>)?.normalizeMap().orEmpty()
        if (mfeSection.containsKey("environments") && envs.isEmpty() && mfeSection["environments"] !is Map<*, *>) {
            issues += Issue.error("microfrontend.environments must be a map")
            return
        }
        if (envs.isEmpty()) {
            return
        }
        val order = mfeSection["order"] as? List<*>
        if (order != null) {
            val invalid = order.mapNotNull { it?.asString() }.filter { it !in envs.keys }
            if (invalid.isNotEmpty()) {
                issues += Issue.error("microfrontend.order references unknown environments: ${invalid.joinToString(", ")}")
            }
        }
        envs.forEach { (name, value) ->
            val envCfg = value as? Map<*, *>
            if (envCfg == null) {
                issues += Issue.error("microfrontend.$name must be a map")
                return@forEach
            }
            val normalized = envCfg.normalizeMap()
            if (deliveryMode == "staged") {
                if (normalized.containsKey("when")) {
                    issues += Issue.warning("microfrontend.environments.$name.when is ignored in staged mode")
                }
                if (normalized.containsKey("approval")) {
                    issues += Issue.warning("microfrontend.environments.$name.approval is ignored in staged mode")
                }
            }
        }
    }

    private fun analyzeRelease(cfg: Map<String, Any?>, issues: MutableList<Issue>) {
        val release = (cfg["release"] as? Map<*, *>)?.normalizeMap().orEmpty()
        val autoTag = (release["autoTag"] as? Map<*, *>)?.normalizeMap().orEmpty()
        val enabled = autoTag["enabled"]?.asBoolean() ?: true
        if (enabled && !autoTag.containsKey("bump")) {
            issues += Issue.warning("release.autoTag.bump defaults to patch; set explicitly to avoid surprises")
        }
    }

    private fun analyzeRecipeCompatibility(
        cfg: Map<String, Any?>,
        recipe: String,
        issues: MutableList<Issue>
    ) {
        val terraform = hasTerraformEnvironments(cfg)
        val microfrontend = hasMicrofrontendEnvironments(cfg)
        val deploy = hasDeployEnvironments(cfg)
        val image = isImageEnabled(cfg)
        val release = hasReleaseAutomation(cfg)

        if (recipe.isBlank()) {
            if (terraform && (microfrontend || deploy || image)) {
                issues += Issue.error("terraform environments cannot be combined with deploy, microfrontend, or image-enabled orchestration")
            }
            if (microfrontend && (deploy || image)) {
                issues += Issue.error("microfrontend environments cannot be combined with deploy or image-enabled orchestration")
            }
            if (deploy && !image) {
                issues += Issue.error("deploy environments require image builds; service recipes must enable image orchestration")
            }
            return
        }

        when (recipe) {
            "build" -> {
                if (image) issues += Issue.error("recipe=build cannot enable image orchestration")
                if (deploy) issues += Issue.error("recipe=build cannot define deploy environments")
                if (microfrontend) issues += Issue.error("recipe=build cannot define microfrontend environments")
                if (terraform) issues += Issue.error("recipe=build cannot define terraform environments")
                if (release) issues += Issue.error("recipe=build cannot define release automation or release stages")
            }
            "package" -> {
                if (image) issues += Issue.error("recipe=package cannot enable image orchestration")
                if (deploy) issues += Issue.error("recipe=package cannot define deploy environments")
                if (microfrontend) issues += Issue.error("recipe=package cannot define microfrontend environments")
                if (terraform) issues += Issue.error("recipe=package cannot define terraform environments")
                if (!release) issues += Issue.error("recipe=package requires release automation or release stages")
            }
            "image" -> {
                if (!image) issues += Issue.error("recipe=image requires image-enabled orchestration")
                if (deploy) issues += Issue.error("recipe=image cannot define deploy environments")
                if (microfrontend) issues += Issue.error("recipe=image cannot define microfrontend environments")
                if (terraform) issues += Issue.error("recipe=image cannot define terraform environments")
            }
            "service" -> {
                if (!deploy) issues += Issue.error("recipe=service requires deploy environments")
                if (!image) issues += Issue.error("recipe=service requires image-enabled orchestration")
                if (microfrontend) issues += Issue.error("recipe=service cannot define microfrontend environments")
                if (terraform) issues += Issue.error("recipe=service cannot define terraform environments")
            }
            "microfrontend" -> {
                if (!microfrontend) issues += Issue.error("recipe=microfrontend requires microfrontend environments")
                if (image) issues += Issue.error("recipe=microfrontend cannot enable image orchestration")
                if (deploy) issues += Issue.error("recipe=microfrontend cannot define deploy environments")
                if (terraform) issues += Issue.error("recipe=microfrontend cannot define terraform environments")
            }
            "terraform" -> {
                if (!terraform) issues += Issue.error("recipe=terraform requires terraform environments")
                if (image) issues += Issue.error("recipe=terraform cannot enable image orchestration")
                if (deploy) issues += Issue.error("recipe=terraform cannot define deploy environments")
                if (microfrontend) issues += Issue.error("recipe=terraform cannot define microfrontend environments")
            }
        }
    }

    private fun hasDeployEnvironments(cfg: Map<String, Any?>): Boolean {
        val deploy = cfg["deploy"] as? Map<*, *> ?: return false
        return deploy.keys.any { it?.asString() != "order" }
    }

    private fun hasTerraformEnvironments(cfg: Map<String, Any?>): Boolean {
        val terraform = cfg["terraform"] as? Map<*, *> ?: return false
        val envs = terraform["environments"] as? Map<*, *> ?: return false
        return envs.isNotEmpty()
    }

    private fun hasMicrofrontendEnvironments(cfg: Map<String, Any?>): Boolean {
        val mfe = cfg["microfrontend"] as? Map<*, *> ?: return false
        val envs = mfe["environments"] as? Map<*, *> ?: return false
        return envs.isNotEmpty()
    }

    private fun isImageEnabled(cfg: Map<String, Any?>): Boolean {
        val image = cfg["image"]
        return when (image) {
            null -> true
            is Boolean -> image
            is Map<*, *> -> image["enabled"]?.asBoolean() ?: true
            else -> true
        }
    }

    private fun hasReleaseAutomation(cfg: Map<String, Any?>): Boolean {
        val releaseStages = cfg["releaseStages"] as? List<*>
        if (!releaseStages.isNullOrEmpty()) {
            return true
        }
        val release = cfg["release"] as? Map<*, *> ?: return false
        return release.isNotEmpty()
    }

    private fun Map<*, *>.normalizeMap(): Map<String, Any?> {
        return entries.associate { (key, value) -> key.toString() to value }
    }

    private fun Any?.asString(): String = this?.toString().orEmpty()

    private fun Any?.asInt(): Int? {
        return when (this) {
            is Number -> toInt()
            is String -> toIntOrNull()
            else -> null
        }
    }

    private fun Any?.asBoolean(): Boolean? {
        return when (this) {
            is Boolean -> this
            is String -> when (trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
            else -> null
        }
    }

    data class Issue(val severity: Severity, val message: String) {
        companion object {
            fun error(message: String) = Issue(Severity.ERROR, message)
            fun warning(message: String) = Issue(Severity.WARNING, message)
        }
    }

    enum class Severity {
        ERROR,
        WARNING
    }
}
