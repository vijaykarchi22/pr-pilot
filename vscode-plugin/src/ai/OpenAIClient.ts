import { httpPost } from '../utils/httpClient';
import { Settings } from '../settings/Settings';
import { SkillsService } from '../skills/SkillsService';

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

interface ChatCompletionResponse {
  choices: Array<{ message: { content: string } }>;
}

// Conservative connect timeout — kept fixed; read timeout is user-configurable.
const CONNECT_TIMEOUT_MS = 60_000;

/**
 * Multi-provider LLM client.
 * Supports OpenAI (official), OpenAI-compatible (vLLM, LM Studio, etc.), and Ollama.
 */
export class OpenAIClient {
  constructor(private readonly skillsService: SkillsService) {}

  async generateSummary(userPrompt: string, prContext?: PrContext): Promise<string> {
    const settings = Settings.instance;
    const start = Date.now();
    console.log(`[OpenAIClient] generateSummary start | provider=${settings.aiProvider}`);

    try {
      let result: string;
      switch (settings.aiProvider) {
        case 'OPENAI':
          result = await this.callOpenAi(userPrompt, prContext);
          break;
        case 'OPENAI_COMPATIBLE':
          result = await this.callOpenAiCompatible(userPrompt, prContext);
          break;
        case 'OLLAMA':
          result = await this.callOllama(userPrompt, prContext);
          break;
      }
      console.log(`[OpenAIClient] generateSummary success | elapsed=${Date.now() - start}ms`);
      return result;
    } catch (err) {
      console.error(`[OpenAIClient] generateSummary failed | elapsed=${Date.now() - start}ms | error=${err instanceof Error ? err.message : String(err)}`);
      throw err;
    }
  }

  private async callOpenAi(userPrompt: string, prContext?: PrContext): Promise<string> {
    const settings = Settings.instance;
    const apiKey = await settings.getOpenAiKey();
    if (!apiKey) {
      throw new Error('OpenAI API key is not configured. Open PR Pilot Settings → AI Provider.');
    }
    return this.postChat(
      'https://api.openai.com/v1/chat/completions',
      apiKey,
      settings.openAiModel || 'gpt-4o',
      await this.buildMessages(userPrompt, prContext)
    );
  }

  private async callOpenAiCompatible(userPrompt: string, prContext?: PrContext): Promise<string> {
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
      await this.buildMessages(userPrompt, prContext)
    );
  }

  private async callOllama(userPrompt: string, prContext?: PrContext): Promise<string> {
    const settings = Settings.instance;
    const baseUrl = settings.ollamaBaseUrl.replace(/\/$/, '');
    if (!baseUrl) {
      throw new Error('Ollama Base URL is not configured. Open PR Pilot Settings → AI Provider.');
    }
    return this.postChat(
      `${baseUrl}/v1/chat/completions`,
      '',  // Ollama requires no API key
      settings.ollamaModel || 'llama3',
      await this.buildMessages(userPrompt, prContext)
    );
  }

  private async postChat(
    url: string,
    apiKey: string,
    model: string,
    messages: ChatMessage[]
  ): Promise<string> {
    const readTimeoutMs = Settings.instance.aiReadTimeoutMs;
    const body = JSON.stringify({
      model,
      messages,
      max_tokens: 4096,
      temperature: 0.3,
    });

    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
    };
    if (apiKey) {
      headers['Authorization'] = `Bearer ${apiKey}`;
    }

    console.log(`[OpenAIClient] POST ${url} | model=${model} | readTimeout=${readTimeoutMs}ms`);

    let responseText: string;
    try {
      responseText = await httpPost(url, body, headers, readTimeoutMs);
      console.log(`[OpenAIClient] Raw response (first 500 chars): ${responseText.slice(0, 500)}`);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      console.error(`[OpenAIClient] HTTP error: ${msg}`);
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

    const parsed: ChatCompletionResponse | null = JSON.parse(responseText);
    console.log(`[OpenAIClient] Parsed response:`, JSON.stringify(parsed, null, 2).slice(0, 800));
    if (!parsed) {
      throw new Error(`Null response from AI.\nFull response:\n${responseText.slice(0, 500)}`);
    }
    const content = parsed.choices?.[0]?.message?.content;
    console.log(`[OpenAIClient] Extracted content (first 200 chars): ${content?.slice(0, 200)}`);
    if (!content) {
      throw new Error(`Empty response from AI (choices list was empty).\nFull response:\n${responseText.slice(0, 500)}`);
    }
    return content;
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
