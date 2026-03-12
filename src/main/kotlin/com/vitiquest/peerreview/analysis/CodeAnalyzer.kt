package com.vitiquest.peerreview.analysis

/**
 * Lightweight source-code analyser.
 *
 * Extracts class/interface/object declarations and method/function signatures
 * with their exact line numbers from raw file text.
 *
 * Supports:  Kotlin, Java, Python, TypeScript/JavaScript, Go, Swift, Scala, C/C++
 * Falls back to a plain line-count summary for unknown file types.
 */
object CodeAnalyzer {

    // ── Public API ────────────────────────────────────────────────────────────

    data class MethodInfo(
        val name: String,
        val signature: String,
        val lineNumber: Int,
        val isChanged: Boolean      // true when the line falls inside a diff hunk
    )

    data class ClassInfo(
        val name: String,
        val kind: String,           // "class" | "interface" | "object" | "enum" | …
        val lineNumber: Int,
        val methods: List<MethodInfo>
    )

    data class FileAnalysis(
        val path: String,
        val language: String,
        val totalLines: Int,
        val classes: List<ClassInfo>,
        val topLevelFunctions: List<MethodInfo>,   // functions outside any class
        val changedLineNumbers: Set<Int>,
        val impactedMethods: List<MethodInfo>,      // methods whose body overlaps changed lines
        /** true when this file was not directly changed but imported by a changed file */
        val isReferenced: Boolean = false,
        /** raw import statements found in this file */
        val imports: List<String> = emptyList()
    )

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Analyse [fileContent] and cross-reference with [diffPatch] to mark
     * which methods/functions were actually touched by the PR.
     *
     * @param path       relative file path — used to determine language
     * @param fileContent full current file text
     * @param diffPatch  unified diff patch for this file (may be empty)
     */
    fun analyze(path: String, fileContent: String, diffPatch: String, isReferenced: Boolean = false): FileAnalysis {
        val lines    = fileContent.lines()
        val lang     = detectLanguage(path)
        val changed  = extractChangedLines(diffPatch)
        val imports  = extractImports(fileContent, lang)

        val (classes, topLevel) = when (lang) {
            "kotlin"     -> parseKotlinJava(lines, changed, kotlinPatterns)
            "java"       -> parseKotlinJava(lines, changed, javaPatterns)
            "python"     -> parsePython(lines, changed)
            "typescript",
            "javascript" -> parseTypeScript(lines, changed)
            "go"         -> parseGo(lines, changed)
            "swift"      -> parseSwift(lines, changed)
            "scala"      -> parseKotlinJava(lines, changed, scalaPatterns)
            "cpp", "c"   -> parseCpp(lines, changed)
            else         -> Pair(emptyList(), emptyList())
        }

        val allMethods = classes.flatMap { it.methods } + topLevel
        val impacted   = allMethods.filter { it.isChanged }

        return FileAnalysis(
            path                = path,
            language            = lang,
            totalLines          = lines.size,
            classes             = classes,
            topLevelFunctions   = topLevel,
            changedLineNumbers  = changed,
            impactedMethods     = impacted,
            isReferenced        = isReferenced,
            imports             = imports
        )
    }

    /**
     * Formats a [FileAnalysis] into a Markdown block ready to be injected
     * into the AI prompt.
     */
    fun formatForPrompt(analysis: FileAnalysis): String = buildString {
        appendLine("#### `${analysis.path}` (${analysis.language}, ${analysis.totalLines} lines)")
        appendLine()

        if (analysis.changedLineNumbers.isNotEmpty()) {
            val sorted = analysis.changedLineNumbers.sorted()
            val ranges = compactRanges(sorted)
            appendLine("**Changed lines:** $ranges")
            appendLine()
        }

        if (analysis.classes.isNotEmpty()) {
            appendLine("**Impacted classes & methods:**")
            appendLine()
            analysis.classes.forEach { cls ->
                val changedMethods = cls.methods.filter { it.isChanged }
                val marker = if (changedMethods.isNotEmpty()) "🔴" else "⬜"
                appendLine("$marker **${cls.kind} `${cls.name}`** (line ${cls.lineNumber})")
                if (changedMethods.isNotEmpty()) {
                    changedMethods.forEach { m ->
                        appendLine("  - `${m.signature}` — line **${m.lineNumber}** ⟵ changed")
                    }
                } else {
                    val allM = cls.methods
                    if (allM.isNotEmpty()) {
                        allM.forEach { m -> appendLine("  - `${m.signature}` — line ${m.lineNumber}") }
                    }
                }
                appendLine()
            }
        }

        if (analysis.topLevelFunctions.isNotEmpty()) {
            appendLine("**Top-level functions:**")
            analysis.topLevelFunctions.forEach { m ->
                val marker = if (m.isChanged) "🔴 " else ""
                appendLine("- ${marker}`${m.signature}` — line ${m.lineNumber}" +
                        if (m.isChanged) " ⟵ changed" else "")
            }
            appendLine()
        }

        if (analysis.impactedMethods.isNotEmpty()) {
            appendLine("**Summary of changed methods:** " +
                    analysis.impactedMethods.joinToString(", ") { "`${it.name}`" })
        }
    }

