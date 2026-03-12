package com.vitiquest.peerreview.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.vitiquest.peerreview.bitbucket.BitbucketClientRegistry
import com.vitiquest.peerreview.bitbucket.BitbucketPatRegistry
import com.vitiquest.peerreview.skills.PRPilotSkillsService
import java.awt.*
import java.io.File
import javax.swing.*
import javax.swing.border.CompoundBorder
import javax.swing.table.DefaultTableModel

/**
 * Dedicated full-size settings window for PR Review Assistant.
 *
 * Registered as the top-level parent configurable in plugin.xml so it opens in
 * its own IDE settings page instead of being squeezed into the "Tools" panel.
 *
 * Tabs:
 *   1. Git Providers   — Bitbucket per-repo PATs (table) + GitHub PAT
 *   2. AI Provider     — model, base URL, key selection
 *   3. Skills & Prompts — inline editors for system_prompt.md, review_rules.md,
 *                         coding_standards.md stored in .idea/pr-pilot/skills/
 */
class PRPilotSettingsConfigurable : Configurable {

    // ── root ──────────────────────────────────────────────────────────────────
    private var root: JComponent? = null
    private val tabs = JBTabbedPane(JTabbedPane.TOP)
    private var tabsBuilt = false

    // ── Git Providers tab ─────────────────────────────────────────────────────
    private val bbTableModel = DefaultTableModel(arrayOf("Workspace", "Repo Slug", "Access Token"), 0)
    private val bbTable      = com.intellij.ui.table.JBTable(bbTableModel)
    private val githubPatField = JBPasswordField()
    private val gitProviderCombo = ComboBox(arrayOf("Bitbucket", "GitHub"))
    private val gitProviderCards = CardLayout()
    private val gitProviderPanel = JPanel(gitProviderCards)
    private val GIT_CARD_BB     = "BB"
    private val GIT_CARD_GH     = "GH"

    // ── AI Provider tab ───────────────────────────────────────────────────────
    private val aiProviderCombo     = ComboBox(arrayOf("OpenAI", "OpenAI Compatible", "Ollama"))
    private val openAiKeyField      = JBPasswordField()
    private val openAiModelField    = JBTextField("gpt-4o")
    private val compatKeyField      = JBPasswordField()
    private val compatBaseUrlField  = JBTextField("https://api.openai.com")
    private val compatModelField    = JBTextField("gpt-4o")
    private val ollamaBaseUrlField  = JBTextField("http://localhost:11434")
    private val ollamaModelField    = JBTextField("llama3")
    private val aiCards             = CardLayout()
    private val aiPanel             = JPanel(aiCards)
    private val AI_CARD_OPENAI      = "OPENAI"
    private val AI_CARD_COMPAT      = "COMPAT"
    private val AI_CARD_OLLAMA      = "OLLAMA"

    // ── Skills tab ────────────────────────────────────────────────────────────
    private val systemPromptEditor   = buildEditor()
    private val reviewRulesEditor    = buildEditor()
    private val codingStdEditor      = buildEditor()

    // =========================================================================
    override fun getDisplayName() = "PR Review Assistant"

    override fun createComponent(): JComponent {
        if (!tabsBuilt) {
            tabs.addTab("⚙  Git Providers",    buildGitProvidersTab())
            tabs.addTab("🤖  AI Provider",     buildAiProviderTab())
            tabs.addTab("📝  Skills & Prompts", buildSkillsTab())
            tabsBuilt = true
        }

        root = tabs
        populateFields()
        return tabs
    }

    // =========================================================================
    // Tab 1 — Git Providers
    // =========================================================================

    private fun buildGitProvidersTab(): JComponent {
        val panel = JPanel(BorderLayout(0, 12))
        panel.border = JBUI.Borders.empty(16, 20)

        // Provider selector at top
        val selectorRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        selectorRow.add(JBLabel("Active provider:  ").apply {
            font = font.deriveFont(Font.BOLD)
        })
        selectorRow.add(gitProviderCombo)
        gitProviderCombo.addActionListener { switchGitCard() }

        // Bitbucket card
        gitProviderPanel.add(buildBitbucketCard(), GIT_CARD_BB)
        // GitHub card
        gitProviderPanel.add(buildGithubCard(),    GIT_CARD_GH)

        panel.add(selectorRow,      BorderLayout.NORTH)
        panel.add(gitProviderPanel, BorderLayout.CENTER)
        return panel
    }

