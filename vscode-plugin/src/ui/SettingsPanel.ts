import * as vscode from 'vscode';
import { Settings, BitbucketTokenEntry } from '../settings/Settings';
import { SkillsService } from '../skills/SkillsService';

/**
 * Tabbed settings webview panel for PR Pilot.
 * Mirrors the IntelliJ plugin's 4-tab settings UI:
 *   1. Git Providers (Bitbucket per-repo tokens, GitHub PAT)
 *   2. AI Provider (OpenAI / compatible / Ollama)
 *   3. JIRA
 *   4. Skills & Prompts
 */
export class SettingsPanel {
  static currentPanel: SettingsPanel | undefined;
  private readonly _panel: vscode.WebviewPanel;
  private _disposables: vscode.Disposable[] = [];

  private constructor(
    panel: vscode.WebviewPanel,
    private readonly skillsService: SkillsService
  ) {
    this._panel = panel;
    this._panel.onDidDispose(() => this.dispose(), null, this._disposables);
    this._panel.webview.onDidReceiveMessage(
      (msg) => this.handleMessage(msg),
      null,
      this._disposables
    );
    this.refresh();
  }

  static show(extensionUri: vscode.Uri, skillsService: SkillsService): SettingsPanel {
    if (SettingsPanel.currentPanel) {
      SettingsPanel.currentPanel._panel.reveal(vscode.ViewColumn.One);
      SettingsPanel.currentPanel.refresh();
      return SettingsPanel.currentPanel;
    }

    const panel = vscode.window.createWebviewPanel(
      'prPilotSettings',
      'PR Pilot Settings',
      vscode.ViewColumn.One,
      { enableScripts: true, retainContextWhenHidden: true, localResourceRoots: [extensionUri] }
    );

    SettingsPanel.currentPanel = new SettingsPanel(panel, skillsService);
    return SettingsPanel.currentPanel;
  }

  private async refresh(): Promise<void> {
    const state = await Settings.instance.toWebViewState();
    const skills = {
      system_prompt: this.skillsService.readSkill('system_prompt'),
      review_rules: this.skillsService.readSkill('review_rules'),
      coding_standards: this.skillsService.readSkill('coding_standards'),
    };
    this._panel.webview.html = this.getHtml(state, skills);
  }

  private async handleMessage(message: Record<string, unknown>): Promise<void> {
    switch (message.command) {
      case 'save': {
        try {
          await Settings.instance.fromWebViewState(message as Record<string, unknown>);
          vscode.window.showInformationMessage('PR Pilot settings saved.');
          await this.refresh();
        } catch (err) {
          vscode.window.showErrorMessage(`Failed to save settings: ${err instanceof Error ? err.message : String(err)}`);
        }
        break;
      }

      case 'saveSkill': {
        const name = message.name as string;
        const content = message.content as string;
        if (name && content !== undefined) {
          this.skillsService.writeSkill(name, content);
          vscode.window.showInformationMessage(`Skill '${name}' saved.`);
        }
        break;
      }

      case 'resetSkill': {
        const name = message.name as string;
        if (name) {
          const ok = this.skillsService.resetToDefault(name);
          if (ok) {
            vscode.window.showInformationMessage(`Skill '${name}' reset to default.`);
            await this.refresh();
          } else {
            vscode.window.showWarningMessage(`Could not reset '${name}' — bundled resource not found.`);
          }
        }
        break;
      }

      case 'openSkillsFolder': {
        const dir = this.skillsService.getSkillsDir();
        vscode.env.openExternal(vscode.Uri.file(dir));
        break;
      }
    }
  }

