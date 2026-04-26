package org.mereb.jenkins.mjc

import kotlin.math.min
import org.yaml.snakeyaml.Yaml

class MerebJenkinsConfigEngine {
    private val reservedDeployKeys = setOf("order", "defaults", "generatedBaseDefaults")
    private val supportedRecipes = setOf("build", "package", "image", "service", "microfrontend", "terraform")

    fun analyze(rawText: String, configFilePath: String? = null): MerebJenkinsAnalysisResult {
        val projectScan = MerebJenkinsProjectScanner.scan(configFilePath)
        val parsed = try {
            Yaml().load<Any?>(rawText)
        } catch (error: Throwable) {
            return MerebJenkinsAnalysisResult(
                projectScan = projectScan,
                parseError = error.message ?: "Unable to parse YAML",
                findings = listOf(
                    MerebJenkinsFinding(
                        id = "yaml-parse-error",
                        severity = MerebJenkinsSeverity.ERROR,
                        message = "Invalid YAML: ${error.message ?: "parse failed"}",
                    )
                ),
            )
        }

        val cfg = (parsed as? Map<*, *>)?.normalizeMap().orEmpty()
        val findings = mutableListOf<MerebJenkinsFinding>()

        if (cfg["version"] != null && cfg["version"].asInt() != 1) {
            findings += finding("version-invalid", MerebJenkinsSeverity.ERROR, "version must be 1", MerebJenkinsPath.root().key("version"))
        }

        val explicitRecipe = cfg["recipe"]?.asString()?.trim()?.takeIf(String::isNotEmpty)
        val resolvedRecipe = resolveRecipe(cfg, explicitRecipe)
        val suggestedRecipe = projectScan.expectedRecipe ?: resolvedRecipe

        when {
            explicitRecipe == null -> {
                findings += finding(
                    id = "recipe-missing",
                    severity = MerebJenkinsSeverity.WARNING,
                    message = "Set recipe explicitly to improve readability and editor validation.",
                    anchorPath = MerebJenkinsPath.root().key("version"),
                    quickFixes = listOf(
                        MerebJenkinsFixSuggestion(
                            kind = MerebJenkinsFixKind.ADD_RECIPE,
                            label = "Add recipe: $suggestedRecipe",
                            data = mapOf("recipe" to suggestedRecipe),
                        )
                    ),
                )
            }
            explicitRecipe !in supportedRecipes -> {
                val replacement = closestRecipe(explicitRecipe)
                findings += finding(
                    id = "recipe-invalid",
                    severity = MerebJenkinsSeverity.ERROR,
                    message = "recipe must be one of: ${supportedRecipes.joinToString(", ")}",
                    path = MerebJenkinsPath.root().key("recipe"),
                    quickFixes = replacement
                        ?.let {
                            listOf(
                                MerebJenkinsFixSuggestion(
                                    kind = MerebJenkinsFixKind.REPLACE_RECIPE,
                                    label = "Replace recipe with $it",
                                    data = mapOf("recipe" to it),
                                )
                            )
                        }
                        .orEmpty(),
                )
            }
        }

        val imageEnabled = isImageEnabled(cfg)
        if (imageEnabled && !hasImageRepository(cfg)) {
            findings += finding(
                id = "image-repository-missing",
                severity = MerebJenkinsSeverity.ERROR,
                message = "image.repository or app.name must be provided when docker image builds are enabled",
                path = MerebJenkinsPath.root().key("image"),
                quickFixes = listOf(
                    MerebJenkinsFixSuggestion(
                        kind = MerebJenkinsFixKind.ADD_IMAGE_REPOSITORY,
                        label = "Add image.repository placeholder",
                        data = mapOf("repository" to suggestedImageRepository(projectScan)),
                    )
                ),
            )
        }

        analyzeDeploy(cfg, findings)
        analyzeMicrofrontend(cfg, findings)
        analyzeTerraform(cfg, findings)
        analyzeRelease(cfg, findings)
        analyzeRecipeCompatibility(cfg, explicitRecipe, resolvedRecipe, projectScan, findings)
        analyzeRepoAwareness(projectScan, findings)

        if (cfg["pnpm"] == null && (cfg["build"] as? Map<*, *>)?.isEmpty() != false && cfg["preset"]?.asString().isNullOrBlank()) {
            findings += finding(
                id = "build-empty",
                severity = MerebJenkinsSeverity.WARNING,
                message = "No build stages defined. The pipeline will skip build/test unless custom stages are added.",
                anchorPath = MerebJenkinsPath.root().key("version"),
            )
        }

        return MerebJenkinsAnalysisResult(
            rawConfig = cfg,
            resolvedRecipe = resolvedRecipe,
            findings = findings.distinctBy { listOf(it.id, it.message, it.path?.toString(), it.anchorPath?.toString()) },
            summary = buildSummary(cfg, explicitRecipe, resolvedRecipe, findings),
            projectScan = projectScan,
        )
    }

