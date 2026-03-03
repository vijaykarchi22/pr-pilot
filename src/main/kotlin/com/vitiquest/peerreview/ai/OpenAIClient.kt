package com.vitiquest.peerreview.ai

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.vitiquest.peerreview.settings.AiProvider
import com.vitiquest.peerreview.settings.PluginSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenAIClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)   // LLMs can be slow to stream a full response
        .callTimeout(360, TimeUnit.SECONDS)
        .build()
    private val mapper = jacksonObjectMapper()

    fun generateSummary(userPrompt: String): String {
        val s = PluginSettings.instance
        return when (s.aiProvider) {
            AiProvider.OPENAI            -> callOpenAi(userPrompt, s)
            AiProvider.OPENAI_COMPATIBLE -> callOpenAiCompatible(userPrompt, s)
            AiProvider.OLLAMA            -> callOllama(userPrompt, s)
        }
    }

    // ── OpenAI official ───────────────────────────────────────────────────────
    private fun callOpenAi(userPrompt: String, s: PluginSettings): String {
        val apiKey = s.getOpenAiKey()
        require(apiKey.isNotBlank()) {
            "OpenAI API key is not configured. Go to Settings → Tools → PR Review Assistant."
        }
        return postChat(
            url      = "https://api.openai.com/v1/chat/completions",
            apiKey   = apiKey,
            model    = s.openAiModel.ifBlank { "gpt-4o" },
            messages = buildMessages(s.systemPrompt, userPrompt)
        )
    }

    // ── OpenAI-compatible (vLLM, LM Studio, Together AI …) ───────────────────
    private fun callOpenAiCompatible(userPrompt: String, s: PluginSettings): String {
        val apiKey  = s.getOpenAiCompatKey()
        val baseUrl = s.openAiCompatBaseUrl.trimEnd('/')
        require(baseUrl.isNotBlank()) {
            "OpenAI-compatible Base URL is not configured. Go to Settings → Tools → PR Review Assistant."
        }
        return postChat(
            url      = "$baseUrl/v1/chat/completions",
            apiKey   = apiKey,              // may be blank for some local endpoints — sent anyway, ignored if not needed
            model    = s.openAiCompatModel.ifBlank { "gpt-4o" },
            messages = buildMessages(s.systemPrompt, userPrompt)
        )
    }

    // ── Ollama ────────────────────────────────────────────────────────────────
    private fun callOllama(userPrompt: String, s: PluginSettings): String {
        val baseUrl = s.ollamaBaseUrl.trimEnd('/')
        val model   = s.ollamaModel.ifBlank { "llama3" }
        require(baseUrl.isNotBlank()) {
            "Ollama Base URL is not configured. Go to Settings → Tools → PR Review Assistant."
        }

        // Ollama supports the OpenAI-compatible /v1/chat/completions endpoint (>= 0.1.24)
        return postChat(
            url      = "$baseUrl/v1/chat/completions",
            apiKey   = "",               // no key for Ollama
            model    = model,
            messages = buildMessages(s.systemPrompt, userPrompt)
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

    // ── Build messages list — inject system prompt if set ─────────────────────
    private fun buildMessages(systemPrompt: String, userPrompt: String): List<Map<String, String>> {
        val messages = mutableListOf<Map<String, String>>()
        if (systemPrompt.isNotBlank()) {
            messages.add(mapOf("role" to "system", "content" to systemPrompt.trim()))
        }
        messages.add(mapOf("role" to "user", "content" to userPrompt))
        return messages
    }
}

// ── DTOs ──────────────────────────────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatCompletionResponse(val choices: List<Choice> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class Choice(val message: Message = Message())

@JsonIgnoreProperties(ignoreUnknown = true)
data class Message(val content: String = "")
