package org.mereb.jenkins.mjc

import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

class MerebJenkinsColorSettingsPage : ColorSettingsPage {
    override fun getIcon(): Icon = MerebJenkinsConfigurationFileType.INSTANCE.icon

    override fun getHighlighter(): SyntaxHighlighter = MerebJenkinsSyntaxHighlighter()

    override fun getDemoText(): String = """
        <metadata># recipe block chooses the pipeline executor</metadata>
        version: 1
        recipe: service
        delivery:
          mode: staged
        image:
          repository: registry.example.com/app-admin
        release:
          autoTag:
            enabled: <literal>true</literal>
            bump: patch
        # deploy block defines rollout environments
        deploy:
          order:
            - dev
            - prd
          dev:
            namespace: apps-dev
          prd:
            namespace: apps-prd
    """.trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap() = mapOf(
        "metadata" to MerebJenkinsHighlighting.BLOCK_METADATA,
        "literal" to MerebJenkinsHighlighting.LITERAL,
    )

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = arrayOf(
        AttributesDescriptor("Mereb Jenkins key", MerebJenkinsHighlighting.KEY),
        AttributesDescriptor("Mereb Jenkins value", MerebJenkinsHighlighting.VALUE),
        AttributesDescriptor("Mereb Jenkins literal", MerebJenkinsHighlighting.LITERAL),
        AttributesDescriptor("Mereb Jenkins comment", MerebJenkinsHighlighting.COMMENT),
        AttributesDescriptor("Mereb Jenkins block metadata", MerebJenkinsHighlighting.BLOCK_METADATA),
    )

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getDisplayName(): String = "Mereb Jenkins"
}
