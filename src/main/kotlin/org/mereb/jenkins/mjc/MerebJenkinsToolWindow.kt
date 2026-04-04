package org.mereb.jenkins.mjc

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
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
import java.awt.datatransfer.StringSelection
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Paths
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextArea

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
    private val uiStateService = service<MerebJenkinsProjectUiStateService>()
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
    private var updatingRunSelectors = false
    private var currentJobMapping: MerebJenkinsJobMapping? = null
    private var currentJobVariantSelection: MerebJenkinsJobVariantSelection? = null
    private var currentLiveData: MerebJenkinsLiveJobData? = null
    private var currentCompareContext: MerebJenkinsCompareContext? = null
    private var currentJenkinsProblem: MerebJenkinsApiProblem? = null
    private var currentSelectedPath: MerebJenkinsPath? = null
    private var currentStageMappings: List<MerebJenkinsConfigStageMapping> = emptyList()
    private var currentDriftFindings: List<MerebJenkinsDriftFinding> = emptyList()
    private var currentDeploymentTimeline: List<MerebJenkinsDeploymentTimelineEntry> = emptyList()
    private var currentImpactPreview: MerebJenkinsImpactPreview? = null
    private var currentConsoleExcerpt: MerebJenkinsConsoleExcerpt? = null
    private var pendingJobSelectionRoots: MutableSet<String> = linkedSetOf()
    private val manualJobVariantSelections: MutableMap<String, String> = linkedMapOf()

    private val root = JPanel(BorderLayout(0, 12)).apply {
        border = JBUI.Borders.empty(12)
    }
    private val tabs = JBTabbedPane()

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
    private val openLogButton = JButton("Open Exact Failing Log")
    private val openApprovalButton = JButton("Open Approval")
    private val rebuildButton = JButton("Rebuild")
    private val artifactOpenButton = JButton("Open Artifact")
    private val artifactCopyLinkButton = JButton("Copy Artifact Link")
    private val artifactDownloadButton = JButton("Download Artifact")
    private val compareModeToggle = JCheckBox("Compare to main")

    private val recipeCard = StatusCard("Recipe")
    private val imageCard = StatusCard("Image")
    private val releaseCard = StatusCard("Release")
    private val relationCard = StatusCard("Relations")
    private val noticeCard = StatusCard("Notices")
    private val fixCard = StatusCard("Safe Fixes")
    private val jenkinsConnectionCard = StatusCard("Jenkins")
    private val jenkinsJobCard = StatusCard("Job")
    private val jenkinsBuildCard = StatusCard("Live Build")
    private val jenkinsQueueCard = StatusCard("Queue")
    private val jenkinsApprovalCard = StatusCard("Approval")
    private val jenkinsSuccessCard = StatusCard("Last Success")
    private val jenkinsCompareCard = StatusCard("Compare")
    private val jenkinsTestCard = StatusCard("Tests")
    private val jenkinsArtifactCard = StatusCard("Artifacts")
    private val jenkinsTrendCard = StatusCard("Flaky Stages")
    private val jenkinsOpsCard = StatusCard("Ops")

    private val findingsModel = DefaultListModel<MerebJenkinsFinding>()
    private val sectionsModel = DefaultListModel<MerebJenkinsSectionState>()
    private val flowModel = DefaultListModel<MerebJenkinsFlowStep>()
    private val relationsModel = DefaultListModel<MerebJenkinsRelation>()
    private val jenkinsRunsModel = DefaultListModel<MerebJenkinsRun>()
    private val jenkinsStagesModel = DefaultListModel<MerebJenkinsStage>()
    private val jenkinsPendingModel = DefaultListModel<MerebJenkinsPendingInput>()
    private val jenkinsArtifactsModel = DefaultListModel<MerebJenkinsArtifactLink>()
    private val compareRunsModel = DefaultListModel<MerebJenkinsRun>()
    private val compareStagesModel = DefaultListModel<MerebJenkinsStage>()
    private val driftModel = DefaultListModel<MerebJenkinsDriftFinding>()
    private val timelineModel = DefaultListModel<MerebJenkinsDeploymentTimelineEntry>()
    private val jenkinsFailedTestsModel = DefaultListModel<MerebJenkinsFailedTest>()
    private val jenkinsTrendModel = DefaultListModel<MerebJenkinsStageTrend>()

    private val findingsList = JBList(findingsModel)
    private val sectionsList = JBList(sectionsModel)
    private val flowList = JBList(flowModel)
    private val relationsList = JBList(relationsModel)
    private val jenkinsRunsList = JBList(jenkinsRunsModel)
    private val jenkinsStagesList = JBList(jenkinsStagesModel)
    private val jenkinsPendingList = JBList(jenkinsPendingModel)
    private val jenkinsArtifactsList = JBList(jenkinsArtifactsModel)
    private val compareRunsList = JBList(compareRunsModel)
    private val compareStagesList = JBList(compareStagesModel)
    private val driftList = JBList(driftModel)
    private val timelineList = JBList(timelineModel)
    private val jenkinsFailedTestsList = JBList(jenkinsFailedTestsModel)
    private val jenkinsTrendList = JBList(jenkinsTrendModel)

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
    private val jenkinsVariantLabel = JBLabel("Primary:")
    private val jenkinsVariantSelector = ComboBox<MerebJenkinsJobCandidate>()
    private val jenkinsRunLabel = JBLabel("Run:")
    private val jenkinsRunSelector = ComboBox<MerebJenkinsRunChoice>()
    private val compareVariantLabel = JBLabel("Compare:")
    private val compareVariantSelector = ComboBox<MerebJenkinsJobCandidate>()
    private val compareRunLabel = JBLabel("Compare Run:")
    private val compareRunSelector = ComboBox<MerebJenkinsRunChoice>()
    private val jenkinsVariantHintLabel = JBLabel("Defaulting to the current branch when possible.")
    private val flowInfoLabel = JBLabel("Derived runtime sequence. Double-click a step to jump to the relevant YAML section.")
    private val relationsInfoLabel = JBLabel("Order lists, environment blocks, and recipe-driven sections are color-coded by relation status.")
    private val stageExcerptLabel = JBLabel("Select a Jenkins stage to inspect a recent console excerpt.")
    private val stageExcerptArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = JBFont.small()
    }
    private val testSummaryLabel = JBLabel("No JUnit summary for the selected run.")
    private val compareTestSummaryLabel = JBLabel("Compare-side test summary is unavailable.")
    private val trendSummaryLabel = JBLabel("Flaky/stage trend analysis is unavailable.")
    private val overviewSchemaLabel = JBLabel("Schema: idle").apply {
        font = JBFont.small()
        foreground = JBColor.GRAY
    }
    private val overviewCenterPanel = JPanel(GridLayout(1, 1, 10, 10))
    private val overviewFindingsPanel = titledPanel("Findings", JBScrollPane(findingsList))
    private val overviewSectionsPanel = titledPanel("Sections", JBScrollPane(sectionsList))
    private val jenkinsCardsPanel = JPanel(GridLayout(2, 5, 10, 10))
    private val jenkinsLivePanel = JPanel(GridLayout(1, 1, 10, 10))
    private val jenkinsPrimaryPanel = JPanel(BorderLayout(0, 8))
    private val jenkinsComparePanel = JPanel(BorderLayout(0, 8))

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
        openLogButton.addActionListener { openSelectedLog() }
        openApprovalButton.addActionListener { openApproval() }
        rebuildButton.addActionListener { rebuildSelectedJob() }
        artifactOpenButton.addActionListener { openSelectedArtifact() }
        artifactCopyLinkButton.addActionListener { copySelectedArtifactLink() }
        artifactDownloadButton.addActionListener { downloadSelectedArtifact() }
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
        compareModeToggle.addActionListener {
            if (updatingRunSelectors) return@addActionListener
            rememberViewSelection()
            scheduleJenkinsRefresh(delayMs = 0, manual = true)
        }
        jenkinsRunSelector.addActionListener {
            if (updatingRunSelectors) return@addActionListener
            rememberViewSelection()
            scheduleJenkinsRefresh(delayMs = 0, manual = true)
        }
        compareVariantSelector.addActionListener {
            if (updatingRunSelectors) return@addActionListener
            rememberViewSelection()
            scheduleJenkinsRefresh(delayMs = 0, manual = true)
        }
        compareRunSelector.addActionListener {
            if (updatingRunSelectors) return@addActionListener
            rememberViewSelection()
            scheduleJenkinsRefresh(delayMs = 0, manual = true)
        }

        configureLists()
        configureTargetSelector()
        configureJenkinsVariantSelector()
        buildUi()
        root.addHierarchyListener { event ->
            if ((event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong()) != 0L) {
                if (root.isShowing && toolWindow.isVisible) {
                    scheduleRefresh(delayMs = 0)
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
        project.messageBus.connect(this).subscribe(MerebJenkinsProjectUiStateService.TOPIC, MerebJenkinsUiRequestListener { path, tabName ->
            currentSelectedPath = path
            currentImpactPreview = currentAnalysis?.let { MerebJenkinsInsights.buildImpactPreview(it, path, currentJobVariantSelection) }
            updateFlowAndRelations(currentAnalysis)
            val tabIndex = (0 until tabs.tabCount).firstOrNull { tabs.getTitleAt(it) == tabName }
            if (tabIndex != null) {
                tabs.selectedIndex = tabIndex
            }
            scheduleRefresh(delayMs = 0)
            scheduleJenkinsRefresh(delayMs = 0, manual = true)
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
        EditorFactory.getInstance().eventMulticaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.editor.document) ?: return
                val target = currentTarget ?: return
                if (target.isTrackedArtifact(file.path)) {
                    scheduleRefresh(delayMs = 120)
                }
            }
        }, this)

        refreshCurrentFile()
        renderJenkinsDisconnected("Connect to Jenkins to show live build data for this project.")
        renderUpstreamIdle()
    }

    private fun buildUi() {
        root.add(buildHeader(), BorderLayout.NORTH)

        tabs.addTab("Overview", buildOverviewTab())
        tabs.addTab("Jenkins", buildJenkinsTab())
        tabs.addTab("Flow", buildFlowTab())
        tabs.addTab("Relations", buildRelationsTab())
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
        val cards = JPanel(GridLayout(3, 4, 10, 10)).apply {
            add(recipeCard)
            add(imageCard)
            add(releaseCard)
            add(relationCard)
            add(noticeCard)
            add(fixCard)
            add(jenkinsConnectionCard)
            add(jenkinsJobCard)
            add(jenkinsBuildCard)
            add(jenkinsTestCard)
            add(jenkinsTrendCard)
            add(jenkinsOpsCard)
        }

        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(safeFixesButton)
            add(recipeButton)
            add(openJenkinsfileButton)
            add(migrateButton)
        }
        val footer = JPanel(BorderLayout(0, 8)).apply {
            add(actions, BorderLayout.NORTH)
            add(overviewSchemaLabel, BorderLayout.SOUTH)
        }
        updateOverviewLayout()

        return JPanel(BorderLayout(0, 10)).apply {
            add(cards, BorderLayout.NORTH)
            add(overviewCenterPanel, BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)
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
        jenkinsCardsPanel.apply {
            add(jenkinsConnectionCard)
            add(jenkinsJobCard)
            add(jenkinsBuildCard)
            add(jenkinsQueueCard)
            add(jenkinsApprovalCard)
            add(jenkinsSuccessCard)
            add(jenkinsTestCard)
            add(jenkinsArtifactCard)
            add(jenkinsTrendCard)
            add(jenkinsOpsCard)
        }
        val primarySelectorRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(jenkinsVariantLabel)
            add(jenkinsVariantSelector)
            add(jenkinsRunLabel)
            add(jenkinsRunSelector)
            add(compareModeToggle)
        }
        val compareSelectorRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(compareVariantLabel)
            add(compareVariantSelector)
            add(compareRunLabel)
            add(compareRunSelector)
            add(jenkinsVariantHintLabel)
        }
        val top = JPanel(BorderLayout(0, 8)).apply {
            add(statusStack, BorderLayout.NORTH)
            add(jenkinsCardsPanel, BorderLayout.CENTER)
            add(JPanel(BorderLayout(0, 6)).apply {
                add(primarySelectorRow, BorderLayout.NORTH)
                add(compareSelectorRow, BorderLayout.SOUTH)
            }, BorderLayout.SOUTH)
        }

        jenkinsPrimaryPanel.apply {
            add(titledPanel("Recent Runs", JBScrollPane(jenkinsRunsList)), BorderLayout.NORTH)
            add(titledPanel("Stages", JBScrollPane(jenkinsStagesList)), BorderLayout.CENTER)
        }
        jenkinsComparePanel.apply {
            add(JPanel(BorderLayout(0, 6)).apply {
                add(compareTestSummaryLabel, BorderLayout.NORTH)
                add(titledPanel("Compare Runs", JBScrollPane(compareRunsList)), BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(titledPanel("Compare Stages", JBScrollPane(compareStagesList)), BorderLayout.CENTER)
        }
        updateJenkinsCompareLayout()
        val testPanel = JPanel(BorderLayout(0, 8)).apply {
            add(testSummaryLabel, BorderLayout.NORTH)
            add(JBScrollPane(jenkinsFailedTestsList), BorderLayout.CENTER)
        }
        val trendPanel = JPanel(BorderLayout(0, 8)).apply {
            add(trendSummaryLabel, BorderLayout.NORTH)
            add(JBScrollPane(jenkinsTrendList), BorderLayout.CENTER)
        }
        val artifactPanel = JPanel(BorderLayout(0, 8)).apply {
            add(JBScrollPane(jenkinsArtifactsList), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                add(artifactOpenButton)
                add(artifactCopyLinkButton)
                add(artifactDownloadButton)
            }, BorderLayout.SOUTH)
        }
        val supportPanel = JPanel(GridLayout(3, 2, 10, 10)).apply {
            add(titledPanel("Pending Input", JBScrollPane(jenkinsPendingList)))
            add(titledPanel("Artifacts", artifactPanel))
            add(titledPanel("Test Summary", testPanel))
            add(titledPanel("Flaky Stage Trends", trendPanel))
            add(titledPanel("Deployment Timeline", JBScrollPane(timelineList)))
            add(titledPanel("Drift Findings", JBScrollPane(driftList)))
        }
        val excerptPanel = JPanel(BorderLayout(0, 8)).apply {
            add(stageExcerptLabel, BorderLayout.NORTH)
            add(JBScrollPane(stageExcerptArea), BorderLayout.CENTER)
        }
        val center = JPanel(BorderLayout(0, 10)).apply {
            add(jenkinsLivePanel, BorderLayout.NORTH)
            add(supportPanel, BorderLayout.CENTER)
            add(excerptPanel, BorderLayout.SOUTH)
        }
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(connectJenkinsButton)
            add(remapJobButton)
            add(refreshJenkinsButton)
            add(rebuildButton)
            add(openApprovalButton)
            add(openLogButton)
            add(openInJenkinsButton)
        }
        add(top, BorderLayout.NORTH)
        add(center, BorderLayout.CENTER)
        add(actions, BorderLayout.SOUTH)
    }

    private fun configureJenkinsVariantSelector() {
        jenkinsVariantSelector.preferredSize = JBUI.size(320, 28)
        jenkinsRunSelector.preferredSize = JBUI.size(220, 28)
        compareVariantSelector.preferredSize = JBUI.size(320, 28)
        compareRunSelector.preferredSize = JBUI.size(220, 28)
        jenkinsVariantLabel.isVisible = false
        jenkinsVariantSelector.isVisible = false
        jenkinsRunLabel.isVisible = false
        jenkinsRunSelector.isVisible = false
        compareVariantLabel.isVisible = false
        compareVariantSelector.isVisible = false
        compareRunLabel.isVisible = false
        compareRunSelector.isVisible = false
        compareModeToggle.isVisible = false
        openLogButton.isVisible = false
        jenkinsVariantHintLabel.foreground = JBColor.GRAY
        val variantRenderer = object : ColoredListCellRenderer<MerebJenkinsJobCandidate>() {
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
        jenkinsVariantSelector.renderer = variantRenderer
        compareVariantSelector.renderer = variantRenderer
        val runRenderer = object : ColoredListCellRenderer<MerebJenkinsRunChoice>() {
            override fun customizeCellRenderer(
                list: JList<out MerebJenkinsRunChoice>,
                value: MerebJenkinsRunChoice?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value == null) return
                append(value.label, if (value.isLatest) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }
        jenkinsRunSelector.renderer = runRenderer
        compareRunSelector.renderer = runRenderer
    }

    private fun buildFlowTab(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        add(flowInfoLabel, BorderLayout.NORTH)
        add(JBScrollPane(flowList), BorderLayout.CENTER)
    }

    private fun buildRelationsTab(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        add(relationsInfoLabel, BorderLayout.NORTH)
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
        compareRunsList.cellRenderer = jenkinsRunsList.cellRenderer
        compareStagesList.cellRenderer = jenkinsStagesList.cellRenderer

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
                value.relativePath?.takeIf { it.isNotBlank() }?.let {
                    append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            }
        }
        jenkinsFailedTestsList.cellRenderer = object : ColoredListCellRenderer<MerebJenkinsFailedTest>() {
            override fun customizeCellRenderer(
                list: JList<out MerebJenkinsFailedTest>,
                value: MerebJenkinsFailedTest?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value == null) return
                append(value.caseName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  ${value.suiteName}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append("  ${value.status.lowercase()}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }
        jenkinsTrendList.cellRenderer = object : ColoredListCellRenderer<MerebJenkinsStageTrend>() {
            override fun customizeCellRenderer(
                list: JList<out MerebJenkinsStageTrend>,
                value: MerebJenkinsStageTrend?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value == null) return
                append(value.stageName, if (value.flaky) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append(
                    "  fail ${value.failureCount}/${value.appearanceCount}  unstable ${value.unstableCount}",
                    if (value.flaky) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES,
                )
            }
        }
        driftList.cellRenderer = object : ColoredListCellRenderer<MerebJenkinsDriftFinding>() {
            override fun customizeCellRenderer(
                list: JList<out MerebJenkinsDriftFinding>,
                value: MerebJenkinsDriftFinding?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value == null) return
                append("[${value.kind.name.lowercase()}] ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(value.message, attrsForDrift(value.kind))
                value.detail?.let { append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
            }
        }
        timelineList.cellRenderer = object : ColoredListCellRenderer<MerebJenkinsDeploymentTimelineEntry>() {
            override fun customizeCellRenderer(
                list: JList<out MerebJenkinsDeploymentTimelineEntry>,
                value: MerebJenkinsDeploymentTimelineEntry?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value == null) return
                append(value.environment, attrsForStageMapping(value.status))
                value.runName?.let { append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
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
        jenkinsRunsList.addMouseListener(doubleClickListener {
            val run = jenkinsRunsList.selectedValue ?: return@doubleClickListener
            browseJenkinsUrl(run.url, "No Jenkins run URL is available for the selected build.")
        })
        compareRunsList.addMouseListener(doubleClickListener {
            val run = compareRunsList.selectedValue ?: return@doubleClickListener
            browseJenkinsUrl(run.url, "No Jenkins run URL is available for the selected build.")
        })
        jenkinsStagesList.addMouseListener(doubleClickListener {
            loadConsoleExcerpt(primary = true)
        })
        compareStagesList.addMouseListener(doubleClickListener {
            loadConsoleExcerpt(primary = false)
        })
        jenkinsArtifactsList.addMouseListener(doubleClickListener {
            val artifact = jenkinsArtifactsList.selectedValue ?: return@doubleClickListener
            browseJenkinsUrl(artifact.url, "No Jenkins artifact URL is available for the selected artifact.")
        })
        jenkinsPendingList.addMouseListener(doubleClickListener {
            openApproval()
        })
        jenkinsFailedTestsList.addMouseListener(doubleClickListener {
            openSelectedLog()
        })
        jenkinsTrendList.addMouseListener(doubleClickListener {
            val trend = jenkinsTrendList.selectedValue ?: return@doubleClickListener
            val stageIndex = currentLiveData?.selectedRun?.stages?.indexOfFirst {
                it.name.equals(trend.stageName, ignoreCase = true)
            } ?: -1
            if (stageIndex >= 0) {
                jenkinsStagesList.selectedIndex = stageIndex
                loadConsoleExcerpt(primary = true)
            } else {
                openSelectedLog()
            }
        })
        driftList.addMouseListener(doubleClickListener {
            val finding = driftList.selectedValue ?: return@doubleClickListener
            currentAnalysis?.let { navigate(finding.path, it) }
            uiStateService.requestFocus(finding.path, "Jenkins")
        })
        listOf(
            jenkinsArtifactsList,
            jenkinsPendingList,
            jenkinsStagesList,
            compareStagesList,
            jenkinsRunsList,
            compareRunsList,
            jenkinsFailedTestsList,
            jenkinsTrendList,
        ).forEach { list ->
            list.addListSelectionListener { updateButtons() }
        }
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
        currentSelectedPath = resolveSelectedConfigPath(selectedConfig)
        currentImpactPreview = MerebJenkinsInsights.buildImpactPreview(analysis, currentSelectedPath, currentJobVariantSelection)

        titleLabel.text = analysis.summary.explicitRecipe?.let { "Mereb Jenkins: $it" } ?: "Mereb Jenkins: ${analysis.summary.resolvedRecipe}"
        subtitleLabel.text = buildSubtitle(target, analysis)

        refill(findingsModel, analysis.findings)
        refill(sectionsModel, analysis.summary.sections)
        updateFlowAndRelations(analysis)
        updateCards(analysis)
        updateButtons()
        consumeRequestedTab()
        scheduleJenkinsRefresh(delayMs = 0)
    }

    private fun refreshUpstream() {
        if (disposed || project.isDisposed) return
        upstreamStateLabel.text = "Schema status: checking…"
        upstreamHashesLabel.text = "Fetching live schema from GitHub…"
        upstreamErrorLabel.text = ""
        overviewSchemaLabel.text = "Schema: checking upstream hash…"
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
                overviewSchemaLabel.text = buildString {
                    append("Schema: ")
                    append(if (status.isCurrent) "current" else "stale or unavailable")
                    append(". Bundled ")
                    append(status.bundledHash.take(10))
                    append("…")
                    status.remoteHash?.let {
                        append(" vs remote ")
                        append(it.take(10))
                        append("…")
                    }
                    status.error?.takeIf(String::isNotBlank)?.let {
                        append(" — ")
                        append(it)
                    }
                }
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
            var visibleJobs: List<MerebJenkinsJobCandidate>? = null
            val preloadedSummaries = linkedMapOf<String, MerebJenkinsJobSummary>()
            val existingMapping = if (!forceRemap) jenkinsStateService.getJobMapping(target.projectRootPath) else null
            val resolution = if (existingMapping != null) {
                when (val summaryResult = client.fetchJobSummary(existingMapping.jobPath)) {
                    is MerebJenkinsApiResult.Success -> {
                        preloadedSummaries[existingMapping.jobPath] = summaryResult.value
                        visibleJobs = when (val familyResult = client.fetchJobFamilyCandidates(existingMapping.jobPath, summaryResult.value)) {
                            is MerebJenkinsApiResult.Success -> familyResult.value
                            is MerebJenkinsApiResult.Failure -> listOf(
                                MerebJenkinsJobCandidate(
                                    jobPath = existingMapping.jobPath,
                                    jobDisplayName = existingMapping.jobDisplayName.ifBlank { summaryResult.value.displayName },
                                    leafName = existingMapping.jobPath.substringAfterLast('/'),
                                    url = summaryResult.value.url,
                                    color = summaryResult.value.color,
                                    container = summaryResult.value.jobClass?.contains("WorkflowMultiBranchProject") == true,
                                    jobClass = summaryResult.value.jobClass,
                                )
                            )
                        }
                        MerebJenkinsJobResolution.Resolved(existingMapping, autoSelected = false)
                    }
                    is MerebJenkinsApiResult.Failure -> {
                        if (summaryResult.problem.kind == MerebJenkinsApiProblemKind.NOT_FOUND) {
                            jenkinsStateService.clearJobMapping(target.projectRootPath)
                            null
                        } else {
                            recordConnectionProblem(summaryResult.problem)
                            application.invokeLater({
                                if (isDisposedOrStaleJenkins(refreshId)) return@invokeLater
                                currentJenkinsProblem = summaryResult.problem
                                renderJenkinsProblem(summaryResult.problem, preserveLiveData = shouldPreserveLiveData(target))
                            }, ModalityState.any(), Condition<Any?> { isDisposedOrStaleJenkins(refreshId) })
                            return@executeOnPooledThread
                        }
                    }
                }
            } else {
                null
            } ?: run {
                val visibleJobsResult = client.fetchVisibleJobs()
                visibleJobs = when (visibleJobsResult) {
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
                MerebJenkinsJobResolver.resolveWithVisibleJobs(
                    stateService = jenkinsStateService,
                    client = client,
                    workspaceTarget = target,
                    workspaceBasePath = project.basePath,
                    visibleJobs = visibleJobs.orEmpty(),
                    forceRemap = forceRemap,
                )
            }
            when (resolution) {
                is MerebJenkinsJobResolution.Resolved -> {
                    val branchName = MerebJenkinsGitSupport.currentBranch(target.projectRootPath)
                    val rememberedView = jenkinsStateService.getViewSelection(target.projectRootPath)
                    val variantSelection = MerebJenkinsJobResolver.resolveVariantSelection(
                        mapping = resolution.mapping,
                        visibleJobs = visibleJobs.orEmpty(),
                        branchName = branchName,
                        manuallySelectedJobPath = manualJobVariantSelections[target.projectRootPath] ?: rememberedView?.primaryVariantJobPath,
                    )
                    val compareEnabled = rememberedView?.compareEnabled == true
                    val primaryRunId = rememberedView?.primaryRunId
                    val compareRunId = rememberedView?.compareRunId
                    val compareCandidate = if (compareEnabled) {
                        MerebJenkinsJobResolver.selectCompareCandidate(
                            variantSelection.candidates,
                            variantSelection.selected,
                            preferredJobPath = rememberedView?.compareVariantJobPath,
                        )
                    } else {
                        null
                    }
                    val selectedSummary = preloadedSummaries[variantSelection.selected.jobPath]
                    when (val live = client.fetchLiveJobData(variantSelection.selected.jobPath, primaryRunId, selectedSummary)) {
                        is MerebJenkinsApiResult.Success -> {
                            val compareLive = compareCandidate?.let { candidate ->
                                when (val result = client.fetchLiveJobData(candidate.jobPath, compareRunId)) {
                                    is MerebJenkinsApiResult.Success -> result.value
                                    is MerebJenkinsApiResult.Failure -> null
                                }
                            }
                            val compareContext = MerebJenkinsCompareContext(compareEnabled, compareCandidate, compareLive)
                            val analysis = currentAnalysis
                            val stageMappings = analysis?.let { MerebJenkinsInsights.buildStageMappings(it, live.value) }.orEmpty()
                            val driftFindings = analysis?.let {
                                MerebJenkinsInsights.buildDriftFindings(it, branchName, variantSelection, live.value, stageMappings)
                            }.orEmpty()
                            val deploymentTimeline = analysis?.let { MerebJenkinsInsights.buildDeploymentTimeline(it, live.value.runs) }.orEmpty()
                            val impactPreview = analysis?.let {
                                MerebJenkinsInsights.buildImpactPreview(it, currentSelectedPath, variantSelection)
                            }
                            jenkinsStateService.recordConnectionStatus(MerebJenkinsConnectionStatus.CONNECTED, null, System.currentTimeMillis())
                            application.invokeLater({
                                if (isDisposedOrStaleJenkins(refreshId)) return@invokeLater
                                pendingJobSelectionRoots.remove(target.projectRootPath)
                                currentJobMapping = resolution.mapping
                                currentJobVariantSelection = variantSelection
                                currentLiveData = live.value
                                currentCompareContext = compareContext
                                currentStageMappings = stageMappings
                                currentDriftFindings = driftFindings
                                currentDeploymentTimeline = deploymentTimeline
                                currentImpactPreview = impactPreview
                                currentJenkinsProblem = null
                                currentConsoleExcerpt = null
                                rememberViewSelection()
                                updateFlowAndRelations(analysis)
                                renderJenkinsLive(resolution.mapping, variantSelection, live.value, compareContext, resolution.autoSelected)
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
                                    currentCompareContext = null
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
        currentCompareContext = null
        currentJenkinsProblem = null
        currentStageMappings = emptyList()
        currentDriftFindings = emptyList()
        currentDeploymentTimeline = emptyList()
        currentImpactPreview = null
        currentConsoleExcerpt = null
        clearJenkinsModels()
        updateJobVariantSelector(null)
        val snapshot = jenkinsStateService.snapshot()
        jenkinsStatusLabel.text = message
        jenkinsJobLabel.text = "Mapped job: none"
        jenkinsRefreshLabel.text = "Last refresh: never"
        jenkinsErrorLabel.text = ""
        jenkinsDiagnosticsLabel.text = ""
        stageExcerptLabel.text = "Select a Jenkins stage to inspect a recent console excerpt."
        stageExcerptArea.text = ""
        jenkinsConnectionCard.update(
            "Disconnected",
            snapshot.baseUrl.ifBlank { "Configure Jenkins" },
            Tone.NEUTRAL
        )
        jenkinsJobCard.update("Unmapped", "No Jenkins job is selected for this project.", Tone.NEUTRAL)
        jenkinsBuildCard.update("No data", "Connect Jenkins to load recent runs and stages.", Tone.NEUTRAL)
        jenkinsQueueCard.update("Idle", "No queue information yet.", Tone.NEUTRAL)
        jenkinsApprovalCard.update("None", "No live approval state.", Tone.NEUTRAL)
        jenkinsSuccessCard.update("None", "No recent successful build.", Tone.NEUTRAL)
        jenkinsCompareCard.update("Off", "Compare mode is disabled.", Tone.NEUTRAL)
        jenkinsTestCard.update("No data", "No JUnit summary loaded.", Tone.NEUTRAL)
        jenkinsArtifactCard.update("No artifacts", "No Jenkins artifacts loaded.", Tone.NEUTRAL)
        jenkinsTrendCard.update("No trends", "No Jenkins trend data loaded.", Tone.NEUTRAL)
        jenkinsOpsCard.update("Unavailable", "Connect Jenkins to load project operations insight.", Tone.NEUTRAL)
        updateJenkinsInsightPanels(null, null)
        updateFlowAndRelations(currentAnalysis)
        updateButtons()
    }

    private fun renderJenkinsNeedsSelection(matchCount: Int) {
        currentJobMapping = null
        currentJobVariantSelection = null
        currentLiveData = null
        currentCompareContext = null
        clearJenkinsModels()
        updateJobVariantSelector(null)
        jenkinsStatusLabel.text = "Multiple Jenkins jobs matched this project."
        jenkinsJobLabel.text = "Mapped job: choose one of $matchCount candidates"
        jenkinsRefreshLabel.text = "Last refresh: waiting for job selection"
        jenkinsErrorLabel.text = "Use Remap Job to select the Jenkins job that should power live data."
        jenkinsDiagnosticsLabel.text = ""
        stageExcerptLabel.text = "Select a Jenkins stage to inspect a recent console excerpt."
        stageExcerptArea.text = ""
        jenkinsConnectionCard.update("Connected", "Jenkins connection is ready.", Tone.SUCCESS)
        jenkinsJobCard.update("Selection required", "$matchCount candidate jobs found.", Tone.WARNING)
        jenkinsBuildCard.update("No data", "Live data will load once a job is selected.", Tone.NEUTRAL)
        jenkinsQueueCard.update("Pending", "Choose the Jenkins job to inspect live queue data.", Tone.WARNING)
        jenkinsApprovalCard.update("None", "No live approval state.", Tone.NEUTRAL)
        jenkinsSuccessCard.update("None", "No successful build selected.", Tone.NEUTRAL)
        jenkinsCompareCard.update("Off", "Compare mode waits for a concrete job selection.", Tone.NEUTRAL)
        jenkinsTestCard.update("No data", "No JUnit summary loaded.", Tone.NEUTRAL)
        jenkinsArtifactCard.update("No artifacts", "No Jenkins artifacts loaded.", Tone.NEUTRAL)
        jenkinsTrendCard.update("No trends", "No Jenkins trend data loaded.", Tone.NEUTRAL)
        jenkinsOpsCard.update("Waiting", "Choose a concrete Jenkins job to enable deeper insight.", Tone.WARNING)
        updateJenkinsInsightPanels(null, null)
        updateFlowAndRelations(currentAnalysis)
        updateButtons()
    }

    private fun renderJenkinsNoMatch(searchedLabels: List<String>) {
        currentJobMapping = null
        currentJobVariantSelection = null
        currentLiveData = null
        currentCompareContext = null
        clearJenkinsModels()
        updateJobVariantSelector(null)
        jenkinsStatusLabel.text = "No Jenkins job matched the current project."
        jenkinsJobLabel.text = "Searched for: ${searchedLabels.joinToString(", ").ifBlank { "current project labels" }}"
        jenkinsRefreshLabel.text = "Last refresh: ${formatTimestamp(System.currentTimeMillis())}"
        jenkinsErrorLabel.text = "Use Remap Job after creating the Jenkins job or adjust the job naming."
        jenkinsDiagnosticsLabel.text = ""
        stageExcerptLabel.text = "Select a Jenkins stage to inspect a recent console excerpt."
        stageExcerptArea.text = ""
        jenkinsConnectionCard.update("Connected", "Jenkins connection is ready.", Tone.SUCCESS)
        jenkinsJobCard.update("No match", searchedLabels.joinToString(", ").ifBlank { "No matching job" }, Tone.WARNING)
        jenkinsBuildCard.update("No data", "No Jenkins job is mapped for this project.", Tone.NEUTRAL)
        jenkinsQueueCard.update("Unknown", "The plugin could not resolve a Jenkins job family.", Tone.WARNING)
        jenkinsApprovalCard.update("None", "No live approval state.", Tone.NEUTRAL)
        jenkinsSuccessCard.update("None", "No successful build selected.", Tone.NEUTRAL)
        jenkinsCompareCard.update("Off", "Compare mode is unavailable until a job is matched.", Tone.NEUTRAL)
        jenkinsTestCard.update("No data", "No JUnit summary loaded.", Tone.NEUTRAL)
        jenkinsArtifactCard.update("No artifacts", "No Jenkins artifacts loaded.", Tone.NEUTRAL)
        jenkinsTrendCard.update("No trends", "No Jenkins trend data loaded.", Tone.NEUTRAL)
        jenkinsOpsCard.update("No match", "No Jenkins project family could be matched for this workspace target.", Tone.WARNING)
        updateJenkinsInsightPanels(null, null)
        updateFlowAndRelations(currentAnalysis)
        updateButtons()
    }

    private fun renderJenkinsProblem(problem: MerebJenkinsApiProblem, preserveLiveData: Boolean) {
        val snapshot = jenkinsStateService.snapshot()
        val staleLiveData = if (preserveLiveData) currentLiveData else null
        if (staleLiveData == null) {
            currentLiveData = null
            currentCompareContext = null
            clearJenkinsModels()
        } else {
            refill(jenkinsRunsModel, staleLiveData.runs)
            refill(jenkinsStagesModel, staleLiveData.selectedRun?.stages.orEmpty())
            refill(jenkinsPendingModel, staleLiveData.pendingInputs)
            refill(jenkinsArtifactsModel, staleLiveData.artifacts)
            refill(compareRunsModel, currentCompareContext?.liveData?.runs.orEmpty())
            refill(compareStagesModel, currentCompareContext?.liveData?.selectedRun?.stages.orEmpty())
            refill(driftModel, currentDriftFindings)
            refill(timelineModel, currentDeploymentTimeline)
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
        jenkinsQueueCard.update(
            if (staleLiveData?.summary?.queueState?.inQueue == true) "Queued" else "Unknown",
            staleLiveData?.summary?.queueState?.reason ?: "Queue details are unavailable while Jenkins is failing.",
            if (staleLiveData?.summary?.queueState?.inQueue == true) Tone.WARNING else Tone.NEUTRAL
        )
        jenkinsApprovalCard.update(
            if (staleLiveData?.pendingInputs?.isNotEmpty() == true) "${staleLiveData.pendingInputs.size} waiting" else "None",
            staleLiveData?.pendingInputs?.firstOrNull()?.message ?: "No pending input details.",
            if (staleLiveData?.pendingInputs?.isNotEmpty() == true) Tone.WARNING else Tone.NEUTRAL
        )
        jenkinsSuccessCard.update(
            staleLiveData?.summary?.lastSuccessfulBuildNumber?.let { "#$it" } ?: "Unavailable",
            staleLiveData?.summary?.lastSuccessfulBuildUrl ?: "No last successful build URL available.",
            if (staleLiveData?.summary?.lastSuccessfulBuildNumber != null) Tone.SUCCESS else Tone.NEUTRAL
        )
        jenkinsCompareCard.update(
            if (currentCompareContext?.enabled == true) "Stale" else "Off",
            currentCompareContext?.selection?.jobDisplayName ?: "Compare mode is disabled.",
            if (currentCompareContext?.enabled == true) Tone.WARNING else Tone.NEUTRAL
        )
        jenkinsTestCard.update(
            staleLiveData?.testSummary?.let { "${it.failedCount} failed" } ?: "Unavailable",
            staleLiveData?.opsSnapshot?.testHeadline ?: "Test data is unavailable while Jenkins is failing.",
            when {
                (staleLiveData?.testSummary?.failedCount ?: 0) > 0 -> Tone.ERROR
                staleLiveData?.testSummary != null -> Tone.SUCCESS
                else -> Tone.NEUTRAL
            }
        )
        jenkinsArtifactCard.update(
            staleLiveData?.artifacts?.size?.let { "$it artifact${if (it == 1) "" else "s"}" } ?: "Unavailable",
            staleLiveData?.artifacts?.firstOrNull()?.relativePath ?: "Artifact details are unavailable while Jenkins is failing.",
            if (staleLiveData?.artifacts?.isNotEmpty() == true) Tone.INFO else Tone.NEUTRAL,
        )
        jenkinsTrendCard.update(
            staleLiveData?.trendSummary?.flakyStageCount?.let { "$it flaky" } ?: "Unavailable",
            staleLiveData?.trendSummary?.sampleSize?.takeIf { it > 0 }?.let { "Latest $it runs for the selected variant." }
                ?: "Trend analysis is unavailable while Jenkins is failing.",
            if ((staleLiveData?.trendSummary?.flakyStageCount ?: 0) > 0) Tone.WARNING else Tone.NEUTRAL,
        )
        jenkinsOpsCard.update(
            staleLiveData?.opsSnapshot?.headline ?: "Unavailable",
            staleLiveData?.opsSnapshot?.let {
                "${it.buildStatus} • ${it.pendingApprovalCount} approval${if (it.pendingApprovalCount == 1) "" else "s"} • ${it.testHeadline}"
            } ?: "Showing last known Jenkins state only.",
            if (staleLiveData != null) Tone.WARNING else Tone.NEUTRAL,
        )
        updateJenkinsInsightPanels(staleLiveData, currentCompareContext?.liveData)
        updateButtons()
    }

    private fun renderJenkinsLive(
        mapping: MerebJenkinsJobMapping,
        variantSelection: MerebJenkinsJobVariantSelection,
        liveData: MerebJenkinsLiveJobData,
        compareContext: MerebJenkinsCompareContext,
        autoSelected: Boolean,
    ) {
        val snapshot = jenkinsStateService.snapshot()
        updateJobVariantSelector(variantSelection)
        refill(jenkinsRunsModel, liveData.runs)
        refill(jenkinsStagesModel, liveData.selectedRun?.stages.orEmpty())
        refill(jenkinsPendingModel, liveData.pendingInputs)
        refill(jenkinsArtifactsModel, liveData.artifacts)
        refill(compareRunsModel, compareContext.liveData?.runs.orEmpty())
        refill(compareStagesModel, compareContext.liveData?.selectedRun?.stages.orEmpty())
        refill(driftModel, currentDriftFindings)
        refill(timelineModel, currentDeploymentTimeline)
        jenkinsStatusLabel.text = buildJenkinsStatusMessage(autoSelected, variantSelection)
        jenkinsJobLabel.text = "Viewing job: ${variantSelection.selected.jobDisplayName} (${variantSelection.selected.jobPath})"
        jenkinsRefreshLabel.text = "Last refresh: ${formatTimestamp(liveData.refreshedAt)}"
        jenkinsErrorLabel.text = if (liveData.summary.jobClass?.contains("WorkflowMultiBranchProject") == true && liveData.summary.branchCount == 0) {
            "The matching multibranch Jenkins job exists, but it has no indexed branch jobs yet."
        } else if (liveData.pipelineAvailable) {
            if (liveData.pendingInputs.isNotEmpty()) "${liveData.pendingInputs.size} pending input action${if (liveData.pendingInputs.size == 1) "" else "s"} detected." else ""
        } else {
            "Pipeline stage details are unavailable for this Jenkins job."
        }
        jenkinsDiagnosticsLabel.text = buildJenkinsDiagnosticsText(snapshot, currentJenkinsProblem)
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
        val queueState = liveData.summary.queueState
        jenkinsQueueCard.update(
            when {
                queueState?.inQueue == true -> "Queued"
                liveData.selectedRun?.running == true -> "Running"
                else -> "Idle"
            },
            queueState?.reason ?: liveData.selectedRun?.status ?: "No queue or running state reported.",
            when {
                queueState?.inQueue == true -> Tone.WARNING
                liveData.selectedRun?.running == true -> Tone.INFO
                else -> Tone.NEUTRAL
            }
        )
        jenkinsApprovalCard.update(
            if (liveData.pendingInputs.isNotEmpty()) "${liveData.pendingInputs.size} waiting" else "None",
            liveData.pendingInputs.firstOrNull()?.message ?: "No pending approval input.",
            if (liveData.pendingInputs.isNotEmpty()) Tone.WARNING else Tone.NEUTRAL
        )
        jenkinsSuccessCard.update(
            liveData.summary.lastSuccessfulBuildNumber?.let { "#$it" } ?: "Unavailable",
            liveData.summary.lastSuccessfulBuildUrl ?: "No successful build URL reported.",
            if (liveData.summary.lastSuccessfulBuildNumber != null) Tone.SUCCESS else Tone.NEUTRAL
        )
        jenkinsCompareCard.update(
            if (compareContext.enabled) compareContext.selection?.leafName ?: "On" else "Off",
            compareContext.liveData?.selectedRun?.let { "${it.name} ${it.status}" }
                ?: compareContext.selection?.jobDisplayName
                ?: "Compare mode is disabled.",
            if (compareContext.enabled) Tone.INFO else Tone.NEUTRAL
        )
        jenkinsTestCard.update(
            liveData.testSummary?.let { "${it.failedCount} failed / ${it.totalCount}" } ?: "No report",
            liveData.opsSnapshot?.testHeadline ?: "No test summary for the selected run.",
            when {
                (liveData.testSummary?.failedCount ?: 0) > 0 -> Tone.ERROR
                liveData.testSummary != null -> Tone.SUCCESS
                else -> Tone.NEUTRAL
            }
        )
        jenkinsArtifactCard.update(
            "${liveData.artifacts.size} artifact${if (liveData.artifacts.size == 1) "" else "s"}",
            liveData.artifacts.firstOrNull()?.relativePath ?: "No artifacts for the selected run.",
            if (liveData.artifacts.isNotEmpty()) Tone.INFO else Tone.NEUTRAL,
        )
        jenkinsTrendCard.update(
            "${liveData.trendSummary.flakyStageCount} flaky",
            liveData.trendSummary.sampleSize.takeIf { it > 0 }?.let { "Based on the latest $it runs." } ?: "No trend sample available.",
            if (liveData.trendSummary.flakyStageCount > 0) Tone.WARNING else Tone.SUCCESS,
        )
        jenkinsOpsCard.update(
            liveData.opsSnapshot?.headline ?: liveData.summary.displayName,
            liveData.opsSnapshot?.let {
                "${it.buildStatus} • ${it.pendingApprovalCount} approval${if (it.pendingApprovalCount == 1) "" else "s"} • ${it.testHeadline}"
            } ?: "No operational summary available.",
            toneForBuildStatus(liveData.selectedRun?.status),
        )
        stageExcerptLabel.text = currentConsoleExcerpt?.let {
            "Showing ${if (it.anchored) "anchored" else "tail"} console excerpt for ${it.stageName ?: "the selected run"}."
        } ?: "Select a Jenkins stage to inspect a recent console excerpt."
        stageExcerptArea.text = currentConsoleExcerpt?.excerpt.orEmpty()
        updateJenkinsInsightPanels(liveData, compareContext.liveData)
        updateFlowAndRelations(currentAnalysis)
        updateButtons()
    }

    private fun clearJenkinsModels() {
        jenkinsRunsModel.clear()
        jenkinsStagesModel.clear()
        jenkinsPendingModel.clear()
        jenkinsArtifactsModel.clear()
        compareRunsModel.clear()
        compareStagesModel.clear()
        driftModel.clear()
        timelineModel.clear()
        jenkinsFailedTestsModel.clear()
        jenkinsTrendModel.clear()
    }

    private fun updateJenkinsInsightPanels(
        primary: MerebJenkinsLiveJobData?,
        compare: MerebJenkinsLiveJobData?,
    ) {
        refill(jenkinsFailedTestsModel, primary?.testSummary?.failedTests.orEmpty())
        refill(jenkinsTrendModel, primary?.trendSummary?.stages.orEmpty())
        testSummaryLabel.text = when {
            primary?.testSummary == null -> "No JUnit summary for the selected run."
            primary.testSummary.failedCount > 0 ->
                "${primary.testSummary.failedCount} failed, ${primary.testSummary.skippedCount} skipped, ${primary.testSummary.totalCount} total."
            else -> "${primary.testSummary.totalCount} total, ${primary.testSummary.passedCount} passed."
        }
        compareTestSummaryLabel.text = when {
            compare == null -> "Compare-side test summary is unavailable."
            compare.testSummary == null -> "Compare run has no published JUnit report."
            compare.testSummary.failedCount > 0 ->
                "Compare: ${compare.testSummary.failedCount} failed / ${compare.testSummary.totalCount} total."
            else -> "Compare: ${compare.testSummary.totalCount} passed."
        }
        trendSummaryLabel.text = primary?.trendSummary?.sampleSize?.takeIf { it > 0 }?.let { sample ->
            val flaky = primary.trendSummary.flakyStageCount
            "$flaky flaky stage${if (flaky == 1) "" else "s"} across the latest $sample runs."
        } ?: "Flaky/stage trend analysis is unavailable."
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

    private fun openSelectedLog() {
        val liveData = currentLiveData
        val selectedStage = jenkinsStagesList.selectedValue
        val selectedRun = liveData?.selectedRun
        val failingStage = selectedRun?.stages?.firstOrNull { it.status.contains("FAIL", ignoreCase = true) || it.status.contains("UNSTABLE", ignoreCase = true) }
        val targetStage = selectedStage ?: failingStage
        if (targetStage != null && selectedRun != null && (currentConsoleExcerpt?.stageName != targetStage.name || currentConsoleExcerpt?.runId != selectedRun.id)) {
            loadConsoleExcerpt(primary = true)
        }
        val url = currentConsoleExcerpt?.takeIf { targetStage == null || it.stageName == targetStage.name }?.logUrl
            ?: currentLiveData?.actionAvailability?.failingLogUrl
            ?: currentLiveData?.selectedRun?.url?.let { "${it.trimEnd('/')}/console" }
            ?: currentCompareContext?.liveData?.selectedRun?.url?.let { "${it.trimEnd('/')}/console" }
        browseJenkinsUrl(url, "No Jenkins console log is available for the current selection.")
    }

    private fun openApproval() {
        val url = jenkinsPendingList.selectedValue?.proceedUrl
            ?: currentLiveData?.pendingInputs?.firstOrNull()?.proceedUrl
            ?: currentLiveData?.actionAvailability?.approvalUrl
        browseJenkinsUrl(url, "No Jenkins approval URL is available for the current selection.")
    }

    private fun rebuildSelectedJob() {
        val selection = currentJobVariantSelection ?: return
        val snapshot = jenkinsStateService.snapshot()
        val token = jenkinsStateService.resolveToken(snapshot.baseUrl, snapshot.username)
        if (token.isNullOrBlank()) {
            jenkinsErrorLabel.text = "No Jenkins API token is stored for the current Jenkins connection."
            return
        }
        val summary = currentLiveData?.summary
        val prompt = buildString {
            append("Trigger a Jenkins rebuild for:\n")
            append(selection.selected.jobDisplayName)
            append("\n\nJob path:\n")
            append(selection.selected.jobPath)
        }
        val confirmed = Messages.showYesNoDialog(
            project,
            prompt,
            "Confirm Jenkins Rebuild",
            "Rebuild",
            "Cancel",
            Messages.getQuestionIcon(),
        )
        if (confirmed != Messages.YES) return

        jenkinsErrorLabel.text = "Triggering Jenkins rebuild…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val client = MerebJenkinsJenkinsClient(snapshot.baseUrl, snapshot.username, token)
            val result = client.triggerRebuild(selection.selected.jobPath, summary)
            ApplicationManager.getApplication().invokeLater({
                when (result) {
                    is MerebJenkinsApiResult.Success -> {
                        if (result.value.success) {
                            jenkinsErrorLabel.text = result.value.message
                            scheduleJenkinsRefresh(delayMs = 0, manual = true)
                        } else {
                            jenkinsErrorLabel.text = result.value.message
                            result.value.openedUrl?.let { BrowserUtil.browse(it) }
                        }
                    }
                    is MerebJenkinsApiResult.Failure -> {
                        jenkinsErrorLabel.text = result.problem.message ?: "Unable to trigger a Jenkins rebuild."
                    }
                }
                updateButtons()
            }, ModalityState.any(), Condition<Any?> { disposed || project.isDisposed })
        }
    }

    private fun openSelectedArtifact() {
        val artifact = jenkinsArtifactsList.selectedValue ?: return
        browseJenkinsUrl(artifact.url, "No Jenkins artifact URL is available for the selected artifact.")
    }

    private fun copySelectedArtifactLink() {
        val artifact = jenkinsArtifactsList.selectedValue ?: return
        if (artifact.url.isBlank()) {
            jenkinsErrorLabel.text = "No Jenkins artifact URL is available for the selected artifact."
            return
        }
        CopyPasteManager.getInstance().setContents(StringSelection(artifact.url))
        jenkinsErrorLabel.text = "Copied Jenkins artifact link for ${artifact.label}."
    }

    private fun downloadSelectedArtifact() {
        val artifact = jenkinsArtifactsList.selectedValue ?: return
        val snapshot = jenkinsStateService.snapshot()
        val token = jenkinsStateService.resolveToken(snapshot.baseUrl, snapshot.username)
        if (token.isNullOrBlank()) {
            jenkinsErrorLabel.text = "No Jenkins API token is stored for the current Jenkins connection."
            return
        }
        val chooser = JFileChooser().apply {
            selectedFile = java.io.File(artifact.label)
            dialogTitle = "Save Jenkins Artifact"
        }
        if (chooser.showSaveDialog(root) != JFileChooser.APPROVE_OPTION) return
        val destination = chooser.selectedFile?.toPath() ?: return
        jenkinsErrorLabel.text = "Downloading ${artifact.label}…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val client = MerebJenkinsJenkinsClient(snapshot.baseUrl, snapshot.username, token)
            val result = client.downloadArtifact(artifact, destination)
            ApplicationManager.getApplication().invokeLater({
                when (result) {
                    is MerebJenkinsApiResult.Success -> {
                        jenkinsErrorLabel.text = "Saved Jenkins artifact to ${result.value.fileName}."
                    }
                    is MerebJenkinsApiResult.Failure -> {
                        jenkinsErrorLabel.text = result.problem.message ?: "Unable to download Jenkins artifact."
                    }
                }
            }, ModalityState.any(), Condition<Any?> { disposed || project.isDisposed })
        }
    }

    private fun rememberViewSelection() {
        val target = currentTarget ?: return
        jenkinsStateService.rememberViewSelection(
            MerebJenkinsViewSelection(
                projectRootPath = target.projectRootPath,
                compareEnabled = compareModeToggle.isSelected,
                primaryVariantJobPath = (jenkinsVariantSelector.selectedItem as? MerebJenkinsJobCandidate)?.jobPath ?: currentJobVariantSelection?.selected?.jobPath,
                compareVariantJobPath = (compareVariantSelector.selectedItem as? MerebJenkinsJobCandidate)?.jobPath ?: currentCompareContext?.selection?.jobPath,
                primaryRunId = (jenkinsRunSelector.selectedItem as? MerebJenkinsRunChoice)?.id,
                compareRunId = (compareRunSelector.selectedItem as? MerebJenkinsRunChoice)?.id,
            )
        )
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
        problem: MerebJenkinsApiProblem?,
    ): String {
        if (problem == null) {
            return listOfNotNull(
                snapshot.baseUrl.ifBlank { null }?.let { "Base URL: $it" },
                currentJobMapping?.jobPath?.let { "Job: $it" },
            ).joinToString("  •  ")
        }
        val parts = listOfNotNull(
            snapshot.baseUrl.ifBlank { null }?.let { "Base URL: $it" },
            problem.requestUrl?.let { "Request: $it" },
            problem.redirectTarget?.let { "Redirect: $it" },
            problem.redirectRelation?.let { "Redirect type: $it" },
        )
        return parts.joinToString("  •  ")
    }

    private fun loadConsoleExcerpt(primary: Boolean) {
        val target = currentTarget ?: return
        val snapshot = jenkinsStateService.snapshot()
        val token = jenkinsStateService.resolveToken(snapshot.baseUrl, snapshot.username) ?: return
        val liveData = if (primary) currentLiveData else currentCompareContext?.liveData
        val stage = if (primary) jenkinsStagesList.selectedValue else compareStagesList.selectedValue
        val run = liveData?.selectedRun ?: return
        val stageName = stage?.name
        stageExcerptLabel.text = "Loading console excerpt…"
        stageExcerptArea.text = ""
        ApplicationManager.getApplication().executeOnPooledThread {
            val client = MerebJenkinsJenkinsClient(snapshot.baseUrl, snapshot.username, token)
            val excerpt = client.fetchConsoleExcerpt(
                jobPath = (if (primary) currentJobVariantSelection?.selected else currentCompareContext?.selection)?.jobPath
                    ?: currentJobMapping?.jobPath
                    ?: return@executeOnPooledThread,
                runId = run.id,
                stageName = stageName,
            )
            ApplicationManager.getApplication().invokeLater({
                if (disposed || project.isDisposed || currentTarget?.projectRootPath != target.projectRootPath) return@invokeLater
                when (excerpt) {
                    is MerebJenkinsApiResult.Success -> {
                        currentConsoleExcerpt = excerpt.value
                        stageExcerptLabel.text =
                            "Showing ${if (excerpt.value.anchored) "anchored" else "tail"} console excerpt for ${excerpt.value.stageName ?: run.name}."
                        stageExcerptArea.text = excerpt.value.excerpt
                    }
                    is MerebJenkinsApiResult.Failure -> {
                        stageExcerptLabel.text = excerpt.problem.message ?: "Unable to load Jenkins console excerpt."
                        stageExcerptArea.text = ""
                    }
                }
                updateButtons()
            }, ModalityState.any(), Condition<Any?> { disposed || project.isDisposed })
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

    private fun resolveSelectedConfigPath(configFile: VirtualFile): MerebJenkinsPath? {
        uiStateService.consumeRequestedPath()?.let { return it }
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val editorFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        if (editorFile.path != configFile.path) return null
        return ApplicationManager.getApplication().runReadAction<MerebJenkinsPath?> {
            val psiFile = PsiManager.getInstance(project).findFile(editorFile) ?: return@runReadAction null
            val element = psiFile.findElementAt(editor.caretModel.offset) ?: return@runReadAction null
            MerebJenkinsPsiUtils.pathForElement(element)
        }
    }

    private fun consumeRequestedTab() {
        val requested = uiStateService.consumeRequestedTabName() ?: return
        val index = (0 until tabs.tabCount).firstOrNull { tabs.getTitleAt(it) == requested } ?: return
        tabs.selectedIndex = index
    }

    private fun updateFlowAndRelations(analysis: MerebJenkinsAnalysisResult?) {
        if (analysis == null) {
            refill(flowModel, emptyList())
            refill(relationsModel, emptyList())
            flowInfoLabel.text = "Derived runtime sequence. Double-click a step to jump to the relevant YAML section."
            relationsInfoLabel.text = "Order lists, environment blocks, and recipe-driven sections are color-coded by relation status."
            return
        }
        val stageMappingsByPath = currentStageMappings
            .filter { it.configPath != null }
            .associateBy { it.configPath.toString() }
        val flowSteps = analysis.summary.flowSteps.map { step ->
            val mapping = step.path?.toString()?.let(stageMappingsByPath::get)
            if (mapping == null) step else step.copy(status = relationStatusForMapping(mapping.status), detail = mapping.detail)
        }
        val relationEntries = buildList {
            addAll(analysis.summary.relations)
            addAll(
                currentStageMappings.map { mapping ->
                    MerebJenkinsRelation(
                        id = "jenkins-${mapping.id}",
                        group = "Jenkins",
                        label = mapping.label,
                        sourcePath = mapping.configPath,
                        targetPath = mapping.configPath,
                        status = relationStatusForMapping(mapping.status),
                        detail = mapping.detail,
                    )
                }
            )
        }
        refill(flowModel, flowSteps)
        refill(relationsModel, relationEntries)
        flowInfoLabel.text = currentImpactPreview?.let { "${it.title}: ${it.details.joinToString(" • ")}" }
            ?: "Derived runtime sequence. Double-click a step to jump to the relevant YAML section."
        val matchedCount = currentStageMappings.count { it.status == MerebJenkinsStageMappingStatus.MATCHED }
        val missingCount = currentStageMappings.count { it.status == MerebJenkinsStageMappingStatus.MISSING }
        val extraCount = currentStageMappings.count { it.status == MerebJenkinsStageMappingStatus.EXTRA }
        val driftCount = currentDriftFindings.size
        relationsInfoLabel.text = if (currentStageMappings.isNotEmpty()) {
            "$matchedCount Jenkins matches • $missingCount missing • $extraCount custom • $driftCount drift finding${if (driftCount == 1) "" else "s"}."
        } else {
            "Order lists, environment blocks, and recipe-driven sections are color-coded by relation status."
        }
    }

    private fun updateCards(analysis: MerebJenkinsAnalysisResult?) {
        if (analysis == null) {
            listOf(
                recipeCard,
                imageCard,
                releaseCard,
                relationCard,
                noticeCard,
                fixCard,
                jenkinsConnectionCard,
                jenkinsJobCard,
                jenkinsBuildCard,
                jenkinsTestCard,
                jenkinsTrendCard,
                jenkinsOpsCard,
            ).forEach {
                it.update("No config", "Open a supported config file to populate this panel.", Tone.NEUTRAL)
            }
            updateOverviewLayout()
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
            "${summary.referencedButMissing.size} missing, ${summary.definedButUnused.size} unused, ${currentDriftFindings.size} drift",
            when {
                summary.referencedButMissing.isNotEmpty() -> Tone.ERROR
                summary.definedButUnused.isNotEmpty() -> Tone.WARNING
                currentDriftFindings.isNotEmpty() -> Tone.WARNING
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
        val ops = currentLiveData?.opsSnapshot
        jenkinsTestCard.update(
            currentLiveData?.testSummary?.let { "${it.failedCount} failed / ${it.totalCount}" } ?: "No report",
            ops?.testHeadline ?: "No JUnit summary for the selected run.",
            when {
                (currentLiveData?.testSummary?.failedCount ?: 0) > 0 -> Tone.ERROR
                currentLiveData?.testSummary != null -> Tone.SUCCESS
                else -> Tone.NEUTRAL
            }
        )
        jenkinsTrendCard.update(
            currentLiveData?.trendSummary?.flakyStageCount?.let { "$it flaky" } ?: "No trends",
            currentLiveData?.trendSummary?.sampleSize?.takeIf { it > 0 }?.let { "Latest $it runs analyzed." }
                ?: "No stage trend data available.",
            if ((currentLiveData?.trendSummary?.flakyStageCount ?: 0) > 0) Tone.WARNING else Tone.NEUTRAL,
        )
        jenkinsOpsCard.update(
            ops?.headline ?: "No live run",
            ops?.let {
                "${it.buildStatus} • ${it.pendingApprovalCount} approvals • ${it.artifactCount} artifacts"
            } ?: "Connect Jenkins and select a run to populate operations insight.",
            if (ops != null) toneForBuildStatus(currentLiveData?.selectedRun?.status) else Tone.NEUTRAL,
        )
        updateOverviewLayout()
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
        val actions = currentLiveData?.actionAvailability
        openLogButton.isEnabled = currentConsoleExcerpt != null ||
            actions?.failingLogUrl != null ||
            currentLiveData?.selectedRun != null ||
            currentCompareContext?.liveData?.selectedRun != null
        openApprovalButton.isEnabled = actions?.approvalUrl != null || currentLiveData?.pendingInputs?.isNotEmpty() == true
        rebuildButton.isEnabled = actions?.canRebuild == true
        artifactOpenButton.isEnabled = jenkinsArtifactsList.selectedValue != null
        artifactCopyLinkButton.isEnabled = jenkinsArtifactsList.selectedValue != null
        artifactDownloadButton.isEnabled = snapshot.isConfigured && jenkinsArtifactsList.selectedValue != null
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
        overviewSchemaLabel.text = "Schema: idle. Run a manual check when you want to compare bundled and live hashes."
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
        updateOverviewLayout()
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

    private fun attrsForStageMapping(status: MerebJenkinsStageMappingStatus): SimpleTextAttributes = when (status) {
        MerebJenkinsStageMappingStatus.MATCHED -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, toneColor(Tone.SUCCESS))
        MerebJenkinsStageMappingStatus.MISSING -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, toneColor(Tone.ERROR))
        MerebJenkinsStageMappingStatus.EXTRA -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, toneColor(Tone.WARNING))
        MerebJenkinsStageMappingStatus.AMBIGUOUS -> SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, toneColor(Tone.WARNING))
    }

    private fun attrsForDrift(kind: MerebJenkinsDriftKind): SimpleTextAttributes = when (kind) {
        MerebJenkinsDriftKind.LOCAL -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, toneColor(Tone.WARNING))
        MerebJenkinsDriftKind.RUNTIME -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, toneColor(Tone.ERROR))
        MerebJenkinsDriftKind.BRANCH -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, toneColor(Tone.INFO))
    }

    private fun relationStatusForMapping(status: MerebJenkinsStageMappingStatus): MerebJenkinsRelationStatus = when (status) {
        MerebJenkinsStageMappingStatus.MATCHED -> MerebJenkinsRelationStatus.OK
        MerebJenkinsStageMappingStatus.MISSING -> MerebJenkinsRelationStatus.MISSING
        MerebJenkinsStageMappingStatus.EXTRA -> MerebJenkinsRelationStatus.UNUSED
        MerebJenkinsStageMappingStatus.AMBIGUOUS -> MerebJenkinsRelationStatus.INACTIVE
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
        updatingRunSelectors = true
        try {
            currentJobVariantSelection = variantSelection
            jenkinsVariantSelector.removeAllItems()
            compareVariantSelector.removeAllItems()
            jenkinsRunSelector.removeAllItems()
            compareRunSelector.removeAllItems()
            if (variantSelection == null) {
                jenkinsVariantLabel.isVisible = false
                jenkinsVariantSelector.isVisible = false
                jenkinsRunLabel.isVisible = false
                jenkinsRunSelector.isVisible = false
                compareModeToggle.isVisible = false
                compareVariantLabel.isVisible = false
                compareVariantSelector.isVisible = false
                compareRunLabel.isVisible = false
                compareRunSelector.isVisible = false
                openLogButton.isVisible = false
                jenkinsVariantHintLabel.text = "Defaulting to the current branch when possible."
                updateJenkinsCompareLayout()
                return
            }
            variantSelection.candidates.forEach(jenkinsVariantSelector::addItem)
            jenkinsVariantSelector.selectedItem = variantSelection.selected
            val hasChoices = variantSelection.candidates.size > 1
            jenkinsVariantLabel.isVisible = hasChoices
            jenkinsVariantSelector.isVisible = hasChoices
            val primaryChoices = MerebJenkinsJobResolver.runChoices(currentLiveData)
            primaryChoices.forEach(jenkinsRunSelector::addItem)
            jenkinsRunSelector.selectedItem = primaryChoices.firstOrNull { it.id == currentLiveData?.selectedRun?.id } ?: primaryChoices.firstOrNull()
            jenkinsRunLabel.isVisible = currentLiveData != null
            jenkinsRunSelector.isVisible = currentLiveData != null
            compareModeToggle.isVisible = hasChoices
            compareModeToggle.isSelected = currentCompareContext?.enabled == true
            variantSelection.candidates.forEach(compareVariantSelector::addItem)
            compareVariantSelector.selectedItem = currentCompareContext?.selection
                ?: MerebJenkinsJobResolver.selectCompareCandidate(variantSelection.candidates, variantSelection.selected)
            val compareChoices = MerebJenkinsJobResolver.runChoices(currentCompareContext?.liveData)
            compareChoices.forEach(compareRunSelector::addItem)
            compareRunSelector.selectedItem = compareChoices.firstOrNull { it.id == currentCompareContext?.liveData?.selectedRun?.id } ?: compareChoices.firstOrNull()
            compareVariantLabel.isVisible = compareModeToggle.isSelected && hasChoices
            compareVariantSelector.isVisible = compareModeToggle.isSelected && hasChoices
            compareRunLabel.isVisible = compareModeToggle.isSelected && currentCompareContext?.liveData != null
            compareRunSelector.isVisible = compareModeToggle.isSelected && currentCompareContext?.liveData != null
            openLogButton.isVisible = currentLiveData?.selectedRun != null || currentCompareContext?.liveData?.selectedRun != null
            jenkinsVariantHintLabel.text = when (variantSelection.mode) {
                MerebJenkinsJobVariantSelectionMode.CURRENT_BRANCH ->
                    variantSelection.branchName?.let { "Following current branch '$it'." } ?: "Following the current branch."
                MerebJenkinsJobVariantSelectionMode.MAIN_FALLBACK ->
                    variantSelection.branchName?.let { "No Jenkins job matched branch '$it'. Showing main." } ?: "Showing the main branch build."
                MerebJenkinsJobVariantSelectionMode.MANUAL -> "Showing the manually selected Jenkins job."
                MerebJenkinsJobVariantSelectionMode.MAPPED -> "Showing the mapped Jenkins job."
            }
            updateJenkinsCompareLayout()
        } finally {
            updatingRunSelectors = false
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

    private fun updateOverviewLayout() {
        overviewCenterPanel.removeAll()
        val grid = overviewCenterPanel.layout as GridLayout
        if (findingsModel.size == 0) {
            grid.rows = 1
            grid.columns = 1
            overviewCenterPanel.add(overviewSectionsPanel)
        } else {
            grid.rows = 1
            grid.columns = 2
            overviewCenterPanel.add(overviewFindingsPanel)
            overviewCenterPanel.add(overviewSectionsPanel)
        }
        overviewCenterPanel.revalidate()
        overviewCenterPanel.repaint()
    }

    private fun updateJenkinsCompareLayout() {
        jenkinsLivePanel.removeAll()
        val grid = jenkinsLivePanel.layout as GridLayout
        if (currentCompareContext?.enabled == true) {
            grid.rows = 1
            grid.columns = 2
            jenkinsLivePanel.add(jenkinsPrimaryPanel)
            jenkinsLivePanel.add(jenkinsComparePanel)
        } else {
            grid.rows = 1
            grid.columns = 1
            jenkinsLivePanel.add(jenkinsPrimaryPanel)
        }
        jenkinsLivePanel.revalidate()
        jenkinsLivePanel.repaint()
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
            font = JBFont.h3().asBold()
        }
        private val detailLabel = JBLabel(" ").apply {
            font = JBFont.small()
        }

        init {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1),
                JBUI.Borders.empty(10)
            )
            preferredSize = Dimension(140, 76)
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