    private fun buildBitbucketCard(): JPanel {
        val panel = JPanel(BorderLayout(0, 10))
        panel.border = JBUI.Borders.emptyTop(14)

        // ── Section header ────────────────────────────────────────────────────
        val header = JPanel(BorderLayout())
        header.add(TitledSeparator("Bitbucket Repository Access Tokens"), BorderLayout.NORTH)
        val infoLabel = JBLabel(
            "<html><small>" +
            "Each Bitbucket repository can have its own Access Token (Bitbucket → Repo settings → Access tokens).<br>" +
            "Add one row per repository.  The <b>Workspace</b> and <b>Repo Slug</b> must match the URL:<br>" +
            "<code>  https://bitbucket.org/<u>workspace</u>/<u>repo-slug</u>/pull-requests</code>" +
            "</small></html>"
        ).apply { border = JBUI.Borders.empty(6, 4, 10, 4) }
        header.add(infoLabel, BorderLayout.CENTER)

        // ── Table ─────────────────────────────────────────────────────────────
        bbTable.apply {
            rowHeight = 28
            columnModel.getColumn(0).preferredWidth = 160
            columnModel.getColumn(1).preferredWidth = 200
            columnModel.getColumn(2).preferredWidth = 320
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        }

        // Mask token column with a custom renderer
        bbTable.columnModel.getColumn(2).cellRenderer = MaskedTokenRenderer()

        val tableScroll = JBScrollPane(bbTable).apply {
            preferredSize = Dimension(Int.MAX_VALUE, 130)
            minimumSize   = Dimension(0, 100)
        }

        // ── Toolbar (above table so always visible) ────────────────────────────
        val addBtn = JButton("＋  Add Repo").apply {
            addActionListener { addBitbucketRow() }
        }
        val removeBtn = JButton("－  Remove").apply {
            addActionListener { removeBitbucketRow() }
        }
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(addBtn); add(removeBtn)
            border = JBUI.Borders.emptyBottom(4)
        }

        val center = JPanel(BorderLayout(0, 0))
        center.add(toolbar,     BorderLayout.NORTH)
        center.add(tableScroll, BorderLayout.CENTER)

