package com.zaycev.libshelper.core.inventory

internal fun extractBlock(text: String, name: String): String =
    extractNamedBlocks(text, name).firstOrNull().orEmpty()

internal fun extractNamedBlocks(text: String, name: String): List<String> {
    val regex = Regex("""(?<![A-Za-z0-9_])${Regex.escape(name)}\s*\{""")
    val blocks = mutableListOf<String>()
    var searchFrom = 0
    while (true) {
        val match = regex.find(text, searchFrom) ?: break
        val open = match.range.last
        val close = matchingBrace(text, open) ?: break
        blocks += text.substring(open, close + 1)
        searchFrom = close + 1
    }
    return blocks
}

internal fun stripNamedBlocks(text: String, vararg names: String): String {
    var result = text
    for (name in names) {
        val regex = Regex("""(?<![A-Za-z0-9_])${Regex.escape(name)}\s*\{""")
        while (true) {
            val match = regex.find(result) ?: break
            val open = match.range.last
            val close = matchingBrace(result, open) ?: break
            result = result.removeRange(match.range.first, close + 1)
        }
    }
    return result
}

@Suppress("CyclomaticComplexMethod")
internal fun matchingBrace(text: String, openIndex: Int): Int? {
    var depth = 0
    var quote: Char? = null
    var escaped = false
    var lineComment = false
    var blockComment = false
    for (i in openIndex until text.length) {
        val char = text[i]
        val next = text.getOrNull(i + 1)
        if (lineComment) {
            if (char == '\n') lineComment = false
            continue
        }
        if (blockComment) {
            if (char == '*' && next == '/') blockComment = false
            continue
        }
        if (quote != null) {
            if (escaped) {
                escaped = false
                continue
            }
            if (char == '\\' && quote == '"') {
                escaped = true
                continue
            }
            if (char == quote) quote = null
            continue
        }
        if (char == '/' && next == '/') {
            lineComment = true
            continue
        }
        if (char == '/' && next == '*') {
            blockComment = true
            continue
        }
        when (char) {
            '"', '\'' -> quote = char
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return i
            }
        }
    }
    return null
}
