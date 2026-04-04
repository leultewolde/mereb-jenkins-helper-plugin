package org.mereb.intellij.mjc

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType
import org.jetbrains.yaml.YAMLSyntaxHighlighter
import org.jetbrains.yaml.YAMLTokenTypes

class MerebJenkinsSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter {
        return if (virtualFile != null && MerebJenkinsConfigPaths.isSchemaTarget(virtualFile)) {
            MerebJenkinsSyntaxHighlighter()
        } else {
            YAMLSyntaxHighlighter()
        }
    }
}

class MerebJenkinsSyntaxHighlighter : SyntaxHighlighterBase() {
    private val delegate = YAMLSyntaxHighlighter()

    override fun getHighlightingLexer(): Lexer = delegate.highlightingLexer

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = when (tokenType) {
        YAMLTokenTypes.SCALAR_KEY -> pack(MerebJenkinsHighlighting.KEY)
        YAMLTokenTypes.TEXT,
        YAMLTokenTypes.SCALAR_TEXT,
        YAMLTokenTypes.SCALAR_STRING,
        YAMLTokenTypes.SCALAR_DSTRING,
        YAMLTokenTypes.SCALAR_LIST -> pack(MerebJenkinsHighlighting.VALUE)
        YAMLTokenTypes.COMMENT -> pack(MerebJenkinsHighlighting.COMMENT)
        else -> delegate.getTokenHighlights(tokenType)
    }
}