    private fun analyzeDeploy(cfg: Map<String, Any?>, findings: MutableList<MerebJenkinsFinding>) {
        val deploy = when (val direct = cfg["deploy"]) {
            is Map<*, *> -> direct.normalizeMap()
            else -> when (val legacy = cfg["environments"]) {
                is Map<*, *> -> legacy.normalizeMap()
                else -> emptyMap()
            }
        }
        if (deploy.isEmpty()) return

        val deliveryMode = deliveryMode(cfg)
        val envs = deploy.filterKeys { it !in reservedDeployKeys }
        val order = deploy["order"] as? List<*>
        if (order != null) {
            val validNames = envs.keys.toList()
            order.mapIndexedNotNull { index, value ->
                val name = value?.asString() ?: return@mapIndexedNotNull null
                if (name !in envs.keys) index to name else null
            }.forEach { (index, name) ->
                findings += finding(
                    id = "deploy-order-unknown",
                    severity = MerebJenkinsSeverity.ERROR,
                    message = "deploy.order references unknown environment: $name",
                    path = MerebJenkinsPath.root().key("deploy").key("order").index(index),
                    quickFixes = listOf(
                        MerebJenkinsFixSuggestion(
                            kind = MerebJenkinsFixKind.FIX_ORDER,
                            label = "Remove unknown values from deploy.order",
                            data = mapOf("path" to "deploy.order", "validNames" to validNames.joinToString(",")),
                        )
                    ),
                )
            }
        }

        envs.forEach { (name, value) ->
            val env = (value as? Map<*, *>)?.normalizeMap()
            if (env == null) {
                findings += finding(
                    id = "deploy-env-type",
                    severity = MerebJenkinsSeverity.ERROR,
                    message = "deploy.$name must be a map",
                    path = MerebJenkinsPath.root().key("deploy").key(name),
                )
                return@forEach
            }

            if (deliveryMode == "staged") {
                listOf("when", "autoPromote", "approval", "approve").forEach { key ->
                    if (env.containsKey(key)) findings += ignoredFinding("deploy", name, key)
                }
            }
        }
    }

    private fun analyzeMicrofrontend(cfg: Map<String, Any?>, findings: MutableList<MerebJenkinsFinding>) {
        val microfrontend = (cfg["microfrontend"] as? Map<*, *>)?.normalizeMap()
        if (cfg["microfrontend"] != null && microfrontend == null) {
            findings += finding(
                id = "microfrontend-type",
                severity = MerebJenkinsSeverity.ERROR,
                message = "microfrontend must be a map",
                path = MerebJenkinsPath.root().key("microfrontend"),
            )
            return
        }
        val envs = (microfrontend?.get("environments") as? Map<*, *>)?.normalizeMap().orEmpty()
        if (envs.isEmpty()) return

        val order = microfrontend?.get("order") as? List<*>
        if (order != null) {
            val validNames = envs.keys.toList()
            order.mapIndexedNotNull { index, value ->
                val name = value?.asString() ?: return@mapIndexedNotNull null
                if (name !in envs.keys) index to name else null
            }.forEach { (index, name) ->
                findings += finding(
                    id = "microfrontend-order-unknown",
                    severity = MerebJenkinsSeverity.ERROR,
                    message = "microfrontend.order references unknown environment: $name",
                    path = MerebJenkinsPath.root().key("microfrontend").key("order").index(index),
                    quickFixes = listOf(
                        MerebJenkinsFixSuggestion(
                            kind = MerebJenkinsFixKind.FIX_ORDER,
                            label = "Remove unknown values from microfrontend.order",
                            data = mapOf("path" to "microfrontend.order", "validNames" to validNames.joinToString(",")),
                        )
                    ),
                )
            }
        }

        if (deliveryMode(cfg) == "staged") {
            envs.forEach { (name, value) ->
                val env = (value as? Map<*, *>)?.normalizeMap().orEmpty()
                listOf("when", "approval").forEach { key ->
                    if (env.containsKey(key)) findings += ignoredFinding("microfrontend.environments", name, key)
                }
            }
        }
    }

    private fun analyzeTerraform(cfg: Map<String, Any?>, findings: MutableList<MerebJenkinsFinding>) {
        val terraform = (cfg["terraform"] as? Map<*, *>)?.normalizeMap() ?: return
        val envs = (terraform["environments"] as? Map<*, *>)?.normalizeMap().orEmpty()
        val order = terraform["order"] as? List<*>
        if (envs.isEmpty() || order == null) return

        order.mapIndexedNotNull { index, value ->
            val name = value?.asString() ?: return@mapIndexedNotNull null
            if (name !in envs.keys) index to name else null
        }.forEach { (index, name) ->
            findings += finding(
                id = "terraform-order-unknown",
                severity = MerebJenkinsSeverity.ERROR,
                message = "terraform.order references unknown environment: $name",
                path = MerebJenkinsPath.root().key("terraform").key("order").index(index),
            )
        }
    }

    private fun analyzeRelease(cfg: Map<String, Any?>, findings: MutableList<MerebJenkinsFinding>) {
        val release = (cfg["release"] as? Map<*, *>)?.normalizeMap().orEmpty()
        val autoTag = (release["autoTag"] as? Map<*, *>)?.normalizeMap().orEmpty()
        val enabled = autoTag["enabled"]?.asBoolean() ?: true
        if (enabled && !autoTag.containsKey("bump")) {
            findings += finding(
                id = "release-autoTag-bump-default",
                severity = MerebJenkinsSeverity.WARNING,
                message = "release.autoTag.bump defaults to patch; set explicitly to avoid surprises",
                path = MerebJenkinsPath.root().key("release").key("autoTag"),
            )
        }
    }

