package org.mereb.intellij.mjc

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import java.nio.file.Paths
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import kotlin.io.path.exists
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JScrollPane

class MerebJenkinsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = MerebJenkinsToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        toolWindow.contentManager.addContent(content)
        Disposer.register(content, panel)
    }

    override fun shouldBeAvailable(project: Project): Boolean = project.basePath
        ?.let(::projectContainsSupportedConfig)
        ?: false
}

private class MerebJenkinsToolWindowPanel(
    private val project: Project,
) : Disposable {
    private val analyzer = MerebJenkinsConfigAnalyzer()
    private val upstreamChecker = MerebJenkinsUpstreamChecker()
    private val previewPane = htmlPane()
    private val flowPane = htmlPane()
    private val upstreamPane = htmlPane()
    private val root = JPanel(BorderLayout(0, 8))
    private val upstreamRefreshVersion = AtomicInteger()
    @Volatile private var disposed = false
    @Volatile private var upstreamRefreshTask: Future<*>? = null

    val component: JComponent
        get() = root

    init {
        val refreshButton = JButton("Refresh").apply {
            addActionListener { refreshCurrentFile() }
        }
        val refreshUpstreamButton = JButton("Check Upstream Schema").apply {
            addActionListener { refreshUpstream() }
        }

        val tabs = JBTabbedPane()
        tabs.addTab("Preview", JScrollPane(previewPane))
        tabs.addTab("Flow", JScrollPane(flowPane))
        tabs.addTab("Upstream", JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(refreshUpstreamButton, BorderLayout.NORTH)
            add(JScrollPane(upstreamPane), BorderLayout.CENTER)
        })

        root.add(refreshButton, BorderLayout.NORTH)
        root.add(tabs, BorderLayout.CENTER)

        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                refreshCurrentFile()
            }
        })
        refreshCurrentFile()
        renderUpstreamIdle()
    }

    private fun refreshCurrentFile() {
        if (disposed || project.isDisposed) return
        val virtualFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        if (virtualFile == null || !MerebJenkinsConfigPaths.isSchemaTarget(virtualFile)) {
            previewPane.text = "<html><body><p>Select a Mereb Jenkins config file to view the effective pipeline preview.</p></body></html>"
            flowPane.text = "<html><body><p>No active Mereb Jenkins config selected.</p></body></html>"
            return
        }

        val document = FileEditorManager.getInstance(project)
            .selectedTextEditor
            ?.document
        val text = document?.text ?: virtualFile.inputStream.bufferedReader().use { it.readText() }
        val analysis = analyzer.analyzeDetailed(text, virtualFile.path)
        previewPane.text = renderPreview(analysis)
        flowPane.text = renderFlow(analysis.summary)
    }

    private fun refreshUpstream() {
        if (disposed || project.isDisposed) return
        upstreamPane.text = "<html><body><p>Checking upstream schema…</p></body></html>"
        upstreamRefreshTask?.cancel(true)
        val refreshId = upstreamRefreshVersion.incrementAndGet()
        val application = ApplicationManager.getApplication()
        upstreamRefreshTask = application.executeOnPooledThread {
            if (isDisposedOrStale(refreshId)) return@executeOnPooledThread
            val bundled = MerebJenkinsSchemaFileProvider()
                .getSchemaResource()
                ?.openStream()
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: "{}"
            val status = upstreamChecker.compareWithRemote(bundled)
            if (isDisposedOrStale(refreshId)) return@executeOnPooledThread
            application.invokeLater({
                if (isDisposedOrStale(refreshId)) return@invokeLater
                upstreamPane.text = """
                    <html><body>
                    <h3>Upstream Schema</h3>
                    <p>Remote URL: <code>${status.remoteUrl}</code></p>
                    <p>Bundled hash: <code>${status.bundledHash}</code></p>
                    <p>Remote hash: <code>${status.remoteHash ?: "unavailable"}</code></p>
                    <p>Status: <b>${if (status.isCurrent) "Current" else "Stale or unavailable"}</b></p>
                    ${status.error?.let { "<p>Error: $it</p>" } ?: ""}
                    </body></html>
                """.trimIndent()
            }, ModalityState.any(), Condition<Any?> { isDisposedOrStale(refreshId) })
        }
    }

    private fun renderPreview(analysis: MerebJenkinsAnalysisResult): String {
        val summary = analysis.summary
        val warnings = (summary.repoWarnings + summary.ignoredFields).joinToString("") { "<li>$it</li>" }
        return """
            <html><body>
            <h3>Preview</h3>
            <p>Resolved recipe: <b>${summary.resolvedRecipe}</b>${summary.explicitRecipe?.let { " (explicit: $it)" } ?: " (auto-detected)"}</p>
            <p>Image enabled: <b>${summary.imageEnabled}</b></p>
            <p>Release automation: <b>${summary.releaseEnabled}</b></p>
            <p>Deploy order: <code>${summary.deployOrder.joinToString(" -> ").ifBlank { "none" }}</code></p>
            <p>Microfrontend order: <code>${summary.microfrontendOrder.joinToString(" -> ").ifBlank { "none" }}</code></p>
            <p>Terraform order: <code>${summary.terraformOrder.joinToString(" -> ").ifBlank { "none" }}</code></p>
            <h4>Notices</h4>
            <ul>${if (warnings.isBlank()) "<li>No warnings.</li>" else warnings}</ul>
            ${analysis.parseError?.let { "<p>Parse error: $it</p>" } ?: ""}
            </body></html>
        """.trimIndent()
    }

    private fun renderFlow(summary: MerebJenkinsPipelineSummary): String {
        val items = summary.flowSteps.joinToString("") { "<li>$it</li>" }
        return """
            <html><body>
            <h3>Flow</h3>
            <ul>${if (items.isBlank()) "<li>No derived flow available.</li>" else items}</ul>
            </body></html>
        """.trimIndent()
    }

    private fun renderUpstreamIdle() {
        upstreamPane.text = "<html><body><p>Run a manual refresh to compare the bundled schema with the live Mereb Jenkins schema.</p></body></html>"
    }

    private fun htmlPane(): JEditorPane = JEditorPane("text/html", "").apply {
        isEditable = false
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    }

    private fun isDisposedOrStale(refreshId: Int): Boolean {
        return disposed || project.isDisposed || refreshId != upstreamRefreshVersion.get()
    }

    override fun dispose() {
        disposed = true
        upstreamRefreshVersion.incrementAndGet()
        upstreamRefreshTask?.cancel(true)
        upstreamRefreshTask = null
    }
}

private fun projectContainsSupportedConfig(basePath: String): Boolean {
    val root = Paths.get(basePath)
    return MerebJenkinsConfigPaths.supportedRelativePaths().any { root.resolve(it).exists() }
}
