package com.vitiquest.peerreview.bitbucket

import com.vitiquest.peerreview.settings.PluginSettings

/**
 * Thin facade over [PluginSettings] for per-repo Bitbucket PAT access.
 *
 * PATs are stored in IntelliJ's PasswordSafe (one entry per repo, keyed by
 * "PRReviewAssistant.Bitbucket.workspace/repoSlug") so they survive IDE restarts
 * and sandbox reloads reliably.
 *
 * All workspace/repoSlug keys are normalised to lowercase so that a token
 * saved in Settings always matches the key derived from the git remote URL.
 */
class BitbucketPatRegistry {

    companion object {
        private val INSTANCE = BitbucketPatRegistry()
        fun getInstance(): BitbucketPatRegistry = INSTANCE
    }

    fun getToken(workspace: String, repoSlug: String): String? =
        PluginSettings.instance.getBitbucketToken(workspace.lowercase(), repoSlug.lowercase())

    fun setToken(workspace: String, repoSlug: String, pat: String) =
        PluginSettings.instance.setBitbucketToken(workspace.lowercase(), repoSlug.lowercase(), pat.trim())

    fun removeToken(workspace: String, repoSlug: String) =
        PluginSettings.instance.removeBitbucketToken(workspace.lowercase(), repoSlug.lowercase())

    fun allRepos(): Set<String> =
        PluginSettings.instance.allBitbucketRepoKeys()

    fun allEntries(): List<Triple<String, String, String>> =
        PluginSettings.instance.allBitbucketTokens()
}



