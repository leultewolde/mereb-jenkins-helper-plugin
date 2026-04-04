package org.mereb.intellij.mjc

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.editor.Document
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.util.ProcessingContext

class MerebJenkinsCompletionContributor : CompletionContributor() {
    init {
        extend(
            com.intellij.codeInsight.completion.CompletionType.BASIC,
            psiElement(),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    val file = parameters.originalFile.virtualFile ?: return
                    if (!MerebJenkinsConfigPaths.isSchemaTarget(file)) return
                    val path = MerebJenkinsPsiUtils.elementPathString(parameters.position)
                        ?: MerebJenkinsPsiUtils.elementPathString(parameters.position.parent)
                    val linePrefix = currentLinePrefix(parameters.editor.document, parameters.offset)
                    MerebJenkinsCompletionModel.suggestions(parameters.originalFile.text, path, linePrefix).forEach { item ->
                        result.addElement(
                            LookupElementBuilder.create(item.lookup)
                                .withTypeText(item.typeText, true)
                                .withTailText(item.tailText, true)
                                .withPresentableText(item.lookup)
                                .withInsertHandler { insertionContext, _ ->
                                    insertionContext.document.replaceString(
                                        insertionContext.startOffset,
                                        insertionContext.tailOffset,
                                        item.insertText,
                                    )
                                }
                        )
                    }
                }
            }
        )
    }

    private fun currentLinePrefix(document: Document, offset: Int): String {
        val line = document.getLineNumber(offset)
        val start = document.getLineStartOffset(line)
        return document.getText(com.intellij.openapi.util.TextRange(start, offset))
    }
}
