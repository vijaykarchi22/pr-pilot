<div align="center">

<!-- Replace with your actual banner image -->
![PR Pilot Banner](https://raw.githubusercontent.com/vitiquest/pr-pilot/main/docs/assets/banner.png)

# PR Pilot for VS Code

**AI-powered pull request reviews, right inside VS Code.**

[![Version](https://img.shields.io/visual-studio-marketplace/v/vitiquest.pr-pilot?label=VS%20Code%20Marketplace&color=blue&logo=visual-studio-code)](https://marketplace.visualstudio.com/items?itemName=thedeveloperbhai.pr-pilot)
[![Installs](https://img.shields.io/visual-studio-marketplace/i/vitiquest.pr-pilot?color=brightgreen)](https://marketplace.visualstudio.com/items?itemName=vitiquest.pr-pilot)
[![Rating](https://img.shields.io/visual-studio-marketplace/r/vitiquest.pr-pilot)](https://marketplace.visualstudio.com/items?itemName=vitiquest.pr-pilot)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://github.com/vitiquest/pr-pilot/blob/main/LICENSE)

[📖 Documentation](https://thedeveloperbhai.github.io/pr-pilot/) · [🚀 Getting Started](https://thedeveloperbhai.github.io/pr-pilot/installation.html) · [⚙️ Configuration](https://thedeveloperbhai.github.io/pr-pilot/configuration.html) · [🐛 Report an Issue](https://github.com/thedeveloperbhai/pr-pilot/issues)

</div>

---

PR Pilot connects VS Code to your **GitHub** or **Bitbucket Cloud** repositories. Browse pull requests, inspect diffs side-by-side, and generate deep AI code-review reports — without ever leaving your editor.

Supports **OpenAI**, any **OpenAI-compatible** endpoint (vLLM, LM Studio, Together AI, Groq), and **Ollama** for fully local, private reviews.

---

## ✨ Features

### 🔍 PR Browser
Browse and filter all pull requests for the current repository directly in the Activity Bar. Filter by state (Open, Merged, Declined), search by ID or title, and select a PR in one click.

<!-- Replace with actual screenshot -->
![PR Browser Screenshot](https://raw.githubusercontent.com/vitiquest/pr-pilot/main/docs/images/screenshot-pr-browser.png)

### 📂 Side-by-Side Diff Viewer
Click any changed file to open it in VS Code's built-in diff editor with full syntax highlighting. ADDED, MODIFIED, DELETED, and RENAMED files are shown with colour-coded badges.

<!-- Replace with actual screenshot -->
![Diff Viewer Screenshot](https://raw.githubusercontent.com/vitiquest/pr-pilot/main/docs/images/screenshot-diff.png)

### 🤖 AI Code Review
Generate a comprehensive AI review report with a single click. The report includes:
- **Overview** — what the PR does and its overall quality
- **File Analysis** — per-file feedback with severity labels (🔴 Critical · 🟡 Warning · 🟢 Suggestion)
- **Inline Comments** — posted directly on the PR lines in GitHub or Bitbucket
- **Summary Table** — consolidated list of all identified issues

<!-- Replace with actual screenshot -->
![AI Review Screenshot](https://raw.githubusercontent.com/vitiquest/pr-pilot/main/docs/images/screenshot-ai-review.png)

### ✅ PR Actions
Approve, merge, or decline pull requests without leaving VS Code. When Jira is configured, review outcomes are automatically synced back to the linked Jira issue.

### 🎯 Skills & Prompt Customisation
Control exactly how the AI reviews your code using plain Markdown skill files committed to your repository. Your whole team shares the same review standards automatically.

```
.vscode/
└── pr-pilot/
    └── skills/
        ├── system_prompt.md      # AI persona, tone, output format
        ├── review_rules.md       # What to look for (security, perf, testing…)
        └── coding_standards.md   # Your team's style guide and architecture rules
```

📖 [Full Skills Guide →](https://vitiquest.github.io/pr-pilot/skills.html)

---

## 📋 Requirements

| Requirement | Version |
|---|---|
| VS Code | 1.85.0 or newer |
| Git repository | Workspace must have a Bitbucket Cloud or GitHub remote |
| Internet connection | Required for API calls to GitHub / Bitbucket and cloud AI providers |

> **Ollama users:** No internet required for AI calls — but you still need connectivity for GitHub/Bitbucket APIs.

---

## 🚀 Getting Started

1. **Install** the extension from the Marketplace
2. Open a workspace with a **Git repository** pointing to GitHub or Bitbucket Cloud
3. Click the **PR Pilot icon** in the Activity Bar to open the panel
4. Click the **⚙ gear icon** to open Settings — connect your Git provider and AI provider
5. Click **Refresh** to load pull requests

📖 [Detailed Installation Guide →](https://vitiquest.github.io/pr-pilot/installation.html)

---

## ⚙️ Configuration

All settings are managed in the **PR Pilot Settings panel** (accessible via the gear icon or `PR Pilot: Open Settings`). Sensitive tokens are stored in VS Code's encrypted SecretStorage — never in `settings.json`.

### Git Providers

**Bitbucket Cloud** — Requires a repository-level Personal Access Token (PAT) per repo.
- Go to: Bitbucket → Repository Settings → Access Tokens
- Required scopes: `Pull requests: Read` + `Pull requests: Write`

**GitHub** — Requires a single fine-grained or classic Personal Access Token.
- Go to: GitHub → Settings → Developer Settings → Personal access tokens
- Required permissions: `Pull requests: Read and write`, `Contents: Read`

### AI Providers

| Provider | Setup |
|---|---|
| **OpenAI** | Enter your API key (`sk-…`). Default model: `gpt-4o` |
| **OpenAI-compatible** | Enter a Base URL. Works with vLLM, LM Studio, Together AI, Groq, Anyscale |
| **Ollama** | Enter your server address. Default: `http://localhost:11434` |

### Jira Integration *(optional)*

| Field | Description |
|---|---|
| Base URL | `https://yourcompany.atlassian.net` |
| Email | Your Atlassian account email |
| API Token | Generate at [id.atlassian.com](https://id.atlassian.com) → Security → API tokens |
| Issue Key Pattern | Regex to match issue keys in PR data (default: `[A-Z][A-Z0-9]+-\d+`) |

📖 [Full Configuration Guide →](https://vitiquest.github.io/pr-pilot/configuration.html)

---

## 🎮 Commands

Open the Command Palette (`⌘⇧P` / `Ctrl+Shift+P`) and search for **PR Pilot**:

| Command | Description |
|---|---|
| `PR Pilot: Open Settings` | Open the settings panel |
| `PR Pilot: Refresh` | Reload the PR list |
| `PR Pilot: View Files` | Load changed files for the selected PR |
| `PR Pilot: Generate AI Review` | Generate an AI code review report |
| `PR Pilot: Approve PR` | Approve the selected PR |
| `PR Pilot: Merge PR` | Merge the selected PR |
| `PR Pilot: Decline PR` | Decline / close the selected PR |
| `PR Pilot: Post Inline Comments` | Post AI-generated comments on PR lines |
| `PR Pilot: Request Changes` | Post inline comments + register a Changes Requested review |

---

## 🔒 Privacy & Security

- **Tokens & API keys** are stored exclusively in VS Code's `SecretStorage` (system keychain) — never in `settings.json` or any plain-text file
- **Skill files** contain only review instructions, not credentials
- **PR diff content** is sent to your configured AI provider for analysis — review your provider's privacy policy for sensitive codebases
- **No telemetry** is collected by this extension

---

## 📚 Documentation

Full documentation is available at **[vitiquest.github.io/pr-pilot](https://vitiquest.github.io/pr-pilot/)**:

| Guide | Description |
|---|---|
| [Installation](https://vitiquest.github.io/pr-pilot/installation.html) | Install from Marketplace, VSIX, or build from source |
| [Configuration](https://vitiquest.github.io/pr-pilot/configuration.html) | Git providers, AI settings, Jira integration |
| [Skills & Prompts](https://vitiquest.github.io/pr-pilot/skills.html) | Customise AI review behaviour per project |
| [Features](https://vitiquest.github.io/pr-pilot/features.html) | Complete feature reference |
| [Changelog](https://vitiquest.github.io/pr-pilot/changelog.html) | Version history and release notes |

---

## 🤝 Contributing

Contributions, bug reports, and feature requests are welcome!

- [Open an issue](https://github.com/vitiquest/pr-pilot/issues)
- [Submit a pull request](https://github.com/vitiquest/pr-pilot/pulls)
- [View the source](https://github.com/vitiquest/pr-pilot)

---

## 📄 License

Released under the [MIT License](https://github.com/vitiquest/pr-pilot/blob/main/LICENSE).  
© 2026 [Vitiquest](https://www.vitiquest.com)