  private getHtml(state: Record<string, unknown>, skills: Record<string, string>): string {
    const tokens = JSON.stringify(state.bitbucketTokens ?? []);
    const esc = (v: unknown): string => String(v ?? '').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

    return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline';">
  <title>PR Pilot Settings</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: var(--vscode-font-family);
      font-size: var(--vscode-font-size);
      color: var(--vscode-foreground);
      background: var(--vscode-editor-background);
      display: flex;
      flex-direction: column;
      height: 100vh;
    }
    .header {
      padding: 12px 20px;
      background: var(--vscode-titleBar-activeBackground);
      border-bottom: 1px solid var(--vscode-panel-border);
    }
    .header h1 { font-size: 16px; color: var(--vscode-titleBar-activeForeground); }
    .layout { display: flex; flex: 1; overflow: hidden; }
    .sidebar {
      width: 180px;
      background: var(--vscode-sideBar-background);
      border-right: 1px solid var(--vscode-panel-border);
      padding: 8px 0;
      flex-shrink: 0;
      overflow-y: auto;
    }
    .sidebar-item {
      padding: 10px 16px;
      cursor: pointer;
      font-size: 13px;
      color: var(--vscode-sideBarTitle-foreground);
      border-left: 3px solid transparent;
    }
    .sidebar-item:hover { background: var(--vscode-list-hoverBackground); }
    .sidebar-item.active {
      background: var(--vscode-list-activeSelectionBackground);
      color: var(--vscode-list-activeSelectionForeground);
      border-left-color: var(--vscode-focusBorder);
    }
    .content { flex: 1; overflow-y: auto; padding: 20px; }
    .section { display: none; }
    .section.active { display: block; }
    .section h2 {
      font-size: 14px;
      font-weight: 600;
      color: var(--vscode-foreground);
      margin-bottom: 16px;
      padding-bottom: 8px;
      border-bottom: 1px solid var(--vscode-panel-border);
    }
    .field { margin-bottom: 14px; }
    .field label {
      display: block;
      font-size: 12px;
      color: var(--vscode-descriptionForeground);
      margin-bottom: 4px;
    }
    .field input, .field select, .field textarea {
      width: 100%;
      padding: 6px 8px;
      background: var(--vscode-input-background);
      color: var(--vscode-input-foreground);
      border: 1px solid var(--vscode-input-border, var(--vscode-panel-border));
      border-radius: 3px;
      font-size: 13px;
      font-family: inherit;
    }
    .field textarea {
      font-family: var(--vscode-editor-font-family, monospace);
      font-size: 12px;
      resize: vertical;
      min-height: 200px;
    }
    .field input:focus, .field select:focus, .field textarea:focus {
      outline: 1px solid var(--vscode-focusBorder);
    }
    .radio-group { display: flex; gap: 16px; margin-top: 4px; }
    .radio-group label {
      display: flex;
      align-items: center;
      gap: 6px;
      color: var(--vscode-foreground);
      cursor: pointer;
    }
    .conditional { display: none; }
    .provider-fields { margin-top: 16px; }
    table { width: 100%; border-collapse: collapse; margin: 8px 0; }
    th, td { padding: 6px 10px; border: 1px solid var(--vscode-panel-border); font-size: 12px; text-align: left; }
    th { background: var(--vscode-editor-selectionBackground); font-weight: 600; }
    .token-input { width: 100%; background: transparent; color: inherit; border: none; font-size: 12px; font-family: var(--vscode-editor-font-family, monospace); }
    .token-input:focus { outline: none; }
    .btn-row { display: flex; gap: 8px; margin-top: 8px; flex-wrap: wrap; }
    button {
      padding: 6px 14px;
      border-radius: 3px;
      border: 1px solid var(--vscode-button-border, transparent);
      cursor: pointer;
      font-size: 12px;
      font-family: inherit;
    }
    button.primary { background: var(--vscode-button-background); color: var(--vscode-button-foreground); }
    button.primary:hover { background: var(--vscode-button-hoverBackground); }
    button.secondary { background: var(--vscode-button-secondaryBackground); color: var(--vscode-button-secondaryForeground); }
    button.secondary:hover { background: var(--vscode-button-secondaryHoverBackground); }
    button.danger { background: transparent; color: #f14c4c; border-color: #f14c4c44; }
    button.danger:hover { background: #f14c4c15; }
    .skill-actions { display: flex; gap: 8px; margin-top: 6px; }
    .hint { font-size: 11px; color: var(--vscode-descriptionForeground); margin-top: 3px; }
    .info-box {
      border: 1px solid var(--vscode-panel-border);
      border-radius: 5px;
      overflow: hidden;
    }
    .info-box > summary {
      list-style: none;
      padding: 8px 12px;
      cursor: pointer;
      font-size: 12px;
      font-weight: 600;
      background: var(--vscode-editor-inactiveSelectionBackground);
      user-select: none;
    }
    .info-box > summary::-webkit-details-marker { display: none; }
    .info-box[open] > summary { border-bottom: 1px solid var(--vscode-panel-border); }
    .info-body {
      padding: 12px 14px;
      font-size: 12px;
      line-height: 1.6;
      color: var(--vscode-foreground);
    }
    .info-body ol, .info-body ul { margin-left: 20px; margin-top: 6px; }
    .info-body li { margin-bottom: 4px; }
    .info-body p { margin-top: 4px; }
    .info-body code {
      background: var(--vscode-textCodeBlock-background);
      padding: 1px 5px;
      border-radius: 3px;
      font-family: var(--vscode-editor-font-family, monospace);
    }
    .var-table { width: 100%; border-collapse: collapse; margin-top: 6px; }
    .var-table th, .var-table td {
      padding: 5px 10px;
      border: 1px solid var(--vscode-panel-border);
      font-size: 12px;
      text-align: left;
    }
    .var-table th { background: var(--vscode-editor-selectionBackground); font-weight: 600; }
    .footer {
      padding: 12px 20px;
      border-top: 1px solid var(--vscode-panel-border);
      display: flex;
      gap: 8px;
      background: var(--vscode-panel-background);
      flex-shrink: 0;
    }
  </style>
</head>
<body>
  <div class="header"><h1>⚙ PR Pilot Settings</h1></div>
  <div class="layout">
    <div class="sidebar">
      <div class="sidebar-item active" onclick="showSection('git')">🔗 Git Providers</div>
      <div class="sidebar-item" onclick="showSection('ai')">🤖 AI Provider</div>
      <div class="sidebar-item" onclick="showSection('jira')">🎫 JIRA</div>
      <div class="sidebar-item" onclick="showSection('skills')">📝 Skills & Prompts</div>
    </div>
    <div class="content">

      <!-- Git Providers -->
      <div id="section-git" class="section active">
        <h2>Git Providers</h2>
        <div class="field">
          <label>Provider</label>
          <div class="radio-group">
            <label><input type="radio" name="gitProvider" value="BITBUCKET" ${state.gitProvider === 'BITBUCKET' ? 'checked' : ''} onchange="updateGitProvider()"> Bitbucket Cloud</label>
            <label><input type="radio" name="gitProvider" value="GITHUB" ${state.gitProvider === 'GITHUB' ? 'checked' : ''} onchange="updateGitProvider()"> GitHub</label>
          </div>
        </div>

        <!-- Bitbucket -->
        <div id="bitbucket-config" class="conditional provider-fields" style="${state.gitProvider === 'BITBUCKET' ? 'display:block' : ''}">
          <h2 style="margin-top:16px">Bitbucket Access Tokens</h2>
          <p class="hint" style="margin-bottom:8px">Add a Repository/Workspace Access Token per repo. Generate tokens under Bitbucket → Repo Settings → Access tokens.</p>
          <table id="token-table">
            <thead><tr><th>Workspace</th><th>Repo Slug</th><th>Token</th><th></th></tr></thead>
            <tbody id="token-tbody"></tbody>
          </table>
          <div class="btn-row">
            <button class="secondary" onclick="addTokenRow()">+ Add Token</button>
          </div>
        </div>

        <!-- GitHub -->
        <div id="github-config" class="conditional provider-fields" style="${state.gitProvider === 'GITHUB' ? 'display:block' : ''}">
          <div class="field">
            <label>GitHub Personal Access Token (PAT)</label>
            <input type="password" id="githubPat" value="${esc(state.githubPat)}" placeholder="github_pat_...">
            <div class="hint">Requires 'repo' and 'pull_requests' scopes. Generate at GitHub → Settings → Developer Settings → Tokens.</div>
          </div>
        </div>
      </div>

      <!-- AI Provider -->
      <div id="section-ai" class="section">
        <h2>AI Provider</h2>
        <div class="field">
          <label>Provider</label>
          <select id="aiProvider" onchange="updateAiProvider()">
            <option value="OPENAI" ${state.aiProvider === 'OPENAI' ? 'selected' : ''}>OpenAI (official)</option>
            <option value="OPENAI_COMPATIBLE" ${state.aiProvider === 'OPENAI_COMPATIBLE' ? 'selected' : ''}>OpenAI-compatible (vLLM, LM Studio, Together AI…)</option>
            <option value="OLLAMA" ${state.aiProvider === 'OLLAMA' ? 'selected' : ''}>Ollama (local, no API key required)</option>
          </select>
        </div>

        <div id="ai-openai" class="conditional provider-fields" style="${state.aiProvider === 'OPENAI' ? 'display:block' : ''}">
          <div class="field">
            <label>OpenAI API Key</label>
            <input type="password" id="openaiKey" value="${esc(state.openaiKey)}" placeholder="sk-...">
          </div>
          <div class="field">
            <label>Model</label>
            <input type="text" id="openAiModel" value="${esc(state.openAiModel)}" placeholder="gpt-4o">
            <div class="hint">e.g. gpt-4o, gpt-4-turbo, gpt-3.5-turbo</div>
          </div>
        </div>

        <div id="ai-compat" class="conditional provider-fields" style="${state.aiProvider === 'OPENAI_COMPATIBLE' ? 'display:block' : ''}">
          <div class="field">
            <label>Base URL</label>
            <input type="text" id="openAiCompatBaseUrl" value="${esc(state.openAiCompatBaseUrl)}" placeholder="https://api.example.com">
            <div class="hint">Enter the base URL only — do not include /v1 or /v1/chat/completions.</div>
          </div>
          <div class="field">
            <label>API Key (optional)</label>
            <input type="password" id="openaiKeyCompat" value="${esc(state.openaiKey)}" placeholder="Leave blank if not required">
          </div>
          <div class="field">
            <label>Model</label>
            <input type="text" id="openAiCompatModel" value="${esc(state.openAiCompatModel)}" placeholder="gpt-4o">
          </div>
        </div>

        <div id="ai-ollama" class="conditional provider-fields" style="${state.aiProvider === 'OLLAMA' ? 'display:block' : ''}">
          <div class="field">
            <label>Ollama Base URL</label>
            <input type="text" id="ollamaBaseUrl" value="${esc(state.ollamaBaseUrl)}" placeholder="http://localhost:11434">
          </div>
          <div class="field">
            <label>Model</label>
            <input type="text" id="ollamaModel" value="${esc(state.ollamaModel)}" placeholder="llama3">
            <div class="hint">e.g. llama3, codellama, mistral, deepseek-coder</div>
          </div>
        </div>

        <div class="field" style="margin-top:20px; padding-top:16px; border-top:1px solid var(--vscode-panel-border)">
          <label>Response Timeout (seconds)</label>
          <input type="number" id="aiReadTimeoutSeconds" value="${esc(state.aiReadTimeoutSeconds ?? 900)}" min="30" step="30" style="width:120px">
          <div class="hint">How long to wait for an AI response before giving up. Increase for slow or large local models (default: 900 = 15 min).</div>
        </div>
      </div>

      <!-- JIRA -->
      <div id="section-jira" class="section">
        <h2>JIRA Integration</h2>
        <p class="hint" style="margin-bottom:12px">When configured, PR actions (Approve, Merge, Request Changes) will add a comment to the linked JIRA issue and optionally reassign it.</p>
        <div class="field">
          <label>JIRA Cloud Base URL</label>
          <input type="text" id="jiraBaseUrl" value="${esc(state.jiraBaseUrl)}" placeholder="https://yourcompany.atlassian.net">
        </div>
        <div class="field">
          <label>Atlassian Account Email</label>
          <input type="text" id="jiraEmail" value="${esc(state.jiraEmail)}" placeholder="you@company.com">
        </div>
        <div class="field">
          <label>Atlassian API Token</label>
          <input type="password" id="jiraApiToken" value="${esc(state.jiraApiToken)}" placeholder="ATATT3x...">
          <div class="hint">Generate at: id.atlassian.com → Security → API tokens</div>
        </div>
        <div class="field">
          <label>Issue Key Pattern (regex)</label>
          <input type="text" id="jiraIssueKeyPattern" value="${esc(state.jiraIssueKeyPattern)}" placeholder="Leave blank for default: [A-Z][A-Z0-9]+-\\d+">
          <div class="hint">The plugin will look for this pattern in PR title, description, and branch name.</div>
        </div>
      </div>

      <!-- Skills & Prompts -->
      <div id="section-skills" class="section">
        <h2>Skills &amp; Prompts</h2>
        <p class="hint" style="margin-bottom:12px">
          These files control how the AI reviews code. They are stored in
          <code>.vscode/pr-pilot/skills/</code> in your workspace and can be committed to source control
          so the whole team shares the same review behaviour.
          <button class="secondary" onclick="openSkillsFolder()" style="margin-left:8px;font-size:11px;padding:2px 8px">📂 Open Folder</button>
        </p>

        <!-- How it works -->
        <details class="info-box" style="margin-bottom:20px">
          <summary>ℹ️ How the review pipeline works</summary>
          <div class="info-body">
            <p>When you click <strong>Generate AI Review</strong>, PR Pilot performs these steps for each changed file:</p>
            <ol>
              <li>Fetches the <strong>git diff</strong> and full file content from the provider API.</li>
              <li>Resolves any locally imported files to add as <strong>reference context</strong>.</li>
              <li>Builds the prompt by replacing <code>{prContext}</code> in <strong>system_prompt.md</strong> with:
                <ul>
                  <li>PR metadata (id, title, author, branches)</li>
                  <li>The file's git diff</li>
                  <li>The full file content</li>
                  <li>Referenced / imported file content</li>
                </ul>
              </li>
              <li>Sends the filled system prompt + <strong>review_rules.md</strong> + <strong>coding_standards.md</strong> to the LLM.</li>
              <li>Collects inline comments and per-file summaries, then makes a final <strong>consolidation call</strong> to produce the overall review.</li>
            </ol>
          </div>
        </details>

        <!-- Variables reference -->
        <details class="info-box" style="margin-bottom:20px">
          <summary>🔧 Available template variables</summary>
          <div class="info-body">
            <table class="var-table">
              <thead><tr><th>Variable</th><th>Where to use</th><th>Replaced with</th></tr></thead>
              <tbody>
                <tr><td><code>{prContext}</code></td><td>system_prompt.md</td><td>PR metadata + git diff + full file content + referenced files</td></tr>
              </tbody>
            </table>
            <p style="margin-top:8px">Place <code>{prContext}</code> anywhere in <strong>system_prompt.md</strong> to control where in the system message the live file context is injected. If omitted, the context is appended automatically at the end.</p>
          </div>
        </details>

        <div class="field">
          <label><strong>system_prompt.md</strong> — AI role, behaviour, tone &amp; output format</label>
          <div class="hint" style="margin-bottom:6px">
            Defines <em>who the AI is</em> and <em>how it should respond</em>. Use <code>{prContext}</code> as a placeholder
            where the live PR diff and file content will be injected at review time.
            Change this to adjust the review style, verbosity, or focus areas.
          </div>
          <textarea id="skill-system_prompt" rows="20">${escText(skills.system_prompt)}</textarea>
          <div class="skill-actions">
            <button class="secondary" onclick="saveSkill('system_prompt')">💾 Save</button>
            <button class="secondary" onclick="resetSkill('system_prompt')">↺ Reset to Default</button>
          </div>
        </div>

        <div class="field">
          <label><strong>review_rules.md</strong> — Review checklist &amp; mandatory checks</label>
          <div class="hint" style="margin-bottom:6px">
            A list of rules the AI must apply to every review regardless of the code being reviewed.
            Add project-specific security rules, architecture guidelines, or non-negotiables here.
            Sent as a separate system message so it is never diluted by the diff content.
          </div>
          <textarea id="skill-review_rules" rows="14">${escText(skills.review_rules)}</textarea>
          <div class="skill-actions">
            <button class="secondary" onclick="saveSkill('review_rules')">💾 Save</button>
            <button class="secondary" onclick="resetSkill('review_rules')">↺ Reset to Default</button>
          </div>
        </div>

        <div class="field">
          <label><strong>coding_standards.md</strong> — Team coding conventions</label>
          <div class="hint" style="margin-bottom:6px">
            Describes your team's language-specific conventions, naming rules, preferred patterns, and anti-patterns.
            The AI will flag deviations from these standards as review comments.
            Customise this per-project and commit it alongside your code.
          </div>
          <textarea id="skill-coding_standards" rows="14">${escText(skills.coding_standards)}</textarea>
          <div class="skill-actions">
            <button class="secondary" onclick="saveSkill('coding_standards')">💾 Save</button>
            <button class="secondary" onclick="resetSkill('coding_standards')">↺ Reset to Default</button>
          </div>
        </div>
      </div>

    </div>
  </div>
  <div class="footer">
    <button class="primary" onclick="saveAll()">💾 Save Settings</button>
    <button class="secondary" onclick="cancel()">✕ Close</button>
  </div>

  <script>
    const vscode = acquireVsCodeApi();
    let tokens = ${tokens};

    function showSection(name) {
      document.querySelectorAll('.section').forEach(s => s.classList.remove('active'));
      document.querySelectorAll('.sidebar-item').forEach(s => s.classList.remove('active'));
      document.getElementById('section-' + name)?.classList.add('active');
      event.currentTarget.classList.add('active');
    }

    function updateGitProvider() {
      const val = document.querySelector('input[name="gitProvider"]:checked')?.value;
      document.getElementById('bitbucket-config').style.display = val === 'BITBUCKET' ? 'block' : 'none';
      document.getElementById('github-config').style.display = val === 'GITHUB' ? 'block' : 'none';
    }

    function updateAiProvider() {
      const val = document.getElementById('aiProvider').value;
      document.getElementById('ai-openai').style.display = val === 'OPENAI' ? 'block' : 'none';
      document.getElementById('ai-compat').style.display = val === 'OPENAI_COMPATIBLE' ? 'block' : 'none';
      document.getElementById('ai-ollama').style.display = val === 'OLLAMA' ? 'block' : 'none';
    }

    function renderTokenTable() {
      const tbody = document.getElementById('token-tbody');
      tbody.innerHTML = tokens.map((t, i) => \`
        <tr>
          <td><input class="token-input" value="\${esc(t.workspace)}" oninput="tokens[\${i}].workspace=this.value"></td>
          <td><input class="token-input" value="\${esc(t.repoSlug)}" oninput="tokens[\${i}].repoSlug=this.value"></td>
          <td><input class="token-input" type="password" value="\${esc(t.token)}" oninput="tokens[\${i}].token=this.value"></td>
          <td><button class="danger" onclick="removeToken(\${i})">Remove</button></td>
        </tr>
      \`).join('');
    }

    function addTokenRow() {
      tokens.push({ workspace: '', repoSlug: '', token: '' });
      renderTokenTable();
    }

    function removeToken(i) {
      tokens.splice(i, 1);
      renderTokenTable();
    }

    function esc(v) {
      return String(v || '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
    }

    function saveAll() {
      const provider = document.querySelector('input[name="gitProvider"]:checked')?.value || 'BITBUCKET';
      const aiProvider = document.getElementById('aiProvider').value;
      const openaiKey = aiProvider === 'OPENAI_COMPATIBLE'
        ? document.getElementById('openaiKeyCompat').value
        : document.getElementById('openaiKey')?.value || '';

      vscode.postMessage({
        command: 'save',
        gitProvider: provider,
        aiProvider,
        openAiModel: document.getElementById('openAiModel')?.value || 'gpt-4o',
        openAiCompatBaseUrl: document.getElementById('openAiCompatBaseUrl')?.value || '',
        openAiCompatModel: document.getElementById('openAiCompatModel')?.value || 'gpt-4o',
        ollamaBaseUrl: document.getElementById('ollamaBaseUrl')?.value || '',
        ollamaModel: document.getElementById('ollamaModel')?.value || 'llama3',
        aiReadTimeoutSeconds: parseInt(document.getElementById('aiReadTimeoutSeconds')?.value || '900', 10),
        githubPat: document.getElementById('githubPat')?.value || '',
        openaiKey,
        jiraBaseUrl: document.getElementById('jiraBaseUrl')?.value || '',
        jiraEmail: document.getElementById('jiraEmail')?.value || '',
        jiraApiToken: document.getElementById('jiraApiToken')?.value || '',
        jiraIssueKeyPattern: document.getElementById('jiraIssueKeyPattern')?.value || '',
        bitbucketTokens: tokens,
      });
    }

    function saveSkill(name) {
      const content = document.getElementById('skill-' + name).value;
      vscode.postMessage({ command: 'saveSkill', name, content });
    }

    function resetSkill(name) {
      vscode.postMessage({ command: 'resetSkill', name });
    }

    function openSkillsFolder() {
      vscode.postMessage({ command: 'openSkillsFolder' });
    }

    function cancel() {
      // Panel will be closed by VS Code when user clicks X
    }

    renderTokenTable();
  </script>
</body>
</html>`;
  }

  private dispose(): void {
    SettingsPanel.currentPanel = undefined;
    this._panel.dispose();
    while (this._disposables.length) {
      const d = this._disposables.pop();
      if (d) d.dispose();
    }
  }
}

function escText(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
