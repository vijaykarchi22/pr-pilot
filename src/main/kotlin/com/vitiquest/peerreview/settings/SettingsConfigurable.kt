package com.vitiquest.peerreview.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.*

class SettingsConfigurable : Configurable {

    private var root: JPanel? = null

    // ── Git provider selector ─────────────────────────────────────────────────
    private val gitProviderCombo = ComboBox(arrayOf("Bitbucket", "GitHub"))

    // ── Bitbucket ─────────────────────────────────────────────────────────────
    private val bitbucketPatField = JBPasswordField()

    // ── GitHub ────────────────────────────────────────────────────────────────
    private val githubPatField = JBPasswordField()

    // ── AI provider selector ──────────────────────────────────────────────────
    private val providerCombo = ComboBox(arrayOf("OpenAI", "OpenAI Compatible", "Ollama"))

    // ── OpenAI fields ─────────────────────────────────────────────────────────
    private val openAiKeyField   = JBPasswordField()
    private val openAiModelField = JBTextField().apply { text = "gpt-4o" }

    // ── OpenAI-compatible fields ──────────────────────────────────────────────
    private val compatKeyField     = JBPasswordField()
    private val compatBaseUrlField = JBTextField().apply { text = "https://api.openai.com" }
    private val compatModelField   = JBTextField().apply { text = "gpt-4o" }

    // ── Ollama fields ─────────────────────────────────────────────────────────
    private val ollamaBaseUrlField = JBTextField().apply { text = "http://localhost:11434" }
    private val ollamaModelField   = JBTextField().apply { text = "llama3" }

    // ── System prompt ─────────────────────────────────────────────────────────
    private val systemPromptArea = JBTextArea(8, 40).apply {
        lineWrap = true; wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        toolTipText = "Injected as a system prompt before every AI summary request"
    }

    // ── Card names ────────────────────────────────────────────────────────────
    private val CARD_BITBUCKET = "BITBUCKET"
    private val CARD_GITHUB    = "GITHUB"
    private val CARD_OPENAI    = "OPENAI"
    private val CARD_COMPAT    = "COMPAT"
    private val CARD_OLLAMA    = "OLLAMA"
    private val CARD_LOADING   = "LOADING"
    private val CARD_FORM      = "FORM"

    // Git-provider PAT cards
    private val gitPatCards  = CardLayout()
    private val gitPatPanel  = JPanel(gitPatCards)

    // AI-provider config cards
    private val providerCards = CardLayout()
    private val providerPanel = JPanel(providerCards)

    // Root switcher
    private val rootCards = CardLayout()
    private val rootPanel = JPanel(rootCards)

    override fun getDisplayName() = "PR Review Assistant"

    override fun createComponent(): JComponent {
        val loadingPanel = JPanel(BorderLayout()).apply {
            add(JBLabel("Loading settings…").apply {
                horizontalAlignment = SwingConstants.CENTER
            }, BorderLayout.CENTER)
        }

        // Git PAT cards
        gitPatPanel.add(buildBitbucketPatCard(), CARD_BITBUCKET)
        gitPatPanel.add(buildGitHubPatCard(),    CARD_GITHUB)
        gitProviderCombo.addActionListener { switchGitPatCard() }

        // AI provider cards
        providerPanel.add(buildOpenAiCard(),  CARD_OPENAI)
        providerPanel.add(buildCompatCard(),  CARD_COMPAT)
        providerPanel.add(buildOllamaCard(),  CARD_OLLAMA)
        providerCombo.addActionListener { switchProviderCard() }

        val form = buildForm()

        rootPanel.add(loadingPanel, CARD_LOADING)
        rootPanel.add(form,         CARD_FORM)
        rootCards.show(rootPanel, CARD_LOADING)

        root = rootPanel

        ApplicationManager.getApplication().executeOnPooledThread {
            PluginSettings.instance.warmUpSecretsCache()
            ApplicationManager.getApplication().invokeLater {
                populateFields()
                rootCards.show(rootPanel, CARD_FORM)
            }
        }

        return rootPanel
    }

    // =========================================================================
    // Form builder
    // =========================================================================

