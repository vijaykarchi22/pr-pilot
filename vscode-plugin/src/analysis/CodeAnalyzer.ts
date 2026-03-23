/**
 * Lightweight source-code analyser.
 * Extracts class/interface declarations and method signatures with line numbers.
 * Supports: Kotlin, Java, Python, TypeScript/JavaScript, Go, Swift, Scala, C/C++
 */

export interface MethodInfo {
  name: string;
  signature: string;
  lineNumber: number;
  isChanged: boolean;
}

export interface ClassInfo {
  name: string;
  kind: string;        // "class" | "interface" | "object" | "enum" | ...
  visibility: string;  // "public" | "private" | "protected" | "internal" | "" (package-private)
  lineNumber: number;
  methods: MethodInfo[];
}

export interface FileAnalysis {
  path: string;
  language: string;
  totalLines: number;
  classes: ClassInfo[];
  topLevelFunctions: MethodInfo[];
  changedLineNumbers: Set<number>;
  impactedMethods: MethodInfo[];
  isReferenced: boolean;
  imports: string[];
}

// ── Language detection ────────────────────────────────────────────────────────

export function detectLanguage(path: string): string {
  const ext = path.split('.').pop()?.toLowerCase() ?? '';
  switch (ext) {
    case 'kt': case 'kts': return 'kotlin';
    case 'java': return 'java';
    case 'py': return 'python';
    case 'ts': case 'tsx': return 'typescript';
    case 'js': case 'jsx': case 'mjs': return 'javascript';
    case 'go': return 'go';
    case 'swift': return 'swift';
    case 'scala': return 'scala';
    case 'cpp': case 'cc': case 'cxx': return 'cpp';
    case 'c': case 'h': return 'c';
    case 'rs': return 'rust';
    case 'rb': return 'ruby';
    case 'cs': return 'csharp';
    case 'php': return 'php';
    default: return 'text';
  }
}

// ── Changed line extraction ───────────────────────────────────────────────────

export function extractChangedLines(diffPatch: string): Set<number> {
  const changed = new Set<number>();
  let currentLine = 0;

  for (const line of diffPatch.split('\n')) {
    const hunkMatch = line.match(/^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@/);
    if (hunkMatch) {
      currentLine = parseInt(hunkMatch[1], 10);
      continue;
    }
    if (line.startsWith('+') && !line.startsWith('+++')) {
      changed.add(currentLine);
      currentLine++;
    } else if (line.startsWith('-') && !line.startsWith('---')) {
      // deleted lines don't advance the new file line counter
    } else if (!line.startsWith('\\')) {
      currentLine++;
    }
  }
  return changed;
}

// ── Import extraction ─────────────────────────────────────────────────────────

export function extractImports(source: string, language: string): string[] {
  const imports: string[] = [];
  const lines = source.split('\n');

  for (const line of lines) {
    const trimmed = line.trim();
    switch (language) {
      case 'kotlin': case 'java': case 'scala': {
        const m = trimmed.match(/^import\s+([\w.]+(?:\.\*)?)/);
        if (m) imports.push(m[1]);
        break;
      }
      case 'python': {
        const m1 = trimmed.match(/^from\s+([\w.]+)\s+import/);
        const m2 = trimmed.match(/^import\s+([\w.]+)/);
        if (m1) imports.push(m1[1]);
        else if (m2) imports.push(m2[1]);
        break;
      }
      case 'typescript': case 'javascript': {
        const m = trimmed.match(/^import\s+.*from\s+['"]([^'"]+)['"]/);
        if (m) imports.push(m[1]);
        break;
      }
      case 'go': {
        const m = trimmed.match(/^"([^"]+)"/);
        if (m && trimmed.startsWith('"')) imports.push(m[1]);
        break;
      }
    }
  }
  return imports;
}

// ── Local import resolution ───────────────────────────────────────────────────

import * as path from 'path';
import * as fs from 'fs';

export function resolveLocalImports(
  imports: string[],
  language: string,
  projectRoot: string,
  alreadyIncluded: Set<string>
): string[] {
  const results: string[] = [];
  const extensions = extensionsForLanguage(language);

  for (const imp of imports) {
    if (isExternalImport(imp, language)) continue;

    const relative = imp.replace(/\./g, '/');
    for (const ext of extensions) {
      const candidate = `${relative}.${ext}`;
      const fullPath = path.join(projectRoot, candidate);
      if (!alreadyIncluded.has(candidate) && fs.existsSync(fullPath)) {
        results.push(candidate);
        break;
      }
    }
  }
  return results;
}

