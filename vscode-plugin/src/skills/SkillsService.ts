import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import { Logger } from '../utils/Logger';

const DEFAULT_SKILL_FILES = [
  // Core prompts (always seeded)
  'system_prompt.md',
  'review_rules.md',
  'coding_standards.md',
  // Angular type rules
  'component_rules.md',
  'service_rules.md',
  'guard_rules.md',
  'interceptor_rules.md',
  'state_rules.md',
  // Spring Boot / JVM type rules
  'controller_rules.md',
  'java_service_rules.md',
  'repository_rules.md',
  'model_rules.md',
];

/**
 * Manages PR Pilot skill files stored under `.vscode/pr-pilot/skills/` in the workspace.
 *
 * On first use, it seeds the folder with the three default skill files bundled
 * inside the extension. Teams can commit these files and customise them per-project.
 */
export class SkillsService {
  private readonly skillsDir: string;

  constructor(private readonly context: vscode.ExtensionContext) {
    // Store skills in workspace .vscode folder, or extension global storage as fallback
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
    if (workspaceRoot) {
      this.skillsDir = path.join(workspaceRoot, '.vscode', 'pr-pilot', 'skills');
    } else {
      this.skillsDir = path.join(context.globalStorageUri.fsPath, 'skills');
    }
  }

  /** Creates the skills directory and seeds missing default files. */
  ensureSkillsExist(): void {
    try {
      fs.mkdirSync(this.skillsDir, { recursive: true });
      for (const fileName of DEFAULT_SKILL_FILES) {
        const target = path.join(this.skillsDir, fileName);
        if (!fs.existsSync(target)) {
          this.seedFromResource(fileName, target);
        }
      }
    } catch (err) {
      Logger.error(`[Skills] Could not initialise skills directory: ${err instanceof Error ? err.message : String(err)}`);
    }
  }

  /** Returns all .md files in the skills directory, sorted by name. */
  getSkillFiles(): string[] {
    this.ensureSkillsExist();
    try {
      return fs.readdirSync(this.skillsDir)
        .filter((f) => f.endsWith('.md'))
        .sort()
        .map((f) => path.join(this.skillsDir, f));
    } catch {
      return [];
    }
  }

  /** Reads a skill file by name (without .md extension). Returns empty string if not found. */
  readSkill(nameWithoutExtension: string): string {
    const filePath = path.join(this.skillsDir, `${nameWithoutExtension}.md`);
    try {
      return fs.existsSync(filePath) ? fs.readFileSync(filePath, 'utf8').trim() : '';
    } catch {
      return '';
    }
  }

  /** Writes a skill file by name (without .md extension). */
  writeSkill(nameWithoutExtension: string, content: string): void {
    this.ensureSkillsExist();
    const filePath = path.join(this.skillsDir, `${nameWithoutExtension}.md`);
    fs.writeFileSync(filePath, content, 'utf8');
  }

  /** Reads all skill files as a map of name → content. */
  readAllSkills(): Map<string, string> {
    const result = new Map<string, string>();
    for (const name of DEFAULT_SKILL_FILES.map((f) => f.replace('.md', ''))) {
      const content = this.readSkill(name);
      if (content) result.set(name, content);
    }
    return result;
  }

  /** Returns the absolute path to the skills directory. */
  getSkillsDir(): string {
    return this.skillsDir;
  }

  /** Resets a skill file to its bundled default. */
  resetToDefault(nameWithoutExtension: string): boolean {
    const fileName = `${nameWithoutExtension}.md`;
    const target = path.join(this.skillsDir, fileName);
    return this.seedFromResource(fileName, target, true);
  }

  private seedFromResource(fileName: string, target: string, overwrite = false): boolean {
    if (!overwrite && fs.existsSync(target)) return false;
    try {
      const resourcePath = path.join(this.context.extensionPath, 'resources', 'skills', fileName);
      if (fs.existsSync(resourcePath)) {
        fs.copyFileSync(resourcePath, target);
        Logger.info(`[Skills] Seeded ${fileName} → ${target}`);
        return true;
      }
    } catch (err) {
      Logger.error(`[Skills] Failed to seed ${fileName}: ${err instanceof Error ? err.message : String(err)}`);
    }
    return false;
  }
}