    private fun analyzeRecipeCompatibility(
        cfg: Map<String, Any?>,
        explicitRecipe: String?,
        resolvedRecipe: String,
        projectScan: MerebJenkinsProjectScan,
        findings: MutableList<MerebJenkinsFinding>,
    ) {
        val terraform = hasTerraformEnvironments(cfg)
        val microfrontend = hasMicrofrontendEnvironments(cfg)
        val deploy = hasDeployEnvironments(cfg)
        val image = isImageEnabled(cfg)
        val release = hasReleaseAutomation(cfg)

        if (explicitRecipe == null) {
            if (terraform && (microfrontend || deploy || image)) {
                findings += incompatibleSection("terraform environments cannot be combined with deploy, microfrontend, or image-enabled orchestration", "terraform")
            }
            if (microfrontend && (deploy || image)) {
                findings += incompatibleSection("microfrontend environments cannot be combined with deploy or image-enabled orchestration", "microfrontend")
            }
            if (deploy && !image) {
                findings += finding(
                    id = "recipe-combo-service",
                    severity = MerebJenkinsSeverity.ERROR,
                    message = "deploy environments require image builds; service recipes must enable image orchestration",
                    path = MerebJenkinsPath.root().key("deploy"),
                    quickFixes = listOf(
                        MerebJenkinsFixSuggestion(
                            kind = MerebJenkinsFixKind.ADD_IMAGE_REPOSITORY,
                            label = "Add image.repository placeholder",
                            data = mapOf("repository" to suggestedImageRepository(projectScan)),
                        )
                    ),
                )
            }
        }

        when (explicitRecipe ?: resolvedRecipe) {
            "build" -> {
                if (image) findings += incompatibleSection("recipe=build cannot enable image orchestration", "image")
                if (deploy) findings += incompatibleSection("recipe=build cannot define deploy environments", "deploy")
                if (microfrontend) findings += incompatibleSection("recipe=build cannot define microfrontend environments", "microfrontend")
                if (terraform) findings += incompatibleSection("recipe=build cannot define terraform environments", "terraform")
                if (release) findings += incompatibleSection("recipe=build cannot define release automation or release stages", "release")
            }
            "package" -> {
                if (image) findings += incompatibleSection("recipe=package cannot enable image orchestration", "image")
                if (deploy) findings += incompatibleSection("recipe=package cannot define deploy environments", "deploy")
                if (microfrontend) findings += incompatibleSection("recipe=package cannot define microfrontend environments", "microfrontend")
                if (terraform) findings += incompatibleSection("recipe=package cannot define terraform environments", "terraform")
                if (!release) {
                    findings += finding(
                        id = "recipe-package-release",
                        severity = MerebJenkinsSeverity.ERROR,
                        message = "recipe=package requires release automation or release stages",
                        path = MerebJenkinsPath.root().key("recipe"),
                    )
                }
            }
            "image" -> {
                if (!image) {
                    findings += finding(
                        id = "recipe-image-requires-image",
                        severity = MerebJenkinsSeverity.ERROR,
                        message = "recipe=image requires image-enabled orchestration",
                        path = MerebJenkinsPath.root().key("recipe"),
                        quickFixes = listOf(
                            MerebJenkinsFixSuggestion(
                                kind = MerebJenkinsFixKind.ADD_IMAGE_REPOSITORY,
                                label = "Add image.repository placeholder",
                                data = mapOf("repository" to suggestedImageRepository(projectScan)),
                            )
                        ),
                    )
                }
                if (deploy) findings += incompatibleSection("recipe=image cannot define deploy environments", "deploy")
                if (microfrontend) findings += incompatibleSection("recipe=image cannot define microfrontend environments", "microfrontend")
                if (terraform) findings += incompatibleSection("recipe=image cannot define terraform environments", "terraform")
            }
            "service" -> {
                if (!deploy) {
                    findings += finding(
                        id = "recipe-service-deploy",
                        severity = MerebJenkinsSeverity.ERROR,
                        message = "recipe=service requires deploy environments",
                        path = MerebJenkinsPath.root().key("recipe"),
                    )
                }
                if (!image) {
                    findings += finding(
                        id = "recipe-service-image",
                        severity = MerebJenkinsSeverity.ERROR,
                        message = "recipe=service requires image-enabled orchestration",
                        path = MerebJenkinsPath.root().key("recipe"),
                        quickFixes = listOf(
                            MerebJenkinsFixSuggestion(
                                kind = MerebJenkinsFixKind.ADD_IMAGE_REPOSITORY,
                                label = "Add image.repository placeholder",
                                data = mapOf("repository" to suggestedImageRepository(projectScan)),
                            )
                        ),
                    )
                }
                if (microfrontend) findings += incompatibleSection("recipe=service cannot define microfrontend environments", "microfrontend")
                if (terraform) findings += incompatibleSection("recipe=service cannot define terraform environments", "terraform")
            }
            "microfrontend" -> {
                if (!microfrontend) {
                    findings += finding(
                        id = "recipe-microfrontend-envs",
                        severity = MerebJenkinsSeverity.ERROR,
                        message = "recipe=microfrontend requires microfrontend environments",
                        path = MerebJenkinsPath.root().key("recipe"),
                    )
                }
                if (image) findings += incompatibleSection("recipe=microfrontend cannot enable image orchestration", "image")
                if (deploy) findings += incompatibleSection("recipe=microfrontend cannot define deploy environments", "deploy")
                if (terraform) findings += incompatibleSection("recipe=microfrontend cannot define terraform environments", "terraform")
            }
            "terraform" -> {
                if (!terraform) {
                    findings += finding(
                        id = "recipe-terraform-envs",
                        severity = MerebJenkinsSeverity.ERROR,
                        message = "recipe=terraform requires terraform environments",
                        path = MerebJenkinsPath.root().key("recipe"),
                    )
                }
                if (image) findings += incompatibleSection("recipe=terraform cannot enable image orchestration", "image")
                if (deploy) findings += incompatibleSection("recipe=terraform cannot define deploy environments", "deploy")
                if (microfrontend) findings += incompatibleSection("recipe=terraform cannot define microfrontend environments", "microfrontend")
            }
        }

        projectScan.expectedRecipe?.let { expected ->
            if (explicitRecipe != null && explicitRecipe in supportedRecipes && explicitRecipe != expected) {
                findings += finding(
                    id = "recipe-expected-mismatch",
                    severity = MerebJenkinsSeverity.WARNING,
                    message = "This project path usually maps to recipe=$expected, but the config says recipe=$explicitRecipe",
                    path = MerebJenkinsPath.root().key("recipe"),
                    quickFixes = listOf(
                        MerebJenkinsFixSuggestion(
                            kind = MerebJenkinsFixKind.REPLACE_RECIPE,
                            label = "Replace recipe with $expected",
                            data = mapOf("recipe" to expected),
                        )
                    ),
                )
            }
        }
    }

