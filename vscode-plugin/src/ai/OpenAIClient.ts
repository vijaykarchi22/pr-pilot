import { httpPostStream } from '../utils/httpClient';
import { Settings } from '../settings/Settings';
import { SkillsService } from '../skills/SkillsService';
import { Logger } from '../utils/Logger';

export interface PrContext {
  id: number;
  title: string;
  author: string;
  sourceBranch: string;
  destinationBranch: string;
  fileCount: number;
  /**
   * Content injected into the `{prContext}` placeholder in system_prompt.md.
   * Contains PR metadata, impacted file list, git diff, and referenced file content.
   */
  diffContext?: string;
  /**
   * When set, triggers the lean per-file review message structure:
   *   1. system: prContext block
   *   2. system: these type-specific rules
   *   3. system: hardcoded inline-comments schema
   *   4. user:   review instruction
   * Loaded from `.vscode/pr-pilot/skills/<type>_rules.md`.
   */
  typeRulesContent?: string;
}

function buildPrContextBlock(ctx: PrContext): string {
  const lines = [
    '## Pull Request Metadata',
    `- **PR ID:** #${ctx.id}`,
    `- **Title:** ${ctx.title}`,
    `- **Author:** ${ctx.author}`,
    `- **Source branch:** \`${ctx.sourceBranch}\``,
    `- **Target branch:** \`${ctx.destinationBranch}\``,
    `- **Files changed:** ${ctx.fileCount}`,
  ];
  if (ctx.diffContext) {
    lines.push('');
    lines.push(ctx.diffContext);
  }
  return lines.join('\n');
}

export interface ChatMessage {
  role: 'system' | 'user' | 'assistant';
  content: string;
}

interface StreamDelta {
  choices: Array<{ delta: { content?: string }; finish_reason?: string | null }>;
}

// Conservative connect timeout — kept fixed; read timeout is user-configurable.
const CONNECT_TIMEOUT_MS = 60_000;

/**
 * Hardcoded output-format instructions for per-file reviews.
 * Replaces the inline-comments section from system_prompt.md so each
 * per-file call has a minimal, focused set of system messages.
 */
const INLINE_COMMENTS_SCHEMA = `## Required Output Format

After your analysis summary, append inline comments using EXACTLY this block:

<!-- INLINE_COMMENTS_START -->
[
  {
    "file": "relative/path/to/file.ext",
    "line": 42,
    "severity": "critical",
    "issue": "What is wrong (one sentence)",
    "cause": "Why it is a problem",
    "fix": "Concrete code-level recommendation",
    "comment": ""
  }
]
<!-- INLINE_COMMENTS_END -->

Severity levels:
- "critical" — bugs, security vulnerabilities, data loss risk
- "warning"  — code quality, maintainability, performance
- "suggestion" — optional improvements, style, readability

Constraints:
- Only flag issues in the changed lines shown in the git diff
- Emit between 0 and 20 inline comments
- Every entry MUST include: file, line, severity, issue, cause, fix
- The JSON array MUST appear between the HTML comment delimiters`;

/**
 * Parses streamed text for <think>…</think> tags, which reasoning models
 * (DeepSeek-R1, QwQ, etc.) emit to separate inner monologue from the final answer.
 * Calls `emit(text, isThinking)` for each classified segment.
 * Handles tags split across TCP chunks via `state.pending`.
 */
function processThinkDelta(
  newText: string,
  state: { inThink: boolean; pending: string },
  emit: (text: string, isThinking: boolean) => void
): void {
  let text = state.pending + newText;
  state.pending = '';

  while (text.length > 0) {
    const tag = state.inThink ? '</think>' : '<think>';
    const idx = text.indexOf(tag);

    if (idx === -1) {
      // Tag not found yet — keep the tail that could be a partial match
      const safeLen = safeEmitLength(text, tag);
      if (safeLen > 0) { emit(text.slice(0, safeLen), state.inThink); }
      state.pending = text.slice(safeLen);
      return;
    }

    if (idx > 0) { emit(text.slice(0, idx), state.inThink); }
    text = text.slice(idx + tag.length);
    state.inThink = !state.inThink;
  }
}

