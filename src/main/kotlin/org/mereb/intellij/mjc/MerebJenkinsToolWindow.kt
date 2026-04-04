package org.mereb.intellij.mjc

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
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
import com.intellij.util.Alarm
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.HierarchyEvent
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
import javax.swing.JList
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

class MerebJenkinsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = MerebJenkinsToolWindowPanel(project, toolWindow)
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
    private val toolWindow: ToolWindow,
) : Disposable {
    private val analyzer = MerebJenkinsConfigAnalyzer()
    private val upstreamChecker = MerebJenkinsUpstreamChecker()
    private val jenkinsStateService = service<MerebJenkinsJenkinsStateService>()
    private val upstreamRefreshVersion = AtomicInteger()
    private val jenkinsRefreshVersion = AtomicInteger()
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val jenkinsPollAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    @Volatile private var disposed = false
    @Volatile private var upstreamRefreshTask: Future<*>? = null
    @Volatile private var jenkinsRefreshTask: Future<*>? = null
    private var workspaceRescanRequested = true
    private var currentVirtualFile: VirtualFile? = null
    private var currentAnalysis: MerebJenkinsAnalysisResult? = null
    private var currentTarget: MerebJenkinsWorkspaceTarget? = null
    private var availableTargets: List<MerebJenkinsWorkspaceTarget> = emptyList()
    private var updatingTargetSelector = false
    private var updatingVariantSelector = false
    private var currentJobMapping: MerebJenkinsJobMapping? = null
    private var currentJobVariantSelection: MerebJenkinsJobVariantSelection? = null
    private var currentLiveData: MerebJenkinsLiveJobData? = null
    private var currentJenkinsProblem: MerebJenkinsApiProblem? = null
    private var pendingJobSelectionRoots: MutableSet<String> = linkedSetOf()
    private val manualJobVariantSelections: MutableMap<String, String> = linkedMapOf()

    private val root = JPanel(BorderLayout(0, 12)).apply {
        border = JBUI.Borders.empty(12)
    }

    private val titleLabel = JBLabel("Mereb Jenkins", toolWindowIcon(), JBLabel.LEADING).apply {
        font = JBFont.h3().asBold()
    }
    private val subtitleLabel = JBLabel("Detecting Mereb Jenkins project context…")
    private val targetLabel = JBLabel("Project:")
    private val targetSelector = ComboBox<MerebJenkinsWorkspaceTarget>()
    private val refreshButton = JButton("Refresh")
    private val safeFixesButton = JButton("Apply Safe Fixes")
    private val recipeButton = JButton("Set Recipe")
    private val openJenkinsfileButton = JButton("Open Jenkinsfile")
    private val migrateButton = JButton("Migrate")
    private val checkUpstreamButton = JButton("Check Upstream Schema")
    private val connectJenkinsButton = JButton("Connect Jenkins")
    private val remapJobButton = JButton("Remap Job")
    private val refreshJenkinsButton = JButton("Refresh Live Data")
    private val openInJenkinsButton = JButton("Open in Jenkins")

    private val recipeCard = StatusCard("Recipe")
    private val imageCard = StatusCard("Image")
    private val releaseCard = StatusCard("Release")
    private val relationCard = StatusCard("Relations")
    private val noticeCard = StatusCard("Notices")
    private val fixCard = StatusCard("Safe Fixes")
    private val jenkinsConnectionCard = StatusCard("Jenkins")
    private val jenkinsJobCard = StatusCard("Job")
    private val jenkinsBuildCard = StatusCard("Live Build")

    private val findingsModel = DefaultListModel<MerebJenkinsFinding>()
    private val sectionsModel = DefaultListModel<MerebJenkinsSectionState>()
    private val flowModel = DefaultListModel<MerebJenkinsFlowStep>()
    private val relationsModel = DefaultListModel<MerebJenkinsRelation>()
    private val jenkinsRunsModel = DefaultListModel<MerebJenkinsRun>()
    private val jenkinsStagesModel = DefaultListModel<MerebJenkinsStage>()
    private val jenkinsPendingModel = DefaultListModel<MerebJenkinsPendingInput>()
    private val jenkinsArtifactsModel = DefaultListModel<MerebJenkinsArtifactLink>()

    private val findingsList = JBList(findingsModel)
    private val sectionsList = JBList(sectionsModel)
    private val flowList = JBList(flowModel)
    private val relationsList = JBList(relationsModel)
    private val jenkinsRunsList = JBList(jenkinsRunsModel)
    private val jenkinsStagesList = JBList(jenkinsStagesModel)
    private val jenkinsPendingList = JBList(jenkinsPendingModel)
    private val jenkinsArtifactsList = JBList(jenkinsArtifactsModel)

    private val upstreamStateLabel = JBLabel("Schema status: idle")
    private val upstreamHashesLabel = JBLabel("Run a manual check to compare bundled and live schema hashes.")
    private val upstreamErrorLabel = JBLabel("").apply {
        foreground = toneColor(Tone.ERROR)
    }
    private val jenkinsStatusLabel = JBLabel("Jenkins is not connected.")
    private val jenkinsJobLabel = JBLabel("Mapped job: none")
    private val jenkinsRefreshLabel = JBLabel("Last refresh: never")
    private val jenkinsErrorLabel = JBLabel("").apply {
        foreground = toneColor(Tone.ERROR)
    }
    private val jenkinsDiagnosticsLabel = JBLabel("").apply {
        font = JBFont.small()
        foreground = JBColor.GRAY
    }
    private val jenkinsVariantLabel = JBLabel("View:")
    private val jenkinsVariantSelector = ComboBox<MerebJenkinsJobCandidate>()
    private val jenkinsVariantHintLabel = JBLabel("Defaulting to the current branch when possible.")

    val component: JComponent
        get() = root

    init {
        refreshButton.addActionListener {
            refreshCurrentFile()
            scheduleJenkinsRefresh(delayMs = 0, manual = true)
        }
        safeFixesButton.addActionListener { applySafeFixes() }
        recipeButton.addActionListener { applyRecipeFix() }
        openJenkinsfileButton.addActionListener { openJenkinsfile() }
        migrateButton.addActionListener { runMigration() }
        checkUpstreamButton.addActionListener { refreshUpstream() }
        connectJenkinsButton.addActionListener {
            MerebJenkinsConnectionSupport.showConnectionDialog(project) {
                renderJenkinsDisconnected("Jenkins connection saved. Refreshing live data…")
                scheduleJenkinsRefresh(delayMs = 0, manual = true)
            }
        }
        remapJobButton.addActionListener { scheduleJenkinsRefresh(delayMs = 0, forceRemap = true, manual = true) }
        refreshJenkinsButton.addActionListener { scheduleJenkinsRefresh(delayMs = 0, manual = true) }
        openInJenkinsButton.addActionListener { openInJenkins() }
        targetSelector.addActionListener {
            if (updatingTargetSelector) return@addActionListener
            currentTarget = targetSelector.selectedItem as? MerebJenkinsWorkspaceTarget
            scheduleRefresh(delayMs = 0)
            scheduleJenkinsRefresh(delayMs = 0)
        }
        jenkinsVariantSelector.addActionListener {
            if (updatingVariantSelector) return@addActionListener
            val target = currentTarget ?: return@addActionListener
            val selected = jenkinsVariantSelector.selectedItem as? MerebJenkinsJobCandidate ?: return@addActionListener
            if (currentJobVariantSelection?.selected?.jobPath == selected.jobPath) return@addActionListener
            manualJobVariantSelections[target.projectRootPath] = selected.jobPath
            scheduleJenkinsRefresh(delayMs = 0, manual = true)
        }

        configureLists()
        configureTargetSelector()
        configureJenkinsVariantSelector()
        buildUi()
        root.addHierarchyListener { event ->
            if ((event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong()) != 0L) {
                if (root.isShowing && toolWindow.isVisible) {
                    scheduleJenkinsRefresh(delayMs = 0)
                } else {
                    jenkinsPollAlarm.cancelAllRequests()
                }
            }
        }

        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                scheduleRefresh()
                scheduleJenkinsRefresh()
            }
        })
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.isEmpty()) return
                val needsWorkspaceRescan = events.any { isWorkspaceTargetChange(it.path) }
                val target = currentTarget
                if (needsWorkspaceRescan || (target != null && events.any { target.isTrackedArtifact(it.path) })) {
                    scheduleRefresh(rescanWorkspace = needsWorkspaceRescan)
                }
            }
        })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                val target = currentTarget
                val needsWorkspaceRescan = isWorkspaceTargetChange(file.path)
                if (needsWorkspaceRescan || (target != null && target.isTrackedArtifact(file.path))) {
                    scheduleRefresh(rescanWorkspace = needsWorkspaceRescan)
                    scheduleJenkinsRefresh(rescanWorkspace = needsWorkspaceRescan)
                }
            }
        }, this)

        refreshCurrentFile()
        renderJenkinsDisconnected("Connect to Jenkins to show live build data for this project.")
        renderUpstreamIdle()
    }

    private fun buildUi() {
        root.add(buildHeader(), BorderLayout.NORTH)

        val tabs = JBTabbedPane()
        tabs.addTab("Overview", buildOverviewTab())
        tabs.addTab("Jenkins", buildJenkinsTab())
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
            add(targetLabel)
            add(targetSelector)
            add(refreshButton)
            add(checkUpstreamButton)
        }
        panel.add(left, BorderLayout.CENTER)
        panel.add(right, BorderLayout.EAST)
        return panel
    }

    private fun buildOverviewTab(): JComponent {
        val cards = JPanel(GridLayout(3, 3, 10, 10)).apply {
            add(recipeCard)
            add(imageCard)
            add(releaseCard)
            add(relationCard)
            add(noticeCard)
            add(fixCard)
            add(jenkinsConnectionCard)
            add(jenkinsJobCard)
            add(jenkinsBuildCard)
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

    private fun buildJenkinsTab(): JComponent = JPanel(BorderLayout(0, 10)).apply {
        val statusStack = JPanel(GridLayout(0, 1, 0, 6)).apply {
            add(jenkinsStatusLabel)
            add(jenkinsJobLabel)
            add(jenkinsRefreshLabel)
            add(jenkinsErrorLabel)
            add(jenkinsDiagnosticsLabel)
        }
        val selectorRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(jenkinsVariantLabel)
            add(jenkinsVariantSelector)
            add(jenkinsVariantHintLabel)
        }
        val top = JPanel(BorderLayout(0, 8)).apply {
            add(statusStack, BorderLayout.NORTH)
            add(selectorRow, BorderLayout.SOUTH)
        }
        val center = JPanel(GridLayout(2, 2, 10, 10)).apply {
            add(titledPanel("Recent Runs", JBScrollPane(jenkinsRunsList)))
            add(titledPanel("Stages", JBScrollPane(jenkinsStagesList)))
            add(titledPanel("Pending Input", JBScrollPane(jenkinsPendingList)))
            add(titledPanel("Artifacts", JBScrollPane(jenkinsArtifactsList)))
        }
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(connectJenkinsButton)
            add(remapJobButton)
            add(refreshJenkinsButton)
            add(openInJenkinsButton)
        }
        add(top, BorderLayout.NORTH)
        add(center, BorderLayout.CENTER)
        add(actions, BorderLayout.SOUTH)
    }

    private fun configureJenkinsVariantSelector() {
        jenkinsVariantSelector.preferredSize = JBUI.size(320, 28)
        jenkinsVariantLabel.isVisible = false
        jenkinsVariantSelector.isVisible = false
        jenkinsVariantHintLabel.foreground = JBColor.GRAY
        jenkinsVariantSelector.renderer = object : ColoredListCellRenderer<MerebJenkinsJobCandidate>() {
            override fun customizeCellRenderer(
                list: JList<out MerebJenkinsJobCandidate>,
                value: MerebJenkinsJobCandidate?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value == null) return
                append(value.leafName.ifBlank { value.jobDisplayName }, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append("  ${value.jobDisplayName}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
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

        jenkinsRunsList.cellRenderer = object : ColoredListCellRenderer<MerebJenkinsRun>() {
            override fun customizeCellRenderer(
                list: JList<out MerebJenkinsRun>,
                value: MerebJenkinsRun?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value == null) return
                append(value.name, attrsForBuildStatus(value.status))
                append("  ${value.status.lowercase().replace('_', ' ')}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }

        jenkinsStagesList.cellRenderer = object : ColoredListCellRenderer<MerebJenkinsStage>() {
            override fun customizeCellRenderer(
                list: JList<out MerebJenkinsStage>,
                value: MerebJenkinsStage?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value == null) return
                append(value.name, attrsForBuildStatus(value.status))
                append("  ${value.status.lowercase().replace('_', ' ')}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }

        jenkinsPendingList.cellRenderer = object : ColoredListCellRenderer<MerebJenkinsPendingInput>() {
            override fun customizeCellRenderer(
                list: JList<out MerebJenkinsPendingInput>,
                value: MerebJenkinsPendingInput?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value == null) return
                append(value.message, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            }
        }

        jenkinsArtifactsList.cellRenderer = object : ColoredListCellRenderer<MerebJenkinsArtifactLink>() {
            override fun customizeCellRenderer(
                list: JList<out MerebJenkinsArtifactLink>,
                value: MerebJenkinsArtifactLink?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value == null) return
                append(value.label, SimpleTextAttributes.REGULAR_ATTRIBUTES)
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
        jenkinsRunsList.addMouseListener(doubleClickListener {
            val run = jenkinsRunsList.selectedValue ?: return@doubleClickListener
            browseJenkinsUrl(run.url, "No Jenkins run URL is available for the selected build.")
        })
        jenkinsArtifactsList.addMouseListener(doubleClickListener {
            val artifact = jenkinsArtifactsList.selectedValue ?: return@doubleClickListener
            browseJenkinsUrl(artifact.url, "No Jenkins artifact URL is available for the selected artifact.")
        })
    }

    private fun configureTargetSelector() {
        targetLabel.isVisible = false
        targetSelector.isVisible = false
        targetSelector.preferredSize = JBUI.size(260, 28)
        targetSelector.renderer = object : ColoredListCellRenderer<MerebJenkinsWorkspaceTarget>() {
            override fun customizeCellRenderer(
                list: JList<out MerebJenkinsWorkspaceTarget>,
                value: MerebJenkinsWorkspaceTarget?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value == null) return
                append(workspaceTargetLabel(value), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                value.jenkinsfilePath?.let {
                    append("  Jenkinsfile", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            }
        }
    }

    private fun refreshCurrentFile() {
        if (disposed || project.isDisposed) return
        resolveWorkspaceFocus()
        val target = currentTarget
        currentVirtualFile = target?.let { VfsUtil.findFile(Paths.get(it.configFilePath), true) }
        val selectedConfig = currentVirtualFile
        if (target == null || selectedConfig == null) {
            titleLabel.text = "Mereb Jenkins"
            subtitleLabel.text = "No Mereb Jenkins project detected in this workspace."
            currentAnalysis = null
            clearModels()
            updateCards(null)
            updateButtons()
            renderJenkinsDisconnected("No Mereb Jenkins project detected in this workspace.")
            return
        }

        val document = FileDocumentManager.getInstance().getCachedDocument(selectedConfig)
            ?: FileDocumentManager.getInstance().getDocument(selectedConfig)
        val text = document?.text ?: selectedConfig.inputStream.bufferedReader().use { it.readText() }
        val analysis = analyzer.analyzeDetailed(text, selectedConfig.path)
        currentAnalysis = analysis

        titleLabel.text = analysis.summary.explicitRecipe?.let { "Mereb Jenkins: $it" } ?: "Mereb Jenkins: ${analysis.summary.resolvedRecipe}"
        subtitleLabel.text = buildSubtitle(target, analysis)

        refill(findingsModel, analysis.findings)
        refill(sectionsModel, analysis.summary.sections)
        refill(flowModel, analysis.summary.flowSteps)
        refill(relationsModel, analysis.summary.relations)
        updateCards(analysis)
        updateButtons()
        scheduleJenkinsRefresh(delayMs = 0)
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

    private fun refreshJenkins(forceRemap: Boolean = false, manual: Boolean = false) {
        if (disposed || project.isDisposed) return
        if (!manual && !canPollJenkins()) return
        resolveWorkspaceFocus()
        val target = currentTarget ?: run {
            renderJenkinsDisconnected("No Mereb Jenkins project detected in this workspace.")
            return
        }
        val snapshot = jenkinsStateService.snapshot()
        if (!snapshot.isConfigured) {
            renderJenkinsDisconnected("Connect to Jenkins to load live build data for this project.")
            return
        }

        val token = jenkinsStateService.resolveToken(snapshot.baseUrl, snapshot.username)
        if (token.isNullOrBlank()) {
            renderJenkinsDisconnected("No Jenkins API token is stored for the current Jenkins connection.")
            return
        }

        jenkinsStatusLabel.text = "Refreshing Jenkins live data…"
        jenkinsErrorLabel.text = ""
        jenkinsRefreshTask?.cancel(true)
        val refreshId = jenkinsRefreshVersion.incrementAndGet()
        val application = ApplicationManager.getApplication()

        jenkinsRefreshTask = application.executeOnPooledThread {
            if (isDisposedOrStaleJenkins(refreshId)) return@executeOnPooledThread
            val client = MerebJenkinsJenkinsClient(snapshot.baseUrl, snapshot.username, token)
            when (val validation = client.validateConnection()) {
                is MerebJenkinsApiResult.Failure -> {
                    recordConnectionProblem(validation.problem)
                    application.invokeLater({
                        if (isDisposedOrStaleJenkins(refreshId)) return@invokeLater
                        currentJenkinsProblem = validation.problem
                        renderJenkinsProblem(validation.problem, preserveLiveData = shouldPreserveLiveData(target))
                    }, ModalityState.any(), Condition<Any?> { isDisposedOrStaleJenkins(refreshId) })
                    return@executeOnPooledThread
                }
                is MerebJenkinsApiResult.Success -> Unit
            }
            val visibleJobsResult = client.fetchVisibleJobs()
            val visibleJobs = when (visibleJobsResult) {
                is MerebJenkinsApiResult.Success -> visibleJobsResult.value
                is MerebJenkinsApiResult.Failure -> {
                    recordConnectionProblem(visibleJobsResult.problem)
                    application.invokeLater({
                        if (isDisposedOrStaleJenkins(refreshId)) return@invokeLater
                        currentJenkinsProblem = visibleJobsResult.problem
                        renderJenkinsProblem(visibleJobsResult.problem, preserveLiveData = shouldPreserveLiveData(target))
                    }, ModalityState.any(), Condition<Any?> { isDisposedOrStaleJenkins(refreshId) })
                    return@executeOnPooledThread
                }
            }
            when (
                val resolution = MerebJenkinsJobResolver.resolveWithVisibleJobs(
                    stateService = jenkinsStateService,
                    client = client,
                    workspaceTarget = target,
                    workspaceBasePath = project.basePath,
                    visibleJobs = visibleJobs,
                    forceRemap = forceRemap,
                )
            ) {
                is MerebJenkinsJobResolution.Resolved -> {
                    val branchName = MerebJenkinsGitSupport.currentBranch(target.projectRootPath)
                    val variantSelection = MerebJenkinsJobResolver.resolveVariantSelection(
                        mapping = resolution.mapping,
                        visibleJobs = visibleJobs,
                        branchName = branchName,
                        manuallySelectedJobPath = manualJobVariantSelections[target.projectRootPath],
                    )
                    when (val live = client.fetchLiveJobData(variantSelection.selected.jobPath)) {
                        is MerebJenkinsApiResult.Success -> {
                            jenkinsStateService.recordConnectionStatus(MerebJenkinsConnectionStatus.CONNECTED, null, System.currentTimeMillis())
                            application.invokeLater({
                                if (isDisposedOrStaleJenkins(refreshId)) return@invokeLater
                                pendingJobSelectionRoots.remove(target.projectRootPath)
                                currentJobMapping = resolution.mapping
                                currentJobVariantSelection = variantSelection
                                currentLiveData = live.value
                                currentJenkinsProblem = null
                                renderJenkinsLive(resolution.mapping, variantSelection, live.value, resolution.autoSelected)
                                scheduleNextJenkinsPoll()
                            }, ModalityState.any(), Condition<Any?> { isDisposedOrStaleJenkins(refreshId) })
                        }
                        is MerebJenkinsApiResult.Failure -> {
                            if (live.problem.kind == MerebJenkinsApiProblemKind.NOT_FOUND && !forceRemap) {
                                jenkinsStateService.clearJobMapping(target.projectRootPath)
                                application.invokeLater({
                                    if (isDisposedOrStaleJenkins(refreshId)) return@invokeLater
                                    scheduleJenkinsRefresh(delayMs = 0, forceRemap = true, manual = manual)
                                }, ModalityState.any(), Condition<Any?> { isDisposedOrStaleJenkins(refreshId) })
                            } else {
                                recordConnectionProblem(live.problem)
                                application.invokeLater({
                                    if (isDisposedOrStaleJenkins(refreshId)) return@invokeLater
                                    currentJobMapping = resolution.mapping
                                    currentJobVariantSelection = variantSelection
                                    currentJenkinsProblem = live.problem
                                    renderJenkinsProblem(live.problem, preserveLiveData = shouldPreserveLiveData(target))
                                }, ModalityState.any(), Condition<Any?> { isDisposedOrStaleJenkins(refreshId) })
                            }
                        }
                    }
                }
                is MerebJenkinsJobResolution.NeedsSelection -> {
                    application.invokeLater({
                        if (isDisposedOrStaleJenkins(refreshId)) return@invokeLater
                        promptForJobSelection(target, resolution.matches, forcePrompt = manual || forceRemap)
                    }, ModalityState.any(), Condition<Any?> { isDisposedOrStaleJenkins(refreshId) })
                }
                is MerebJenkinsJobResolution.NoMatch -> {
                    application.invokeLater({
                        if (isDisposedOrStaleJenkins(refreshId)) return@invokeLater
                        renderJenkinsNoMatch(resolution.searchedLabels)
                    }, ModalityState.any(), Condition<Any?> { isDisposedOrStaleJenkins(refreshId) })
                }
                is MerebJenkinsJobResolution.Failure -> {
                    recordConnectionProblem(resolution.problem)
                    application.invokeLater({
                        if (isDisposedOrStaleJenkins(refreshId)) return@invokeLater
                        currentJenkinsProblem = resolution.problem
                        renderJenkinsProblem(resolution.problem, preserveLiveData = shouldPreserveLiveData(target))
                    }, ModalityState.any(), Condition<Any?> { isDisposedOrStaleJenkins(refreshId) })
                }
            }
        }
    }

    private fun promptForJobSelection(
        target: MerebJenkinsWorkspaceTarget,
        matches: List<MerebJenkinsJobCandidateMatch>,
        forcePrompt: Boolean,
    ) {
        if (!forcePrompt && pendingJobSelectionRoots.contains(target.projectRootPath)) {
            renderJenkinsNeedsSelection(matches.size)
            return
        }
        val dialog = MerebJenkinsJobPickerDialog(project, workspaceTargetLabel(target), matches)
        if (dialog.showAndGet()) {
            val selected = dialog.selectedCandidate
            if (selected != null) {
                pendingJobSelectionRoots.remove(target.projectRootPath)
                manualJobVariantSelections.remove(target.projectRootPath)
                jenkinsStateService.rememberJobMapping(target.projectRootPath, selected.jobPath, selected.jobDisplayName)
                scheduleJenkinsRefresh(delayMs = 0, manual = true)
                return
            }
        }
        pendingJobSelectionRoots += target.projectRootPath
        renderJenkinsNeedsSelection(matches.size)
    }

    private fun renderJenkinsDisconnected(message: String) {
        currentJobMapping = null
        currentJobVariantSelection = null
        currentLiveData = null
        currentJenkinsProblem = null
        clearJenkinsModels()
        updateJobVariantSelector(null)
        val snapshot = jenkinsStateService.snapshot()
        jenkinsStatusLabel.text = message
        jenkinsJobLabel.text = "Mapped job: none"
        jenkinsRefreshLabel.text = "Last refresh: never"
        jenkinsErrorLabel.text = ""
        jenkinsDiagnosticsLabel.text = ""
        jenkinsConnectionCard.update(
            "Disconnected",
            snapshot.baseUrl.ifBlank { "Configure Jenkins" },
            Tone.NEUTRAL
        )
        jenkinsJobCard.update("Unmapped", "No Jenkins job is selected for this project.", Tone.NEUTRAL)
        jenkinsBuildCard.update("No data", "Connect Jenkins to load recent runs and stages.", Tone.NEUTRAL)
        updateButtons()
    }

    private fun renderJenkinsNeedsSelection(matchCount: Int) {
        currentJobMapping = null
        currentJobVariantSelection = null
        currentLiveData = null
        clearJenkinsModels()
        updateJobVariantSelector(null)
        jenkinsStatusLabel.text = "Multiple Jenkins jobs matched this project."
        jenkinsJobLabel.text = "Mapped job: choose one of $matchCount candidates"
        jenkinsRefreshLabel.text = "Last refresh: waiting for job selection"
        jenkinsErrorLabel.text = "Use Remap Job to select the Jenkins job that should power live data."
        jenkinsDiagnosticsLabel.text = ""
        jenkinsConnectionCard.update("Connected", "Jenkins connection is ready.", Tone.SUCCESS)
        jenkinsJobCard.update("Selection required", "$matchCount candidate jobs found.", Tone.WARNING)
        jenkinsBuildCard.update("No data", "Live data will load once a job is selected.", Tone.NEUTRAL)
        updateButtons()
    }

    private fun renderJenkinsNoMatch(searchedLabels: List<String>) {
        currentJobMapping = null
        currentJobVariantSelection = null
        currentLiveData = null
        clearJenkinsModels()
        updateJobVariantSelector(null)
        jenkinsStatusLabel.text = "No Jenkins job matched the current project."
        jenkinsJobLabel.text = "Searched for: ${searchedLabels.joinToString(", ").ifBlank { "current project labels" }}"
        jenkinsRefreshLabel.text = "Last refresh: ${formatTimestamp(System.currentTimeMillis())}"
        jenkinsErrorLabel.text = "Use Remap Job after creating the Jenkins job or adjust the job naming."
        jenkinsDiagnosticsLabel.text = ""
        jenkinsConnectionCard.update("Connected", "Jenkins connection is ready.", Tone.SUCCESS)
        jenkinsJobCard.update("No match", searchedLabels.joinToString(", ").ifBlank { "No matching job" }, Tone.WARNING)
        jenkinsBuildCard.update("No data", "No Jenkins job is mapped for this project.", Tone.NEUTRAL)
        updateButtons()
    }

    private fun renderJenkinsProblem(problem: MerebJenkinsApiProblem, preserveLiveData: Boolean) {
        val snapshot = jenkinsStateService.snapshot()
        val staleLiveData = if (preserveLiveData) currentLiveData else null
        if (staleLiveData == null) {
            currentLiveData = null
            clearJenkinsModels()
        } else {
            refill(jenkinsRunsModel, staleLiveData.runs)
            refill(jenkinsStagesModel, staleLiveData.selectedRun?.stages.orEmpty())
            refill(jenkinsPendingModel, staleLiveData.pendingInputs)
            refill(jenkinsArtifactsModel, staleLiveData.artifacts)
        }
        updateJobVariantSelector(currentJobVariantSelection)
        jenkinsStatusLabel.text = when (problem.kind) {
            MerebJenkinsApiProblemKind.AUTH -> "Jenkins authentication failed."
            MerebJenkinsApiProblemKind.LOGIN_REDIRECT_WITH_AUTH_HEADER -> "Auth redirect detected."
            MerebJenkinsApiProblemKind.CROSS_ORIGIN_REDIRECT -> "Proxy or base URL issue detected."
            MerebJenkinsApiProblemKind.EDGE_FILTERED -> "Edge or CDN filtering detected."
            MerebJenkinsApiProblemKind.UNREACHABLE, MerebJenkinsApiProblemKind.TIMEOUT -> "Jenkins is unreachable."
            MerebJenkinsApiProblemKind.NOT_FOUND -> "The mapped Jenkins job no longer exists."
            MerebJenkinsApiProblemKind.INVALID_RESPONSE -> "Jenkins returned a response the plugin could not interpret."
            MerebJenkinsApiProblemKind.UNKNOWN -> "Jenkins returned an unexpected response."
        }
        jenkinsJobLabel.text = currentJobVariantSelection?.selected?.let { "Viewing job: ${it.jobDisplayName} (${it.jobPath})" }
            ?: currentJobMapping?.let { "Mapped job: ${it.jobDisplayName} (${it.jobPath})" }
            ?: "Mapped job: none"
        jenkinsRefreshLabel.text = if (staleLiveData != null) {
            "Last good refresh: ${formatTimestamp(staleLiveData.refreshedAt)} (data stale)"
        } else {
            "Last refresh: ${formatTimestamp(System.currentTimeMillis())}"
        }
        jenkinsErrorLabel.text = problem.message.orEmpty()
        jenkinsDiagnosticsLabel.text = buildJenkinsDiagnosticsText(snapshot, problem)
        jenkinsConnectionCard.update(
            if (staleLiveData != null) "Connected, data stale" else snapshot.status.name.lowercase().replace('_', ' ').replaceFirstChar(Char::titlecase),
            buildJenkinsConnectionDetail(snapshot, problem),
            if (staleLiveData != null) Tone.WARNING else toneForConnectionStatus(snapshot.status)
        )
        jenkinsJobCard.update(currentJobMapping?.jobDisplayName ?: "Unmapped", currentJobMapping?.jobPath ?: "No Jenkins job mapping.", if (staleLiveData != null) Tone.INFO else Tone.WARNING)
        jenkinsBuildCard.update(
            if (staleLiveData != null) "Stale" else "Unavailable",
            if (staleLiveData != null) "Showing last known good Jenkins data." else (problem.message ?: "Unable to load Jenkins build data."),
            if (staleLiveData != null) Tone.WARNING else Tone.ERROR
        )
        updateButtons()
    }

    private fun renderJenkinsLive(
        mapping: MerebJenkinsJobMapping,
        variantSelection: MerebJenkinsJobVariantSelection,
        liveData: MerebJenkinsLiveJobData,
        autoSelected: Boolean,
    ) {
        val snapshot = jenkinsStateService.snapshot()
        updateJobVariantSelector(variantSelection)
        refill(jenkinsRunsModel, liveData.runs)
        refill(jenkinsStagesModel, liveData.selectedRun?.stages.orEmpty())
        refill(jenkinsPendingModel, liveData.pendingInputs)
        refill(jenkinsArtifactsModel, liveData.artifacts)
        jenkinsStatusLabel.text = buildJenkinsStatusMessage(autoSelected, variantSelection)
        jenkinsJobLabel.text = "Viewing job: ${variantSelection.selected.jobDisplayName} (${variantSelection.selected.jobPath})"
        jenkinsRefreshLabel.text = "Last refresh: ${formatTimestamp(liveData.refreshedAt)}"
        jenkinsErrorLabel.text = if (liveData.pipelineAvailable) {
            if (liveData.pendingInputs.isNotEmpty()) "${liveData.pendingInputs.size} pending input action${if (liveData.pendingInputs.size == 1) "" else "s"} detected." else ""
        } else {
            "Pipeline stage details are unavailable for this Jenkins job."
        }
        jenkinsDiagnosticsLabel.text = ""
        jenkinsConnectionCard.update(
            "Connected",
            "${snapshot.username} @ ${snapshot.baseUrl}",
            Tone.SUCCESS
        )
        jenkinsJobCard.update(
            variantSelection.selected.leafName.ifBlank { variantSelection.selected.jobDisplayName },
            buildJenkinsVariantDetail(mapping, variantSelection),
            Tone.INFO
        )
        val buildLabel = liveData.selectedRun?.let { "${it.name} ${it.status}" }
            ?: liveData.summary.lastBuildNumber?.let { "#$it recent build" }
            ?: "No builds"
        val buildDetail = liveData.selectedRun?.url
            ?: liveData.summary.lastBuildUrl
            ?: "No Jenkins run URL available."
        jenkinsBuildCard.update(buildLabel, buildDetail, toneForBuildStatus(liveData.selectedRun?.status))
        updateButtons()
    }

    private fun clearJenkinsModels() {
        jenkinsRunsModel.clear()
        jenkinsStagesModel.clear()
        jenkinsPendingModel.clear()
        jenkinsArtifactsModel.clear()
    }

    private fun openInJenkins() {
        val liveData = currentLiveData
        val mapping = currentJobMapping
        val variant = currentJobVariantSelection?.selected
        val url = liveData?.selectedRun?.url
            ?: liveData?.summary?.url
            ?: variant?.let { buildJenkinsJobUrl(jenkinsStateService.snapshot().baseUrl, it.jobPath) }
            ?: mapping?.let { buildJenkinsJobUrl(jenkinsStateService.snapshot().baseUrl, it.jobPath) }
            ?: return
        browseJenkinsUrl(url, "No Jenkins URL is available for the current selection.")
    }

    private fun scheduleJenkinsRefresh(
        delayMs: Int = 300,
        rescanWorkspace: Boolean = false,
        forceRemap: Boolean = false,
        manual: Boolean = false,
    ) {
        if (disposed || project.isDisposed) return
        if (rescanWorkspace) {
            workspaceRescanRequested = true
        }
        if (!manual && !canPollJenkins()) return
        jenkinsPollAlarm.cancelAllRequests()
        jenkinsPollAlarm.addRequest({ refreshJenkins(forceRemap = forceRemap, manual = manual) }, delayMs)
    }

    private fun scheduleNextJenkinsPoll() {
        if (!canPollJenkins()) return
        scheduleJenkinsRefresh(delayMs = 20_000)
    }

    private fun canPollJenkins(): Boolean = !disposed && !project.isDisposed && toolWindow.isVisible && root.isShowing

    private fun recordConnectionProblem(problem: MerebJenkinsApiProblem) {
        val status = when (problem.kind) {
            MerebJenkinsApiProblemKind.AUTH -> MerebJenkinsConnectionStatus.AUTH_FAILED
            MerebJenkinsApiProblemKind.LOGIN_REDIRECT_WITH_AUTH_HEADER -> MerebJenkinsConnectionStatus.REDIRECTED_TO_LOGIN
            MerebJenkinsApiProblemKind.CROSS_ORIGIN_REDIRECT, MerebJenkinsApiProblemKind.EDGE_FILTERED -> MerebJenkinsConnectionStatus.PROXY_OR_BASE_URL_ISSUE
            MerebJenkinsApiProblemKind.UNREACHABLE, MerebJenkinsApiProblemKind.TIMEOUT -> MerebJenkinsConnectionStatus.CONTROLLER_UNREACHABLE
            else -> MerebJenkinsConnectionStatus.ERROR
        }
        jenkinsStateService.recordConnectionStatus(
            status,
            problem.message,
            null,
            problem.requestUrl,
            problem.redirectTarget,
            problem.redirectRelation,
        )
    }

    private fun shouldPreserveLiveData(target: MerebJenkinsWorkspaceTarget): Boolean {
        return currentLiveData != null && currentJobMapping?.projectRootPath == target.projectRootPath
    }

    private fun buildJenkinsConnectionDetail(snapshot: MerebJenkinsConnectionSnapshot, problem: MerebJenkinsApiProblem): String {
        return when {
            problem.kind == MerebJenkinsApiProblemKind.LOGIN_REDIRECT_WITH_AUTH_HEADER ->
                "Use a Jenkins API token. If this token is valid, your Jenkins OIDC/proxy setup may be intercepting API requests."
            problem.kind == MerebJenkinsApiProblemKind.CROSS_ORIGIN_REDIRECT ->
                "Check JENKINS_PUBLIC_URL, proxy redirects, and controller canonical URL handling."
            problem.kind == MerebJenkinsApiProblemKind.EDGE_FILTERED ->
                "A CDN or reverse proxy blocked the scripted API request before Jenkins handled it."
            snapshot.baseUrl.isNotBlank() -> snapshot.baseUrl
            else -> "Configure Jenkins"
        }
    }

    private fun buildJenkinsDiagnosticsText(
        snapshot: MerebJenkinsConnectionSnapshot,
        problem: MerebJenkinsApiProblem,
    ): String {
        val parts = listOfNotNull(
            snapshot.baseUrl.ifBlank { null }?.let { "Base URL: $it" },
            problem.requestUrl?.let { "Request: $it" },
            problem.redirectTarget?.let { "Redirect: $it" },
            problem.redirectRelation?.let { "Redirect type: $it" },
        )
        return parts.joinToString("  •  ")
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
            listOf(recipeCard, imageCard, releaseCard, relationCard, noticeCard, fixCard, jenkinsConnectionCard, jenkinsJobCard, jenkinsBuildCard).forEach {
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
        val snapshot = jenkinsStateService.snapshot()
        safeFixesButton.isEnabled = analysis?.summary?.safeFixes?.isNotEmpty() == true
        recipeButton.isEnabled = analysis?.summary?.safeFixes?.any {
            it.kind == MerebJenkinsFixKind.ADD_RECIPE || it.kind == MerebJenkinsFixKind.REPLACE_RECIPE
        } == true
        openJenkinsfileButton.isEnabled = analysis?.projectScan?.jenkinsfilePath != null
        migrateButton.isEnabled = currentVirtualFile != null
        connectJenkinsButton.text = if (snapshot.isConfigured) "Reconnect Jenkins" else "Connect Jenkins"
        remapJobButton.isEnabled = snapshot.isConfigured && currentTarget != null
        refreshJenkinsButton.isEnabled = snapshot.isConfigured && currentTarget != null
        openInJenkinsButton.isEnabled = currentLiveData != null || currentJobVariantSelection != null || currentJobMapping != null
    }

    private fun buildSubtitle(target: MerebJenkinsWorkspaceTarget, analysis: MerebJenkinsAnalysisResult): String {
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
            append(workspaceTargetLabel(target))
            append("  •  ")
            append(relativeConfigLabel(target))
            if (target.jenkinsfilePath != null) {
                append(" + Jenkinsfile")
            }
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

    private fun scheduleRefresh(delayMs: Int = 300, rescanWorkspace: Boolean = false) {
        if (disposed || project.isDisposed) return
        if (rescanWorkspace) {
            workspaceRescanRequested = true
        }
        refreshAlarm.cancelAllRequests()
        refreshAlarm.addRequest({ refreshCurrentFile() }, delayMs)
    }

    private fun resolveWorkspaceFocus() {
        if (workspaceRescanRequested || availableTargets.isEmpty()) {
            availableTargets = MerebJenkinsProjectScanner.discoverWorkspaceTargets(project.basePath)
            workspaceRescanRequested = false
        }

        if (availableTargets.isEmpty()) {
            currentTarget = null
            updateTargetSelector()
            return
        }

        val selectedFilePath = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.path
        val existingRoot = currentTarget?.projectRootPath
        val targetFromSelection = selectedFilePath?.let { path ->
            availableTargets.firstOrNull { it.ownsPath(path) }
        }
        val retainedTarget = existingRoot?.let { root ->
            availableTargets.firstOrNull { it.projectRootPath == root }
        }

        currentTarget = retainedTarget
            ?: targetFromSelection
            ?: availableTargets.first()
        updateTargetSelector()
    }

    private fun updateTargetSelector() {
        updatingTargetSelector = true
        try {
            targetSelector.removeAllItems()
            availableTargets.forEach(targetSelector::addItem)
            val multiTarget = availableTargets.size > 1
            targetLabel.isVisible = multiTarget
            targetSelector.isVisible = multiTarget
            currentTarget?.let { target ->
                val match = availableTargets.firstOrNull { it.projectRootPath == target.projectRootPath }
                if (match != null) {
                    targetSelector.selectedItem = match
                }
            }
        } finally {
            updatingTargetSelector = false
        }
    }

    private fun workspaceTargetLabel(target: MerebJenkinsWorkspaceTarget): String {
        val workspaceRoot = project.basePath?.let { runCatching { Paths.get(it).normalize() }.getOrNull() }
        val projectRoot = runCatching { Paths.get(target.projectRootPath).normalize() }.getOrNull()
            ?: return target.projectRootPath.substringAfterLast('/')
        return workspaceRoot
            ?.takeIf { projectRoot.startsWith(it) }
            ?.let { root ->
                runCatching { root.relativize(projectRoot).toString().ifBlank { projectRoot.fileName?.toString().orEmpty() } }.getOrNull()
            }
            ?: projectRoot.fileName?.toString()
            ?: target.projectRootPath
    }

    private fun relativeConfigLabel(target: MerebJenkinsWorkspaceTarget): String {
        val projectRoot = runCatching { Paths.get(target.projectRootPath) }.getOrNull() ?: return target.configFilePath
        val configPath = runCatching { Paths.get(target.configFilePath) }.getOrNull() ?: return target.configFilePath
        return runCatching { projectRoot.relativize(configPath).toString() }.getOrDefault(target.configFilePath)
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

    private fun isDisposedOrStaleJenkins(refreshId: Int): Boolean {
        return disposed || project.isDisposed || refreshId != jenkinsRefreshVersion.get()
    }

    private fun updateJobVariantSelector(variantSelection: MerebJenkinsJobVariantSelection?) {
        updatingVariantSelector = true
        try {
            currentJobVariantSelection = variantSelection
            jenkinsVariantSelector.removeAllItems()
            if (variantSelection == null) {
                jenkinsVariantLabel.isVisible = false
                jenkinsVariantSelector.isVisible = false
                jenkinsVariantHintLabel.text = "Defaulting to the current branch when possible."
                return
            }
            variantSelection.candidates.forEach(jenkinsVariantSelector::addItem)
            jenkinsVariantSelector.selectedItem = variantSelection.selected
            val hasChoices = variantSelection.candidates.size > 1
            jenkinsVariantLabel.isVisible = hasChoices
            jenkinsVariantSelector.isVisible = hasChoices
            jenkinsVariantHintLabel.text = when (variantSelection.mode) {
                MerebJenkinsJobVariantSelectionMode.CURRENT_BRANCH ->
                    variantSelection.branchName?.let { "Following current branch '$it'." } ?: "Following the current branch."
                MerebJenkinsJobVariantSelectionMode.MAIN_FALLBACK ->
                    variantSelection.branchName?.let { "No Jenkins job matched branch '$it'. Showing main." } ?: "Showing the main branch build."
                MerebJenkinsJobVariantSelectionMode.MANUAL -> "Showing the manually selected Jenkins job."
                MerebJenkinsJobVariantSelectionMode.MAPPED -> "Showing the mapped Jenkins job."
            }
        } finally {
            updatingVariantSelector = false
        }
    }

    private fun buildJenkinsStatusMessage(
        autoSelected: Boolean,
        variantSelection: MerebJenkinsJobVariantSelection,
    ): String {
        val prefix = if (autoSelected) {
            "Connected to Jenkins. The plugin auto-selected the project job."
        } else {
            "Connected to Jenkins. Live data is up to date for the selected project."
        }
        val selection = when (variantSelection.mode) {
            MerebJenkinsJobVariantSelectionMode.CURRENT_BRANCH ->
                variantSelection.branchName?.let { " Showing the build for local branch '$it'." } ?: " Showing the current branch build."
            MerebJenkinsJobVariantSelectionMode.MAIN_FALLBACK ->
                variantSelection.branchName?.let { " No branch-specific Jenkins job was found for '$it', so the main build is shown." }
                    ?: " Showing the main Jenkins build."
            MerebJenkinsJobVariantSelectionMode.MANUAL -> " Showing the manually selected Jenkins job."
            MerebJenkinsJobVariantSelectionMode.MAPPED -> " Showing the mapped Jenkins job."
        }
        return prefix + selection
    }

    private fun buildJenkinsVariantDetail(
        mapping: MerebJenkinsJobMapping,
        variantSelection: MerebJenkinsJobVariantSelection,
    ): String {
        val viewed = variantSelection.selected.jobPath
        return if (viewed == mapping.jobPath) {
            viewed
        } else {
            "$viewed • mapped from ${mapping.jobPath}"
        }
    }

    private fun browseJenkinsUrl(url: String?, emptyMessage: String) {
        val normalized = url?.trim().orEmpty()
        if (normalized.isBlank()) {
            jenkinsErrorLabel.text = emptyMessage
            return
        }
        BrowserUtil.browse(normalized)
    }

    override fun dispose() {
        disposed = true
        upstreamRefreshVersion.incrementAndGet()
        jenkinsRefreshVersion.incrementAndGet()
        upstreamRefreshTask?.cancel(true)
        upstreamRefreshTask = null
        jenkinsRefreshTask?.cancel(true)
        jenkinsRefreshTask = null
        jenkinsPollAlarm.cancelAllRequests()
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

private fun toneForConnectionStatus(status: MerebJenkinsConnectionStatus): Tone = when (status) {
    MerebJenkinsConnectionStatus.CONNECTED -> Tone.SUCCESS
    MerebJenkinsConnectionStatus.AUTH_FAILED -> Tone.ERROR
    MerebJenkinsConnectionStatus.REDIRECTED_TO_LOGIN -> Tone.WARNING
    MerebJenkinsConnectionStatus.PROXY_OR_BASE_URL_ISSUE -> Tone.ERROR
    MerebJenkinsConnectionStatus.CONTROLLER_UNREACHABLE -> Tone.WARNING
    MerebJenkinsConnectionStatus.ERROR -> Tone.ERROR
    MerebJenkinsConnectionStatus.DISCONNECTED -> Tone.NEUTRAL
}

private fun toneForBuildStatus(status: String?): Tone = when {
    status.isNullOrBlank() -> Tone.NEUTRAL
    status.contains("SUCCESS", ignoreCase = true) -> Tone.SUCCESS
    status.contains("FAIL", ignoreCase = true) || status.contains("ERROR", ignoreCase = true) || status.contains("ABORT", ignoreCase = true) -> Tone.ERROR
    status.contains("IN_PROGRESS", ignoreCase = true) || status.contains("RUNNING", ignoreCase = true) || status.contains("PAUSED", ignoreCase = true) || status.contains("QUEUED", ignoreCase = true) -> Tone.INFO
    else -> Tone.WARNING
}

private fun attrsForBuildStatus(status: String?): SimpleTextAttributes {
    val tone = toneForBuildStatus(status)
    val style = if (tone == Tone.ERROR) SimpleTextAttributes.STYLE_BOLD else SimpleTextAttributes.STYLE_PLAIN
    return SimpleTextAttributes(style, toneColor(tone))
}

private fun formatTimestamp(epochMillis: Long?): String {
    return epochMillis?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: "never"
}

private fun buildJenkinsJobUrl(baseUrl: String, jobPath: String): String {
    val normalizedBaseUrl = MerebJenkinsJenkinsStateService.normalizeBaseUrl(baseUrl)
    return normalizedBaseUrl + MerebJenkinsJenkinsClient.encodeJobPath(jobPath) + "/"
}

private fun doubleClickListener(action: () -> Unit): MouseAdapter = object : MouseAdapter() {
    override fun mouseClicked(event: MouseEvent) {
        if (event.clickCount == 2) {
            action()
        }
    }
}

private fun projectContainsSupportedConfig(basePath: String): Boolean {
    return MerebJenkinsProjectScanner.discoverWorkspaceTargets(basePath).isNotEmpty()
}

private fun MerebJenkinsWorkspaceTarget.isTrackedArtifact(path: String): Boolean {
    return path == configFilePath || path == jenkinsfilePath
}

private fun MerebJenkinsWorkspaceTarget.ownsPath(path: String): Boolean {
    return path == projectRootPath || path.startsWith("$projectRootPath/")
}

private fun isWorkspaceTargetChange(path: String): Boolean {
    return path.endsWith("/Jenkinsfile") || path == "Jenkinsfile" || MerebJenkinsConfigPaths.isSchemaTargetPath(path)
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