    private fun analyzeRepoAwareness(projectScan: MerebJenkinsProjectScan, findings: MutableList<MerebJenkinsFinding>) {
        val configPath = projectScan.jenkinsfileConfigPath ?: return
        if (MerebJenkinsConfigPaths.isLegacyPath(configPath)) {
            findings += finding(
                id = "jenkinsfile-legacy-path",
                severity = MerebJenkinsSeverity.WARNING,
                message = "Jenkinsfile still points to $configPath; prefer ${MerebJenkinsConfigPaths.preferredPath()}",
                anchorPath = MerebJenkinsPath.root().key("version"),
                quickFixes = listOf(
                    MerebJenkinsFixSuggestion(
                        kind = MerebJenkinsFixKind.UPDATE_JENKINSFILE_CONFIG_PATH,
                        label = "Update Jenkinsfile configPath to ${MerebJenkinsConfigPaths.preferredPath()}",
                        data = mapOf("path" to MerebJenkinsConfigPaths.preferredPath()),
                    )
                ),
            )
        }
    }

    private fun buildSummary(
        cfg: Map<String, Any?>,
        explicitRecipe: String?,
        resolvedRecipe: String,
        findings: List<MerebJenkinsFinding>,
    ): MerebJenkinsPipelineSummary {
        val deployOrder = deployOrder(cfg)
        val microfrontendOrder = microfrontendOrder(cfg)
        val terraformOrder = terraformOrder(cfg)
        val relations = buildRelations(cfg, resolvedRecipe, deployOrder, microfrontendOrder, terraformOrder) +
            findingsToRelations(findings)
        val referencedButMissing = relations.filter { it.status == MerebJenkinsRelationStatus.MISSING }.map { it.label }
        val definedButUnused = relations.filter { it.status == MerebJenkinsRelationStatus.UNUSED }.map { it.label }
        val safeFixes = findings
            .flatMap { it.quickFixes }
            .filter { it.kind in setOf(
                MerebJenkinsFixKind.ADD_RECIPE,
                MerebJenkinsFixKind.REPLACE_RECIPE,
                MerebJenkinsFixKind.RENAME_CONFIG_FILE,
                MerebJenkinsFixKind.UPDATE_JENKINSFILE_CONFIG_PATH,
                MerebJenkinsFixKind.REMOVE_KEY,
                MerebJenkinsFixKind.FIX_ORDER,
                MerebJenkinsFixKind.ADD_IMAGE_REPOSITORY,
            ) }
            .distinctBy { listOf(it.kind, it.label, it.data) }
        return MerebJenkinsPipelineSummary(
            explicitRecipe = explicitRecipe,
            resolvedRecipe = resolvedRecipe,
            imageEnabled = isImageEnabled(cfg),
            releaseEnabled = hasReleaseAutomation(cfg),
            deployOrder = deployOrder,
            microfrontendOrder = microfrontendOrder,
            terraformOrder = terraformOrder,
            ignoredFields = findings.filter { it.id.startsWith("ignored-") }.map { it.message },
            repoWarnings = findings.filter { it.id.startsWith("jenkinsfile-") || it.id == "recipe-expected-mismatch" }.map { it.message },
            referencedButMissing = referencedButMissing,
            definedButUnused = definedButUnused,
            capabilities = buildCapabilities(cfg, resolvedRecipe),
            sections = buildSectionStates(cfg, resolvedRecipe),
            relations = relations,
            safeFixes = safeFixes,
            flowSteps = buildFlowSteps(cfg, resolvedRecipe, deployOrder, microfrontendOrder, terraformOrder, hasReleaseAutomation(cfg)),
            errorCount = findings.count { it.severity == MerebJenkinsSeverity.ERROR },
            warningCount = findings.count { it.severity == MerebJenkinsSeverity.WARNING },
        )
    }