/** Returns the number of leading characters in `text` that are safe to emit
 *  without risk of splitting a `tag` that starts at the very end. */
function safeEmitLength(text: string, tag: string): number {
  const maxCheck = Math.min(text.length, tag.length - 1);
  for (let k = maxCheck; k >= 1; k--) {
    if (text.endsWith(tag.slice(0, k))) { return text.length - k; }
  }
  return text.length;
}

/**
 * Multi-provider LLM client.
 * Supports OpenAI (official), OpenAI-compatible (vLLM, LM Studio, etc.), and Ollama.
 */
export class OpenAIClient {
  constructor(private readonly skillsService: SkillsService) {}

  /**
   * Classifies a source file into one of the standard types so the appropriate
   * `<type>_rules.md` skill file can be loaded for the review.
   *
   * Uses a fast regex heuristic on the file path first; only calls the LLM when
   * the heuristic cannot determine the type.
   *
   * @returns lowercase type string, e.g. `controller`, `service`, `component`, etc.
   *          The returned string is used as the stem of the `<type>_rules.md` skill file.
   *
   * Naming convention for skill files:
   *   Angular    → service, component, module, guard, interceptor, resolver, pipe, directive, state
   *   Spring Boot / Java / Kotlin → java_service, controller, repository, model
   */
  async classifyFile(filePath: string, contentSnippet: string): Promise<string> {
    const name = filePath.toLowerCase();
    const isJvm = /\.(java|kt|groovy)$/.test(name);

    // ── Angular file-suffix conventions (checked first — very specific) ────
    if (!isJvm) {
      if (/\.component\.(ts|html|scss|css|less)$/.test(name)) { return 'component'; }
      if (/\.module\.ts$/.test(name))                          { return 'module'; }
      if (/\.guard\.ts$/.test(name))                           { return 'guard'; }
      if (/\.interceptor\.ts$/.test(name))                     { return 'interceptor'; }
      if (/\.resolver\.ts$/.test(name))                        { return 'resolver'; }
      if (/\.pipe\.ts$/.test(name))                            { return 'pipe'; }
      if (/\.directive\.ts$/.test(name))                       { return 'directive'; }
      if (/\.(effects|reducer|action|selector|store)\.ts$/.test(name)) { return 'state'; }
      if (/\.spec\.ts$/.test(name))                            { return 'test'; }
      if (/\.service\.ts$/.test(name))                         { return 'service'; }
    }

    // ── Spring Boot / JVM-specific heuristics ─────────────────────────────
    // These map to dedicated java_* skill files to avoid overwriting Angular rules.
    if (isJvm) {
      if (/test|spec|mock|stub|fixture/i.test(name))                        { return 'test'; }
      if (/controller|resource|handler|endpoint/i.test(name))               { return 'controller'; }
      if (/serviceimpl|service/i.test(name))                                { return 'java_service'; }
      if (/repositor|dao|mapper|persistence/i.test(name))                   { return 'repository'; }
      if (/entity|dto|request|response|domain|model/i.test(name))           { return 'model'; }
      if (/filter|interceptor|middleware|aspect/i.test(name))               { return 'middleware'; }
      if (/config|configuration|properties|settings/i.test(name))           { return 'config'; }
      if (/util|helper|common|shared|tool/i.test(name))                     { return 'util'; }
    }

    // ── Generic heuristics for any other language ─────────────────────────
    const HEURISTICS: Array<[RegExp, string]> = [
      [/test|spec|mock|stub|fixture/i,                          'test'],
      [/controller|resource|handler|endpoint|router|route/i,    'controller'],
      [/service|usecase|use_case|business/i,                    'service'],
      [/repositor|dao|mapper|persistence/i,                     'repository'],
      [/middleware|interceptor|filter|guard/i,                   'middleware'],
      [/model|entity|schema|dto|domain/i,                        'model'],
      [/config|configuration|settings|props|properties/i,        'config'],
      [/util|helper|common|shared|tool/i,                        'util'],
    ];
    for (const [pattern, type] of HEURISTICS) {
      if (pattern.test(name)) { return type; }
    }

    // ── LLM fallback ──────────────────────────────────────────────────────
    const ALLOWED =
      'controller, java_service, service, component, module, guard, interceptor, resolver, pipe, ' +
      'directive, state, repository, middleware, model, config, util, test, other';
    const messages: ChatMessage[] = [
      { role: 'system', content: 'You are a source-file classifier. Reply with exactly one lowercase word (underscores allowed) from the allowed list.' },
      {
        role: 'user',
        content:
          `Classify this file into one category.\n` +
          `Allowed values: ${ALLOWED}\n\n` +
          `File path: ${filePath}\n\nContent (first 800 chars):\n\`\`\`\n${contentSnippet.slice(0, 800)}\n\`\`\``,
      },
    ];
    try {
      const raw = await this.callOnce(messages);
      const escaped = ALLOWED.split(', ').map((v) => v.replace('_', '_')).join('|');
      const match = raw.trim().toLowerCase().match(new RegExp(`\\b(${escaped})\\b`));
      return match ? match[1] : 'other';
    } catch (err) {
      Logger.warn(`[AI] classifyFile failed: ${err instanceof Error ? err.message : String(err)}`);
      return 'other';
    }
  }

