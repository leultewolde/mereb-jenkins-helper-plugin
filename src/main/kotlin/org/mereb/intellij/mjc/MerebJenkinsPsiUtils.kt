package org.mereb.intellij.mjc

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem
import org.jetbrains.yaml.psi.YAMLValue

object MerebJenkinsPsiUtils {
    fun findBestElement(file: PsiFile, path: MerebJenkinsPath?, anchorPath: MerebJenkinsPath? = null): PsiElement? {
        val yamlFile = file as? YAMLFile ?: return file
        return locateElement(yamlFile, path, preferValue = false)
            ?: locateElement(yamlFile, anchorPath, preferValue = false)
            ?: firstMeaningfulElement(yamlFile)
            ?: file
    }

    fun findBestProblemElement(file: PsiFile, path: MerebJenkinsPath?, anchorPath: MerebJenkinsPath? = null): PsiElement? {
        val yamlFile = file as? YAMLFile ?: return file
        return locateElement(yamlFile, path, preferValue = true)
            ?: locateElement(yamlFile, anchorPath, preferValue = true)
            ?: firstMeaningfulElement(yamlFile)
            ?: file
    }

    fun pathForElement(element: PsiElement?): MerebJenkinsPath? {
        var current = element ?: return null
        val segments = mutableListOf<MerebJenkinsPathSegment>()

        while (current !is YAMLFile) {
            when (current) {
                is YAMLKeyValue -> segments += MerebJenkinsPathSegment.Key(current.keyText)
                is YAMLSequenceItem -> {
                    val sequence = current.parent as? YAMLSequence
                    val index = sequence?.items?.indexOf(current) ?: -1
                    if (index >= 0) segments += MerebJenkinsPathSegment.Index(index)
                }
            }
            current = current.parent ?: break
        }

        return if (segments.isEmpty()) null else MerebJenkinsPath(segments.reversed())
    }

    fun elementPathString(element: PsiElement?): String? = pathForElement(element)?.toString()

    fun enclosingMappingPathString(element: PsiElement?): String? {
        val mapping = PsiTreeUtil.getParentOfType(element, YAMLMapping::class.java, false) ?: return null
        val owner = mapping.parent as? YAMLKeyValue ?: return null
        return pathForElement(owner)?.toString()
    }

    fun rangeForElement(element: PsiElement): TextRange = when (element) {
        is YAMLKeyValue -> element.key?.textRange ?: element.textRange
        else -> element.textRange
    }

    fun parsePathString(pathString: String): MerebJenkinsPath? {
        if (pathString.isBlank()) return null
        var current = MerebJenkinsPath.root()
        pathString.split('.').forEach { token ->
            val match = Regex("([A-Za-z0-9_-]+)(\\[(\\d+)])?").matchEntire(token) ?: return@forEach
            current = current.key(match.groupValues[1])
            val indexValue = match.groupValues.getOrNull(3)?.takeIf(String::isNotBlank)?.toIntOrNull()
            if (indexValue != null) {
                current = current.index(indexValue)
            }
        }
        return current
    }

    fun findKeyValue(file: YAMLFile, path: MerebJenkinsPath?): YAMLKeyValue? {
        val element = locateElement(file, path, preferValue = false) ?: findBestElement(file, path)
        return element?.parent as? YAMLKeyValue ?: element as? YAMLKeyValue
    }

    private fun locateElement(file: YAMLFile, path: MerebJenkinsPath?, preferValue: Boolean): PsiElement? {
        if (path == null) return null
        var current: PsiElement? = file.documents.firstOrNull()?.topLevelValue
        if (current == null) {
            return null
        }

        for (segment in path.segments) {
            current = when (segment) {
                is MerebJenkinsPathSegment.Key -> descendToKey(current, segment.name) ?: return current
                is MerebJenkinsPathSegment.Index -> descendToIndex(current, segment.index) ?: return current
            }
        }

        return when (current) {
            is YAMLKeyValue -> {
                when {
                    preferValue && current.value != null -> current.value
                    else -> current.key ?: current
                }
            }
            else -> current
        }
    }

    private fun descendToKey(current: PsiElement?, key: String): PsiElement? {
        val mapping = when (current) {
            is YAMLMapping -> current
            is YAMLKeyValue -> current.value as? YAMLMapping
            is YAMLSequenceItem -> current.value as? YAMLMapping
            is YAMLValue -> current as? YAMLMapping
            else -> null
        } ?: return null

        return mapping.getKeyValueByKey(key)
    }

    private fun descendToIndex(current: PsiElement?, index: Int): PsiElement? {
        val sequence = when (current) {
            is YAMLSequence -> current
            is YAMLKeyValue -> current.value as? YAMLSequence
            else -> null
        } ?: return null
        return sequence.items.getOrNull(index)?.value ?: sequence.items.getOrNull(index)
    }

    private fun firstMeaningfulElement(file: YAMLFile): PsiElement? {
        val top = file.documents.firstOrNull()?.topLevelValue ?: return null
        return when (top) {
            is YAMLMapping -> top.keyValues.firstOrNull()?.key ?: top
            is YAMLScalar -> top
            else -> top
        }
    }
}
