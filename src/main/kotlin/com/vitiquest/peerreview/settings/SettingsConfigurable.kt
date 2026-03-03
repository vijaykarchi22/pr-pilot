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

    // ── Bitbucket ─────────────────────────────────────────────────────────────
    private val bitbucketPatField = JBPasswordField()

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
    private val CARD_OPENAI   = "OPENAI"
    private val CARD_COMPAT   = "COMPAT"
    private val CARD_OLLAMA   = "OLLAMA"
    private val CARD_LOADING  = "LOADING"
    private val CARD_FORM     = "FORM"

    private val providerCards  = CardLayout()
    private val providerPanel  = JPanel(providerCards)

    // Root switcher: shows "Loading…" until secrets are loaded from background thread
    private val rootCards  = CardLayout()
    private val rootPanel  = JPanel(rootCards)

    override fun getDisplayName() = "PR Review Assistant"

    override fun createComponent(): JComponent {
        // ── Loading placeholder card ──────────────────────────────────────────
        val loadingPanel = JPanel(BorderLayout()).apply {
            add(JBLabel("Loading settings…").apply {
                horizontalAlignment = SwingConstants.CENTER
            }, BorderLayout.CENTER)
        }

        // ── Build the real form ───────────────────────────────────────────────
        providerPanel.add(buildOpenAiCard(),  CARD_OPENAI)
        providerPanel.add(buildCompatCard(),  CARD_COMPAT)
        providerPanel.add(buildOllamaCard(),  CARD_OLLAMA)
        providerCombo.addActionListener { switchProviderCard() }

        val form = buildForm()

        rootPanel.add(loadingPanel, CARD_LOADING)
        rootPanel.add(form,         CARD_FORM)
        rootCards.show(rootPanel, CARD_LOADING)

        root = rootPanel

        // ── Warm up the PasswordSafe cache on a pooled thread, then populate ──
        ApplicationManager.getApplication().executeOnPooledThread {
            PluginSettings.instance.warmUpSecretsCache()          // slow I/O — off EDT
            ApplicationManager.getApplication().invokeLater {
                populateFields()                                   // fast — just sets field text
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
        panel.add(TitledSeparator("Bitbucket Cloud"))
        panel.add(Box.createVerticalStrut(4))
        panel.add(formRow("Personal Access Token:", bitbucketPatField))
        panel.add(Box.createVerticalStrut(12))

        panel.add(TitledSeparator("AI Provider"))
        panel.add(Box.createVerticalStrut(4))
        panel.add(formRow("Provider:", providerCombo))
        panel.add(Box.createVerticalStrut(8))
        panel.add(providerPanel)
        panel.add(Box.createVerticalStrut(12))

        panel.add(TitledSeparator("Review Rules / System Prompt"))
        panel.add(Box.createVerticalStrut(4))
        panel.add(JBLabel(
            "<html><small>Injected as a system message before every AI summary request.<br>" +
            "Example: <i>\"Focus on security issues and follow our naming conventions.\"</i></small></html>"
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
            preferredSize = Dimension(160, preferredSize.height)
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
            border = JBUI.Borders.empty(0, 168, 0, 0)
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
    // Configurable lifecycle  —  NO PasswordSafe calls on EDT
    // =========================================================================

    /**
     * Populate all UI fields from the already-cached values.
     * Must be called on EDT after [PluginSettings.warmUpSecretsCache] has finished.
     */
    private fun populateFields() {
        val s = PluginSettings.instance

        // secrets — safe because cache is already warm
        bitbucketPatField.text = s.getBitbucketPat()
        openAiKeyField.text    = s.getOpenAiKey()
        compatKeyField.text    = s.getOpenAiCompatKey()

        // non-secrets
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
        // Secrets compared against cache — no keychain I/O
        return String(bitbucketPatField.password) != s.getBitbucketPat()
            || providerCombo.selectedIndex         != s.aiProvider.ordinal
            || String(openAiKeyField.password)     != s.getOpenAiKey()
            || openAiModelField.text               != s.openAiModel
            || String(compatKeyField.password)     != s.getOpenAiCompatKey()
            || compatBaseUrlField.text             != s.openAiCompatBaseUrl
            || compatModelField.text               != s.openAiCompatModel
            || ollamaBaseUrlField.text             != s.ollamaBaseUrl
            || ollamaModelField.text               != s.ollamaModel
            || systemPromptArea.text               != s.systemPrompt
    }

    override fun apply() {
        val s = PluginSettings.instance

        // Secrets — setBitbucketPat/setOpenAiKey update the cache AND
        // write to PasswordSafe. Writing is fine here because apply() is
        // triggered by the user clicking OK/Apply, which is allowed to be slow.
        val pat = String(bitbucketPatField.password)
        if (pat.isNotBlank()) s.setBitbucketPat(pat)

        s.aiProvider = when (providerCombo.selectedIndex) {
            1    -> AiProvider.OPENAI_COMPATIBLE
            2    -> AiProvider.OLLAMA
            else -> AiProvider.OPENAI
        }

        val openAiKey = String(openAiKeyField.password)
        if (openAiKey.isNotBlank()) s.setOpenAiKey(openAiKey)
        s.openAiModel = openAiModelField.text.trim().ifBlank { "gpt-4o" }

        val compatKey = String(compatKeyField.password)
        if (compatKey.isNotBlank()) s.setOpenAiCompatKey(compatKey)
        s.openAiCompatBaseUrl = compatBaseUrlField.text.trim().ifBlank { "https://api.openai.com" }
        s.openAiCompatModel   = compatModelField.text.trim().ifBlank { "gpt-4o" }

        s.ollamaBaseUrl = ollamaBaseUrlField.text.trim().ifBlank { "http://localhost:11434" }
        s.ollamaModel   = ollamaModelField.text.trim().ifBlank { "llama3" }
        s.systemPrompt  = systemPromptArea.text
    }

    override fun reset() {
        // Only called when user clicks "Reset" inside an already-open settings dialog.
        // Cache is guaranteed warm at this point (createComponent already ran).
        populateFields()
    }

    override fun disposeUIResources() { root = null }
}
