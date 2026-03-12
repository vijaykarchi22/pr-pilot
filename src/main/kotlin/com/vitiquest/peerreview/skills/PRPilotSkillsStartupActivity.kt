package com.vitiquest.peerreview.skills

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Runs once when a project is opened.  Seeds the default skill files under
 * `.idea/pr-pilot/skills/` if they do not already exist.
 *
 * Registered as a <postStartupActivity> in plugin.xml.
 */
class PRPilotSkillsStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        // Run on a background thread — file I/O should never block the EDT
        ApplicationManager.getApplication().executeOnPooledThread {
            PRPilotSkillsService.getInstance(project).ensureSkillsExist()
        }
    }
}

