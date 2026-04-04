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
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Paths
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.io.path.exists

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
    private val upstreamRefreshVersion = AtomicInteger()

    @Volatile private var disposed = false
    @Volatile private var upstreamRefreshTask: Future<*>? = null
    private var currentVirtualFile: VirtualFile? = null
    private var currentAnalysis: MerebJenkinsAnalysisResult? = null

    private val root = JPanel(BorderLayout(0, 12)).apply {
        border = JBUI.Borders.empty(12)
    }

    private val titleLabel = JBLabel("Mereb Jenkins", toolWindowIcon(), JBLabel.LEADING).apply {
        font = JBFont.h3().asBold()
    }
    private val subtitleLabel = JBLabel("Select a Mereb Jenkins config to inspect pipeline behavior.")
    private val refreshButton = JButton("Refresh")
    private val safeFixesButton = JButton("Apply Safe Fixes")
    private val recipeButton = JButton("Set Recipe")
    private val openJenkinsfileButton = JButton("Open Jenkinsfile")
    private val migrateButton = JButton("Migrate")
    private val checkUpstreamButton = JButton("Check Upstream Schema")

    private val recipeCard = StatusCard("Recipe")
    private val imageCard = StatusCard("Image")
    private val releaseCard = StatusCard("Release")
    private val relationCard = StatusCard("Relations")
    private val noticeCard = StatusCard("Notices")
    private val fixCard = StatusCard("Safe Fixes")

    private val findingsModel = DefaultListModel<MerebJenkinsFinding>()
    private val sectionsModel = DefaultListModel<MerebJenkinsSectionState>()
    private val flowModel = DefaultListModel<MerebJenkinsFlowStep>()
    private val relationsModel = DefaultListModel<MerebJenkinsRelation>()

    private val findingsList = JBList(findingsModel)
    private val sectionsList = JBList(sectionsModel)
    private val flowList = JBList(flowModel)
    private val relationsList = JBList(relationsModel)

    private val upstreamStateLabel = JBLabel("Schema status: idle")
    private val upstreamHashesLabel = JBLabel("Run a manual check to compare bundled and live schema hashes.")
    private val upstreamErrorLabel = JBLabel("").apply {
        foreground = toneColor(Tone.ERROR)
    }

    val component: JComponent
        get() = root

    init {
        refreshButton.addActionListener { refreshCurrentFile() }
        safeFixesButton.addActionListener { applySafeFixes() }
        recipeButton.addActionListener { applyRecipeFix() }
        openJenkinsfileButton.addActionListener { openJenkinsfile() }
        migrateButton.addActionListener { runMigration() }
        checkUpstreamButton.addActionListener { refreshUpstream() }

        configureLists()
        buildUi()

        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                refreshCurrentFile()
            }
        })

        refreshCurrentFile()
        renderUpstreamIdle()
    }

    private fun buildUi() {
        root.add(buildHeader(), BorderLayout.NORTH)

        val tabs = JBTabbedPane()
        tabs.addTab("Overview", buildOverviewTab())
        tabs.addTab("Flow", buildFlowTab())
        tabs.addTab("Relations", buildRelationsTab())
        tabs.addTab("Upstream", buildUpstreamTab())
        root.add(tabs, BorderLayout.CENTER)
    }

    private fun buildHeader(): JComponent {
        val panel = JPanel(BorderLayout(12, 0))
        val left = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(titleLabel, BorderLayout.NORTH)
            add(subtitleLabel, BorderLayout.CENTER)
        }
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
            add(refreshButton)
            add(checkUpstreamButton)
        }
        panel.add(left, BorderLayout.CENTER)
        panel.add(right, BorderLayout.EAST)
        return panel
    }

    private fun buildOverviewTab(): JComponent {
        val cards = JPanel(GridLayout(2, 3, 10, 10)).apply {
            add(recipeCard)
            add(imageCard)
            add(releaseCard)
            add(relationCard)
            add(noticeCard)
            add(fixCard)
        }

        val findingsPanel = titledPanel("Findings", JBScrollPane(findingsList))
        val sectionsPanel = titledPanel("Sections", JBScrollPane(sectionsList))
        val center = JPanel(GridLayout(1, 2, 10, 10)).apply {
            add(findingsPanel)
            add(sectionsPanel)
        }
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(safeFixesButton)
            add(recipeButton)
            add(openJenkinsfileButton)
            add(migrateButton)
        }

        return JPanel(BorderLayout(0, 10)).apply {
            add(cards, BorderLayout.NORTH)
            add(center, BorderLayout.CENTER)
            add(actions, BorderLayout.SOUTH)
        }
    }

    private fun buildFlowTab(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        add(JBLabel("Derived runtime sequence. Double-click a step to jump to the relevant YAML section."), BorderLayout.NORTH)
        add(JBScrollPane(flowList), BorderLayout.CENTER)
    }

    private fun buildRelationsTab(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        add(JBLabel("Order lists, environment blocks, and recipe-driven sections are color-coded by relation status."), BorderLayout.NORTH)
        add(JBScrollPane(relationsList), BorderLayout.CENTER)
    }

    private fun buildUpstreamTab(): JComponent = JPanel(BorderLayout(0, 10)).apply {
        val stack = JPanel(GridLayout(3, 1, 0, 6)).apply {
            add(upstreamStateLabel)
            add(upstreamHashesLabel)
            add(upstreamErrorLabel)
        }
        add(stack, BorderLayout.NORTH)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { add(checkUpstreamButton) }, BorderLayout.SOUTH)
    }

    private fun configureLists() {
        findingsList.cellRenderer = object : ColoredListCellRenderer<MerebJenkinsFinding>() {
            override fun customizeCellRenderer(
                list: javax.swing.JList<out MerebJenkinsFinding>,
                value: MerebJenkinsFinding?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value == null) return
                icon = null
                append(
                    value.message,
                    if (value.severity == MerebJenkinsSeverity.ERROR) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES
                )
                val detail = value.path?.toString() ?: value.anchorPath?.toString()
                if (!detail.isNullOrBlank()) {
                    append("  $detail", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            }
        }

        sectionsList.cellRenderer = object : ColoredListCellRenderer<MerebJenkinsSectionState>() {
            override fun customizeCellRenderer(
                list: javax.swing.JList<out MerebJenkinsSectionState>,
                value: MerebJenkinsSectionState?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value == null) return
                append(value.label, attrsForRelation(value.status))
                value.detail?.let { append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
            }
        }

        flowList.cellRenderer = object : ColoredListCellRenderer<MerebJenkinsFlowStep>() {
            override fun customizeCellRenderer(
                list: javax.swing.JList<out MerebJenkinsFlowStep>,
                value: MerebJenkinsFlowStep?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value == null) return
                append("${index + 1}. ${value.label}", attrsForRelation(value.status))
                value.detail?.let { append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
            }
        }

        relationsList.cellRenderer = object : ColoredListCellRenderer<MerebJenkinsRelation>() {
            override fun customizeCellRenderer(
                list: javax.swing.JList<out MerebJenkinsRelation>,
                value: MerebJenkinsRelation?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value == null) return
                append("[${value.group}] ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(value.label, attrsForRelation(value.status))
                value.detail?.let { append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
            }
        }

        findingsList.addMouseListener(doubleClickListener {
            currentAnalysis?.let { analysis ->
                val finding = findingsList.selectedValue ?: return@doubleClickListener
                navigate(finding.path ?: finding.anchorPath, analysis)
            }
        })
        sectionsList.addMouseListener(doubleClickListener {
            currentAnalysis?.let { analysis ->
                val section = sectionsList.selectedValue ?: return@doubleClickListener
                navigate(section.path, analysis)
            }
        })
        flowList.addMouseListener(doubleClickListener {
            currentAnalysis?.let { analysis ->
                val step = flowList.selectedValue ?: return@doubleClickListener
                navigate(step.path, analysis)
            }
        })
        relationsList.addMouseListener(doubleClickListener {
            currentAnalysis?.let { analysis ->
                val relation = relationsList.selectedValue ?: return@doubleClickListener
                navigate(relation.targetPath ?: relation.sourcePath, analysis)
            }
        })
    }

    private fun refreshCurrentFile() {
        if (disposed || project.isDisposed) return
        val virtualFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        currentVirtualFile = virtualFile?.takeIf { MerebJenkinsConfigPaths.isSchemaTarget(it) }
        val selectedConfig = currentVirtualFile
        if (selectedConfig == null) {
            titleLabel.text = "Mereb Jenkins"
            subtitleLabel.text = "Select a Mereb Jenkins config to inspect pipeline behavior."
            currentAnalysis = null
            clearModels()
            updateCards(null)
            updateButtons()
            return
        }

        val document = FileEditorManager.getInstance(project).selectedTextEditor?.document
        val text = document?.text ?: selectedConfig.inputStream.bufferedReader().use { it.readText() }
        val analysis = analyzer.analyzeDetailed(text, selectedConfig.path)
        currentAnalysis = analysis

        titleLabel.text = analysis.summary.explicitRecipe?.let { "Mereb Jenkins: $it" } ?: "Mereb Jenkins: ${analysis.summary.resolvedRecipe}"
        subtitleLabel.text = buildSubtitle(selectedConfig, analysis)

        refill(findingsModel, analysis.findings)
        refill(sectionsModel, analysis.summary.sections)
        refill(flowModel, analysis.summary.flowSteps)
        refill(relationsModel, analysis.summary.relations)
        updateCards(analysis)
        updateButtons()
    }

    private fun refreshUpstream() {
        if (disposed || project.isDisposed) return
        upstreamStateLabel.text = "Schema status: checking…"
        upstreamHashesLabel.text = "Fetching live schema from GitHub…"
        upstreamErrorLabel.text = ""
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
                upstreamStateLabel.text = "Schema status: ${if (status.isCurrent) "Current" else "Stale or unavailable"}"
                upstreamHashesLabel.text = "Bundled ${status.bundledHash.take(10)}…  Remote ${status.remoteHash?.take(10) ?: "unavailable"}…"
                upstreamErrorLabel.text = status.error ?: ""
            }, ModalityState.any(), Condition<Any?> { isDisposedOrStale(refreshId) })
        }
    }

    private fun applySafeFixes() {
        val file = currentVirtualFile ?: return
        val analysis = currentAnalysis ?: return
        MerebJenkinsWorkbench.applySafeFixes(project, file, analysis)
        refreshCurrentFile()
    }

    private fun applyRecipeFix() {
        val file = currentVirtualFile ?: return
        val analysis = currentAnalysis ?: return
        val recipeFix = analysis.summary.safeFixes.firstOrNull { it.kind == MerebJenkinsFixKind.ADD_RECIPE || it.kind == MerebJenkinsFixKind.REPLACE_RECIPE }
            ?: return
        MerebJenkinsWorkbench.applySuggestions(project, file, analysis, listOf(recipeFix))
        refreshCurrentFile()
    }

    private fun openJenkinsfile() {
        val analysis = currentAnalysis ?: return
        if (!MerebJenkinsWorkbench.openJenkinsfile(project, analysis)) {
            subtitleLabel.text = "No sibling Jenkinsfile found for the active config."
        }
    }

    private fun runMigration() {
        val file = currentVirtualFile ?: return
        MerebJenkinsWorkbench.runMigrationAssistant(project, file)
        refreshCurrentFile()
    }

    private fun navigate(path: MerebJenkinsPath?, analysis: MerebJenkinsAnalysisResult) {
        val file = currentVirtualFile ?: return
        MerebJenkinsWorkbench.navigateToPath(project, file, path)
        if (path == null && analysis.projectScan.jenkinsfilePath != null) {
            openJenkinsfile()
        }
    }

    private fun updateCards(analysis: MerebJenkinsAnalysisResult?) {
        if (analysis == null) {
            listOf(recipeCard, imageCard, releaseCard, relationCard, noticeCard, fixCard).forEach {
                it.update("No config", "Open a supported config file to populate this panel.", Tone.NEUTRAL)
            }
            return
        }
        val summary = analysis.summary
        recipeCard.update(
            summary.resolvedRecipe,
            summary.explicitRecipe?.let { "Explicit recipe" } ?: "Inferred from config shape",
            toneForRecipe(summary.resolvedRecipe)
        )
        imageCard.update(
            if (summary.imageEnabled) "Enabled" else "Disabled",
            summary.capabilities.firstOrNull { it.id == "image" }?.detail ?: "",
            if (summary.imageEnabled) Tone.INFO else Tone.NEUTRAL
        )
        releaseCard.update(
            if (summary.releaseEnabled) "On" else "Off",
            summary.capabilities.firstOrNull { it.id == "release" }?.detail ?: "",
            if (summary.releaseEnabled) Tone.SUCCESS else Tone.NEUTRAL
        )
        relationCard.update(
            "${summary.relations.size} links",
            "${summary.referencedButMissing.size} missing, ${summary.definedButUnused.size} unused",
            when {
                summary.referencedButMissing.isNotEmpty() -> Tone.ERROR
                summary.definedButUnused.isNotEmpty() -> Tone.WARNING
                else -> Tone.INFO
            }
        )
        noticeCard.update(
            "${summary.errorCount} errors / ${summary.warningCount} warnings",
            (summary.repoWarnings + summary.ignoredFields).firstOrNull() ?: "No runtime notices.",
            when {
                summary.errorCount > 0 -> Tone.ERROR
                summary.warningCount > 0 -> Tone.WARNING
                else -> Tone.SUCCESS
            }
        )
        fixCard.update(
            "${summary.safeFixes.size} safe fix${if (summary.safeFixes.size == 1) "" else "es"}",
            summary.safeFixes.firstOrNull()?.label ?: "No safe fixes available.",
            if (summary.safeFixes.isNotEmpty()) Tone.SUCCESS else Tone.NEUTRAL
        )
    }

    private fun updateButtons() {
        val analysis = currentAnalysis
        safeFixesButton.isEnabled = analysis?.summary?.safeFixes?.isNotEmpty() == true
        recipeButton.isEnabled = analysis?.summary?.safeFixes?.any {
            it.kind == MerebJenkinsFixKind.ADD_RECIPE || it.kind == MerebJenkinsFixKind.REPLACE_RECIPE
        } == true
        openJenkinsfileButton.isEnabled = analysis?.projectScan?.jenkinsfilePath != null
        migrateButton.isEnabled = currentVirtualFile != null
    }

    private fun buildSubtitle(file: VirtualFile, analysis: MerebJenkinsAnalysisResult): String {
        val extras = mutableListOf<String>()
        if (analysis.summary.referencedButMissing.isNotEmpty()) {
            extras += "${analysis.summary.referencedButMissing.size} missing relation${if (analysis.summary.referencedButMissing.size == 1) "" else "s"}"
        }
        if (analysis.summary.definedButUnused.isNotEmpty()) {
            extras += "${analysis.summary.definedButUnused.size} unused definition${if (analysis.summary.definedButUnused.size == 1) "" else "s"}"
        }
        if (analysis.parseError != null) {
            extras += "YAML parse issue"
        }
        return buildString {
            append(file.path)
            if (extras.isNotEmpty()) {
                append("  •  ")
                append(extras.joinToString(" • "))
            }
        }
    }

    private fun renderUpstreamIdle() {
        upstreamStateLabel.text = "Schema status: idle"
        upstreamHashesLabel.text = "Run a manual check to compare bundled and live schema hashes."
        upstreamErrorLabel.text = ""
    }

    private fun clearModels() {
        findingsModel.clear()
        sectionsModel.clear()
        flowModel.clear()
        relationsModel.clear()
    }

    private fun <T> refill(model: DefaultListModel<T>, values: List<T>) {
        model.clear()
        values.forEach(model::addElement)
    }

    private fun attrsForRelation(status: MerebJenkinsRelationStatus): SimpleTextAttributes = when (status) {
        MerebJenkinsRelationStatus.OK -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, toneColor(Tone.INFO))
        MerebJenkinsRelationStatus.MISSING -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, toneColor(Tone.ERROR))
        MerebJenkinsRelationStatus.UNUSED -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, toneColor(Tone.WARNING))
        MerebJenkinsRelationStatus.IGNORED, MerebJenkinsRelationStatus.INACTIVE -> SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, JBColor.GRAY)
    }

    private fun toneForRecipe(recipe: String): Tone = when (recipe) {
        "service" -> Tone.INFO
        "package" -> Tone.SUCCESS
        "microfrontend" -> Tone.WARNING
        "terraform" -> Tone.ERROR
        "image" -> Tone.INFO
        else -> Tone.NEUTRAL
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

    private class StatusCard(title: String) : JPanel(BorderLayout(0, 6)) {
        private val titleLabel = JBLabel(title).apply {
            font = JBFont.small()
            foreground = JBColor.GRAY
        }
        private val valueLabel = JBLabel("-").apply {
            font = JBFont.h2().asBold()
        }
        private val detailLabel = JBLabel(" ").apply {
            font = JBFont.small()
        }

        init {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1),
                JBUI.Borders.empty(10)
            )
            preferredSize = Dimension(140, 88)
            add(titleLabel, BorderLayout.NORTH)
            add(valueLabel, BorderLayout.CENTER)
            add(detailLabel, BorderLayout.SOUTH)
        }

        fun update(value: String, detail: String, tone: Tone) {
            valueLabel.text = value
            valueLabel.foreground = toneColor(tone)
            detailLabel.text = detail.ifBlank { " " }
        }
    }
}

