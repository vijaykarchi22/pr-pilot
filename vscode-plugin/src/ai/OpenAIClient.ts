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
}

function prContextToSystemMessage(ctx: PrContext): string {
  return [
    '## Pull Request Context',
    `- **PR ID:** #${ctx.id}`,
    `- **Title:** ${ctx.title}`,
    `- **Author:** ${ctx.author}`,
    `- **Source branch:** \`${ctx.sourceBranch}\``,
    `- **Target branch:** \`${ctx.destinationBranch}\``,
    `- **Files changed:** ${ctx.fileCount}`,
  ].join('\n');
}

interface ChatMessage {
  role: 'system' | 'user' | 'assistant';
  content: string;
}

interface StreamDelta {
  choices: Array<{ delta: { content?: string }; finish_reason?: string | null }>;
}

// Conservative connect timeout — kept fixed; read timeout is user-configurable.
const CONNECT_TIMEOUT_MS = 60_000;

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

  async generateSummary(
    userPrompt: string,
    prContext?: PrContext,
    onChunk?: (delta: string, isThinking: boolean) => void
  ): Promise<string> {
    const settings = Settings.instance;
    const start = Date.now();
    Logger.info(`[AI] Review started | provider=${settings.aiProvider} | PR context=${prContext ? `#${prContext.id}` : 'none'}`);

    try {
      let result: string;
      switch (settings.aiProvider) {
        case 'OPENAI':
          result = await this.callOpenAi(userPrompt, prContext, onChunk);
          break;
        case 'OPENAI_COMPATIBLE':
          result = await this.callOpenAiCompatible(userPrompt, prContext, onChunk);
          break;
        case 'OLLAMA':
          result = await this.callOllama(userPrompt, prContext, onChunk);
          break;
      }
      Logger.info(`[AI] Review complete | elapsed=${Date.now() - start}ms`);
      return result;
    } catch (err) {
      Logger.error(`[AI] Review failed | elapsed=${Date.now() - start}ms | ${err instanceof Error ? err.message : String(err)}`);
      throw err;
    }
  }

  private async callOpenAi(userPrompt: string, prContext?: PrContext, onChunk?: (d: string, isThinking: boolean) => void): Promise<string> {
    const settings = Settings.instance;
    const apiKey = await settings.getOpenAiKey();
    if (!apiKey) {
      throw new Error('OpenAI API key is not configured. Open PR Pilot Settings → AI Provider.');
    }
    return this.postChat(
      'https://api.openai.com/v1/chat/completions',
      apiKey,
      settings.openAiModel || 'gpt-4o',
      await this.buildMessages(userPrompt, prContext),
      onChunk
    );
  }

  private async callOpenAiCompatible(userPrompt: string, prContext?: PrContext, onChunk?: (d: string, isThinking: boolean) => void): Promise<string> {
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

    return this.postChat(
      chatUrl,
      apiKey,
      settings.openAiCompatModel || 'gpt-4o',
      await this.buildMessages(userPrompt, prContext),
      onChunk
    );
  }

  private async callOllama(userPrompt: string, prContext?: PrContext, onChunk?: (d: string, isThinking: boolean) => void): Promise<string> {
    const settings = Settings.instance;
    const baseUrl = settings.ollamaBaseUrl.replace(/\/$/, '');
    if (!baseUrl) {
      throw new Error('Ollama Base URL is not configured. Open PR Pilot Settings → AI Provider.');
    }
    return this.postChat(
      `${baseUrl}/v1/chat/completions`,
      '',  // Ollama requires no API key
      settings.ollamaModel || 'llama3',
      await this.buildMessages(userPrompt, prContext),
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
   * Builds the message array for the chat completion.
   * Message order:
   *   1. system_prompt.md  — role, tone, output format
   *   2. review_rules.md + coding_standards.md
   *   3. PR context (id, title, author, branches)
   *   4. User message (diff + code analysis)
   */
  private async buildMessages(userPrompt: string, prContext?: PrContext): Promise<ChatMessage[]> {
    const messages: ChatMessage[] = [];

    // 1. System prompt
    const systemPrompt = this.skillsService.readSkill('system_prompt').trim();
    if (systemPrompt) {
      messages.push({ role: 'system', content: systemPrompt });
    }

    // 2. Review rules + coding standards combined
    const rulesBlock = this.buildSkillsSystemBlock();
    if (rulesBlock) {
      messages.push({ role: 'system', content: rulesBlock });
    }

    // 3. PR context
    if (prContext) {
      messages.push({ role: 'system', content: prContextToSystemMessage(prContext) });
    }

    // 4. User prompt (diff + analysis)
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