  async generateSummary(
    userPrompt: string,
    prContext?: PrContext,
    onChunk?: (delta: string, isThinking: boolean) => void,
    onPromptBuilt?: (messages: ChatMessage[]) => void
  ): Promise<string> {
    const settings = Settings.instance;
    const start = Date.now();
    Logger.info(`[AI] Review started | provider=${settings.aiProvider} | PR context=${prContext ? `#${prContext.id}` : 'none'}`);

    try {
      let result: string;
      switch (settings.aiProvider) {
        case 'OPENAI':
          result = await this.callOpenAi(userPrompt, prContext, onChunk, onPromptBuilt);
          break;
        case 'OPENAI_COMPATIBLE':
          result = await this.callOpenAiCompatible(userPrompt, prContext, onChunk, onPromptBuilt);
          break;
        case 'OLLAMA':
          result = await this.callOllama(userPrompt, prContext, onChunk, onPromptBuilt);
          break;
      }
      Logger.info(`[AI] Review complete | elapsed=${Date.now() - start}ms`);
      return result;
    } catch (err) {
      Logger.error(`[AI] Review failed | elapsed=${Date.now() - start}ms | ${err instanceof Error ? err.message : String(err)}`);
      throw err;
    }
  }

  private async callOpenAi(userPrompt: string, prContext?: PrContext, onChunk?: (d: string, isThinking: boolean) => void, onPromptBuilt?: (messages: ChatMessage[]) => void): Promise<string> {
    const settings = Settings.instance;
    const apiKey = await settings.getOpenAiKey();
    if (!apiKey) {
      throw new Error('OpenAI API key is not configured. Open PR Pilot Settings → AI Provider.');
    }
    const messages = await this.buildMessages(userPrompt, prContext);
    onPromptBuilt?.(messages);
    return this.postChat(
      'https://api.openai.com/v1/chat/completions',
      apiKey,
      settings.openAiModel || 'gpt-4o',
      messages,
      onChunk
    );
  }

  private async callOpenAiCompatible(userPrompt: string, prContext?: PrContext, onChunk?: (d: string, isThinking: boolean) => void, onPromptBuilt?: (messages: ChatMessage[]) => void): Promise<string> {
    const settings = Settings.instance;
    const apiKey = await settings.getOpenAiKey();
    let baseUrl = settings.openAiCompatBaseUrl.replace(/\/$/, '');
    if (!baseUrl) {
      throw new Error('OpenAI-compatible Base URL is not configured. Open PR Pilot Settings → AI Provider.');
    }
    const chatUrl = baseUrl.endsWith('/v1/chat/completions')
      ? baseUrl
      : baseUrl.endsWith('/v1')
        ? `${baseUrl}/chat/completions`
        : `${baseUrl}/v1/chat/completions`;

    const messages = await this.buildMessages(userPrompt, prContext);
    onPromptBuilt?.(messages);
    return this.postChat(
      chatUrl,
      apiKey,
      settings.openAiCompatModel || 'gpt-4o',
      messages,
      onChunk
    );
  }

  private async callOllama(userPrompt: string, prContext?: PrContext, onChunk?: (d: string, isThinking: boolean) => void, onPromptBuilt?: (messages: ChatMessage[]) => void): Promise<string> {
    const settings = Settings.instance;
    const baseUrl = settings.ollamaBaseUrl.replace(/\/$/, '');
    if (!baseUrl) {
      throw new Error('Ollama Base URL is not configured. Open PR Pilot Settings → AI Provider.');
    }
    const messages = await this.buildMessages(userPrompt, prContext);
    onPromptBuilt?.(messages);
    return this.postChat(
      `${baseUrl}/v1/chat/completions`,
      '',  // Ollama requires no API key
      settings.ollamaModel || 'llama3',
      messages,
      onChunk
    );
  }

  private async postChat(
    url: string,
    apiKey: string,
    model: string,
    messages: ChatMessage[],
    onChunk?: (delta: string, isThinking: boolean) => void
  ): Promise<string> {
    const readTimeoutMs = Settings.instance.aiReadTimeoutMs;
    const body = JSON.stringify({
      model,
      messages,
      max_tokens: 16384,
      temperature: 0.3,
      stream: true,
    });

    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
    };
    if (apiKey) {
      headers['Authorization'] = `Bearer ${apiKey}`;
    }

    Logger.info(`[AI] POST ${url} | model=${model} | readTimeout=${readTimeoutMs}ms`);

    let accumulated = '';
    let buffer = '';  // holds incomplete SSE lines between TCP chunks
    const thinkState = { inThink: false, pending: '' };

    try {
      await httpPostStream(url, body, headers, (raw: string) => {
        buffer += raw;
        const lines = buffer.split('\n');
        buffer = lines.pop() ?? '';  // keep last (possibly incomplete) line

        for (const line of lines) {
          const trimmed = line.trim();
          if (!trimmed.startsWith('data:')) { continue; }
          const payload = trimmed.slice(5).trim();
          if (payload === '[DONE]') { continue; }
          try {
            const chunk: StreamDelta = JSON.parse(payload);
            const delta = chunk.choices?.[0]?.delta?.content ?? '';
            if (delta) {
              processThinkDelta(delta, thinkState, (text, isThinking) => {
                if (!isThinking) { accumulated += text; }
                onChunk?.(text, isThinking);
              });
            }
          } catch {
            // Ignore malformed SSE lines
          }
        }
      }, readTimeoutMs);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      Logger.error(`[AI] HTTP error: ${msg}`);
      if (msg.includes('socket hang up') || msg.includes('ECONNRESET') || msg.includes('ETIMEDOUT') || msg.includes('timeout')) {
        const secs = Math.round(readTimeoutMs / 1000);
        throw new Error(
          `Connection to AI model timed out or was reset after ${secs}s.\n` +
          `If your model is slow to respond, increase the timeout in Settings → PR Pilot → AI Read Timeout Seconds.`
        );
      }
      if (msg.includes('HTTP 401')) {
        throw new Error(`${msg}\nCheck your API key in Settings → PR Pilot → AI Provider.`);
      }
      if (msg.includes('HTTP 404')) {
        throw new Error(`${msg}\nEndpoint not found. Verify the Base URL in Settings → PR Pilot → AI Provider.`);
      }
      if (msg.includes('HTTP 405')) {
        throw new Error(
          `${msg}\nMethod not allowed. The Base URL may already include /v1 or /v1/chat/completions — ` +
          `remove the path suffix and enter only the base URL.`
        );
      }
      if (msg.includes('HTTP 429')) {
        throw new Error(`${msg}\nRate limited / quota exceeded.`);
      }
      throw err instanceof Error ? err : new Error(msg);
    }

    if (!accumulated) {
      throw new Error('Empty response from AI — no content tokens were streamed.');
    }
    Logger.info(`[AI] Stream complete | chars=${accumulated.length}`);
    return accumulated;
  }

