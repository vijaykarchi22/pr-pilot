package com.vitiquest.peerreview.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class PRToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = PRToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        // Register panel as a child disposable so dispose() fires when the tool window is removed
        Disposer.register(toolWindow.disposable, panel)
        toolWindow.contentManager.addContent(content)
    }
}

