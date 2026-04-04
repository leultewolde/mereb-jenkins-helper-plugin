package org.mereb.intellij.mjc

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import org.jetbrains.yaml.YAMLHighlighter

object MerebJenkinsHighlighting {
    val KEY: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "MEREB_JENKINS_KEY",
        DefaultLanguageHighlighterColors.KEYWORD,
    )

    val VALUE: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "MEREB_JENKINS_VALUE",
        DefaultLanguageHighlighterColors.STRING,
    )

    val LITERAL: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "MEREB_JENKINS_LITERAL",
        DefaultLanguageHighlighterColors.CONSTANT,
    )

    val COMMENT: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "MEREB_JENKINS_COMMENT",
        DefaultLanguageHighlighterColors.LINE_COMMENT,
    )

    val BLOCK_METADATA: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "MEREB_JENKINS_BLOCK_METADATA",
        DefaultLanguageHighlighterColors.INLAY_TEXT_WITHOUT_BACKGROUND,
    )
}