  /**
   * Makes a non-streaming LLM call and returns the raw accumulated response.
   * Used for lightweight tasks like file classification.
   */
  private async callOnce(messages: ChatMessage[]): Promise<string> {
    const settings = Settings.instance;
    switch (settings.aiProvider) {
      case 'OPENAI': {
        const apiKey = await settings.getOpenAiKey();
        if (!apiKey) { throw new Error('OpenAI API key not configured.'); }
        return this.postChat('https://api.openai.com/v1/chat/completions', apiKey, settings.openAiModel || 'gpt-4o', messages);
      }
      case 'OPENAI_COMPATIBLE': {
        const apiKey = await settings.getOpenAiKey();
        const baseUrl = settings.openAiCompatBaseUrl.replace(/\/$/, '');
        if (!baseUrl) { throw new Error('OpenAI-compatible Base URL not configured.'); }
        const chatUrl = baseUrl.endsWith('/v1/chat/completions') ? baseUrl
          : baseUrl.endsWith('/v1') ? `${baseUrl}/chat/completions`
          : `${baseUrl}/v1/chat/completions`;
        return this.postChat(chatUrl, apiKey || '', settings.openAiCompatModel || 'gpt-4o', messages);
      }
      case 'OLLAMA': {
        const baseUrl = settings.ollamaBaseUrl.replace(/\/$/, '');
        if (!baseUrl) { throw new Error('Ollama Base URL not configured.'); }
        return this.postChat(`${baseUrl}/v1/chat/completions`, '', settings.ollamaModel || 'llama3', messages);
      }
      default:
        throw new Error('Unknown AI provider.');
    }
  }