    // ── Diff hunk parser ──────────────────────────────────────────────────────

    /** Parses a unified diff and returns all NEW-side line numbers that were added/modified. */
    private fun extractChangedLines(patch: String): Set<Int> {
        val changed = mutableSetOf<Int>()
        if (patch.isBlank()) return changed

        var currentLine = 0
        val hunkHeader = Regex("""^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@""")

        for (line in patch.lines()) {
            val m = hunkHeader.find(line)
            if (m != null) {
                currentLine = m.groupValues[1].toIntOrNull() ?: 0
                continue
            }
            when {
                line.startsWith("+") && !line.startsWith("+++") -> {
                    changed.add(currentLine)
                    currentLine++
                }
                line.startsWith("-") && !line.startsWith("---") -> { /* skip — old side */ }
                line.startsWith("\\") -> { /* no newline marker */ }
                else -> currentLine++
            }
        }
        return changed
    }

    // ── Language detection ────────────────────────────────────────────────────

    private fun detectLanguage(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "kt", "kts"         -> "kotlin"
        "java"              -> "java"
        "py"                -> "python"
        "ts", "tsx"         -> "typescript"
        "js", "jsx", "mjs"  -> "javascript"
        "go"                -> "go"
        "swift"             -> "swift"
        "scala"             -> "scala"
        "cpp", "cc", "cxx"  -> "cpp"
        "c", "h"            -> "c"
        else                -> "unknown"
    }

    // ── Language-specific patterns ────────────────────────────────────────────

    private data class LangPatterns(
        val classRegex: Regex,
        val methodRegex: Regex,
        val classKindGroup: Int = 1,
        val classNameGroup: Int = 2,
        val methodNameGroup: Int = 1,
        val methodSigGroup: Int = 0     // 0 = full match
    )

    private val kotlinPatterns = LangPatterns(
        classRegex   = Regex("""^\s*((?:data\s+|sealed\s+|abstract\s+|open\s+)?(?:class|interface|object|enum\s+class))\s+(\w+)"""),
        methodRegex  = Regex("""^\s*(?:override\s+|open\s+|private\s+|protected\s+|internal\s+|suspend\s+)*fun\s+(\w+)\s*(\([^)]*\))(?:\s*:\s*[\w<>, ?*]+)?"""),
        classKindGroup = 1, classNameGroup = 2,
        methodNameGroup = 1, methodSigGroup = 0
    )

    private val javaPatterns = LangPatterns(
        classRegex   = Regex("""^\s*(?:public\s+|private\s+|protected\s+|abstract\s+|final\s+|static\s+)*(class|interface|enum|record)\s+(\w+)"""),
        methodRegex  = Regex("""^\s*(?:(?:public|private|protected|static|final|abstract|synchronized|native|strictfp)\s+)*[\w<>\[\]]+\s+(\w+)\s*(\([^)]*\))(?:\s*throws\s+[\w,\s]+)?\s*\{"""),
        classKindGroup = 1, classNameGroup = 2,
        methodNameGroup = 1, methodSigGroup = 0
    )

    private val scalaPatterns = LangPatterns(
        classRegex   = Regex("""^\s*(class|trait|object|case\s+class|abstract\s+class)\s+(\w+)"""),
        methodRegex  = Regex("""^\s*(?:override\s+|private\s+|protected\s+)?def\s+(\w+)\s*(\([^)]*\))?(?:\s*:\s*[\w\[\], ]+)?"""),
        classKindGroup = 1, classNameGroup = 2,
        methodNameGroup = 1, methodSigGroup = 0
    )

