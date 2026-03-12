package com.vitiquest.peerreview.skills

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Project-level service that manages the PR Pilot skill files stored under
 * `.idea/pr-pilot/skills/` in the project root.
 *
 * On first use (or whenever the files are missing) it seeds the folder with
 * the three default skill files bundled inside the plugin JAR:
 *   - system_prompt.md
 *   - review_rules.md
 *   - coding_standards.md
 *
 * Teams can then commit these files to source control and customise them
 * per-project without touching any plugin settings.
 */
class PRPilotSkillsService(private val project: Project) {

    private val log = logger<PRPilotSkillsService>()

    /** The directory where skill files live: <projectRoot>/.idea/pr-pilot/skills/ */
    val skillsDir: File
        get() = File(project.basePath ?: "", ".idea/pr-pilot/skills")

    // ── Default skill file names (must match resources/skills/*.md) ───────────
    companion object {
        val DEFAULT_SKILL_FILES = listOf(
            "system_prompt.md",
            "review_rules.md",
            "coding_standards.md"
        )

        fun getInstance(project: Project): PRPilotSkillsService =
            project.getService(PRPilotSkillsService::class.java)
    }

    // ── Initialisation ────────────────────────────────────────────────────────

    /**
     * Creates the skills directory and seeds any missing default files from
     * the plugin's bundled resources.  Safe to call multiple times — existing
     * files are never overwritten.
     */
    fun ensureSkillsExist() {
        skillsDir.mkdirs()
        DEFAULT_SKILL_FILES.forEach { fileName ->
            val target = File(skillsDir, fileName)
            if (!target.exists()) {
                seedFromResource(fileName, target)
            }
        }
    }

    // ── Reading ───────────────────────────────────────────────────────────────

    /**
     * Returns all `.md` files in the skills directory, sorted by name.
     * Calls [ensureSkillsExist] first so the folder is always populated.
     */
    fun getSkillFiles(): List<File> {
        ensureSkillsExist()
        return skillsDir
            .listFiles { f -> f.isFile && f.extension == "md" }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /**
     * Reads every skill file and returns a map of
     * `fileNameWithoutExtension → content`.
     */
    fun readAllSkills(): Map<String, String> =
        getSkillFiles().associate { it.nameWithoutExtension to it.readText().trim() }

    /**
     * Reads a single skill file by name (without extension).
     * Returns an empty string if the file does not exist.
     */
    fun readSkill(nameWithoutExtension: String): String {
        val file = File(skillsDir, "$nameWithoutExtension.md")
        return if (file.exists()) file.readText().trim() else ""
    }

    /**
     * Builds a consolidated prompt block from all skill files, ready to be
     * prepended to an AI prompt.
     */
    fun buildSkillsBlock(): String {
        val skills = readAllSkills()
        if (skills.isEmpty()) return ""
        return buildString {
            skills.forEach { (name, content) ->
                if (content.isNotBlank()) {
                    // The file itself already starts with a "# heading" — just append it
                    append(content)
                    append("\n\n---\n\n")
                }
            }
        }.trimEnd('-', '\n', ' ')
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Copies a bundled resource file from the plugin JAR to [target]. */
    private fun seedFromResource(resourceName: String, target: File) {
        val resourcePath = "/skills/$resourceName"
        try {
            val stream = javaClass.getResourceAsStream(resourcePath)
            if (stream == null) {
                log.warn("PR Pilot: bundled resource not found: $resourcePath")
                return
            }
            stream.use { target.writeBytes(it.readBytes()) }
            log.info("PR Pilot: seeded skill file → ${target.absolutePath}")
        } catch (e: Exception) {
            log.error("PR Pilot: failed to seed $resourceName", e)
        }
    }
}



