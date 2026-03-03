package com.vitiquest.peerreview.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

enum class AiProvider { OPENAI, OPENAI_COMPATIBLE, OLLAMA }

/**
 * Non-secret settings (model name, base URL) are stored via PersistentStateComponent.
 * Secrets (PAT, OpenAI key) are stored via PasswordSafe.
 */
@State(
    name = "PRReviewAssistantSettings",
    storages = [Storage("PRReviewAssistant.xml")]
)
class PluginSettings : PersistentStateComponent<PluginSettings.State> {

    companion object {
        val instance: PluginSettings
            get() = ApplicationManager.getApplication().getService(PluginSettings::class.java)

        private const val SERVICE_BITBUCKET = "PRReviewAssistant.Bitbucket"
        private const val SERVICE_OPENAI    = "PRReviewAssistant.OpenAI"
        private const val USERNAME          = "token"
    }

    data class State(
        // AI provider selection
        var aiProvider: String = AiProvider.OPENAI.name,

        // OpenAI (official)
        var openAiModel: String = "gpt-4o",

        // OpenAI-compatible (custom endpoint)
        var openAiCompatBaseUrl: String = "https://api.openai.com",
        var openAiCompatModel: String   = "gpt-4o",

        // Ollama (local, no key needed)
        var ollamaBaseUrl: String = "http://localhost:11434",
        var ollamaModel: String   = "llama3",

        // Review rules / system prompt
        var systemPrompt: String = ""
    )

    private var myState = State()

    // ── In-memory secret cache (populated once on background thread) ──────────
    // This avoids calling PasswordSafe on the EDT, which throws SlowOperations.
    @Volatile private var cachedBitbucketPat: String? = null
    @Volatile private var cachedOpenAiKey: String?    = null

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    // ── Provider ──────────────────────────────────────────────────────────────
    var aiProvider: AiProvider
        get() = runCatching { AiProvider.valueOf(myState.aiProvider) }.getOrElse { AiProvider.OPENAI }
        set(v) { myState.aiProvider = v.name }

    // ── Non-secret settings ───────────────────────────────────────────────────
    var openAiModel: String         get() = myState.openAiModel;         set(v) { myState.openAiModel = v }
    var openAiCompatBaseUrl: String get() = myState.openAiCompatBaseUrl; set(v) { myState.openAiCompatBaseUrl = v }
    var openAiCompatModel: String   get() = myState.openAiCompatModel;   set(v) { myState.openAiCompatModel = v }
    var ollamaBaseUrl: String       get() = myState.ollamaBaseUrl;       set(v) { myState.ollamaBaseUrl = v }
    var ollamaModel: String         get() = myState.ollamaModel;         set(v) { myState.ollamaModel = v }
    var systemPrompt: String        get() = myState.systemPrompt;        set(v) { myState.systemPrompt = v }

    // ── Secrets — always read/write on a background thread ───────────────────

    /**
     * MUST be called on a background (pooled) thread.
     * Populates the in-memory cache so EDT code can safely read [getBitbucketPat]/[getOpenAiKey].
     */
    fun warmUpSecretsCache() {
        cachedBitbucketPat = readSecret(SERVICE_BITBUCKET)
        cachedOpenAiKey    = readSecret(SERVICE_OPENAI)
    }

    /** Safe to call from any thread — returns cached value if already warmed up. */
    fun getBitbucketPat(): String = cachedBitbucketPat ?: ""

    fun setBitbucketPat(pat: String) {
        cachedBitbucketPat = pat
        writeSecret(SERVICE_BITBUCKET, pat)
    }

    fun getOpenAiKey(): String = cachedOpenAiKey ?: ""

    fun setOpenAiKey(key: String) {
        cachedOpenAiKey = key
        writeSecret(SERVICE_OPENAI, key)
    }

    fun getOpenAiCompatKey(): String = getOpenAiKey()
    fun setOpenAiCompatKey(key: String) = setOpenAiKey(key)

    // ── Private PasswordSafe I/O (must be on background thread) ──────────────

    private fun readSecret(service: String): String =
        PasswordSafe.instance.getPassword(CredentialAttributes(service, USERNAME)) ?: ""

    private fun writeSecret(service: String, value: String) {
        PasswordSafe.instance.set(
            CredentialAttributes(service, USERNAME),
            Credentials(USERNAME, value)
        )
    }
}