    // ── Generic brace-based parser (Kotlin / Java / Scala) ───────────────────

    private fun parseKotlinJava(
        lines: List<String>,
        changed: Set<Int>,
        patterns: LangPatterns
    ): Pair<List<ClassInfo>, List<MethodInfo>> {
        val classes    = mutableListOf<ClassInfo>()
        val topLevel   = mutableListOf<MethodInfo>()
        var currentClass: Triple<String, String, Int>? = null   // kind, name, startLine
        val classMethods = mutableListOf<MethodInfo>()
        var braceDepth = 0
        var classStartDepth = -1

        lines.forEachIndexed { idx, raw ->
            val lineNum = idx + 1
            val line    = raw.trimEnd()

            // Track brace depth
            braceDepth += line.count { it == '{' } - line.count { it == '}' }

            // Class / interface / object declaration
            val classMatch = patterns.classRegex.find(line)
            if (classMatch != null && currentClass == null) {
                // Save previous class if any
                currentClass?.let { (kind, name, ln) ->
                    classes.add(ClassInfo(name, kind, ln, classMethods.toList()))
                    classMethods.clear()
                }
                currentClass   = Triple(
                    classMatch.groupValues[patterns.classKindGroup].trim(),
                    classMatch.groupValues[patterns.classNameGroup],
                    lineNum
                )
                classStartDepth = braceDepth - line.count { it == '{' }
            }

            // Method / function declaration
            val methodMatch = patterns.methodRegex.find(line)
            if (methodMatch != null) {
                val name = methodMatch.groupValues[patterns.methodNameGroup]
                val sig  = methodMatch.value.trim()
                    .replace(Regex("""\s*\{.*"""), "")   // strip opening brace
                    .trim()
                val method = MethodInfo(
                    name       = name,
                    signature  = sig,
                    lineNumber = lineNum,
                    isChanged  = changed.any { it >= lineNum && it <= lineNum + 30 }
                )
                if (currentClass != null) classMethods.add(method)
                else topLevel.add(method)
            }

            // Detect end of class scope
            if (currentClass != null && braceDepth <= classStartDepth) {
                val (kind, name, ln) = currentClass!!
                classes.add(ClassInfo(name, kind, ln, classMethods.toList()))
                classMethods.clear()
                currentClass = null
                classStartDepth = -1
            }
        }

        // Flush last open class
        currentClass?.let { (kind, name, ln) ->
            classes.add(ClassInfo(name, kind, ln, classMethods.toList()))
        }

        return Pair(classes, topLevel)
    }

    // ── Python parser ─────────────────────────────────────────────────────────

    private val pyClass  = Regex("""^class\s+(\w+)""")
    private val pyMethod = Regex("""^\s+def\s+(\w+)\s*(\([^)]*\))""")
    private val pyFunc   = Regex("""^def\s+(\w+)\s*(\([^)]*\))""")

    private fun parsePython(lines: List<String>, changed: Set<Int>): Pair<List<ClassInfo>, List<MethodInfo>> {
        val classes  = mutableListOf<ClassInfo>()
        val topLevel = mutableListOf<MethodInfo>()
        var currentClass: Pair<String, Int>? = null
        val classMethods = mutableListOf<MethodInfo>()

        lines.forEachIndexed { idx, line ->
            val lineNum = idx + 1
            pyClass.find(line)?.let { m ->
                currentClass?.let { (n, l) -> classes.add(ClassInfo(n, "class", l, classMethods.toList())); classMethods.clear() }
                currentClass = m.groupValues[1] to lineNum
            }
            pyMethod.find(line)?.let { m ->
                if (currentClass != null) {
                    classMethods.add(MethodInfo(m.groupValues[1], m.value.trim(), lineNum,
                        changed.any { it in lineNum..lineNum + 20 }))
                }
            }
            pyFunc.find(line)?.let { m ->
                topLevel.add(MethodInfo(m.groupValues[1], m.value.trim(), lineNum,
                    changed.any { it in lineNum..lineNum + 20 }))
            }
        }
        currentClass?.let { (n, l) -> classes.add(ClassInfo(n, "class", l, classMethods.toList())) }
        return Pair(classes, topLevel)
    }

