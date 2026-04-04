package org.mereb.intellij.mjc

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.extensions.PluginId
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JScrollPane

class MerebJenkinsOnboardingActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        if (project.isDisposed) return
        if (MerebJenkinsProjectScanner.discoverWorkspaceTargets(project.basePath).isEmpty()) return

        val properties = PropertiesComponent.getInstance(project)
        val key = MerebJenkinsHowToSupport.onboardingKey()
        if (properties.getBoolean(key, false)) return
        properties.setValue(key, true)

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(MerebJenkinsHowToSupport.NOTIFICATION_GROUP_ID)
            .createNotification(
                "Mereb Jenkins Helper is ready",
                "The plugin detected a Mereb Jenkins project. Open the guide to see the preferred config path, the tool window workflow, and the repair/migration actions.",
                NotificationType.INFORMATION,
            )
        notification.addAction(NotificationAction.createSimple("Show How To Use") {
            MerebJenkinsHowToSupport.showHowToDialog(project)
            notification.expire()
        })
        notification.addAction(NotificationAction.createSimple("Open Tool Window") {
            MerebJenkinsHowToSupport.openToolWindow(project)
            notification.expire()
        })
        notification.notify(project)
    }
}

class MerebJenkinsHowToAction : DumbAwareAction("How To Use Mereb Jenkins Helper") {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        MerebJenkinsHowToSupport.showHowToDialog(project)
    }
}

object MerebJenkinsHowToSupport {
    const val NOTIFICATION_GROUP_ID = "Mereb Jenkins"
    private const val PLUGIN_ID = "org.mereb.jenkins.helper"
    private const val TOOL_WINDOW_ID = "Mereb Jenkins"
    private const val HOW_TO_RESOURCE = "/docs/how-to-use.html"

    fun onboardingKey(): String = "mereb.jenkins.onboarding.shown.${pluginVersion()}"

    fun pluginVersion(): String {
        return PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version ?: "unknown"
    }

    fun loadHowToHtml(): String {
        val rawHtml = MerebJenkinsHowToSupport::class.java.getResourceAsStream(HOW_TO_RESOURCE)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: """
            <html>
              <body>
                <h1>Mereb Jenkins Helper</h1>
                <p>Open the Mereb Jenkins tool window to inspect the detected project, config, and Jenkinsfile.</p>
              </body>
            </html>
            """.trimIndent()
        return sanitizeHtmlForSwing(rawHtml)
    }

    fun showHowToDialog(project: Project) {
        MerebJenkinsHowToDialog(project).show()
    }

    fun openToolWindow(project: Project) {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
    }

    private fun sanitizeHtmlForSwing(html: String): String {
        return html
            .replace(Regex("(?is)<style\\b[^>]*>.*?</style>"), "")
            .replace(Regex("(?is)<meta\\b[^>]*>"), "")
            .replace(Regex("(?is)\\sstyle\\s*=\\s*([\"']).*?\\1"), "")
    }
}

private class MerebJenkinsHowToDialog(project: Project) : DialogWrapper(project, true) {
    init {
        title = "How To Use Mereb Jenkins Helper"
        setSize(820, 620)
        init()
    }

    override fun createCenterPanel(): JComponent {
        val content = JEditorPane("text/html", MerebJenkinsHowToSupport.loadHowToHtml()).apply {
            isEditable = false
            border = null
            background = null
            caretPosition = 0
        }
        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(820, 620)
            add(JScrollPane(content), BorderLayout.CENTER)
        }
    }

    override fun createActions() = arrayOf(
        okAction.apply { putValue(Action.NAME, "Close") }
    )

    override fun getDimensionServiceKey(): String = "MerebJenkinsHowToDialog"
}
