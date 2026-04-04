package org.mereb.intellij.mjc

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import javax.swing.Icon
import org.jetbrains.yaml.psi.YAMLKeyValue

class MerebJenkinsLineMarkerProvider : LineMarkerProviderDescriptor() {
    override fun getName(): String = "Mereb Jenkins navigation"

    override fun getIcon(): Icon = ICON

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val keyValue = element.parent as? YAMLKeyValue ?: return null
        val key = keyValue.key ?: return null
        if (element != key) return null
        val file = element.containingFile?.virtualFile ?: return null
        if (!MerebJenkinsConfigPaths.isSchemaTargetPath(file.path)) return null
        val path = MerebJenkinsPsiUtils.pathForElement(keyValue) ?: return null
        if (!isNavigablePath(path)) return null
        val tooltip = "Open Mereb Jenkins for ${path}"
        return LineMarkerInfo(
            element,
            element.textRange,
            ICON,
            { _: PsiElement -> tooltip },
            GutterIconNavigationHandler<PsiElement> { _, elt ->
                MerebJenkinsProjectUiStateService.openToolWindow(elt.project, path, "Jenkins")
            },
            GutterIconRenderer.Alignment.LEFT,
            { tooltip },
        )
    }

    private fun isNavigablePath(path: MerebJenkinsPath): Boolean {
        val segments = path.segments
        if (segments.isEmpty()) return false
        if (segments.size == 1) {
            return (segments.first() as? MerebJenkinsPathSegment.Key)?.name in TOP_LEVEL_KEYS
        }
        val first = segments.first() as? MerebJenkinsPathSegment.Key ?: return false
        return when (first.name) {
            "deploy" -> segments.size >= 2 && (segments[1] as? MerebJenkinsPathSegment.Key)?.name !in setOf(null, "order")
            "microfrontend" ->
                segments.size >= 3 &&
                    (segments[1] as? MerebJenkinsPathSegment.Key)?.name == "environments" &&
                    (segments[2] as? MerebJenkinsPathSegment.Key) != null
            "terraform" ->
                segments.size >= 3 &&
                    (segments[1] as? MerebJenkinsPathSegment.Key)?.name == "environments" &&
                    (segments[2] as? MerebJenkinsPathSegment.Key) != null
            else -> false
        }
    }

    companion object {
        private val ICON = IconLoader.getIcon("/icons/mereb-toolwindow.svg", MerebJenkinsLineMarkerProvider::class.java)
        private val TOP_LEVEL_KEYS = setOf("recipe", "delivery", "build", "image", "release", "releaseStages", "deploy", "microfrontend", "terraform")
    }
}
