package org.mereb.intellij.mjc

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea

class MerebJenkinsPreviewDialog(
    private val plan: MerebJenkinsMigrationPlan,
) : DialogWrapper(true) {
    init {
        title = "Preview Mereb Jenkins Migration"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val tabs = JBTabbedPane()
        plan.changes.forEach { change ->
            val beforeArea = JTextArea(change.beforeText).apply {
                isEditable = false
                lineWrap = false
            }
            val afterArea = JTextArea(change.afterText).apply {
                isEditable = false
                lineWrap = false
            }
            val panel = JPanel(BorderLayout(12, 12))
            panel.add(JScrollPane(beforeArea), BorderLayout.WEST)
            panel.add(JScrollPane(afterArea), BorderLayout.CENTER)
            panel.preferredSize = Dimension(980, 520)
            tabs.addTab(change.title, panel)
        }
        return tabs
    }
}

