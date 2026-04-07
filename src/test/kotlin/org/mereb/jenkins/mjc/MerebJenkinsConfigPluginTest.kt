package org.mereb.jenkins.mjc

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.nio.file.Files
import org.junit.jupiter.api.Test
import org.jetbrains.yaml.YAMLTokenTypes

class MerebJenkinsConfigPluginTest {

    @Test
    fun `mjc file type uses yaml language`() {
        val fileType = MerebJenkinsConfigurationFileType.INSTANCE

        assertEquals("Mereb Jenkins Configuration", fileType.name)
        assertEquals("mjc", fileType.defaultExtension)
        assertEquals("YAML", fileType.language.displayName)
        assertNotNull(fileType.icon)
    }

    @Test
    fun `config path helper recognizes preferred and legacy filenames`() {
        assertTrue(MerebJenkinsConfigPaths.isSchemaTargetPath(".ci/ci.mjc"))
        assertTrue(MerebJenkinsConfigPaths.isSchemaTargetPath("/tmp/repo/.ci/ci.yml"))
        assertTrue(MerebJenkinsConfigPaths.isSchemaTargetPath("/tmp/repo/ci.yml"))
        assertFalse(MerebJenkinsConfigPaths.isSchemaTargetPath("/tmp/repo/service.mjc"))
        assertTrue(MerebJenkinsConfigPaths.isLegacyPath(".ci/ci.yml"))
        assertTrue(MerebJenkinsConfigPaths.isLegacyPath("/tmp/repo/ci.yml"))
        assertFalse(MerebJenkinsConfigPaths.isLegacyPath(".ci/ci.mjc"))
    }

    @Test
    fun `schema provider exposes bundled schema resource`() {
        val provider = MerebJenkinsSchemaFileProvider()
        val resource = provider.getSchemaResource()
        assertNotNull(resource)
        val body = resource.readText()
        assertTrue(body.contains("\"generatedValues\""))
        assertTrue(body.contains("\"outboxWorker\""))
    }

    @Test
    fun `built in how to guide resource is available`() {
        val html = MerebJenkinsHowToSupport.loadHowToHtml()

        assertTrue(html.contains("Mereb Jenkins Helper"))
        assertTrue(html.contains(".ci/ci.mjc"))
        assertFalse(html.contains("<style", ignoreCase = true))
    }

    @Test
    fun `legacy inspection message points users to the preferred filename`() {
        assertEquals(
            "Prefer .ci/ci.mjc for Mereb Jenkins configs. Legacy filenames are still supported.",
            LegacyMerebJenkinsConfigInspection.MESSAGE
        )
    }

    @Test
    fun `inspection suppression policy keeps mereb checks but suppresses unrelated lint`() {
        assertTrue(MerebJenkinsInspectionSuppressionPolicy.shouldSuppressPath("/tmp/repo/.ci/ci.mjc", "sonar-yaml:S1234"))
        assertTrue(MerebJenkinsInspectionSuppressionPolicy.shouldSuppressPath("/tmp/repo/.ci/ci.yml", "YAMLLint"))
        assertFalse(MerebJenkinsInspectionSuppressionPolicy.shouldSuppressPath("/tmp/repo/.ci/ci.mjc", "MerebJenkinsConfigSemantics"))
        assertFalse(MerebJenkinsInspectionSuppressionPolicy.shouldSuppressPath("/tmp/repo/.ci/ci.mjc", "LegacyMerebJenkinsConfigFilename"))
        assertFalse(MerebJenkinsInspectionSuppressionPolicy.shouldSuppressPath("/tmp/repo/.ci/ci.mjc", "JsonSchemaCompliance"))
        assertFalse(MerebJenkinsInspectionSuppressionPolicy.shouldSuppressPath("/tmp/repo/app.yml", "sonar-yaml:S1234"))
    }

