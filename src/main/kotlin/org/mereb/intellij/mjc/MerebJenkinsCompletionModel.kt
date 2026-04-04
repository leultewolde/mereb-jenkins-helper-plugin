package org.mereb.intellij.mjc

import org.yaml.snakeyaml.Yaml

data class MerebJenkinsCompletionItem(
    val lookup: String,
    val insertText: String,
    val typeText: String,
    val tailText: String? = null,
)

data class MerebJenkinsCompletionRequest(
    val rawText: String,
    val pathString: String? = null,
    val parentPathString: String? = null,
    val linePrefix: String,
    val keyContext: Boolean = false,
    val valueContext: Boolean = true,
)

object MerebJenkinsCompletionModel {
    private val recipeValues = listOf("build", "package", "image", "service", "microfrontend", "terraform")
    private val deliveryModes = listOf("staged", "custom")
    private val bumpValues = listOf("patch", "minor", "major")
    private val presetValues = listOf("node", "pnpm", "docker")
    private val commonEnvironmentNames = listOf("dev", "stg", "prd")

    fun suggestions(
        rawText: String,
        pathString: String?,
        linePrefix: String,
    ): List<MerebJenkinsCompletionItem> {
        return suggestions(
            MerebJenkinsCompletionRequest(
                rawText = rawText,
                pathString = pathString,
                linePrefix = linePrefix,
                valueContext = true,
            )
        )
    }

    fun suggestions(request: MerebJenkinsCompletionRequest): List<MerebJenkinsCompletionItem> {
        val cfg = runCatching { (Yaml().load<Any?>(request.rawText) as? Map<*, *>)?.normalizeMap().orEmpty() }.getOrDefault(emptyMap())
        val suggestions = mutableListOf<MerebJenkinsCompletionItem>()
        val normalizedPath = request.pathString.orEmpty()
        val parentPath = request.parentPathString.orEmpty()
        val trimmedLine = request.linePrefix.trimStart()
        val recipe = cfg["recipe"]?.asString()?.takeIf(String::isNotBlank)

        if (request.valueContext) {
            suggestions += valueSuggestions(cfg, normalizedPath, trimmedLine)
        }

        if (request.keyContext) {
            suggestions += keySuggestions(cfg, parentPath)
            suggestions += contextAwareBlocks(recipe, parentPath)
            suggestions += MerebJenkinsTemplates.snippetTemplates().map { (label, snippet) ->
                MerebJenkinsCompletionItem(label, snippet, "Mereb Jenkins snippet")
            }
        }

        return suggestions.distinctBy { listOf(it.lookup, it.insertText, it.typeText) }
    }

    private fun valueSuggestions(
        cfg: Map<String, Any?>,
        normalizedPath: String,
        trimmedLine: String,
    ): List<MerebJenkinsCompletionItem> = buildList {
        if (normalizedPath == "recipe" || trimmedLine.startsWith("recipe:")) {
            addAll(recipeValues.map { value -> MerebJenkinsCompletionItem(value, value, "recipe") })
        }

        if (normalizedPath == "delivery.mode" || trimmedLine.startsWith("mode:")) {
            addAll(deliveryModes.map { value -> MerebJenkinsCompletionItem(value, value, "delivery mode") })
        }

        if (normalizedPath == "release.autoTag.bump" || trimmedLine.startsWith("bump:")) {
            addAll(bumpValues.map { value -> MerebJenkinsCompletionItem(value, value, "version bump") })
        }

        if (normalizedPath == "preset" || trimmedLine.startsWith("preset:")) {
            addAll(presetValues.map { value -> MerebJenkinsCompletionItem(value, value, "preset") })
        }

        if (normalizedPath.startsWith("deploy.order[")) {
            addAll((deployEnvironmentNames(cfg) + commonEnvironmentNames).distinct().map { value ->
                MerebJenkinsCompletionItem(value, value, "deploy env")
            })
        }

        if (normalizedPath.startsWith("microfrontend.order[")) {
            addAll((microfrontendEnvironmentNames(cfg) + commonEnvironmentNames).distinct().map { value ->
                MerebJenkinsCompletionItem(value, value, "microfrontend env")
            })
        }

        if (normalizedPath.startsWith("terraform.order[")) {
            addAll((terraformEnvironmentNames(cfg) + commonEnvironmentNames).distinct().map { value ->
                MerebJenkinsCompletionItem(value, value, "terraform env")
            })
        }
    }