private fun toolWindowIcon() = IconLoader.getIcon("/icons/mereb-toolwindow.svg", MerebJenkinsToolWindowFactory::class.java)

private fun titledPanel(title: String, component: JComponent): JComponent = JPanel(BorderLayout(0, 8)).apply {
    add(JBLabel(title).apply { font = JBFont.medium().asBold() }, BorderLayout.NORTH)
    add(component, BorderLayout.CENTER)
}

private fun toneColor(tone: Tone): java.awt.Color = when (tone) {
    Tone.SUCCESS -> JBColor(ColorPalette.SUCCESS_LIGHT, ColorPalette.SUCCESS_DARK)
    Tone.WARNING -> JBColor(ColorPalette.WARNING_LIGHT, ColorPalette.WARNING_DARK)
    Tone.ERROR -> JBColor(ColorPalette.ERROR_LIGHT, ColorPalette.ERROR_DARK)
    Tone.INFO -> JBColor(ColorPalette.INFO_LIGHT, ColorPalette.INFO_DARK)
    Tone.NEUTRAL -> JBColor.foreground()
}

private fun doubleClickListener(action: () -> Unit): MouseAdapter = object : MouseAdapter() {
    override fun mouseClicked(event: MouseEvent) {
        if (event.clickCount == 2) {
            action()
        }
    }
}

private fun projectContainsSupportedConfig(basePath: String): Boolean {
    val root = Paths.get(basePath)
    return MerebJenkinsConfigPaths.supportedRelativePaths().any { root.resolve(it).exists() }
}

private object ColorPalette {
    val SUCCESS_LIGHT = java.awt.Color(0x2E7D32)
    val SUCCESS_DARK = java.awt.Color(0x7FD38A)
    val WARNING_LIGHT = java.awt.Color(0xB36B00)
    val WARNING_DARK = java.awt.Color(0xF0B451)
    val ERROR_LIGHT = java.awt.Color(0xC62828)
    val ERROR_DARK = java.awt.Color(0xFF8A80)
    val INFO_LIGHT = java.awt.Color(0x1565C0)
    val INFO_DARK = java.awt.Color(0x8AB4F8)
}

private enum class Tone {
    NEUTRAL,
    SUCCESS,
    WARNING,
    ERROR,
    INFO,
}