    private fun buildFlowSteps(
        cfg: Map<String, Any?>,
        recipe: String,
        deployOrder: List<String>,
        microfrontendOrder: List<String>,
        terraformOrder: List<String>,
        releaseEnabled: Boolean,
    ): List<MerebJenkinsFlowStep> {
        val steps = mutableListOf(MerebJenkinsFlowStep("Build", MerebJenkinsPath.root().key("build")))
        when (recipe) {
            "service" -> {
                steps += MerebJenkinsFlowStep("Build Image", MerebJenkinsPath.root().key("image"))
                steps += deployOrder.ifEmpty { listOf("Deploy") }.flatMap { envName ->
                    buildList {
                        add(MerebJenkinsFlowStep("Deploy $envName", MerebJenkinsPath.root().key("deploy").key(envName)))
                        postDeployStageNames(cfg, envName).forEachIndexed { index, stageName ->
                            add(
                                MerebJenkinsFlowStep(
                                    "Post-Deploy $envName: $stageName",
                                    MerebJenkinsPath.root().key("deploy").key(envName).key("postDeployStages").index(index)
                                )
                            )
                        }
                    }
                }
            }
            "image" -> steps += MerebJenkinsFlowStep("Build Image", MerebJenkinsPath.root().key("image"))
            "microfrontend" -> steps += microfrontendOrder.ifEmpty { listOf("Publish") }.map {
                MerebJenkinsFlowStep("Publish $it", MerebJenkinsPath.root().key("microfrontend").key("environments").key(it))
            }
            "terraform" -> steps += terraformOrder.ifEmpty { listOf("Terraform") }.map {
                MerebJenkinsFlowStep("Terraform $it", MerebJenkinsPath.root().key("terraform").key("environments").key(it))
            }
            "package" -> steps += MerebJenkinsFlowStep("Release Stages", MerebJenkinsPath.root().key("releaseStages"))
        }
        if (releaseEnabled) steps += MerebJenkinsFlowStep("Release", MerebJenkinsPath.root().key("release"))
        return steps
    }

    private fun buildCapabilities(cfg: Map<String, Any?>, recipe: String): List<MerebJenkinsCapability> {
        val deployEnvCount = deployEnvironmentNames(cfg).size
        val microfrontendEnvCount = microfrontendEnvironmentNames(cfg).size
        val terraformEnvCount = terraformEnvironmentNames(cfg).size
        return listOf(
            MerebJenkinsCapability("recipe", recipe, true, if (cfg["recipe"] == null) "Inferred from config shape" else "Explicitly configured"),
            MerebJenkinsCapability("image", "Image", isImageEnabled(cfg), if (isImageEnabled(cfg)) imageRepositoryLabel(cfg) else "Disabled"),
            MerebJenkinsCapability("release", "Release", hasReleaseAutomation(cfg), if (hasReleaseAutomation(cfg)) "Tagging or release stages configured" else "No release automation"),
            MerebJenkinsCapability("deploy", "Deploy", deployEnvCount > 0, if (deployEnvCount > 0) "$deployEnvCount environment(s)" else "No deploy environments"),
            MerebJenkinsCapability("microfrontend", "Microfrontend", microfrontendEnvCount > 0, if (microfrontendEnvCount > 0) "$microfrontendEnvCount environment(s)" else "No microfrontend environments"),
            MerebJenkinsCapability("terraform", "Terraform", terraformEnvCount > 0, if (terraformEnvCount > 0) "$terraformEnvCount environment(s)" else "No terraform environments"),
        )
    }

    private fun buildSectionStates(cfg: Map<String, Any?>, recipe: String): List<MerebJenkinsSectionState> {
        val activeSections = activeSectionsForRecipe(recipe)
        val requiredSections = requiredSectionsForRecipe(recipe)
        val sectionDefs = listOf(
            Triple("build", "Build", sectionDefined(cfg, "build", isActive = true)),
            Triple("image", "Image", sectionDefined(cfg, "image", isImageEnabled(cfg))),
            Triple("deploy", "Deploy", hasDeployEnvironments(cfg)),
            Triple("microfrontend", "Microfrontend", hasMicrofrontendEnvironments(cfg)),
            Triple("terraform", "Terraform", hasTerraformEnvironments(cfg)),
            Triple("release", "Release", hasReleaseAutomation(cfg)),
        )

        return sectionDefs.map { (id, label, defined) ->
            val status = when {
                id in requiredSections && !defined -> MerebJenkinsRelationStatus.MISSING
                defined && id !in activeSections -> MerebJenkinsRelationStatus.INACTIVE
                defined -> MerebJenkinsRelationStatus.OK
                else -> MerebJenkinsRelationStatus.INACTIVE
            }
            val detail = when {
                id in requiredSections && !defined -> "Required by recipe=$recipe"
                defined && id !in activeSections -> "Defined, but not active for recipe=$recipe"
                defined -> "Active for recipe=$recipe"
                id in activeSections -> "Optional for recipe=$recipe"
                else -> "Not used for recipe=$recipe"
            }
            MerebJenkinsSectionState(
                id = id,
                label = label,
                path = if (id == "release") MerebJenkinsPath.root().key("release") else MerebJenkinsPath.root().key(id),
                status = status,
                detail = detail,
            )
        }
    }

