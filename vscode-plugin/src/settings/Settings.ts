import * as vscode from 'vscode';

export type AiProvider = 'OPENAI' | 'OPENAI_COMPATIBLE' | 'OLLAMA';
export type GitProvider = 'BITBUCKET' | 'GITHUB';

export interface BitbucketTokenEntry {
  workspace: string;
  repoSlug: string;
  token: string;
}

/**
 * Manages all PR Pilot settings.
 *
 * Non-sensitive settings are stored in VS Code's configuration (settings.json).
 * Sensitive settings (tokens, API keys) are stored in VS Code's SecretStorage.
 *
 * Secret storage keys:
 *   - prPilot.github.pat
 *   - prPilot.openai.apiKey
 *   - prPilot.jira.apiToken
 *   - prPilot.bitbucket.tokens  (JSON array of BitbucketTokenEntry)
 */
export class Settings {
  private static _instance: Settings | undefined;
  private readonly secrets: vscode.SecretStorage;

  private constructor(private readonly context: vscode.ExtensionContext) {
    this.secrets = context.secrets;
  }

  static init(context: vscode.ExtensionContext): Settings {
    Settings._instance = new Settings(context);
    return Settings._instance;
  }

  static get instance(): Settings {
    if (!Settings._instance) {
      throw new Error('Settings not initialized. Call Settings.init(context) first.');
    }
    return Settings._instance;
  }

  private get config(): vscode.WorkspaceConfiguration {
    return vscode.workspace.getConfiguration('prPilot');
  }

  // ── Git Provider ──────────────────────────────────────────────────────────

  get gitProvider(): GitProvider {
    return this.config.get<GitProvider>('gitProvider', 'BITBUCKET');
  }

  async setGitProvider(value: GitProvider): Promise<void> {
    await this.config.update('gitProvider', value, vscode.ConfigurationTarget.Global);
  }

  // ── AI Provider ───────────────────────────────────────────────────────────

  get aiProvider(): AiProvider {
    return this.config.get<AiProvider>('aiProvider', 'OPENAI');
  }

  async setAiProvider(value: AiProvider): Promise<void> {
    await this.config.update('aiProvider', value, vscode.ConfigurationTarget.Global);
  }

  get openAiModel(): string {
    return this.config.get<string>('openAiModel', 'gpt-4o');
  }

  async setOpenAiModel(value: string): Promise<void> {
    await this.config.update('openAiModel', value, vscode.ConfigurationTarget.Global);
  }

  get openAiCompatBaseUrl(): string {
    return this.config.get<string>('openAiCompatBaseUrl', 'https://api.openai.com');
  }

  async setOpenAiCompatBaseUrl(value: string): Promise<void> {
    await this.config.update('openAiCompatBaseUrl', value, vscode.ConfigurationTarget.Global);
  }

  get openAiCompatModel(): string {
    return this.config.get<string>('openAiCompatModel', 'gpt-4o');
  }

  async setOpenAiCompatModel(value: string): Promise<void> {
    await this.config.update('openAiCompatModel', value, vscode.ConfigurationTarget.Global);
  }

  get ollamaBaseUrl(): string {
    return this.config.get<string>('ollamaBaseUrl', 'http://localhost:11434');
  }

  async setOllamaBaseUrl(value: string): Promise<void> {
    await this.config.update('ollamaBaseUrl', value, vscode.ConfigurationTarget.Global);
  }

  get ollamaModel(): string {
    return this.config.get<string>('ollamaModel', 'llama3');
  }

  async setOllamaModel(value: string): Promise<void> {
    await this.config.update('ollamaModel', value, vscode.ConfigurationTarget.Global);
  }

  /** AI response read timeout in milliseconds (configurable via prPilot.aiReadTimeoutSeconds). */
  get aiReadTimeoutMs(): number {
    const secs = this.config.get<number>('aiReadTimeoutSeconds', 900);
    return Math.max(secs, 30) * 1000;
  }

  get aiReadTimeoutSeconds(): number {
    return this.config.get<number>('aiReadTimeoutSeconds', 900);
  }

  async setAiReadTimeoutSeconds(value: number): Promise<void> {
    await this.config.update('aiReadTimeoutSeconds', Math.max(value, 30), vscode.ConfigurationTarget.Global);
  }

  // ── JIRA ──────────────────────────────────────────────────────────────────

  get jiraBaseUrl(): string {
    return this.config.get<string>('jiraBaseUrl', '');
  }

  async setJiraBaseUrl(value: string): Promise<void> {
    await this.config.update('jiraBaseUrl', value, vscode.ConfigurationTarget.Global);
  }

  get jiraEmail(): string {
    return this.config.get<string>('jiraEmail', '');
  }

  async setJiraEmail(value: string): Promise<void> {
    await this.config.update('jiraEmail', value, vscode.ConfigurationTarget.Global);
  }

  get jiraIssueKeyPattern(): string {
    return this.config.get<string>('jiraIssueKeyPattern', '');
  }

  async setJiraIssueKeyPattern(value: string): Promise<void> {
    await this.config.update('jiraIssueKeyPattern', value, vscode.ConfigurationTarget.Global);
  }

  // ── Sensitive: JIRA API Token ──────────────────────────────────────────────

  async getJiraApiToken(): Promise<string> {
    return (await this.secrets.get('prPilot.jira.apiToken')) ?? '';
  }

  async setJiraApiToken(token: string): Promise<void> {
    await this.secrets.store('prPilot.jira.apiToken', token.trim());
  }

  // ── Sensitive: GitHub PAT ─────────────────────────────────────────────────

  async getGitHubPat(): Promise<string> {
    return (await this.secrets.get('prPilot.github.pat')) ?? '';
  }

