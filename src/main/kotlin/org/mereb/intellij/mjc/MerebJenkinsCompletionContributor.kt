package org.mereb.intellij.mjc

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
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

                    MerebJenkinsTemplates.snippetTemplates().forEach { (label, snippet) ->
                        result.addElement(
                            LookupElementBuilder.create(label)
                                .withTypeText("Mereb Jenkins", true)
                                .withPresentableText(label)
                                .withInsertHandler { insertionContext, _ ->
                                    insertionContext.document.replaceString(
                                        insertionContext.startOffset,
                                        insertionContext.tailOffset,
                                        snippet,
                                    )
                                }
                        )
                    }
                }
            }
        )
    }
}
