package com.vitiquest.peerreview.bitbucket

/**
 * Resolves the correct [BitbucketClient] for a given workspace + repoSlug pair.
 *
 * Clients are cached in memory and are invalidated whenever the underlying PAT
 * changes (e.g. after saving settings).  Calling code should always go through
 * this registry instead of constructing [BitbucketClient] directly so that
 * multi-repo projects each get their own token.
 */
object BitbucketClientRegistry {

    private val cache = mutableMapOf<String, Pair<String, BitbucketClient>>()
    // key → (pat used to create client, client)

    /**
     * Returns the [BitbucketClient] for the given repo, building (or rebuilding)
     * it if the PAT stored in [BitbucketPatRegistry] has changed.
     *
     * @throws IllegalStateException when no PAT has been configured for this repo.
     */
    fun getClient(workspace: String, repoSlug: String): BitbucketClient {
        val key = "${workspace.lowercase()}/${repoSlug.lowercase()}"
        val registry = BitbucketPatRegistry.getInstance()

        val pat = registry.getToken(workspace, repoSlug)

        pat ?: throw IllegalStateException(
            "No Access Token configured for Bitbucket repo '$key'.\n" +
            "Go to  Settings → PR Review Assistant → Git Providers  and add a token for this repo.\n" +
            "(Workspace: '${workspace.lowercase()}', Repo Slug: '${repoSlug.lowercase()}')"
        )

        val cached = cache[key]
        if (cached != null && cached.first == pat) return cached.second

        val client = BitbucketClient(pat)
        cache[key] = pat to client
        return client
    }

    /** Drops the cached client for a repo so the next call rebuilds it. */
    fun invalidate(workspace: String, repoSlug: String) {
        cache.remove("$workspace/$repoSlug")
    }

    /** Drops all cached clients. */
    fun invalidateAll() = cache.clear()
}