    private fun buildRelations(
        cfg: Map<String, Any?>,
        recipe: String,
        deployOrder: List<String>,
        microfrontendOrder: List<String>,
        terraformOrder: List<String>,
    ): List<MerebJenkinsRelation> {
        val relations = mutableListOf<MerebJenkinsRelation>()
        val recipePath = MerebJenkinsPath.root().key("recipe")
        val activeSections = activeSectionsForRecipe(recipe)
        val requiredSections = requiredSectionsForRecipe(recipe)

        buildSectionStates(cfg, recipe).forEach { section ->
            val label = "recipe=$recipe -> ${section.label}"
            val status = when {
                section.id in requiredSections && section.status == MerebJenkinsRelationStatus.MISSING -> MerebJenkinsRelationStatus.MISSING
                section.id !in activeSections && section.status == MerebJenkinsRelationStatus.OK -> MerebJenkinsRelationStatus.INACTIVE
                section.id !in activeSections -> MerebJenkinsRelationStatus.INACTIVE
                section.status == MerebJenkinsRelationStatus.OK -> MerebJenkinsRelationStatus.OK
                else -> section.status
            }
            relations += MerebJenkinsRelation(
                id = "recipe-${section.id}",
                group = "Recipe",
                label = label,
                sourcePath = recipePath,
                targetPath = section.path,
                status = status,
                detail = section.detail,
            )
        }

        relations += buildOrderRelations(
            group = "Deploy",
            labelPrefix = "deploy.order",
            order = deployOrder,
            knownNames = deployEnvironmentNames(cfg),
            orderRoot = MerebJenkinsPath.root().key("deploy").key("order"),
            targetRoot = MerebJenkinsPath.root().key("deploy"),
        )
        relations += buildOrderRelations(
            group = "Microfrontend",
            labelPrefix = "microfrontend.order",
            order = microfrontendOrder,
            knownNames = microfrontendEnvironmentNames(cfg),
            orderRoot = MerebJenkinsPath.root().key("microfrontend").key("order"),
            targetRoot = MerebJenkinsPath.root().key("microfrontend").key("environments"),
        )
        relations += buildOrderRelations(
            group = "Terraform",
            labelPrefix = "terraform.order",
            order = terraformOrder,
            knownNames = terraformEnvironmentNames(cfg),
            orderRoot = MerebJenkinsPath.root().key("terraform").key("order"),
            targetRoot = MerebJenkinsPath.root().key("terraform").key("environments"),
        )

        return relations.distinctBy { listOf(it.id, it.label, it.group, it.sourcePath?.toString(), it.targetPath?.toString()) }
    }

    private fun buildOrderRelations(
        group: String,
        labelPrefix: String,
        order: List<String>,
        knownNames: List<String>,
        orderRoot: MerebJenkinsPath,
        targetRoot: MerebJenkinsPath,
    ): List<MerebJenkinsRelation> {
        val relations = mutableListOf<MerebJenkinsRelation>()
        order.forEachIndexed { index, name ->
            val exists = name in knownNames
            relations += MerebJenkinsRelation(
                id = "$labelPrefix-$name-$index",
                group = group,
                label = "$labelPrefix[$index] -> $name",
                sourcePath = orderRoot.index(index),
                targetPath = if (exists) targetRoot.key(name) else null,
                status = if (exists) MerebJenkinsRelationStatus.OK else MerebJenkinsRelationStatus.MISSING,
                detail = if (exists) "Matches ${targetRoot.key(name)}" else "Referenced but missing target: $name",
            )
        }
        if (order.isNotEmpty()) {
            knownNames.filterNot { it in order }.forEach { name ->
                relations += MerebJenkinsRelation(
                    id = "$labelPrefix-unused-$name",
                    group = group,
                    label = "$name defined but not referenced by $labelPrefix",
                    sourcePath = targetRoot.key(name),
                    targetPath = orderRoot,
                    status = MerebJenkinsRelationStatus.UNUSED,
                    detail = "Defined but unused",
                )
            }
        }
        return relations
    }

    private fun findingsToRelations(findings: List<MerebJenkinsFinding>): List<MerebJenkinsRelation> {
        return findings.filter { it.id.startsWith("ignored-") }.map {
            MerebJenkinsRelation(
                id = "finding-${it.id}",
                group = "Runtime",
                label = it.message,
                sourcePath = it.path,
                status = MerebJenkinsRelationStatus.IGNORED,
                detail = "Ignored by runtime behavior",
            )
        }
    }

    private fun activeSectionsForRecipe(recipe: String): Set<String> = when (recipe) {
        "build" -> setOf("build")
        "package" -> setOf("build", "release")
        "image" -> setOf("build", "image", "release")
        "service" -> setOf("build", "image", "deploy", "release")
        "microfrontend" -> setOf("build", "microfrontend", "release")
        "terraform" -> setOf("build", "terraform", "release")
        else -> setOf("build")
    }