function extensionsForLanguage(lang: string): string[] {
  switch (lang) {
    case 'kotlin': return ['kt'];
    case 'java': return ['java'];
    case 'python': return ['py'];
    case 'typescript': return ['ts', 'tsx'];
    case 'javascript': return ['js', 'jsx', 'mjs'];
    case 'go': return ['go'];
    case 'swift': return ['swift'];
    case 'scala': return ['scala'];
    default: return [];
  }
}

function isExternalImport(imp: string, lang: string): boolean {
  if (lang === 'typescript' || lang === 'javascript') {
    return !imp.startsWith('.') && !imp.startsWith('/');
  }
  if (lang === 'kotlin') {
    return imp.startsWith('kotlin.') ||
      imp.startsWith('java.') ||
      imp.startsWith('javax.') ||
      imp.startsWith('android.') ||
      imp.startsWith('com.google.') ||
      imp.startsWith('com.fasterxml.');
  }
  if (lang === 'python') {
    const builtins = ['os', 'sys', 'io', 're', 'json', 'time', 'math', 'collections',
      'functools', 'itertools', 'typing', 'pathlib', 'datetime', 'abc', 'enum'];
    return builtins.includes(imp.split('.')[0]);
  }
  return false;
}

// ── Main analysis entry point ─────────────────────────────────────────────────

export function analyze(
  filePath: string,
  fileContent: string,
  diffPatch: string,
  isReferenced = false
): FileAnalysis {
  const lines = fileContent.split('\n');
  const lang = detectLanguage(filePath);
  const changed = extractChangedLines(diffPatch);
  const imports = extractImports(fileContent, lang);

  let classes: ClassInfo[] = [];
  let topLevelFunctions: MethodInfo[] = [];

  switch (lang) {
    case 'kotlin': case 'java': case 'scala':
      [classes, topLevelFunctions] = parseJvmStyle(lines, changed);
      break;
    case 'python':
      [classes, topLevelFunctions] = parsePython(lines, changed);
      break;
    case 'typescript': case 'javascript':
      [classes, topLevelFunctions] = parseTypeScript(lines, changed);
      break;
    case 'go':
      [classes, topLevelFunctions] = parseGo(lines, changed);
      break;
    default:
      [classes, topLevelFunctions] = [[], []];
  }

  const allMethods = [...classes.flatMap((c) => c.methods), ...topLevelFunctions];
  const impactedMethods = allMethods.filter((m) => m.isChanged);

  return {
    path: filePath,
    language: lang,
    totalLines: lines.length,
    classes,
    topLevelFunctions,
    changedLineNumbers: changed,
    impactedMethods,
    isReferenced,
    imports,
  };
}

// ── Prompt formatting ─────────────────────────────────────────────────────────

export function formatForPrompt(analysis: FileAnalysis): string {
  const lines: string[] = [];
  const prefix = analysis.isReferenced ? '⬜' : '🔴';

  lines.push(`#### \`${analysis.path}\` (${analysis.language}, ${analysis.totalLines} lines)`);
  lines.push('');

  if (analysis.changedLineNumbers.size > 0) {
    const sorted = [...analysis.changedLineNumbers].sort((a, b) => a - b);
    lines.push(`**Changed lines:** ${compactRanges(sorted)}`);
  }

  if (analysis.isReferenced) {
    lines.push('**Status:** Referenced (not directly modified)');
  }

  if (analysis.classes.length > 0) {
    lines.push('');
    lines.push('**Classes/Objects:**');
    for (const cls of analysis.classes) {
      const flag = cls.methods.some((m) => m.isChanged) ? '🔴 ' : '';
      const visLabel = cls.visibility ? cls.visibility : '⚠️ no-visibility-modifier (package-private)';
      lines.push(`  - ${flag}${cls.kind} \`${cls.name}\` [${visLabel}] (line ${cls.lineNumber})`);
      for (const method of cls.methods) {
        const mFlag = method.isChanged ? ' ← changed' : '';
        lines.push(`    - \`${method.signature}\` (line ${method.lineNumber})${mFlag}`);
      }
    }
  }

  if (analysis.topLevelFunctions.length > 0) {
    lines.push('');
    lines.push('**Top-level functions:**');
    for (const fn of analysis.topLevelFunctions) {
      const flag = fn.isChanged ? ' ← changed' : '';
      lines.push(`  - \`${fn.signature}\` (line ${fn.lineNumber})${flag}`);
    }
  }

  if (analysis.impactedMethods.length > 0) {
    lines.push('');
    lines.push(`**Impacted methods (${analysis.impactedMethods.length}):** ` +
      analysis.impactedMethods.map((m) => `\`${m.name}\``).join(', '));
  }

  lines.push('');
  return lines.join('\n');
}

