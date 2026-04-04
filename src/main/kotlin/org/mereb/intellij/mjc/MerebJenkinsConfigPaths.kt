package org.mereb.intellij.mjc

import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.vfs.VirtualFile

object MerebJenkinsConfigPaths {
    const val PRIMARY_CONFIG = ".ci/ci.mjc"
    private const val PROJECT_YAML_CONFIG = ".ci/ci.yml"
    private const val ROOT_YAML_CONFIG = "ci.yml"

    fun isSchemaTarget(virtualFile: VirtualFile): Boolean {
        return isSchemaTargetPath(virtualFile.path)
    }

    fun isLegacy(virtualFile: VirtualFile): Boolean {
        return isLegacyPath(virtualFile.path)
    }

    fun isPreferred(virtualFile: VirtualFile): Boolean {
        return normalize(virtualFile.path) == PRIMARY_CONFIG
    }

    fun preferredPath(): String = PRIMARY_CONFIG

    fun isSchemaTargetPath(path: String): Boolean {
        return when (normalize(path)) {
            PRIMARY_CONFIG, PROJECT_YAML_CONFIG, ROOT_YAML_CONFIG -> true
            else -> false
        }
    }

    fun isLegacyPath(path: String): Boolean {
        return when (normalize(path)) {
            PROJECT_YAML_CONFIG, ROOT_YAML_CONFIG -> true
            else -> false
        }
    }

    private fun normalize(path: String): String {
        val systemIndependent = FileUtilRt.toSystemIndependentName(path).trim()
        return when {
            systemIndependent == PRIMARY_CONFIG || systemIndependent.endsWith("/$PRIMARY_CONFIG") -> PRIMARY_CONFIG
            systemIndependent == PROJECT_YAML_CONFIG || systemIndependent.endsWith("/$PROJECT_YAML_CONFIG") -> PROJECT_YAML_CONFIG
            systemIndependent == ROOT_YAML_CONFIG || systemIndependent.endsWith("/$ROOT_YAML_CONFIG") -> ROOT_YAML_CONFIG
            else -> systemIndependent.substringAfterLast('/')
        }
    }
}