    // ── TypeScript / JavaScript parser ────────────────────────────────────────

    private val tsClass  = Regex("""(?:export\s+)?(?:abstract\s+)?(class|interface)\s+(\w+)""")
    private val tsMethod = Regex("""^\s*(?:(?:public|private|protected|static|async|readonly|override)\s+)*(\w+)\s*(?:<[^>]*>)?\s*\(""")
    private val tsFunc   = Regex("""^(?:export\s+)?(?:async\s+)?function\s+(\w+)\s*\(""")
    private val tsArrow  = Regex("""^(?:export\s+)?(?:const|let)\s+(\w+)\s*=\s*(?:async\s*)?\(""")

    private fun parseTypeScript(lines: List<String>, changed: Set<Int>): Pair<List<ClassInfo>, List<MethodInfo>> {
        val classes  = mutableListOf<ClassInfo>()
        val topLevel = mutableListOf<MethodInfo>()
        var currentClass: Triple<String, String, Int>? = null
        val classMethods = mutableListOf<MethodInfo>()
        var braceDepth = 0; var classDepth = -1

        lines.forEachIndexed { idx, raw ->
            val lineNum = idx + 1
            braceDepth += raw.count { it == '{' } - raw.count { it == '}' }

            tsClass.find(raw)?.let { m ->
                currentClass?.let { (k, n, l) -> classes.add(ClassInfo(n, k, l, classMethods.toList())); classMethods.clear() }
                currentClass = Triple(m.groupValues[1], m.groupValues[2], lineNum)
                classDepth   = braceDepth - raw.count { it == '{' }
            }

            if (currentClass != null) {
                tsMethod.find(raw)?.let { m ->
                    val name = m.groupValues[1]
                    if (name !in setOf("if", "for", "while", "switch", "catch")) {
                        classMethods.add(MethodInfo(name, m.value.trim(), lineNum,
                            changed.any { it in lineNum..lineNum + 25 }))
                    }
                }
            } else {
                (tsFunc.find(raw) ?: tsArrow.find(raw))?.let { m ->
                    topLevel.add(MethodInfo(m.groupValues[1], m.value.trim(), lineNum,
                        changed.any { it in lineNum..lineNum + 25 }))
                }
            }

            if (currentClass != null && braceDepth <= classDepth) {
                val (k, n, l) = currentClass!!
                classes.add(ClassInfo(n, k, l, classMethods.toList()))
                classMethods.clear(); currentClass = null; classDepth = -1
            }
        }
        currentClass?.let { (k, n, l) -> classes.add(ClassInfo(n, k, l, classMethods.toList())) }
        return Pair(classes, topLevel)
    }

    // ── Go parser ─────────────────────────────────────────────────────────────

    private val goStruct = Regex("""^type\s+(\w+)\s+struct""")
    private val goFunc   = Regex("""^func\s+(?:\(\w+\s+\*?(\w+)\)\s+)?(\w+)\s*\(""")

    private fun parseGo(lines: List<String>, changed: Set<Int>): Pair<List<ClassInfo>, List<MethodInfo>> {
        val structs  = mutableMapOf<String, MutableList<MethodInfo>>()
        val topLevel = mutableListOf<MethodInfo>()

        lines.forEachIndexed { idx, line ->
            val lineNum = idx + 1
            goStruct.find(line)?.let { structs.getOrPut(it.groupValues[1]) { mutableListOf() } }
            goFunc.find(line)?.let { m ->
                val receiver = m.groupValues[1].ifBlank { null }
                val funcName = m.groupValues[2]
                val method   = MethodInfo(funcName, m.value.trim(), lineNum,
                    changed.any { it in lineNum..lineNum + 25 })
                if (receiver != null) structs.getOrPut(receiver) { mutableListOf() }.add(method)
                else topLevel.add(method)
            }
        }
        val classes = structs.map { (name, methods) ->
            ClassInfo(name, "struct", lines.indexOfFirst { goStruct.containsMatchIn(it) && it.contains(name) } + 1, methods)
        }
        return Pair(classes, topLevel)
    }

    // ── Swift parser ──────────────────────────────────────────────────────────