  async setGitHubPat(pat: string): Promise<void> {
    await this.secrets.store('prPilot.github.pat', pat.trim());
  }

  // ── Sensitive: OpenAI API Key ─────────────────────────────────────────────

  async getOpenAiKey(): Promise<string> {
    return (await this.secrets.get('prPilot.openai.apiKey')) ?? '';
  }

  async setOpenAiKey(key: string): Promise<void> {
    await this.secrets.store('prPilot.openai.apiKey', key.trim());
  }

  // ── Sensitive: Bitbucket per-repo tokens ─────────────────────────────────

  async getBitbucketTokens(): Promise<BitbucketTokenEntry[]> {
    const raw = await this.secrets.get('prPilot.bitbucket.tokens');
    if (!raw) return [];
    try {
      return JSON.parse(raw) as BitbucketTokenEntry[];
    } catch {
      return [];
    }
  }

  async getBitbucketToken(workspace: string, repoSlug: string): Promise<string | undefined> {
    const tokens = await this.getBitbucketTokens();
    const entry = tokens.find(
      (t) => t.workspace.toLowerCase() === workspace.toLowerCase() &&
             t.repoSlug.toLowerCase() === repoSlug.toLowerCase()
    );
    return entry?.token;
  }

  async setBitbucketToken(workspace: string, repoSlug: string, token: string): Promise<void> {
    const tokens = await this.getBitbucketTokens();
    const ws = workspace.toLowerCase();
    const slug = repoSlug.toLowerCase();
    const idx = tokens.findIndex((t) => t.workspace === ws && t.repoSlug === slug);
    if (idx >= 0) {
      tokens[idx].token = token.trim();
    } else {
      tokens.push({ workspace: ws, repoSlug: slug, token: token.trim() });
    }
    await this.secrets.store('prPilot.bitbucket.tokens', JSON.stringify(tokens));
  }

  async removeBitbucketToken(workspace: string, repoSlug: string): Promise<void> {
    const tokens = await this.getBitbucketTokens();
    const ws = workspace.toLowerCase();
    const slug = repoSlug.toLowerCase();
    const filtered = tokens.filter((t) => !(t.workspace === ws && t.repoSlug === slug));
    await this.secrets.store('prPilot.bitbucket.tokens', JSON.stringify(filtered));
  }

  /**
   * Loads all current settings as a plain object for the settings WebView.
   */
  async toWebViewState(): Promise<Record<string, unknown>> {
    const [githubPat, openaiKey, jiraApiToken, bitbucketTokens] = await Promise.all([
      this.getGitHubPat(),
      this.getOpenAiKey(),
      this.getJiraApiToken(),
      this.getBitbucketTokens(),
    ]);

    return {
      gitProvider: this.gitProvider,
      aiProvider: this.aiProvider,
      openAiModel: this.openAiModel,
      openAiCompatBaseUrl: this.openAiCompatBaseUrl,
      openAiCompatModel: this.openAiCompatModel,
      ollamaBaseUrl: this.ollamaBaseUrl,
      ollamaModel: this.ollamaModel,
      aiReadTimeoutSeconds: this.aiReadTimeoutSeconds,
      jiraBaseUrl: this.jiraBaseUrl,
      jiraEmail: this.jiraEmail,
      jiraIssueKeyPattern: this.jiraIssueKeyPattern,
      githubPat,
      openaiKey,
      jiraApiToken,
      bitbucketTokens,
    };
  }

  /**
   * Saves all settings from the WebView state back to VS Code config and SecretStorage.
   */
  async fromWebViewState(state: Record<string, unknown>): Promise<void> {
    const saves: Promise<void>[] = [];

    if (state.gitProvider) saves.push(this.setGitProvider(state.gitProvider as GitProvider));
    if (state.aiProvider) saves.push(this.setAiProvider(state.aiProvider as AiProvider));
    if (state.openAiModel !== undefined) saves.push(this.setOpenAiModel(state.openAiModel as string));
    if (state.openAiCompatBaseUrl !== undefined) saves.push(this.setOpenAiCompatBaseUrl(state.openAiCompatBaseUrl as string));
    if (state.openAiCompatModel !== undefined) saves.push(this.setOpenAiCompatModel(state.openAiCompatModel as string));
    if (state.ollamaBaseUrl !== undefined) saves.push(this.setOllamaBaseUrl(state.ollamaBaseUrl as string));
    if (state.ollamaModel !== undefined) saves.push(this.setOllamaModel(state.ollamaModel as string));
    if (state.aiReadTimeoutSeconds !== undefined) saves.push(this.setAiReadTimeoutSeconds(Number(state.aiReadTimeoutSeconds)));
    if (state.jiraBaseUrl !== undefined) saves.push(this.setJiraBaseUrl(state.jiraBaseUrl as string));
    if (state.jiraEmail !== undefined) saves.push(this.setJiraEmail(state.jiraEmail as string));
    if (state.jiraIssueKeyPattern !== undefined) saves.push(this.setJiraIssueKeyPattern(state.jiraIssueKeyPattern as string));
    if (state.githubPat !== undefined) saves.push(this.setGitHubPat(state.githubPat as string));
    if (state.openaiKey !== undefined) saves.push(this.setOpenAiKey(state.openaiKey as string));
    if (state.jiraApiToken !== undefined) saves.push(this.setJiraApiToken(state.jiraApiToken as string));

    if (Array.isArray(state.bitbucketTokens)) {
      const tokensJson = JSON.stringify(state.bitbucketTokens as BitbucketTokenEntry[]);
      saves.push(Promise.resolve(this.secrets.store('prPilot.bitbucket.tokens', tokensJson)));
    }

    await Promise.all(saves);
  }
}