    private fun buildForm(): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8, 12, 8, 12)
        }

        // ── Git Provider ──────────────────────────────────────────────────────
        panel.add(TitledSeparator("Git Provider"))
        panel.add(Box.createVerticalStrut(4))
        panel.add(formRow("Provider:", gitProviderCombo))
        panel.add(Box.createVerticalStrut(6))
        panel.add(gitPatPanel)
        panel.add(Box.createVerticalStrut(12))

        // ── AI Provider ───────────────────────────────────────────────────────
        panel.add(TitledSeparator("AI Provider"))
        panel.add(Box.createVerticalStrut(4))
        panel.add(formRow("Provider:", providerCombo))
        panel.add(Box.createVerticalStrut(8))
        panel.add(providerPanel)
        panel.add(Box.createVerticalStrut(12))

        // ── System Prompt (legacy) ────────────────────────────────────────────
        panel.add(TitledSeparator("Review Rules / System Prompt"))
        panel.add(Box.createVerticalStrut(4))
        panel.add(JBLabel(
            "<html><small>Injected as a system message before every AI summary request.<br>" +
            "Tip: edit <b>.idea/pr-pilot/skills/system_prompt.md</b> for per-project control.</small></html>"
        ).apply { border = JBUI.Borders.emptyBottom(6) })
        panel.add(JBScrollPane(systemPromptArea).apply {
            preferredSize = Dimension(Int.MAX_VALUE, 160)
            maximumSize   = Dimension(Int.MAX_VALUE, 160)
            border = BorderFactory.createLineBorder(
                JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()
            )
        })

        return JPanel(BorderLayout()).apply { add(panel, BorderLayout.NORTH) }
    }

    private fun buildBitbucketPatCard() = formCard(
        formRow("Access Token:", bitbucketPatField),
        noteRow("Generate at: Bitbucket → Repository/Workspace settings → Access tokens")
    )

    private fun buildGitHubPatCard() = formCard(
        formRow("Personal Access Token:", githubPatField),
        noteRow("Generate at: GitHub → Settings → Developer settings → Personal access tokens")
    )

    private fun buildOpenAiCard() = formCard(
        formRow("API Key:", openAiKeyField),
        formRow("Model:",   openAiModelField),
        noteRow("Uses https://api.openai.com/v1/chat/completions")
    )

    private fun buildCompatCard() = formCard(
        formRow("API Key:",  compatKeyField),
        formRow("Base URL:", compatBaseUrlField),
        formRow("Model:",    compatModelField),
        noteRow("Any OpenAI-compatible endpoint (vLLM, LM Studio, Together AI, etc.)")
    )

    private fun buildOllamaCard() = formCard(
        formRow("Base URL:", ollamaBaseUrlField),
        formRow("Model:",    ollamaModelField),
        noteRow("Ollama runs locally — no API key required")
    )

    // ── Layout helpers ────────────────────────────────────────────────────────

    private fun formCard(vararg rows: JComponent): JPanel {
        val p = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4, 0, 4, 0)
        }
        rows.forEach { p.add(it); p.add(Box.createVerticalStrut(6)) }
        return p
    }

    private fun formRow(label: String, field: JComponent): JPanel {
        val lbl = JBLabel(label).apply {
            preferredSize = Dimension(180, preferredSize.height)
            minimumSize   = preferredSize
        }
        field.maximumSize = Dimension(Int.MAX_VALUE, field.preferredSize.height + 4)
        return JPanel(BorderLayout(8, 0)).apply {
            maximumSize = Dimension(Int.MAX_VALUE, (field.preferredSize.height + 4).coerceAtLeast(28))
            add(lbl,   BorderLayout.WEST)
            add(field, BorderLayout.CENTER)
        }
    }

    private fun noteRow(text: String) =
        JBLabel("<html><small><i>$text</i></small></html>").apply {
            border = JBUI.Borders.empty(0, 188, 0, 0)
        }

    private fun switchGitPatCard() {
        val card = if (gitProviderCombo.selectedIndex == 1) CARD_GITHUB else CARD_BITBUCKET
        gitPatCards.show(gitPatPanel, card)
    }

    private fun switchProviderCard() {
        val card = when (providerCombo.selectedIndex) {
            1    -> CARD_COMPAT
            2    -> CARD_OLLAMA
            else -> CARD_OPENAI
        }
        providerCards.show(providerPanel, card)
    }

    // =========================================================================
    // Configurable lifecycle
    // =========================================================================

    private fun populateFields() {
        val s = PluginSettings.instance

        // Git provider
        gitProviderCombo.selectedIndex = s.gitProvider.ordinal
        switchGitPatCard()

        // Secrets
        bitbucketPatField.text = s.getBitbucketPat()
        githubPatField.text    = s.getGitHubPat()
        openAiKeyField.text    = s.getOpenAiKey()
        compatKeyField.text    = s.getOpenAiCompatKey()

        // Non-secrets
        providerCombo.selectedIndex = s.aiProvider.ordinal
        switchProviderCard()
        openAiModelField.text   = s.openAiModel
        compatBaseUrlField.text = s.openAiCompatBaseUrl
        compatModelField.text   = s.openAiCompatModel
        ollamaBaseUrlField.text = s.ollamaBaseUrl
        ollamaModelField.text   = s.ollamaModel
        systemPromptArea.text   = s.systemPrompt
    }

    override fun isModified(): Boolean {
        val s = PluginSettings.instance
        return gitProviderCombo.selectedIndex              != s.gitProvider.ordinal
            || String(bitbucketPatField.password)          != s.getBitbucketPat()
            || String(githubPatField.password)             != s.getGitHubPat()
            || providerCombo.selectedIndex                 != s.aiProvider.ordinal
            || String(openAiKeyField.password)             != s.getOpenAiKey()
            || openAiModelField.text                       != s.openAiModel
            || String(compatKeyField.password)             != s.getOpenAiCompatKey()
            || compatBaseUrlField.text                     != s.openAiCompatBaseUrl
            || compatModelField.text                       != s.openAiCompatModel
            || ollamaBaseUrlField.text                     != s.ollamaBaseUrl
            || ollamaModelField.text                       != s.ollamaModel
            || systemPromptArea.text                       != s.systemPrompt
    }

    override fun apply() {
        val s = PluginSettings.instance

        val gitProvider  = if (gitProviderCombo.selectedIndex == 1) GitProvider.GITHUB else GitProvider.BITBUCKET
        val bbPat        = String(bitbucketPatField.password)
        val ghPat        = String(githubPatField.password)
        val openAiKey    = String(openAiKeyField.password)
        val compatKey    = String(compatKeyField.password)
        val aiProvider   = when (providerCombo.selectedIndex) {
            1    -> AiProvider.OPENAI_COMPATIBLE
            2    -> AiProvider.OLLAMA
            else -> AiProvider.OPENAI
        }
        val openAiModel    = openAiModelField.text.trim().ifBlank { "gpt-4o" }
        val compatBaseUrl  = compatBaseUrlField.text.trim().ifBlank { "https://api.openai.com" }
        val compatModel    = compatModelField.text.trim().ifBlank { "gpt-4o" }
        val ollamaBaseUrl  = ollamaBaseUrlField.text.trim().ifBlank { "http://localhost:11434" }
        val ollamaModel    = ollamaModelField.text.trim().ifBlank { "llama3" }
        val systemPrompt   = systemPromptArea.text

        // Non-secret writes are safe on EDT
        s.gitProvider         = gitProvider
        s.aiProvider          = aiProvider
        s.openAiModel         = openAiModel
        s.openAiCompatBaseUrl = compatBaseUrl
        s.openAiCompatModel   = compatModel
        s.ollamaBaseUrl       = ollamaBaseUrl
        s.ollamaModel         = ollamaModel
        s.systemPrompt        = systemPrompt

        // Secrets must be written on a background thread
        ApplicationManager.getApplication().executeOnPooledThread {
            if (ghPat.isNotBlank())     s.setGitHubPat(ghPat)
            if (openAiKey.isNotBlank()) s.setOpenAiKey(openAiKey)
            if (compatKey.isNotBlank()) s.setOpenAiCompatKey(compatKey)
        }
    }

    override fun reset() { populateFields() }

    override fun disposeUIResources() { root = null }
}