    @Test
    fun `engine returns field scoped findings and summary`() {
        val result = MerebJenkinsConfigEngine().analyze(
            """
            version: 1
            delivery:
              mode: staged
            image: false
            deploy:
              order: [dev, stg]
              dev:
                when: branch=main
                autoPromote: true
                approval:
                  message: ship it
            release:
              autoTag:
                enabled: true
            """.trimIndent()
        )

        assertEquals("service", result.resolvedRecipe)
        assertTrue(result.findings.any { it.message == "Set recipe explicitly to improve readability and editor validation." })
        assertTrue(result.findings.any { it.path?.toString() == "deploy.order[1]" })
        assertTrue(result.findings.any { it.path?.toString() == "deploy.dev.when" })
        assertTrue(result.summary.ignoredFields.any { it.contains("deploy.dev.when") })
        assertTrue(result.summary.releaseEnabled)
        assertTrue(result.summary.capabilities.any { it.id == "deploy" && it.enabled })
        assertTrue(result.summary.relations.any { it.group == "Deploy" && it.status == MerebJenkinsRelationStatus.MISSING })
        assertTrue(result.summary.sections.any { it.id == "deploy" && it.status == MerebJenkinsRelationStatus.OK })
    }

    @Test
    fun `engine exposes staged mode removal quick fix`() {
        val result = MerebJenkinsConfigEngine().analyze(
            """
            version: 1
            recipe: service
            delivery:
              mode: staged
            image:
              repository: registry.example.com/demo
            deploy:
              dev:
                when: branch=main
            """.trimIndent()
        )

        val finding = result.findings.first { it.id == "ignored-deploy-when" }
        assertTrue(finding.quickFixes.any { it.kind == MerebJenkinsFixKind.REMOVE_KEY && it.label == "Remove ignored key when" })
    }

    @Test
    fun `engine suggests obvious recipe spelling replacement`() {
        val result = MerebJenkinsConfigEngine().analyze(
            """
            version: 1
            recipe: services
            image:
              repository: registry.example.com/demo
            deploy:
              dev:
                namespace: apps-dev
            """.trimIndent()
        )

        val recipeFinding = result.findings.first { it.id == "recipe-invalid" }
        assertTrue(recipeFinding.quickFixes.any { it.kind == MerebJenkinsFixKind.REPLACE_RECIPE && it.data["recipe"] == "service" })
    }

    @Test
    fun `project scanner derives expected recipe from current repo layouts`() {
        val scan = MerebJenkinsProjectScanner.scan("/tmp/mereb-social/web/mfe-admin/.ci/ci.yml")
        assertEquals("/tmp/mereb-social/web/mfe-admin", scan.projectRootPath)
        assertEquals("microfrontend", scan.expectedRecipe)
    }