    private val swiftClass  = Regex("""(?:class|struct|protocol|enum|actor)\s+(\w+)""")
    private val swiftMethod = Regex("""^\s*(?:(?:public|private|internal|open|fileprivate|static|class|override|mutating|async)\s+)*func\s+(\w+)\s*\(""")

    private fun parseSwift(lines: List<String>, changed: Set<Int>): Pair<List<ClassInfo>, List<MethodInfo>> {
        val classes  = mutableListOf<ClassInfo>()
        val topLevel = mutableListOf<MethodInfo>()
        var currentClass: Triple<String, String, Int>? = null
        val classMethods = mutableListOf<MethodInfo>()
        var depth = 0; var classDepth = -1

        lines.forEachIndexed { idx, raw ->
            val lineNum = idx + 1
            depth += raw.count { it == '{' } - raw.count { it == '}' }
            swiftClass.find(raw)?.let { m ->
                currentClass?.let { (k, n, l) -> classes.add(ClassInfo(n, k, l, classMethods.toList())); classMethods.clear() }
                currentClass = Triple(m.groupValues[0].substringBefore(" "), m.groupValues[1], lineNum)
                classDepth   = depth - raw.count { it == '{' }
            }
            swiftMethod.find(raw)?.let { m ->
                val method = MethodInfo(m.groupValues[1], m.value.trim(), lineNum,
                    changed.any { it in lineNum..lineNum + 25 })
                if (currentClass != null) classMethods.add(method) else topLevel.add(method)
            }
            if (currentClass != null && depth <= classDepth) {
                val (k, n, l) = currentClass!!
                classes.add(ClassInfo(n, k, l, classMethods.toList()))
                classMethods.clear(); currentClass = null; classDepth = -1
            }
        }
        currentClass?.let { (k, n, l) -> classes.add(ClassInfo(n, k, l, classMethods.toList())) }
        return Pair(classes, topLevel)
    }

    // ── C/C++ parser ─────────────────────────────────────────────────────────

    private val cppClass  = Regex("""(?:class|struct)\s+(\w+)\s*(?::\s*[\w:, ]+)?\s*\{""")
    private val cppMethod = Regex("""^\s*(?:(?:virtual|static|inline|explicit|override|const)\s+)*[\w:*&<>, ]+\s+(\w+)\s*\([^;]*\)\s*(?:const\s*)?(?:override\s*)?\{""")

