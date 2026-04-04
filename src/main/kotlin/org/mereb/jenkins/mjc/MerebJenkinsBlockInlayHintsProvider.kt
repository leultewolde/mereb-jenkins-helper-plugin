package org.mereb.jenkins.mjc

import com.intellij.codeInsight.hints.FactoryInlayHintsCollector
import com.intellij.codeInsight.hints.ImmediateConfigurable
import com.intellij.codeInsight.hints.InlayHintsCollector
import com.intellij.codeInsight.hints.InlayHintsProvider
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.codeInsight.hints.SettingsKey
import com.intellij.codeInsight.hints.presentation.WithAttributesPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import javax.swing.JPanel
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLKeyValue

class MerebJenkinsBlockInlayHintsProvider : InlayHintsProvider<NoSettings> {
    override val name: String = "Mereb Jenkins block metadata"

    override val key: SettingsKey<NoSettings> = SettingsKey("mereb.jenkins.block.metadata")

    override val previewText: String = """
        version: 1
        recipe: service
        delivery:
          mode: staged
        image:
          repository: registry.example.com/demo
        deploy:
          order:
            - dev
          dev:
            namespace: apps-dev
    """.trimIndent()

    override fun getCollectorFor(
        file: com.intellij.psi.PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink,
    ): InlayHintsCollector? {
        val virtualFile = file.virtualFile ?: return null
        if (!MerebJenkinsConfigPaths.isSchemaTarget(virtualFile)) return null
        val analysis = MerebJenkinsAnalysisCache.forFile(file)

        return object : FactoryInlayHintsCollector(editor) {
            override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
                val keyValue = element as? YAMLKeyValue ?: return true
                val path = MerebJenkinsPsiUtils.pathForElement(keyValue)?.toString() ?: return true
                if (!MerebJenkinsMetadataCatalog.shouldRenderInlineHint(path)) return true
                if (keyValue.key == null) return true

                val metadata = MerebJenkinsMetadataCatalog.metadataForPath(path, analysis) ?: return true
                val base = factory.smallTextWithoutBackground(metadata.inlineSummary)
                val styled = WithAttributesPresentation(
                    factory.inset(base, 0, 0, 0, 4),
                    MerebJenkinsHighlighting.BLOCK_METADATA,
                    editor,
                    WithAttributesPresentation.AttributesFlags().withSkipBackground(true).withSkipEffects(true),
                )
                sink.addBlockElement(keyValue.textRange.startOffset, false, true, 0, styled)
                return true
            }
        }
    }

    override fun createSettings(): NoSettings = NoSettings()

    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable = object : ImmediateConfigurable {
        override fun createComponent(listener: com.intellij.codeInsight.hints.ChangeListener) = JPanel()
    }

    override fun isLanguageSupported(language: com.intellij.lang.Language): Boolean = language.isKindOf(YAMLLanguage.INSTANCE)
}