    private fun requiredSectionsForRecipe(recipe: String): Set<String> = when (recipe) {
        "package" -> setOf("release")
        "image" -> setOf("image")
        "service" -> setOf("image", "deploy")
        "microfrontend" -> setOf("microfrontend")
        "terraform" -> setOf("terraform")
        else -> emptySet()
    }

    private fun sectionDefined(cfg: Map<String, Any?>, section: String, isActive: Boolean = false): Boolean = when (section) {
        "build" -> cfg["build"] != null || cfg["preset"] != null || cfg["pnpm"] != null
        "image" -> cfg["image"] != null && isActive
        "release" -> hasReleaseAutomation(cfg)
        else -> cfg[section] != null || isActive
    }

    private fun deployEnvironmentNames(cfg: Map<String, Any?>): List<String> {
        val deploy = when (val direct = cfg["deploy"]) {
            is Map<*, *> -> direct.normalizeMap()
            else -> when (val legacy = cfg["environments"]) {
                is Map<*, *> -> legacy.normalizeMap()
                else -> emptyMap()
            }
        }
        return deploy.keys.filter { it !in reservedDeployKeys }
    }

    private fun microfrontendEnvironmentNames(cfg: Map<String, Any?>): List<String> {
        val microfrontend = (cfg["microfrontend"] as? Map<*, *>)?.normalizeMap().orEmpty()
        return ((microfrontend["environments"] as? Map<*, *>)?.normalizeMap().orEmpty()).keys.toList()
    }

    private fun terraformEnvironmentNames(cfg: Map<String, Any?>): List<String> {
        val terraform = (cfg["terraform"] as? Map<*, *>)?.normalizeMap().orEmpty()
        return ((terraform["environments"] as? Map<*, *>)?.normalizeMap().orEmpty()).keys.toList()
    }

    private fun imageRepositoryLabel(cfg: Map<String, Any?>): String {
        val image = (cfg["image"] as? Map<*, *>)?.normalizeMap().orEmpty()
        return image["repository"]?.asString()?.takeIf(String::isNotBlank)
            ?: "Enabled"
    }

    private fun resolveRecipe(cfg: Map<String, Any?>, explicitRecipe: String?): String {
        if (explicitRecipe in supportedRecipes) return explicitRecipe ?: "build"
        return when {
            hasTerraformEnvironments(cfg) -> "terraform"
            hasMicrofrontendEnvironments(cfg) -> "microfrontend"
            hasDeployEnvironments(cfg) -> "service"
            isImageEnabled(cfg) -> "image"
            hasReleaseAutomation(cfg) -> "package"
            else -> "build"
        }
    }

    private fun closestRecipe(value: String): String? {
        val normalized = value.trim().lowercase()
        if (normalized == "services") return "service"
        if (normalized == "packages") return "package"
        return supportedRecipes
            .map { it to levenshtein(normalized, it) }
            .filter { it.second <= 3 }
            .minByOrNull { it.second }
            ?.first
    }

    private fun levenshtein(left: String, right: String): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length