    private fun keySuggestions(
        cfg: Map<String, Any?>,
        parentPath: String,
    ): List<MerebJenkinsCompletionItem> = when (parentPath) {
        "" -> listOf(
            scalarKey("version"),
            scalarKey("recipe"),
            scalarKey("preset"),
            blockKey("delivery"),
            blockKey("build"),
            blockKey("image"),
            blockKey("release"),
            blockKey("deploy"),
            blockKey("microfrontend"),
            blockKey("terraform"),
        )
        "delivery" -> listOf(scalarKey("mode"))
        "image" -> listOf(scalarKey("repository"), scalarKey("context"), scalarKey("dockerfile"))
        "release" -> listOf(blockKey("autoTag"))
        "release.autoTag" -> listOf(scalarKey("enabled"), scalarKey("when"), scalarKey("bump"))
        "deploy" -> listOf(sequenceKey("order")) + (deployEnvironmentNames(cfg) + commonEnvironmentNames).distinct().map { envKey(it) }
        "microfrontend" -> listOf(scalarKey("name"), scalarKey("packageName"), sequenceKey("order"), blockKey("environments"))
        "microfrontend.environments" -> (microfrontendEnvironmentNames(cfg) + commonEnvironmentNames).distinct().map { envKey(it) }
        "terraform" -> listOf(scalarKey("path"), sequenceKey("order"), blockKey("environments"))
        "terraform.environments" -> (terraformEnvironmentNames(cfg) + commonEnvironmentNames).distinct().map { envKey(it) }
        else -> emptyList()
    }

    private fun contextAwareBlocks(
        recipe: String?,
        parentPath: String,
    ): List<MerebJenkinsCompletionItem> {
        if (parentPath.isNotBlank()) return emptyList()
        val base = mutableListOf<MerebJenkinsCompletionItem>()
        when (recipe) {
            "service" -> {
                base += MerebJenkinsCompletionItem("deploy env: dev", "dev:\n  namespace: apps-dev\n  chart: app-chart", "deploy block")
                base += MerebJenkinsCompletionItem("image block", "image:\n  repository: registry.example.com/your-service\n  context: .\n  dockerfile: Dockerfile", "service block")
            }
            "microfrontend" -> {
                base += MerebJenkinsCompletionItem("microfrontend env: dev", "dev:\n  bucket: cdn-dev\n  publicBase: https://cdn-dev.example.com", "microfrontend block")
            }
            "terraform" -> {
                base += MerebJenkinsCompletionItem("terraform env: dev", "dev:\n  when: branch=main & !pr\n  path: envs/dev", "terraform block")
            }
            "package" -> {
                base += MerebJenkinsCompletionItem("release autoTag", "release:\n  autoTag:\n    enabled: true\n    when: branch=main & !pr\n    bump: patch", "release block")
            }
        }
        return base
    }

    private fun scalarKey(name: String): MerebJenkinsCompletionItem =
        MerebJenkinsCompletionItem(name, "$name: ", "config key")

    private fun blockKey(name: String): MerebJenkinsCompletionItem =
        MerebJenkinsCompletionItem(name, "$name:\n  ", "config block")

    private fun sequenceKey(name: String): MerebJenkinsCompletionItem =
        MerebJenkinsCompletionItem(name, "$name:\n  - ", "config list")

    private fun envKey(name: String): MerebJenkinsCompletionItem =
        MerebJenkinsCompletionItem(name, "$name:\n  ", "environment block")

    private fun deployEnvironmentNames(cfg: Map<String, Any?>): List<String> {
        val deploy = (cfg["deploy"] as? Map<*, *>)?.normalizeMap().orEmpty()
        return deploy.keys.filter { it != "order" }
    }

    private fun microfrontendEnvironmentNames(cfg: Map<String, Any?>): List<String> {
        val microfrontend = (cfg["microfrontend"] as? Map<*, *>)?.normalizeMap().orEmpty()
        return ((microfrontend["environments"] as? Map<*, *>)?.normalizeMap().orEmpty()).keys.toList()
    }

    private fun terraformEnvironmentNames(cfg: Map<String, Any?>): List<String> {
        val terraform = (cfg["terraform"] as? Map<*, *>)?.normalizeMap().orEmpty()
        return ((terraform["environments"] as? Map<*, *>)?.normalizeMap().orEmpty()).keys.toList()
    }
}