        panel.add(header, BorderLayout.NORTH)
        panel.add(center, BorderLayout.CENTER)
        return panel
    }

    private fun buildGithubCard(): JPanel {
        val panel = JPanel(BorderLayout(0, 10))
        panel.border = JBUI.Borders.emptyTop(14)

        val header = JPanel(BorderLayout())
        header.add(TitledSeparator("GitHub Personal Access Token"), BorderLayout.NORTH)
        val infoLabel = JBLabel(
            "<html><small>" +
            "Go to <b>GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic)</b>.<br>" +
            "Required scopes: <code>repo</code>  (or <code>public_repo</code> for public repositories only)." +
            "</small></html>"
        ).apply { border = JBUI.Borders.empty(6, 4, 10, 4) }
        header.add(infoLabel, BorderLayout.CENTER)

        val form = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(formRow("Personal Access Token:", githubPatField))
        }

        panel.add(header, BorderLayout.NORTH)
        panel.add(form,   BorderLayout.CENTER)
        return panel
    }

    private fun addBitbucketRow() {
        val ws   = JBTextField(20)
        val repo = JBTextField(20)
        val pat  = JBPasswordField()

        val dlgPanel = JPanel(GridBagLayout()).apply {
            val gbc = GridBagConstraints().apply {
                insets = JBUI.insets(4)
                fill   = GridBagConstraints.HORIZONTAL
            }

            gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
            add(JBLabel("Workspace:"), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            add(ws, gbc)

            gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
            add(JBLabel("Repo slug:"), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            add(repo, gbc)

            gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
            add(JBLabel("Access token:"), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            add(pat, gbc)

            add(JBLabel(
                "<html><small><i>Workspace and Repo Slug must exactly match the Bitbucket URL.</i></small></html>"
            ).apply { border = JBUI.Borders.emptyTop(6) }.also {
                gbc.gridx = 0; gbc.gridy = 3
                gbc.gridwidth = 2; gbc.weightx = 1.0
            }, gbc)
        }

        val result = JOptionPane.showConfirmDialog(
            tabs, dlgPanel, "Add Bitbucket Repository Token",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        )

        if (result != JOptionPane.OK_OPTION) return

        val workspace = ws.text.trim().lowercase()
        val repoSlug  = repo.text.trim().lowercase()
        val token     = String(pat.password).trim()

        val errors = buildList {
            if (workspace.isBlank()) add("Workspace cannot be empty.")
            else if (!workspace.matches(Regex("[a-z0-9_\\-]+"))) add("Workspace contains invalid characters (use a-z, 0-9, - _).")
            if (repoSlug.isBlank()) add("Repo Slug cannot be empty.")
            else if (!repoSlug.matches(Regex("[a-z0-9_\\-.]+"))) add("Repo Slug contains invalid characters (use a-z, 0-9, - _ .).")
            if (token.isBlank()) add("Access Token cannot be empty.")
        }

        if (errors.isNotEmpty()) {
            JOptionPane.showMessageDialog(tabs, errors.joinToString("\n"), "Validation Error", JOptionPane.ERROR_MESSAGE)
            return
        }

        // Check for duplicate
        for (i in 0 until bbTableModel.rowCount) {
            if (bbTableModel.getValueAt(i, 0) == workspace &&
                bbTableModel.getValueAt(i, 1) == repoSlug) {
                JOptionPane.showMessageDialog(tabs,
                    "A token for '$workspace/$repoSlug' already exists.\nRemove the existing row first.",
                    "Duplicate Entry", JOptionPane.WARNING_MESSAGE)
                return
            }
        }

        bbTableModel.addRow(arrayOf(workspace, repoSlug, token))
    }

    private fun removeBitbucketRow() {
        val row = bbTable.selectedRow
        if (row < 0) {
            JOptionPane.showMessageDialog(tabs, "Select a row to remove.", "No Selection", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val ws   = bbTableModel.getValueAt(row, 0)
        val repo = bbTableModel.getValueAt(row, 1)
        val confirm = JOptionPane.showConfirmDialog(tabs,
            "Remove token for '$ws/$repo'?",
            "Confirm Remove", JOptionPane.YES_NO_OPTION)
        if (confirm == JOptionPane.YES_OPTION) bbTableModel.removeRow(row)
    }

    // =========================================================================
    // Tab 2 — AI Provider
    // =========================================================================

    private fun buildAiProviderTab(): JComponent {
        val panel = JPanel(BorderLayout(0, 12))
        panel.border = JBUI.Borders.empty(16, 20)

        val selectorRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        selectorRow.add(JBLabel("AI provider:  ").apply { font = font.deriveFont(Font.BOLD) })
        selectorRow.add(aiProviderCombo)
        aiProviderCombo.addActionListener { switchAiCard() }

        aiPanel.add(buildOpenAiCard(),  AI_CARD_OPENAI)
        aiPanel.add(buildCompatCard(),  AI_CARD_COMPAT)
        aiPanel.add(buildOllamaCard(),  AI_CARD_OLLAMA)

        panel.add(selectorRow, BorderLayout.NORTH)
        panel.add(aiPanel,     BorderLayout.CENTER)
        return panel
    }

    private fun buildOpenAiCard() = aiFormCard(
        TitledSeparator("OpenAI"),
        infoLabel("Calls <code>https://api.openai.com/v1/chat/completions</code> — requires an OpenAI API key."),
        formRow("API Key:", openAiKeyField),
        formRow("Model:",   openAiModelField),
        noteRow("Recommended models: gpt-4o, gpt-4o-mini, gpt-4-turbo")
    )

    private fun buildCompatCard() = aiFormCard(
        TitledSeparator("OpenAI-Compatible Endpoint"),
        infoLabel("Works with any server that implements the OpenAI Chat Completions API<br>(LM Studio, vLLM, Together AI, Groq, Mistral AI, etc.)"),
        formRow("API Key:",  compatKeyField),
        formRow("Base URL:", compatBaseUrlField),
        formRow("Model:",    compatModelField)
    )

    private fun buildOllamaCard() = aiFormCard(
        TitledSeparator("Ollama (Local)"),
        infoLabel("Runs models locally — no API key required.<br>Install from <b>https://ollama.com</b> then pull a model: <code>ollama pull llama3</code>"),
        formRow("Base URL:", ollamaBaseUrlField),
        formRow("Model:",    ollamaModelField),
        noteRow("Popular models: llama3, mistral, gemma3, phi4, codellama")
    )

    private fun aiFormCard(vararg components: JComponent): JPanel {
        val p = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyTop(14)
        }
        components.forEach { p.add(it); p.add(Box.createVerticalStrut(8)) }
        return JPanel(BorderLayout()).apply { add(p, BorderLayout.NORTH) }
    }

    // =========================================================================
    // Tab 3 — Skills & Prompts
    // =========================================================================

    private fun buildSkillsTab(): JComponent {
        val panel = JPanel(BorderLayout(0, 0))
        panel.border = JBUI.Borders.empty(16, 20)

        val header = JPanel(BorderLayout())
        header.add(TitledSeparator("AI Skill Files"), BorderLayout.NORTH)
        val infoLabel = JBLabel(
            "<html><small>" +
            "These files are stored in <b>.idea/pr-pilot/skills/</b> inside your project and can be committed to source control.<br>" +
            "Changes saved here are written directly to disk.  Reload the plugin panel after saving." +
            "</small></html>"
        ).apply { border = JBUI.Borders.empty(6, 4, 12, 4) }
        header.add(infoLabel, BorderLayout.CENTER)

        val skillTabs = JBTabbedPane(JTabbedPane.LEFT)
        skillTabs.addTab("System Prompt",     buildSkillEditor("system_prompt",   systemPromptEditor,
            "Injected as the system message before every AI request.  Define the AI's role, tone, and output format."))
        skillTabs.addTab("Review Rules",      buildSkillEditor("review_rules",    reviewRulesEditor,
            "Rules the AI applies during code review — security, performance, error handling, code quality, testing…"))
        skillTabs.addTab("Coding Standards",  buildSkillEditor("coding_standards", codingStdEditor,
            "Your team's coding standards — naming, style, docs, architecture, testing.  Edit to match your conventions."))

        panel.add(header,     BorderLayout.NORTH)
        panel.add(skillTabs,  BorderLayout.CENTER)
        return panel
    }

    private fun buildSkillEditor(skillName: String, area: JTextArea, hint: String): JPanel {
        val panel = JPanel(BorderLayout(0, 8))
        panel.border = JBUI.Borders.empty(10, 12)

        val hintLabel = JBLabel("<html><small><i>$hint</i></small></html>").apply {
            border = JBUI.Borders.emptyBottom(6)
        }

        val scroll = JBScrollPane(area).apply {
            border = CompoundBorder(
                BorderFactory.createLineBorder(com.intellij.ui.JBColor.border()),
                JBUI.Borders.empty(4)
            )
        }

        val saveBtn = JButton("💾  Save to disk").apply {
            toolTipText = "Writes the content to .idea/pr-pilot/skills/$skillName.md immediately"
            addActionListener { saveSkillToDisk(skillName, area.text) }
        }
        val resetBtn = JButton("↺  Reset to default").apply {
            toolTipText = "Restore the bundled default content for $skillName.md"
            addActionListener { resetSkillToDefault(skillName, area) }
        }
        val btnRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        btnRow.add(saveBtn); btnRow.add(resetBtn)

        panel.add(hintLabel, BorderLayout.NORTH)
        panel.add(scroll,    BorderLayout.CENTER)
        panel.add(btnRow,    BorderLayout.SOUTH)
        return panel
    }

    private fun saveSkillToDisk(skillName: String, content: String) {
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: run {
            JOptionPane.showMessageDialog(tabs, "No open project found.", "Error", JOptionPane.ERROR_MESSAGE)
            return
        }
        val skillsService = PRPilotSkillsService.getInstance(project)
        val file = File(skillsService.skillsDir, "$skillName.md")
        try {
            skillsService.skillsDir.mkdirs()
            file.writeText(content)
            JOptionPane.showMessageDialog(tabs,
                "Saved: ${file.absolutePath}",
                "Saved", JOptionPane.INFORMATION_MESSAGE)
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(tabs, "Failed to save: ${e.message}", "Error", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun resetSkillToDefault(skillName: String, area: JTextArea) {
        val confirm = JOptionPane.showConfirmDialog(tabs,
            "Reset '$skillName.md' to the bundled default?\nThis will overwrite your current edits in the editor (not on disk).",
            "Confirm Reset", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
        if (confirm != JOptionPane.YES_OPTION) return
        val default = loadBundledSkill("$skillName.md")
        if (default != null) area.text = default
        else JOptionPane.showMessageDialog(tabs, "Could not find bundled default for '$skillName'.", "Error", JOptionPane.ERROR_MESSAGE)
    }

    private fun loadBundledSkill(fileName: String): String? {
        return try {
            javaClass.getResourceAsStream("/skills/$fileName")?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Exception) { null }
    }

    private fun loadProjectSkill(skillName: String): String? {
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return null
        val skillsService = PRPilotSkillsService.getInstance(project)
        val file = File(skillsService.skillsDir, "$skillName.md")
        return if (file.exists()) file.readText() else loadBundledSkill("$skillName.md")
    }

    // =========================================================================
    // Population & switch helpers
    // =========================================================================

    /** Populate every field from the current PluginSettings state (all synchronous — XML). */
    private fun populateFields() {
        val s = PluginSettings.instance

        // Git provider
        gitProviderCombo.selectedIndex = s.gitProvider.ordinal
        switchGitCard()

        // Bitbucket table
        bbTableModel.rowCount = 0
        BitbucketPatRegistry.getInstance().allEntries().forEach { (ws, repo, pat) ->
            bbTableModel.addRow(arrayOf(ws, repo, pat))
        }

        // GitHub PAT
        githubPatField.text = s.getGitHubPat()

        // AI provider
        aiProviderCombo.selectedIndex = s.aiProvider.ordinal
        switchAiCard()
        openAiModelField.text   = s.openAiModel
        compatBaseUrlField.text = s.openAiCompatBaseUrl
        compatModelField.text   = s.openAiCompatModel
        ollamaBaseUrlField.text = s.ollamaBaseUrl
        ollamaModelField.text   = s.ollamaModel
        openAiKeyField.text     = s.getOpenAiKey()
        compatKeyField.text     = s.getOpenAiCompatKey()

        // Skills — prefer on-disk project files, fall back to bundled defaults
        systemPromptEditor.text = loadProjectSkill("system_prompt")    ?: ""
        reviewRulesEditor.text  = loadProjectSkill("review_rules")     ?: ""
        codingStdEditor.text    = loadProjectSkill("coding_standards") ?: ""
        listOf(systemPromptEditor, reviewRulesEditor, codingStdEditor).forEach { it.caretPosition = 0 }
    }

    private fun switchGitCard() {
        val card = if (gitProviderCombo.selectedIndex == 1) GIT_CARD_GH else GIT_CARD_BB
        gitProviderCards.show(gitProviderPanel, card)
    }

    private fun switchAiCard() {
        val card = when (aiProviderCombo.selectedIndex) {
            1    -> AI_CARD_COMPAT
            2    -> AI_CARD_OLLAMA
            else -> AI_CARD_OPENAI
        }
        aiCards.show(aiPanel, card)
    }

    // =========================================================================
    // Configurable lifecycle
    // =========================================================================

    override fun isModified() = true

    override fun apply() {
        applyGitProviders()
        applyAiProvider()
        // Skills are saved individually via the "Save to disk" button in each tab
    }

    private fun applyGitProviders() {
        val s = PluginSettings.instance

        s.gitProvider = if (gitProviderCombo.selectedIndex == 1) GitProvider.GITHUB else GitProvider.BITBUCKET

        // Validate Bitbucket table
        val errors = mutableListOf<String>()
        val validRows = mutableListOf<Triple<String, String, String>>()
        for (i in 0 until bbTableModel.rowCount) {
            val ws   = bbTableModel.getValueAt(i, 0).toString().trim().lowercase()
            val repo = bbTableModel.getValueAt(i, 1).toString().trim().lowercase()
            val pat  = bbTableModel.getValueAt(i, 2).toString().trim()
            when {
                ws.isBlank()   -> errors.add("Row ${i+1}: Workspace cannot be empty.")
                repo.isBlank() -> errors.add("Row ${i+1}: Repo Slug cannot be empty.")
                pat.isBlank()  -> errors.add("Row ${i+1}: Access Token cannot be empty.")
                !ws.matches(Regex("[a-z0-9_\\-]+"))    -> errors.add("Row ${i+1}: Workspace '$ws' has invalid characters.")
                !repo.matches(Regex("[a-z0-9_\\-.]+")) -> errors.add("Row ${i+1}: Repo Slug '$repo' has invalid characters.")
                else -> validRows.add(Triple(ws, repo, pat))
            }
        }
        if (errors.isNotEmpty()) {
            JOptionPane.showMessageDialog(tabs, errors.joinToString("\n"),
                "Bitbucket Token Validation Errors — not saved", JOptionPane.ERROR_MESSAGE)
            return
        }

        // Clear all existing entries then write the current table rows
        val registry = BitbucketPatRegistry.getInstance()
        registry.allRepos().toList().forEach { key ->
            val parts = key.split("/", limit = 2)
            if (parts.size == 2) registry.removeToken(parts[0], parts[1])
        }
        validRows.forEach { (ws, repo, pat) ->
            registry.setToken(ws, repo, pat)
            BitbucketClientRegistry.invalidate(ws, repo)
        }

        // GitHub PAT
        val ghPat = String(githubPatField.password).trim()
        if (ghPat.isNotBlank()) s.setGitHubPat(ghPat)
    }

    private fun applyAiProvider() {
        val s = PluginSettings.instance
        s.aiProvider          = when (aiProviderCombo.selectedIndex) {
            1    -> AiProvider.OPENAI_COMPATIBLE
            2    -> AiProvider.OLLAMA
            else -> AiProvider.OPENAI
        }
        s.openAiModel         = openAiModelField.text.trim().ifBlank { "gpt-4o" }
        s.openAiCompatBaseUrl = compatBaseUrlField.text.trim().ifBlank { "https://api.openai.com" }
        s.openAiCompatModel   = compatModelField.text.trim().ifBlank { "gpt-4o" }
        s.ollamaBaseUrl       = ollamaBaseUrlField.text.trim().ifBlank { "http://localhost:11434" }
        s.ollamaModel         = ollamaModelField.text.trim().ifBlank { "llama3" }

        val openAiKey = String(openAiKeyField.password).trim()
        val compatKey = String(compatKeyField.password).trim()
        if (openAiKey.isNotBlank()) s.setOpenAiKey(openAiKey)
        if (compatKey.isNotBlank()) s.setOpenAiCompatKey(compatKey)
    }

    override fun reset() {
        populateFields()
    }


    override fun disposeUIResources() { root = null }

    // =========================================================================
    // Layout helpers
    // =========================================================================

    private fun buildEditor() = JTextArea().apply {
        font       = Font(Font.MONOSPACED, Font.PLAIN, 13)
        lineWrap   = true
        wrapStyleWord = true
        tabSize    = 4
    }

    private fun formRow(label: String, field: JComponent): JPanel {
        val lbl = JBLabel(label).apply {
            preferredSize = Dimension(180, preferredSize.height)
            minimumSize   = preferredSize
        }
        field.maximumSize = Dimension(Int.MAX_VALUE, field.preferredSize.height + 6)
        return JPanel(BorderLayout(8, 0)).apply {
            maximumSize = Dimension(Int.MAX_VALUE, (field.preferredSize.height + 6).coerceAtLeast(30))
            add(lbl,   BorderLayout.WEST)
            add(field, BorderLayout.CENTER)
        }
    }

    private fun noteRow(text: String) =
        JBLabel("<html><small><i>$text</i></small></html>").apply {
            border = JBUI.Borders.emptyLeft(4)
        }

    private fun infoLabel(html: String) =
        JBLabel("<html><small>$html</small></html>").apply {
            border = JBUI.Borders.empty(6, 4)
        }

    // =========================================================================
    // Custom table renderer — masks token column
    // =========================================================================

    private inner class MaskedTokenRenderer : javax.swing.table.DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?,
            isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val display = if (value != null && value.toString().length > 6)
                "••••••••" + value.toString().takeLast(4)
            else "••••••••"
            return super.getTableCellRendererComponent(table, display, isSelected, hasFocus, row, column)
        }
    }
}
