  /**
   * Builds the message array for the chat completion.
   *
   * **Per-file review** (when `prContext.typeRulesContent` is set):
   *   1. system: PR context block (metadata + diff + file content)
   *   2. system: type-specific rules loaded from `<type>_rules.md`
   *   3. system: hardcoded inline-comments output schema
   *   4. user:   review instruction
   *
   * **Consolidation / legacy calls** (`typeRulesContent` absent):
   *   1. system: system_prompt.md with {prContext} substituted
   *   2. system: review_rules.md + coding_standards.md
   *   3. user:   prompt
   */
  private async buildMessages(userPrompt: string, prContext?: PrContext): Promise<ChatMessage[]> {
    // ── Per-file review mode (lean, focused structure) ───────────────────────
    if (prContext?.typeRulesContent !== undefined) {
      const contextBlock = buildPrContextBlock(prContext);
      return [
        { role: 'system', content: contextBlock },
        { role: 'system', content: prContext.typeRulesContent },
        { role: 'system', content: INLINE_COMMENTS_SCHEMA },
        { role: 'user',   content: userPrompt },
      ];
    }

    // ── Legacy / consolidation mode ─────────────────────────────────────────
    const messages: ChatMessage[] = [];

    // 1. system_prompt.md — with {prContext} substitution
    const rawSystemPrompt = this.skillsService.readSkill('system_prompt').trim();
    if (rawSystemPrompt) {
      const contextBlock = prContext ? buildPrContextBlock(prContext) : '';
      const systemPrompt = rawSystemPrompt.replace('{prContext}', contextBlock);
      messages.push({ role: 'system', content: systemPrompt });
    }

    // 2. review_rules.md + coding_standards.md
    const rulesBlock = this.buildSkillsSystemBlock();
    if (rulesBlock) {
      messages.push({ role: 'system', content: rulesBlock });
    }

    // 3. user prompt
    messages.push({ role: 'user', content: userPrompt });

    return messages;
  }

  private buildSkillsSystemBlock(): string {
    const rules = this.skillsService.readSkill('review_rules').trim();
    const standards = this.skillsService.readSkill('coding_standards').trim();
    const parts = [rules, standards].filter((s) => s.length > 0);
    return parts.join('\n\n');
  }
}