function compactRanges(sorted: number[]): string {
  if (sorted.length === 0) return '';
  const ranges: string[] = [];
  let start = sorted[0];
  let end = sorted[0];
  for (let i = 1; i < sorted.length; i++) {
    if (sorted[i] === end + 1) {
      end = sorted[i];
    } else {
      ranges.push(start === end ? `${start}` : `${start}–${end}`);
      start = sorted[i];
      end = sorted[i];
    }
  }
  ranges.push(start === end ? `${start}` : `${start}–${end}`);
  return ranges.join(', ');
}

// ── Language parsers ──────────────────────────────────────────────────────────

function parseJvmStyle(lines: string[], changed: Set<number>): [ClassInfo[], MethodInfo[]] {
  const classes: ClassInfo[] = [];
  const topLevel: MethodInfo[] = [];
  const classStack: Array<{ info: ClassInfo; indent: number }> = [];

  // Group 1 = modifier keywords, group 2 = class name
  const classRe = /^\s*((?:abstract|open|sealed|data|inner|inline|value|private|public|protected|internal|\s)*)(?:class|interface|object|enum(?:\s+class)?)\s+(\w+)/;
  const visibilityRe = /\b(public|private|protected|internal)\b/;
  const funRe = /^\s*((?:override|private|public|protected|internal|suspend|inline|operator|infix|\s)*)(?:fun|def|void|static\s+\w+|\w+)\s+(\w+)\s*\(/;

  lines.forEach((line, idx) => {
    const lineNum = idx + 1;
    const isChanged = changed.has(lineNum);
    const indent = line.search(/\S/);

    // Pop classes from stack when indentation decreases
    while (classStack.length > 0 && indent <= classStack[classStack.length - 1].indent) {
      classStack.pop();
    }

    const classMatch = classRe.exec(line);
    if (classMatch) {
      const modifiers = classMatch[1] ?? '';
      const visMatch = visibilityRe.exec(modifiers);
      const info: ClassInfo = {
        name: classMatch[2],
        kind: line.trim().includes('interface') ? 'interface' :
              line.trim().includes('object') ? 'object' :
              line.trim().includes('enum') ? 'enum' : 'class',
        visibility: visMatch ? visMatch[1] : '',
        lineNumber: lineNum,
        methods: [],
      };
      classes.push(info);
      classStack.push({ info, indent });
      return;
    }

    const funMatch = funRe.exec(line);
    if (funMatch) {
      const sig = line.trim().replace(/\s*\{.*$/, '').trim();
      const method: MethodInfo = {
        name: funMatch[2],
        signature: sig.slice(0, 80),
        lineNumber: lineNum,
        isChanged,
      };
      if (classStack.length > 0) {
        classStack[classStack.length - 1].info.methods.push(method);
      } else {
        topLevel.push(method);
      }
    }
  });

  return [classes, topLevel];
}

function parsePython(lines: string[], changed: Set<number>): [ClassInfo[], MethodInfo[]] {
  const classes: ClassInfo[] = [];
  const topLevel: MethodInfo[] = [];
  const classRe = /^class\s+(\w+)/;
  const defRe = /^(\s*)def\s+(\w+)\s*\(/;

  let currentClass: ClassInfo | undefined;
  let classIndent = 0;

  lines.forEach((line, idx) => {
    const lineNum = idx + 1;
    const isChanged = changed.has(lineNum);

    const classMatch = classRe.exec(line);
    if (classMatch) {
      currentClass = { name: classMatch[1], kind: 'class', visibility: '', lineNumber: lineNum, methods: [] };
      classes.push(currentClass);
      classIndent = 0;
      return;
    }

    const defMatch = defRe.exec(line);
    if (defMatch) {
      const indent = defMatch[1].length;
      const sig = line.trim().replace(/:\s*$/, '').trim();
      const method: MethodInfo = {
        name: defMatch[2],
        signature: sig.slice(0, 80),
        lineNumber: lineNum,
        isChanged,
      };
      if (currentClass && indent > classIndent) {
        currentClass.methods.push(method);
      } else {
        currentClass = undefined;
        topLevel.push(method);
      }
    }
  });

  return [classes, topLevel];
}

function parseTypeScript(lines: string[], changed: Set<number>): [ClassInfo[], MethodInfo[]] {
  const classes: ClassInfo[] = [];
  const topLevel: MethodInfo[] = [];

  const classRe = /^\s*(?:export\s+)?(?:abstract\s+)?(?:class|interface|enum)\s+(\w+)/;
  const fnRe = /^\s*(?:export\s+)?(?:async\s+)?(?:function\s+(\w+)|(?:const|let|var)\s+(\w+)\s*=\s*(?:async\s+)?\()/;
  const methodRe = /^\s*(?:private\s+|public\s+|protected\s+|static\s+|async\s+|readonly\s+)*(\w+)\s*(?:<[^>]*>)?\s*\(/;

  let currentClass: ClassInfo | undefined;
  let braceDepth = 0;
  let classStartDepth = 0;

  lines.forEach((line, idx) => {
    const lineNum = idx + 1;
    const isChanged = changed.has(lineNum);
    const opens = (line.match(/\{/g) ?? []).length;
    const closes = (line.match(/\}/g) ?? []).length;

    const classMatch = classRe.exec(line);
    if (classMatch) {
      const visMatch = /\b(public|private|protected)\b/.exec(line);
      currentClass = {
        name: classMatch[1],
        kind: line.includes('interface') ? 'interface' : line.includes('enum') ? 'enum' : 'class',
        visibility: visMatch ? visMatch[1] : (line.includes('export') ? 'public' : ''),
        lineNumber: lineNum,
        methods: [],
      };
      classes.push(currentClass);
      classStartDepth = braceDepth + opens;
    } else if (currentClass) {
      if (braceDepth + opens <= classStartDepth && closes > 0) {
        currentClass = undefined;
      } else {
        const mMatch = methodRe.exec(line);
        if (mMatch && !line.trim().startsWith('//') && !classRe.test(line)) {
          const m: MethodInfo = {
            name: mMatch[1],
            signature: line.trim().replace(/\{.*$/, '').trim().slice(0, 80),
            lineNumber: lineNum,
            isChanged,
          };
          currentClass.methods.push(m);
        }
      }
    } else {
      const fnMatch = fnRe.exec(line);
      if (fnMatch) {
        const name = fnMatch[1] ?? fnMatch[2] ?? '(anonymous)';
        topLevel.push({
          name,
          signature: line.trim().replace(/\{.*$/, '').replace(/=>.*$/, '').trim().slice(0, 80),
          lineNumber: lineNum,
          isChanged,
        });
      }
    }

    braceDepth += opens - closes;
    if (braceDepth < 0) braceDepth = 0;
  });

  return [classes, topLevel];
}

function parseGo(lines: string[], changed: Set<number>): [ClassInfo[], MethodInfo[]] {
  const classes: ClassInfo[] = [];
  const topLevel: MethodInfo[] = [];

  const structRe = /^type\s+(\w+)\s+struct\s*\{/;
  const funcRe = /^func\s+(?:\((\w+)\s+\*?(\w+)\)\s+)?(\w+)\s*\(/;

  const structs: Map<string, ClassInfo> = new Map();
  lines.forEach((line, idx) => {
    const structMatch = structRe.exec(line);
    if (structMatch) {
      const info: ClassInfo = { name: structMatch[1], kind: 'struct', visibility: '', lineNumber: idx + 1, methods: [] };
      classes.push(info);
      structs.set(structMatch[1], info);
    }
  });

  lines.forEach((line, idx) => {
    const lineNum = idx + 1;
    const funcMatch = funcRe.exec(line);
    if (funcMatch) {
      const receiverType = funcMatch[2];
      const fnName = funcMatch[3];
      const sig = line.trim().replace(/\s*\{.*$/, '').slice(0, 80);
      const method: MethodInfo = { name: fnName, signature: sig, lineNumber: lineNum, isChanged: changed.has(lineNum) };
      const cls = receiverType ? structs.get(receiverType) : undefined;
      if (cls) {
        cls.methods.push(method);
      } else {
        topLevel.push(method);
      }
    }
  });

  return [classes, topLevel];
}
