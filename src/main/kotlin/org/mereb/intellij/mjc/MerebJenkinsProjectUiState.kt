package org.mereb.intellij.mjc

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.messages.Topic

fun interface MerebJenkinsUiRequestListener {
    fun focusRequested(path: MerebJenkinsPath?, tabName: String)
}

@Service(Service.Level.PROJECT)
class MerebJenkinsProjectUiStateService {
    @Volatile
    private var requestedPathString: String? = null

    @Volatile
    private var requestedTabName: String? = null

    fun requestFocus(path: MerebJenkinsPath?, tabName: String = "Jenkins") {
        requestedPathString = path?.toString()
        requestedTabName = tabName
    }

    fun consumeRequestedPath(): MerebJenkinsPath? {
        val value = requestedPathString
        requestedPathString = null
        return value?.let(MerebJenkinsPsiUtils::parsePathString)
    }

    fun consumeRequestedTabName(): String? {
        val value = requestedTabName
        requestedTabName = null
        return value
    }

    companion object {
        val TOPIC: Topic<MerebJenkinsUiRequestListener> = Topic.create(
            "MerebJenkinsUiRequest",
            MerebJenkinsUiRequestListener::class.java
        )

        fun openToolWindow(project: Project, path: MerebJenkinsPath?, tabName: String = "Jenkins") {
            project.getService(MerebJenkinsProjectUiStateService::class.java).requestFocus(path, tabName)
            project.messageBus.syncPublisher(TOPIC).focusRequested(path, tabName)
            ToolWindowManager.getInstance(project).getToolWindow("Mereb Jenkins")?.show()
        }
    }
}
