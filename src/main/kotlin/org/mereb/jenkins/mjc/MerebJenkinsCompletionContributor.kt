package org.mereb.jenkins.mjc

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
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
                    val documentBackedFile = FileDocumentManager.getInstance().getFile(parameters.editor.document)
                    val psiFile = parameters.position.containingFile ?: parameters.originalFile
                    val isTarget = documentBackedFile?.let { MerebJenkinsConfigPaths.isSchemaTarget(it) } == true
                        || MerebJenkinsConfigPaths.isSchemaTarget(psiFile)
                        || MerebJenkinsConfigPaths.isSchemaTarget(parameters.originalFile)
                    if (!isTarget) return
                    val document = parameters.editor.document
                    val path = MerebJenkinsPsiUtils.elementPathString(parameters.position)
                        ?: MerebJenkinsPsiUtils.elementPathString(parameters.position.parent)
                    val parentPath = MerebJenkinsPsiUtils.enclosingMappingPathString(parameters.position)
                        ?: MerebJenkinsPsiUtils.enclosingMappingPathString(parameters.position.parent)
                        ?: inferredParentPath(document, parameters.offset)
                    val linePrefix = currentLinePrefix(document, parameters.offset)
                    val trimmedLine = linePrefix.trimStart()
                    val valueContext = trimmedLine.startsWith("-") || trimmedLine.contains(":")
                    val keyContext = !valueContext

                    MerebJenkinsCompletionModel.suggestions(
                        MerebJenkinsCompletionRequest(
                            rawText = parameters.originalFile.text,
                            pathString = path,
                            parentPathString = parentPath,
                            linePrefix = linePrefix,
                            keyContext = keyContext,
                            valueContext = valueContext,
                        )
                    ).forEach { item ->
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

    private fun inferredParentPath(document: Document, offset: Int): String? {
        val lineNumber = document.getLineNumber(offset)
        val currentIndent = leadingIndent(currentLinePrefix(document, offset))
        val stack = mutableListOf<Pair<Int, String>>()

        for (line in 0 until lineNumber) {
            val start = document.getLineStartOffset(line)
            val end = document.getLineEndOffset(line)
            val raw = document.getText(com.intellij.openapi.util.TextRange(start, end))
            val trimmed = raw.trimEnd()
            if (trimmed.isBlank()) continue

            val indent = leadingIndent(trimmed)
            val content = trimmed.trimStart()
            while (stack.isNotEmpty() && indent <= stack.last().first) {
                stack.removeAt(stack.lastIndex)
            }
            if (content.endsWith(":") && !content.startsWith("-")) {
                val key = content.removeSuffix(":").trim()
                if (key.isNotBlank()) {
                    stack += indent to key
                }
            }
        }

        while (stack.isNotEmpty() && currentIndent <= stack.last().first) {
            stack.removeAt(stack.lastIndex)
        }

        return stack.takeIf { it.isNotEmpty() }?.joinToString(".") { it.second }
    }

    private fun leadingIndent(text: String): Int = text.takeWhile { it == ' ' || it == '\t' }.length
}