    private fun parseCpp(lines: List<String>, changed: Set<Int>): Pair<List<ClassInfo>, List<MethodInfo>> {
        val classes  = mutableListOf<ClassInfo>()
        val topLevel = mutableListOf<MethodInfo>()
        var currentClass: Triple<String, String, Int>? = null
        val classMethods = mutableListOf<MethodInfo>()
        var depth = 0; var classDepth = -1

        lines.forEachIndexed { idx, raw ->
            val lineNum = idx + 1
            depth += raw.count { it == '{' } - raw.count { it == '}' }
            cppClass.find(raw)?.let { m ->
                currentClass?.let { (k, n, l) -> classes.add(ClassInfo(n, k, l, classMethods.toList())); classMethods.clear() }
                currentClass = Triple(m.groupValues[0].substringBefore(" "), m.groupValues[1], lineNum)
                classDepth   = depth - raw.count { it == '{' }
            }
            cppMethod.find(raw)?.let { m ->
                val method = MethodInfo(m.groupValues[1], m.value.trim().take(120), lineNum,
                    changed.any { it in lineNum..lineNum + 25 })
                if (currentClass != null) classMethods.add(method) else topLevel.add(method)
            }
            if (currentClass != null && depth <= classDepth) {
                val (k, n, l) = currentClass!!
                classes.add(ClassInfo(n, k, l, classMethods.toList()))
                classMethods.clear(); currentClass = null; classDepth = -1
            }
        }
        currentClass?.let { (k, n, l) -> classes.add(ClassInfo(n, k, l, classMethods.toList())) }
        return Pair(classes, topLevel)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Converts a sorted list of integers into a compact range string, e.g. "3-7, 12, 15-20". */
    private fun compactRanges(sorted: List<Int>): String {
        if (sorted.isEmpty()) return ""
        val ranges = mutableListOf<String>()
        var start  = sorted[0]; var end = sorted[0]
        for (i in 1 until sorted.size) {
            if (sorted[i] == end + 1) { end = sorted[i] }
            else { ranges.add(if (start == end) "$start" else "$start–$end"); start = sorted[i]; end = sorted[i] }
        }
        ranges.add(if (start == end) "$start" else "$start–$end")
        return ranges.joinToString(", ")
    }

    /**
     * Extracts raw import lines from [source] for the given [language].
     * Returns a list of fully-qualified names / paths as they appear in the source.
     */
    fun extractImports(source: String, language: String): List<String> {
        val lines = source.lines()
        return when (language) {
            "kotlin", "java", "scala" -> lines
                .filter { it.trimStart().startsWith("import ") }
                .map { it.trim().removePrefix("import ").removeSuffix(";").trim() }

            "python" -> lines
                .mapNotNull { line ->
                    val t = line.trim()
                    when {
                        t.startsWith("from ") -> t.removePrefix("from ").substringBefore(" import").trim()
                        t.startsWith("import ") -> t.removePrefix("import ").substringBefore(" as").trim()
                        else -> null
                    }
                }

            "typescript", "javascript" -> lines
                .mapNotNull { line ->
                    val t = line.trim()
                    // import ... from './path' or import './path'
                    val fromMatch = Regex("""from\s+['"]([^'"]+)['"]""").find(t)
                    val bareMatch = Regex("""^import\s+['"]([^'"]+)['"]""").find(t)
                    (fromMatch ?: bareMatch)?.groupValues?.get(1)
                }

            "go" -> {
                // import ( "pkg/path" ) or import "pkg/path"
                val single = Regex("""^import\s+"([^"]+)"""")
                val block  = Regex(""""([^"]+)"""")
                var inBlock = false
                lines.mapNotNull { line ->
                    val t = line.trim()
                    when {
                        t == "import (" -> { inBlock = true; null }
                        t == ")"        -> { inBlock = false; null }
                        inBlock         -> block.find(t)?.groupValues?.get(1)
                        else            -> single.find(t)?.groupValues?.get(1)
                    }
                }
            }

            "swift" -> lines
                .filter { it.trimStart().startsWith("import ") }
                .map { it.trim().removePrefix("import ").trim() }

            else -> emptyList()
        }
    }

    /**
     * Given the imports from a changed file, resolves which ones map to local
     * project source files under [projectRoot].
     *
     * Returns a list of relative file paths (e.g. "src/main/kotlin/com/foo/Bar.kt")
     * that exist on disk and are therefore part of the same project.
     */
    fun resolveLocalImports(
        imports: List<String>,
        language: String,
        projectRoot: String,
        alreadyIncluded: Set<String>
    ): List<String> {
        val root = java.io.File(projectRoot)
        val resolved = mutableListOf<String>()

        for (imp in imports) {
            val candidates = when (language) {
                "kotlin", "java", "scala" -> {
                    val rel = imp.replace('.', '/')
                    listOf(
                        "$rel.kt", "$rel.java", "$rel.scala",
                        "src/main/kotlin/$rel.kt",
                        "src/main/java/$rel.java",
                        "src/main/scala/$rel.scala",
                    )
                }
                "python" -> {
                    val rel = imp.replace('.', '/')
                    listOf("$rel.py", "src/$rel.py", "$rel/__init__.py")
                }
                "typescript", "javascript" -> {
                    // relative paths like ./service or ../utils/helper
                    if (imp.startsWith(".")) {
                        listOf("$imp.ts", "$imp.tsx", "$imp.js", "$imp.jsx",
                            "$imp/index.ts", "$imp/index.js")
                    } else emptyList()
                }
                "go" -> {
                    // last segment is the package dir
                    val dir = imp.substringAfterLast('/')
                    listOf(dir, "pkg/$dir", "internal/$dir")
                        .flatMap { d -> listOf("$d.go", "$d/main.go") }
                }
                else -> emptyList()
            }

            for (candidate in candidates) {
                val file = java.io.File(root, candidate)
                if (file.exists() && file.isFile) {
                    val relPath = file.relativeTo(root).path
                    if (relPath !in alreadyIncluded) {
                        resolved.add(relPath)
                        break
                    }
                }
            }
        }
        return resolved.distinct()
    }
}
