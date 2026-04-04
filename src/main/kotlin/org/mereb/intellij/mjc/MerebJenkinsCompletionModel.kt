package org.mereb.intellij.mjc

import org.yaml.snakeyaml.Yaml

data class MerebJenkinsCompletionItem(
    val lookup: String,
    val insertText: String,
    val typeText: String,
    val tailText: String? = null,
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
        val cfg = runCatching { (Yaml().load<Any?>(rawText) as? Map<*, *>)?.normalizeMap().orEmpty() }.getOrDefault(emptyMap())
        val suggestions = mutableListOf<MerebJenkinsCompletionItem>()
        val normalizedPath = pathString.orEmpty()
        val trimmedLine = linePrefix.trimStart()

        if (normalizedPath == "recipe" || trimmedLine.startsWith("recipe:")) {
            suggestions += recipeValues.map { value ->
                MerebJenkinsCompletionItem(value, value, "recipe")
            }
        }

        if (normalizedPath == "delivery.mode" || trimmedLine.startsWith("mode:")) {
            suggestions += deliveryModes.map { value ->
                MerebJenkinsCompletionItem(value, value, "delivery mode")
            }
        }

        if (normalizedPath == "release.autoTag.bump" || trimmedLine.startsWith("bump:")) {
            suggestions += bumpValues.map { value ->
                MerebJenkinsCompletionItem(value, value, "version bump")
            }
        }

        if (normalizedPath == "preset" || trimmedLine.startsWith("preset:")) {
            suggestions += presetValues.map { value ->
                MerebJenkinsCompletionItem(value, value, "preset")
            }
        }

        if (normalizedPath.startsWith("deploy.order[")) {
            suggestions += (deployEnvironmentNames(cfg) + commonEnvironmentNames).distinct().map { value ->
                MerebJenkinsCompletionItem(value, value, "deploy env")
            }
        }

        if (normalizedPath.startsWith("microfrontend.order[")) {
            suggestions += (microfrontendEnvironmentNames(cfg) + commonEnvironmentNames).distinct().map { value ->
                MerebJenkinsCompletionItem(value, value, "microfrontend env")
            }
        }

        if (normalizedPath.startsWith("terraform.order[")) {
            suggestions += (terraformEnvironmentNames(cfg) + commonEnvironmentNames).distinct().map { value ->
                MerebJenkinsCompletionItem(value, value, "terraform env")
            }
        }

        val recipe = cfg["recipe"]?.asString()?.takeIf(String::isNotBlank)
        suggestions += contextAwareBlocks(recipe)
        suggestions += MerebJenkinsTemplates.snippetTemplates().map { (label, snippet) ->
            MerebJenkinsCompletionItem(label, snippet, "Mereb Jenkins snippet")
        }
        return suggestions.distinctBy { listOf(it.lookup, it.insertText, it.typeText) }
    }

    private fun contextAwareBlocks(recipe: String?): List<MerebJenkinsCompletionItem> {
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
