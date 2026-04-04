package org.mereb.intellij.mjc

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

class MerebJenkinsConnectionDialog(
    project: Project?,
    private val onSaved: (() -> Unit)? = null,
) : DialogWrapper(project, true) {
    private val stateService = service<MerebJenkinsJenkinsStateService>()
    private val snapshot = stateService.snapshot()

    private val baseUrlField = JBTextField(snapshot.baseUrl.ifBlank { MerebJenkinsJenkinsStateService.DEFAULT_BASE_URL })
    private val usernameField = JBTextField(snapshot.username)
    private val tokenField = JBPasswordField()
    private val tokenHintLabel = JBLabel().apply { font = JBFont.small() }
    private val statusLabel = JBLabel("Enter your Jenkins username and API token.").apply {
        border = JBUI.Borders.emptyTop(8)
    }
    private val diagnosticsLabel = JBLabel().apply {
        font = JBFont.small()
        verticalAlignment = SwingConstants.TOP
        border = JBUI.Borders.emptyTop(4)
        isVisible = false
    }

    init {
        title = "Connect to Jenkins"
        tokenField.emptyText.text = "Leave blank to keep the existing stored token"
        refreshTokenHint()
        init()
    }

    override fun createCenterPanel(): JComponent {
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Jenkins URL", baseUrlField)
            .addLabeledComponent("Username", usernameField)
            .addLabeledComponent("API token", tokenField)
            .addComponent(tokenHintLabel)
            .addComponent(statusLabel)
            .addComponent(diagnosticsLabel)
            .panel
    }

    override fun createActions(): Array<Action> {
        return arrayOf(
            OpenTokenPageAction(),
            TestConnectionAction(),
            ForgetTokenAction(),
            okAction,
            cancelAction,
        )
    }

    override fun doOKAction() {
        val baseUrl = currentBaseUrl()
        val username = currentUsername()
        val token = currentToken()
        if (baseUrl.isBlank()) {
            setErrorText("Jenkins URL is required.")
            return
        }
        if (username.isBlank()) {
            setErrorText("Username is required.")
            return
        }
        if (token.isNullOrBlank() && !stateService.hasStoredToken(baseUrl, username)) {
            setErrorText("Provide an API token or keep an existing stored token for this Jenkins user.")
            return
        }
        stateService.saveConnection(baseUrl, username, token)
        onSaved?.invoke()
        super.doOKAction()
    }

    private fun currentBaseUrl(): String = MerebJenkinsJenkinsStateService.normalizeBaseUrl(baseUrlField.text)

    private fun currentUsername(): String = usernameField.text.trim()

    private fun currentToken(): String? = String(tokenField.password).trim().ifBlank { null }

    private fun refreshTokenHint() {
        val hasStored = stateService.hasStoredToken(currentBaseUrl().ifBlank { snapshot.baseUrl }, currentUsername().ifBlank { snapshot.username })
        tokenHintLabel.text = if (hasStored) {
            "Use a Jenkins API token from your Jenkins user profile. A token is already stored in IntelliJ Password Safe for this Jenkins user."
        } else {
            "Use a Jenkins API token from your Jenkins user profile. The token will be stored securely in IntelliJ Password Safe."
        }
    }

    private fun openTokenPage() {
        val baseUrl = currentBaseUrl().ifBlank { snapshot.baseUrl.ifBlank { MerebJenkinsJenkinsStateService.DEFAULT_BASE_URL } }
        val username = currentUsername()
        val targetUrl = if (username.isNotBlank()) {
            "$baseUrl/user/${encodeUrlSegment(username)}/configure"
        } else {
            "$baseUrl/me/configure/"
        }
        statusLabel.text = if (username.isNotBlank()) {
            "Opened the Jenkins profile page for $username. Create or copy an API token there."
        } else {
            "Opened Jenkins. Sign in first, then open your profile settings to create an API token."
        }
        statusLabel.revalidate()
        statusLabel.repaint()
        BrowserUtil.browse(targetUrl)
    }

    private fun performConnectionTest() {
        val baseUrl = currentBaseUrl()
        val username = currentUsername()
        val token = currentToken()
        if (baseUrl.isBlank() || username.isBlank()) {
            statusLabel.text = "Enter both the Jenkins URL and username before testing the connection."
            diagnosticsLabel.isVisible = false
            return
        }
        setErrorText(null)
        statusLabel.text = "Testing Jenkins connection…"
        diagnosticsLabel.text = ""
        diagnosticsLabel.isVisible = false
        statusLabel.revalidate()
        statusLabel.repaint()
        ApplicationManager.getApplication().executeOnPooledThread {
            val effectiveToken = token ?: stateService.resolveToken(baseUrl, username)
            val result = if (effectiveToken.isNullOrBlank()) {
                MerebJenkinsApiResult.Failure(
                    MerebJenkinsApiProblem(
                        kind = MerebJenkinsApiProblemKind.AUTH,
                        message = "No API token is available for this Jenkins user.",
                    )
                )
            } else {
                MerebJenkinsJenkinsClient(baseUrl, username, effectiveToken).validateConnection()
            }
            ApplicationManager.getApplication().invokeLater {
                statusLabel.text = when (result) {
                    is MerebJenkinsApiResult.Success -> {
                        stateService.recordConnectionStatus(MerebJenkinsConnectionStatus.CONNECTED, null, System.currentTimeMillis())
                        diagnosticsLabel.isVisible = false
                        "Connected to Jenkins as ${result.value.user.name ?: username}${result.value.controller?.nodeName?.let { " ($it)" } ?: ""}."
                    }
                    is MerebJenkinsApiResult.Failure -> {
                        val status = connectionStatusForProblem(result.problem)
                        stateService.recordConnectionStatus(
                            status,
                            result.problem.message,
                            null,
                            result.problem.requestUrl,
                            result.problem.redirectTarget,
                            result.problem.redirectRelation,
                        )
                        diagnosticsLabel.text = buildDiagnosticsHtml(baseUrl, result.problem)
                        diagnosticsLabel.isVisible = diagnosticsLabel.text.isNotBlank()
                        result.problem.message ?: "Unable to connect to Jenkins."
                    }
                }
                refreshTokenHint()
                statusLabel.revalidate()
                statusLabel.repaint()
                diagnosticsLabel.revalidate()
                diagnosticsLabel.repaint()
            }
        }
    }

    private fun forgetToken() {
        val baseUrl = currentBaseUrl()
        val username = currentUsername()
        if (baseUrl.isBlank() || username.isBlank()) {
            statusLabel.text = "Enter the Jenkins URL and username first so the correct stored token can be removed."
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            stateService.clearToken(baseUrl, username)
            ApplicationManager.getApplication().invokeLater {
                tokenField.text = ""
                refreshTokenHint()
                statusLabel.text = "Stored token cleared."
            }
        }
    }

    private fun encodeUrlSegment(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private inner class OpenTokenPageAction : DialogWrapperAction("Open Token Page") {
        override fun doAction(event: java.awt.event.ActionEvent?) {
            openTokenPage()
        }
    }

    private inner class TestConnectionAction : DialogWrapperAction("Test Connection") {
        override fun doAction(event: java.awt.event.ActionEvent?) {
            performConnectionTest()
        }
    }

    private inner class ForgetTokenAction : DialogWrapperAction("Forget Token") {
        override fun doAction(event: java.awt.event.ActionEvent?) {
            forgetToken()
        }
    }
}

class MerebJenkinsJobPickerDialog(
    project: Project?,
    targetLabel: String,
    matches: List<MerebJenkinsJobCandidateMatch>,
) : DialogWrapper(project, true) {
    private val matchList = JBList(matches)

    init {
        title = "Choose Jenkins Job"
        init()
        setOKButtonText("Use Selected Job")
        matchList.selectedIndex = 0
        matchList.cellRenderer = object : ColoredListCellRenderer<MerebJenkinsJobCandidateMatch>() {
            override fun customizeCellRenderer(
                list: javax.swing.JList<out MerebJenkinsJobCandidateMatch>,
                value: MerebJenkinsJobCandidateMatch?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value == null) return
                append(value.candidate.jobDisplayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append("  ${value.candidate.jobPath}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                append("  ${value.kind.name.lowercase().replace('_', ' ')}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        setErrorText("Select the Jenkins job that should back live data for $targetLabel.")
    }

    val selectedCandidate: MerebJenkinsJobCandidate?
        get() = matchList.selectedValue?.candidate

    override fun createCenterPanel(): JComponent {
        return JPanel(BorderLayout(0, 8)).apply {
            preferredSize = JBUI.size(640, 320)
            add(JBScrollPane(matchList), BorderLayout.CENTER)
        }
    }
}

class MerebJenkinsSettingsConfigurable : SearchableConfigurable {
    private val stateService = service<MerebJenkinsJenkinsStateService>()
    private val baseUrlValue = JBLabel()
    private val usernameValue = JBLabel()
    private val tokenValue = JBLabel()
    private val statusValue = JBLabel()
    private val validatedValue = JBLabel()
    private val panel = JPanel(BorderLayout(0, 12))

    init {
        val summary = FormBuilder.createFormBuilder()
            .addLabeledComponent("Jenkins URL", baseUrlValue)
            .addLabeledComponent("Username", usernameValue)
            .addLabeledComponent("Stored token", tokenValue)
            .addLabeledComponent("Connection status", statusValue)
            .addLabeledComponent("Last validated", validatedValue)
            .panel
        val actions = JPanel().apply {
            layout = java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0)
        }
        val manageButton = javax.swing.JButton("Manage Connection").apply {
            addActionListener {
                MerebJenkinsConnectionDialog(null) { refreshSummary() }.showAndGet()
                refreshSummary()
            }
        }
        val testButton = javax.swing.JButton("Test Saved Connection").apply {
            addActionListener { testSavedConnection() }
        }
        val forgetButton = javax.swing.JButton("Forget Token").apply {
            addActionListener {
                ApplicationManager.getApplication().executeOnPooledThread {
                    stateService.clearToken()
                    ApplicationManager.getApplication().invokeLater(::refreshSummary)
                }
            }
        }
        val openButton = javax.swing.JButton("Open Jenkins").apply {
            addActionListener {
                BrowserUtil.browse(stateService.snapshot().baseUrl)
            }
        }
        actions.add(manageButton)
        actions.add(testButton)
        actions.add(forgetButton)
        actions.add(openButton)
        panel.add(summary, BorderLayout.NORTH)
        panel.add(actions, BorderLayout.SOUTH)
        refreshSummary()
    }

    override fun getId(): String = "org.mereb.jenkins.helper.settings"

    override fun getDisplayName(): String = "Mereb Jenkins Helper"

    override fun createComponent(): JComponent = panel

    override fun isModified(): Boolean = false

    override fun apply() = Unit

    override fun reset() {
        refreshSummary()
    }

    private fun testSavedConnection() {
        val snapshot = stateService.snapshot()
        if (!snapshot.isConfigured) {
            statusValue.text = "Not configured"
            return
        }
        statusValue.text = "Testing…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val token = stateService.resolveToken(snapshot.baseUrl, snapshot.username)
            val result = if (token.isNullOrBlank()) {
                MerebJenkinsApiResult.Failure(
                    MerebJenkinsApiProblem(
                        kind = MerebJenkinsApiProblemKind.AUTH,
                        message = "No stored API token is available.",
                    )
                )
            } else {
                MerebJenkinsJenkinsClient(snapshot.baseUrl, snapshot.username, token).validateConnection()
            }
            ApplicationManager.getApplication().invokeLater {
                when (result) {
                    is MerebJenkinsApiResult.Success -> stateService.recordConnectionStatus(MerebJenkinsConnectionStatus.CONNECTED, null, System.currentTimeMillis())
                    is MerebJenkinsApiResult.Failure -> {
                        val status = connectionStatusForProblem(result.problem)
                        stateService.recordConnectionStatus(
                            status,
                            result.problem.message,
                            null,
                            result.problem.requestUrl,
                            result.problem.redirectTarget,
                            result.problem.redirectRelation,
                        )
                    }
                }
                refreshSummary()
            }
        }
    }

    private fun refreshSummary() {
        val snapshot = stateService.snapshot()
        baseUrlValue.text = snapshot.baseUrl.ifBlank { "Not configured" }
        usernameValue.text = snapshot.username.ifBlank { "Not configured" }
        tokenValue.text = if (snapshot.isConfigured && stateService.hasStoredToken(snapshot.baseUrl, snapshot.username)) "Stored in Password Safe" else "Missing"
        statusValue.text = snapshot.status.name.lowercase().replace('_', ' ')
        validatedValue.text = buildString {
            append(snapshot.lastValidatedAt?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: "Never")
            snapshot.lastRequestUrl?.let { append(" • "); append(it) }
            snapshot.lastRedirectTarget?.let {
                append(" • redirect ")
                append(snapshot.lastRedirectRelation ?: "")
                append(" -> ")
                append(it)
            }
        }
    }
}

internal object MerebJenkinsConnectionSupport {
    fun showConnectionDialog(project: Project?, onSaved: (() -> Unit)? = null) {
        MerebJenkinsConnectionDialog(project, onSaved).show()
    }
}

private fun escapeHtml(value: String): String = buildString(value.length) {
    value.forEach { character ->
        append(
            when (character) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&#39;"
                else -> character
            }
        )
    }
}

private fun connectionStatusForProblem(problem: MerebJenkinsApiProblem): MerebJenkinsConnectionStatus = when (problem.kind) {
    MerebJenkinsApiProblemKind.AUTH -> MerebJenkinsConnectionStatus.AUTH_FAILED
    MerebJenkinsApiProblemKind.LOGIN_REDIRECT_WITH_AUTH_HEADER -> MerebJenkinsConnectionStatus.REDIRECTED_TO_LOGIN
    MerebJenkinsApiProblemKind.CROSS_ORIGIN_REDIRECT -> MerebJenkinsConnectionStatus.PROXY_OR_BASE_URL_ISSUE
    MerebJenkinsApiProblemKind.UNREACHABLE, MerebJenkinsApiProblemKind.TIMEOUT -> MerebJenkinsConnectionStatus.CONTROLLER_UNREACHABLE
    else -> MerebJenkinsConnectionStatus.ERROR
}

private fun buildDiagnosticsHtml(baseUrl: String, problem: MerebJenkinsApiProblem): String {
    val details = listOfNotNull(
        "Validated base URL: ${escapeHtml(baseUrl)}",
        problem.requestUrl?.let { "Request: ${escapeHtml(it)}" },
        problem.redirectTarget?.let { "Redirect: ${escapeHtml(it)}" },
        problem.redirectRelation?.let { "Redirect type: ${escapeHtml(it)}" },
    )
    if (details.isEmpty()) return ""
    return buildString {
        append("<html><body>")
        details.forEachIndexed { index, detail ->
            if (index > 0) append("<br/>")
            append(detail)
        }
        append("</body></html>")
    }
}
