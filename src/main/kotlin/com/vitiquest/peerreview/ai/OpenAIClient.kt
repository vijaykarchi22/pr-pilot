package com.vitiquest.peerreview.ai

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.openapi.project.Project
import com.vitiquest.peerreview.settings.AiProvider
import com.vitiquest.peerreview.settings.PluginSettings
import com.vitiquest.peerreview.skills.PRPilotSkillsService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenAIClient(private val project: Project? = null) {

    /**
     * Lightweight bag of PR metadata injected into the system prompt so the AI
     * always knows which PR it is reviewing, who authored it, and which branches
     * are involved — without duplicating this in every user prompt.
     */
    data class PrContext(
        val id: Int,
        val title: String,
        val author: String,
        val sourceBranch: String,
        val destinationBranch: String,
        val fileCount: Int
    ) {
        fun toSystemMessage(): String = """
            ## Pull Request Context
            - **PR ID:** #$id
            - **Title:** $title
            - **Author:** $author
            - **Source branch:** `$sourceBranch`
            - **Target branch:** `$destinationBranch`
            - **Files changed:** $fileCount
        """.trimIndent()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)   // LLMs can be slow to stream a full response
        .callTimeout(360, TimeUnit.SECONDS)
        .build()
    private val mapper = jacksonObjectMapper()

    fun generateSummary(userPrompt: String, prContext: PrContext? = null): String {
        val s = PluginSettings.instance
        return when (s.aiProvider) {
            AiProvider.OPENAI            -> callOpenAi(userPrompt, s, prContext)
            AiProvider.OPENAI_COMPATIBLE -> callOpenAiCompatible(userPrompt, s, prContext)
            AiProvider.OLLAMA            -> callOllama(userPrompt, s, prContext)
        }
    }

    // ── OpenAI official ───────────────────────────────────────────────────────
    private fun callOpenAi(userPrompt: String, s: PluginSettings, prContext: PrContext? = null): String {
        val apiKey = s.getOpenAiKey()
        require(apiKey.isNotBlank()) {
            "OpenAI API key is not configured. Go to Settings → PR Review Assistant."
        }
        return postChat(
            url      = "https://api.openai.com/v1/chat/completions",
            apiKey   = apiKey,
            model    = s.openAiModel.ifBlank { "gpt-4o" },
            messages = buildMessages(userPrompt, prContext)
        )
    }

    // ── OpenAI-compatible (vLLM, LM Studio, Together AI …) ───────────────────
    private fun callOpenAiCompatible(userPrompt: String, s: PluginSettings, prContext: PrContext? = null): String {
        val apiKey  = s.getOpenAiCompatKey()
        val baseUrl = s.openAiCompatBaseUrl.trimEnd('/')
        require(baseUrl.isNotBlank()) {
            "OpenAI-compatible Base URL is not configured. Go to Settings → PR Review Assistant."
        }
        return postChat(
            url      = "$baseUrl/v1/chat/completions",
            apiKey   = apiKey,
            model    = s.openAiCompatModel.ifBlank { "gpt-4o" },
            messages = buildMessages(userPrompt, prContext)
        )
    }

    // ── Ollama ────────────────────────────────────────────────────────────────
    private fun callOllama(userPrompt: String, s: PluginSettings, prContext: PrContext? = null): String {
        val baseUrl = s.ollamaBaseUrl.trimEnd('/')
        val model   = s.ollamaModel.ifBlank { "llama3" }
        require(baseUrl.isNotBlank()) {
            "Ollama Base URL is not configured. Go to Settings → PR Review Assistant."
        }
        return postChat(
            url      = "$baseUrl/v1/chat/completions",
            apiKey   = "",               // no key for Ollama
            model    = model,
            messages = buildMessages(userPrompt, prContext)
        )
    }

    // ── Shared HTTP call ──────────────────────────────────────────────────────
    private fun postChat(
        url: String,
        apiKey: String,
        model: String,
        messages: List<Map<String, String>>
    ): String {
        val body = mapper.writeValueAsString(
            mapOf(
                "model"       to model,
                "messages"    to messages,
                "max_tokens"  to 3000,
                "temperature" to 0.3
            )
        )
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))

        if (apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        http.newCall(requestBuilder.build()).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw IOException("AI API error ${response.code}: $responseBody")
            }
            val parsed = mapper.readValue(responseBody, ChatCompletionResponse::class.java)
            return parsed.choices.firstOrNull()?.message?.content
                ?: throw IOException("Empty response from AI")
        }
    }

    // ── Build messages list — inject system prompt from skill file ────────────
    //
    // Message order:
    //   1. system_prompt.md  — role, tone, output format (user-editable)
    //   2. review_rules.md + coding_standards.md — injected as a second system message
    //   3. PR context        — id, title, author, branches (dynamic, from PrContext)
    //   4. user message      — the actual diff + code analyses
    //
    private fun buildMessages(userPrompt: String, prContext: PrContext? = null): List<Map<String, String>> {
        val messages = mutableListOf<Map<String, String>>()

        // 1. system_prompt.md — preferred over legacy settings field
        val systemPrompt = project
            ?.let { PRPilotSkillsService.getInstance(it).readSkill("system_prompt") }
            ?.ifBlank { null }
            ?: PluginSettings.instance.systemPrompt

        if (systemPrompt.isNotBlank()) {
            messages.add(mapOf("role" to "system", "content" to systemPrompt.trim()))
        }

        // 2. review_rules.md + coding_standards.md
        val skillsBlock = buildSkillsSystemBlock()
        if (skillsBlock.isNotBlank()) {
            messages.add(mapOf("role" to "system", "content" to skillsBlock))
        }

        // 3. PR context — injected as its own system message so it is always visible
        //    to the model regardless of how long the user prompt (diff) is
        if (prContext != null) {
            messages.add(mapOf("role" to "system", "content" to prContext.toSystemMessage()))
        }

        // 4. User message — diff + structured analyses
        messages.add(mapOf("role" to "user", "content" to userPrompt))
        return messages
    }

    /** Reads review_rules.md and coding_standards.md and combines them into one block. */
    private fun buildSkillsSystemBlock(): String {
        val p = project ?: return ""
        val svc = PRPilotSkillsService.getInstance(p)
        val rules    = svc.readSkill("review_rules").trim()
        val standards = svc.readSkill("coding_standards").trim()
        return buildString {
            if (rules.isNotBlank())     { appendLine(rules);     appendLine() }
            if (standards.isNotBlank()) { appendLine(standards) }
        }.trim()
    }
}

// ── DTOs ──────────────────────────────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatCompletionResponse(val choices: List<Choice> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class Choice(val message: Message = Message())

@JsonIgnoreProperties(ignoreUnknown = true)
data class Message(val content: String = "")