        var previous = IntArray(right.length + 1) { it }
        for (i in left.indices) {
            val current = IntArray(right.length + 1)
            current[0] = i + 1
            for (j in right.indices) {
                val cost = if (left[i] == right[j]) 0 else 1
                current[j + 1] = min(min(current[j] + 1, previous[j + 1] + 1), previous[j] + cost)
            }
            previous = current
        }
        return previous[right.length]
    }

    private fun ignoredFinding(section: String, env: String, key: String): MerebJenkinsFinding {
        val path = when (section) {
            "deploy" -> MerebJenkinsPath.root().key("deploy").key(env).key(key)
            else -> MerebJenkinsPath.root().key("microfrontend").key("environments").key(env).key(key)
        }
        val message = when (section) {
            "deploy" -> "deploy.$env.$key is ignored in staged mode"
            else -> "microfrontend.environments.$env.$key is ignored in staged mode"
        }
        return finding(
            id = "ignored-$section-$key",
            severity = MerebJenkinsSeverity.WARNING,
            message = message,
            path = path,
            quickFixes = listOf(
                MerebJenkinsFixSuggestion(
                    kind = MerebJenkinsFixKind.REMOVE_KEY,
                    label = "Remove ignored key $key",
                    data = mapOf("path" to path.toString()),
                )
            ),
        )
    }

    private fun incompatibleSection(message: String, section: String): MerebJenkinsFinding {
        return finding(
            id = "recipe-incompatible-$section",
            severity = MerebJenkinsSeverity.ERROR,
            message = message,
            path = MerebJenkinsPath.root().key(section),
            anchorPath = MerebJenkinsPath.root().key("recipe"),
        )
    }

    private fun finding(
        id: String,
        severity: MerebJenkinsSeverity,
        message: String,
        path: MerebJenkinsPath? = null,
        anchorPath: MerebJenkinsPath? = null,
        quickFixes: List<MerebJenkinsFixSuggestion> = emptyList(),
    ): MerebJenkinsFinding {
        return MerebJenkinsFinding(id, severity, message, path, anchorPath, quickFixes)
    }

    private fun deliveryMode(cfg: Map<String, Any?>): String {
        val delivery = (cfg["delivery"] as? Map<*, *>)?.normalizeMap().orEmpty()
        return delivery["mode"]?.asString()?.trim()?.lowercase().orEmpty().ifEmpty { "custom" }
    }

    private fun hasImageRepository(cfg: Map<String, Any?>): Boolean {
        val image = (cfg["image"] as? Map<*, *>)?.normalizeMap().orEmpty()
        val app = (cfg["app"] as? Map<*, *>)?.normalizeMap().orEmpty()
        return image["repository"]?.asString()?.isNotBlank() == true || app["name"]?.asString()?.isNotBlank() == true
    }

    private fun suggestedImageRepository(projectScan: MerebJenkinsProjectScan): String {
        val name = projectScan.projectRootPath
            ?.substringAfterLast('/')
            ?.replace('_', '-')
            ?.replace(' ', '-')
            ?.lowercase()
            .orEmpty()
            .ifBlank { "app" }
        return "registry.example.com/$name"
    }

    private fun hasDeployEnvironments(cfg: Map<String, Any?>): Boolean {
        val deploy = (cfg["deploy"] as? Map<*, *>)?.normalizeMap() ?: return false
        return deploy.keys.any { it !in reservedDeployKeys }
    }

    private fun hasTerraformEnvironments(cfg: Map<String, Any?>): Boolean {
        val terraform = cfg["terraform"] as? Map<*, *> ?: return false
        val envs = terraform["environments"] as? Map<*, *> ?: return false
        return envs.isNotEmpty()
    }

    private fun hasMicrofrontendEnvironments(cfg: Map<String, Any?>): Boolean {
        val microfrontend = cfg["microfrontend"] as? Map<*, *> ?: return false
        val envs = microfrontend["environments"] as? Map<*, *> ?: return false
        return envs.isNotEmpty()
    }

    private fun hasReleaseAutomation(cfg: Map<String, Any?>): Boolean {
        if ((cfg["releaseStages"] as? List<*>)?.isNotEmpty() == true) return true
        val release = cfg["release"] as? Map<*, *> ?: return false
        return release.isNotEmpty()
    }

    private fun isImageEnabled(cfg: Map<String, Any?>): Boolean {
        return when (val image = cfg["image"]) {
            null -> true
            is Boolean -> image
            is Map<*, *> -> image["enabled"]?.asBoolean() ?: true
            else -> true
        }
    }

    private fun deployOrder(cfg: Map<String, Any?>): List<String> {
        val deploy = (cfg["deploy"] as? Map<*, *>)?.normalizeMap() ?: return emptyList()
        val order = deploy["order"] as? List<*> ?: return deploy.keys.filter { it !in reservedDeployKeys }
        return order.mapNotNull { it?.asString() }
    }

    private fun postDeployStageNames(cfg: Map<String, Any?>, envName: String): List<String> {
        val deploy = (cfg["deploy"] as? Map<*, *>)?.normalizeMap().orEmpty()
        val env = (deploy[envName] as? Map<*, *>)?.normalizeMap().orEmpty()
        val postDeployStages = env["postDeployStages"] as? List<*> ?: return emptyList()
        return postDeployStages.mapIndexedNotNull { index, stage ->
            val name = (stage as? Map<*, *>)?.normalizeMap()?.get("name")?.asString()
            name?.takeIf { it.isNotBlank() } ?: "Stage ${index + 1}"
        }
    }

    private fun microfrontendOrder(cfg: Map<String, Any?>): List<String> {
        val microfrontend = cfg["microfrontend"] as? Map<*, *> ?: return emptyList()
        val envs = microfrontend["environments"] as? Map<*, *> ?: return emptyList()
        val order = microfrontend["order"] as? List<*> ?: return envs.keys.mapNotNull { it?.asString() }
        return order.mapNotNull { it?.asString() }
    }

    private fun terraformOrder(cfg: Map<String, Any?>): List<String> {
        val terraform = cfg["terraform"] as? Map<*, *> ?: return emptyList()
        val envs = terraform["environments"] as? Map<*, *> ?: return emptyList()
        val order = terraform["order"] as? List<*> ?: return envs.keys.mapNotNull { it?.asString() }
        return order.mapNotNull { it?.asString() }
    }
}

internal fun Map<*, *>.normalizeMap(): Map<String, Any?> = entries.associate { (key, value) ->
    key.toString() to when (value) {
        is Map<*, *> -> value.normalizeMap()
        is List<*> -> value.map { if (it is Map<*, *>) it.normalizeMap() else it }
        else -> value
    }
}

internal fun Any?.asString(): String? = when (this) {
    null -> null
    is String -> this
    is Number, is Boolean -> toString()
    else -> null
}

internal fun Any?.asBoolean(): Boolean? = when (this) {
    is Boolean -> this
    is String -> when (trim().lowercase()) {
        "true", "yes", "1" -> true
        "false", "no", "0" -> false
        else -> null
    }
    else -> null
}

internal fun Any?.asInt(): Int? = when (this) {
    is Int -> this
    is Number -> toInt()
    is String -> toIntOrNull()
    else -> null
}
