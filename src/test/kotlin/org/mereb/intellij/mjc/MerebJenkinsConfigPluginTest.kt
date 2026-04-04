package org.mereb.intellij.mjc

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

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

        assertNotNull(provider.getSchemaResource())
    }

    @Test
    fun `legacy inspection message points users to the preferred filename`() {
        assertEquals(
            "Prefer .ci/ci.mjc for Mereb Jenkins configs. Legacy filenames are still supported.",
            LegacyMerebJenkinsConfigInspection.MESSAGE
        )
    }

    @Test
    fun `semantic analyzer reports warnings and errors`() {
        val analyzer = MerebJenkinsConfigAnalyzer()

        val issues = analyzer.analyze(
            """
            version: 2
            delivery:
              mode: staged
            deploy:
              order: [dev, stg]
              dev:
                when: branch=main
                autoPromote: true
                approval:
                  message: ship it
            """.trimIndent()
        )

        assertTrue(issues.any { it.message == "version must be 1" })
        assertTrue(issues.any { it.message == "Set recipe explicitly to improve readability and editor validation." })
        assertTrue(issues.any { it.message == "image.repository or app.name must be provided when docker image builds are enabled" })
        assertTrue(issues.any { it.message == "deploy.order references unknown environments: stg" })
        assertTrue(issues.any { it.message == "deploy.dev.when is ignored in staged mode" })
        assertTrue(issues.any { it.message == "deploy.dev.autoPromote is ignored in staged mode" })
        assertTrue(issues.any { it.message == "deploy.dev.approval is ignored in staged mode" })
    }

    @Test
    fun `semantic analyzer validates explicit recipe compatibility`() {
        val analyzer = MerebJenkinsConfigAnalyzer()

        val issues = analyzer.analyze(
            """
            version: 1
            recipe: package
            image:
              enabled: true
              repository: ghcr.io/mereb/test
            deploy:
              dev:
                namespace: apps
            """.trimIndent()
        )

        assertTrue(issues.any { it.message == "recipe=package cannot enable image orchestration" })
        assertTrue(issues.any { it.message == "recipe=package cannot define deploy environments" })
        assertTrue(issues.any { it.message == "recipe=package requires release automation or release stages" })
    }
}
