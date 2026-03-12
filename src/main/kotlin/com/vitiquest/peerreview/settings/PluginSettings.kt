package com.vitiquest.peerreview.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection

enum class AiProvider { OPENAI, OPENAI_COMPATIBLE, OLLAMA }
enum class GitProvider { BITBUCKET, GITHUB }

/**
 * All plugin settings stored via PersistentStateComponent in PRReviewAssistant.xml.
 *
 * RoamingType.DISABLED prevents cloud sync and ensures the file is written
 * to the local config directory.  saveSettings() is called after every write
 * so data is flushed to disk immediately (not just on clean IDE shutdown).
 *
 * PATs are stored in plain XML — this is intentional for a dev-sandbox plugin
 * where PasswordSafe does not reliably persist between restarts.
 */
@State(
    name = "PRReviewAssistantSettings",
    storages = [Storage(value = "PRReviewAssistant.xml", roamingType = RoamingType.DISABLED)]
)
class PluginSettings : PersistentStateComponent<PluginSettings.State> {

    companion object {
        val instance: PluginSettings
            get() = ApplicationManager.getApplication().getService(PluginSettings::class.java)
    }

    // ── State class ───────────────────────────────────────────────────────────
    // Must be a plain class (not data class) with a no-arg constructor and
    // mutable var fields for IntelliJ's XmlSerializer to work correctly.

    @Tag("PluginState")
    class State {
        var gitProvider: String         = GitProvider.BITBUCKET.name
        var aiProvider: String          = AiProvider.OPENAI.name
        var openAiModel: String         = "gpt-4o"
        var openAiCompatBaseUrl: String = "https://api.openai.com"
        var openAiCompatModel: String   = "gpt-4o"
        var ollamaBaseUrl: String       = "http://localhost:11434"
        var ollamaModel: String         = "llama3"
        var systemPrompt: String        = ""
        var gitHubPat: String           = ""
        var openAiKey: String           = ""

        // Bitbucket per-repo tokens stored as a list of Entry objects
        // so the XmlSerializer handles them without a Map (maps with
        // non-trivial keys are unreliable in IntelliJ's serializer).
        @XCollection(style = XCollection.Style.v2)
        var bitbucketEntries: MutableList<BitbucketEntry> = mutableListOf()
    }

    @Tag("repo")
    class BitbucketEntry {
        var workspace: String = ""
        var repoSlug: String  = ""
        var pat: String       = ""

        // No-arg constructor required by XmlSerializer
        constructor()
        constructor(workspace: String, repoSlug: String, pat: String) {
            this.workspace = workspace
            this.repoSlug  = repoSlug
            this.pat       = pat
        }
    }

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    // ── Git provider ──────────────────────────────────────────────────────────
    var gitProvider: GitProvider
        get() = runCatching { GitProvider.valueOf(myState.gitProvider) }.getOrElse { GitProvider.BITBUCKET }
        set(v) { myState.gitProvider = v.name; save() }

    // ── AI provider ───────────────────────────────────────────────────────────
    var aiProvider: AiProvider
        get() = runCatching { AiProvider.valueOf(myState.aiProvider) }.getOrElse { AiProvider.OPENAI }
        set(v) { myState.aiProvider = v.name; save() }

    // ── Non-secret settings ───────────────────────────────────────────────────
    var openAiModel: String
        get() = myState.openAiModel
        set(v) { myState.openAiModel = v; save() }

    var openAiCompatBaseUrl: String
        get() = myState.openAiCompatBaseUrl
        set(v) { myState.openAiCompatBaseUrl = v; save() }

    var openAiCompatModel: String
        get() = myState.openAiCompatModel
        set(v) { myState.openAiCompatModel = v; save() }

    var ollamaBaseUrl: String
        get() = myState.ollamaBaseUrl
        set(v) { myState.ollamaBaseUrl = v; save() }

    var ollamaModel: String
        get() = myState.ollamaModel
        set(v) { myState.ollamaModel = v; save() }

    var systemPrompt: String
        get() = myState.systemPrompt
        set(v) { myState.systemPrompt = v; save() }

    // ── GitHub PAT ────────────────────────────────────────────────────────────
    fun getGitHubPat(): String = myState.gitHubPat
    fun setGitHubPat(pat: String) { myState.gitHubPat = pat.trim(); save() }

    // ── OpenAI key ────────────────────────────────────────────────────────────
    fun getOpenAiKey(): String = myState.openAiKey
    fun setOpenAiKey(key: String) { myState.openAiKey = key.trim(); save() }
    fun getOpenAiCompatKey(): String = getOpenAiKey()
    fun setOpenAiCompatKey(key: String) = setOpenAiKey(key)

    // ── Bitbucket per-repo PATs ───────────────────────────────────────────────

    fun getBitbucketToken(workspace: String, repoSlug: String): String? {
        val ws = workspace.trim().lowercase()
        val rs = repoSlug.trim().lowercase()
        return myState.bitbucketEntries
            .firstOrNull { it.workspace == ws && it.repoSlug == rs }
            ?.pat?.takeIf { it.isNotBlank() }
    }

    fun setBitbucketToken(workspace: String, repoSlug: String, pat: String) {
        val ws = workspace.trim().lowercase()
        val rs = repoSlug.trim().lowercase()
        val existing = myState.bitbucketEntries.firstOrNull { it.workspace == ws && it.repoSlug == rs }
        if (existing != null) {
            existing.pat = pat.trim()
        } else {
            myState.bitbucketEntries.add(BitbucketEntry(ws, rs, pat.trim()))
        }
        save()
    }

    fun removeBitbucketToken(workspace: String, repoSlug: String) {
        val ws = workspace.trim().lowercase()
        val rs = repoSlug.trim().lowercase()
        myState.bitbucketEntries.removeIf { it.workspace == ws && it.repoSlug == rs }
        save()
    }

    fun allBitbucketTokens(): List<Triple<String, String, String>> =
        myState.bitbucketEntries
            .filter { it.workspace.isNotBlank() && it.repoSlug.isNotBlank() && it.pat.isNotBlank() }
            .map { Triple(it.workspace, it.repoSlug, it.pat) }
            .sortedWith(compareBy({ it.first }, { it.second }))

    fun allBitbucketRepoKeys(): Set<String> =
        myState.bitbucketEntries
            .filter { it.workspace.isNotBlank() && it.repoSlug.isNotBlank() }
            .map { "${it.workspace}/${it.repoSlug}" }
            .toSet()

    // ── Compat stubs ──────────────────────────────────────────────────────────
    fun warmUpSecretsCache() = Unit   // no-op — everything is in XML now
    fun getBitbucketPat(): String = ""

    // ── Force immediate flush to disk ─────────────────────────────────────────
    private fun save() {
        ApplicationManager.getApplication().saveSettings()
    }
}