    @Test
    fun `workspace discovery prefers ci mjc and returns multiple targets`() {
        val workspace = Files.createTempDirectory("mereb-mjc-workspace")
        try {
            val alpha = workspace.resolve("services").resolve("svc-auth")
            Files.createDirectories(alpha.resolve(".ci"))
            Files.writeString(alpha.resolve(".ci/ci.yml"), "version: 1\nrecipe: service\n")
            Files.writeString(alpha.resolve(".ci/ci.mjc"), "version: 1\nrecipe: service\n")
            Files.writeString(alpha.resolve("Jenkinsfile"), "ciV1(configPath: '.ci/ci.mjc')\n")

            val beta = workspace.resolve("packages").resolve("app-admin")
            Files.createDirectories(beta.resolve(".ci"))
            Files.writeString(beta.resolve(".ci/ci.yml"), "version: 1\nrecipe: package\n")
            Files.writeString(beta.resolve("Jenkinsfile"), "ciV1(configPath: '.ci/ci.yml')\n")

            val targets = MerebJenkinsProjectScanner.discoverWorkspaceTargets(workspace.toString())

            assertEquals(2, targets.size)
            assertTrue(targets.any { it.projectRootPath == alpha.toString() && it.configFilePath.endsWith(".ci/ci.mjc") })
            assertTrue(targets.any { it.projectRootPath == beta.toString() && it.configFilePath.endsWith(".ci/ci.yml") })
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `migration planner updates jenkinsfile path text`() {
        val updated = MerebJenkinsMigrationPlanner.updateJenkinsfileText(
            """
            @Library('mereb-jenkins') _
            ciV1(configPath: '.ci/ci.yml')
            """.trimIndent()
        )

        assertTrue(updated.contains(".ci/ci.mjc"))
        assertFalse(updated.contains(".ci/ci.yml"))
    }

    @Test
    fun `upstream checker compares schema hashes`() {
        val checker = MerebJenkinsUpstreamChecker(fetch = { """{"version":1}""" })
        val current = checker.compareWithRemote("""{"version":1}""")
        val stale = checker.compareWithRemote("""{"version":2}""")

        assertTrue(current.isCurrent)
        assertFalse(stale.isCurrent)
        assertNotNull(stale.remoteHash)
    }

    @Test
    fun `snippet templates include common starter blocks`() {
        val labels = MerebJenkinsTemplates.snippetTemplates().map { it.first }

        assertTrue("service recipe" in labels)
        assertTrue("image block" in labels)
        assertTrue("release autoTag" in labels)
        assertTrue("generated outbox deploy env" in labels)
    }

    @Test
    fun `completion model suggests known values and env references`() {
        val suggestions = MerebJenkinsCompletionModel.suggestions(
            rawText = """
                version: 1
                recipe: service
                delivery:
                  mode: staged
                deploy:
                  order:
                    - d
                  dev:
                    namespace: apps-dev
                  prd:
                    namespace: apps-prd
            """.trimIndent(),
            pathString = "deploy.order[0]",
            linePrefix = "  - d"
        )

        assertTrue(suggestions.any { it.lookup == "dev" && it.typeText == "deploy env" })
        assertTrue(suggestions.any { it.lookup == "prd" && it.typeText == "deploy env" })
    }

    @Test
    fun `completion model suggests recipe values`() {
        val suggestions = MerebJenkinsCompletionModel.suggestions(
            rawText = "version: 1\nrecipe: s",
            pathString = "recipe",
            linePrefix = "recipe: s"
        )

        assertTrue(suggestions.any { it.lookup == "service" && it.typeText == "recipe" })
        assertTrue(suggestions.any { it.lookup == "terraform" && it.typeText == "recipe" })
    }

    @Test
    fun `completion model suggests nested keys for image block`() {
        val suggestions = MerebJenkinsCompletionModel.suggestions(
            MerebJenkinsCompletionRequest(
                rawText = """
                    version: 1
                    image:
                      rep
                """.trimIndent(),
                parentPathString = "image",
                linePrefix = "  rep",
                keyContext = true,
                valueContext = false,
            )
        )

        assertTrue(suggestions.any { it.lookup == "repository" && it.insertText == "repository: " })
        assertTrue(suggestions.any { it.lookup == "dockerfile" })
    }

    @Test
    fun `completion model suggests generated values block for deploy environments`() {
        val suggestions = MerebJenkinsCompletionModel.suggestions(
            MerebJenkinsCompletionRequest(
                rawText = """
                    version: 1
                    recipe: service
                    deploy:
                      dev_outbox:
                        val
                """.trimIndent(),
                parentPathString = "deploy.dev_outbox",
                linePrefix = "    val",
                keyContext = true,
                valueContext = false,
            )
        )

        assertTrue(suggestions.any { it.lookup == "generatedValues" && it.insertText == "generatedValues:\n  " })
        assertTrue(suggestions.any { it.lookup == "valuesFiles" })
    }

    @Test
    fun `completion model suggests generated values profiles`() {
        val suggestions = MerebJenkinsCompletionModel.suggestions(
            MerebJenkinsCompletionRequest(
                rawText = """
                    version: 1
                    recipe: service
                    deploy:
                      dev_outbox:
                        generatedValues:
                          profile: o
                """.trimIndent(),
                pathString = "deploy.dev_outbox.generatedValues.profile",
                linePrefix = "      profile: o",
                valueContext = true,
            )
        )

        assertTrue(suggestions.any { it.lookup == "outboxWorker" && it.typeText == "generated values profile" })
    }

    @Test
    fun `syntax highlighter remaps yaml keys and values to mereb categories`() {
        val highlighter = MerebJenkinsSyntaxHighlighter()

        assertTrue(highlighter.getTokenHighlights(YAMLTokenTypes.SCALAR_KEY).contains(MerebJenkinsHighlighting.KEY))
        assertTrue(highlighter.getTokenHighlights(YAMLTokenTypes.SCALAR_TEXT).contains(MerebJenkinsHighlighting.VALUE))
        assertTrue(highlighter.getTokenHighlights(YAMLTokenTypes.COMMENT).contains(MerebJenkinsHighlighting.COMMENT))
    }
}
