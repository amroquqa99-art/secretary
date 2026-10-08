package com.alsekretary.app.domain

sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Bullet(val text: String, val checked: Boolean? = null) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val language: String?, val code: String) : MarkdownBlock
    data class Math(val expression: String) : MarkdownBlock
    data class Table(val rows: List<List<String>>) : MarkdownBlock
}

object MarkdownParser {
    fun parse(markdown: String): List<MarkdownBlock> {
        val lines = markdown.replace("\r\n", "\n").split("\n")
        val out = mutableListOf<MarkdownBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("```")) {
                val lang = line.removePrefix("```").trim().ifBlank { null }
                val buffer = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].startsWith("```")) { buffer += lines[i]; i++ }
                out += MarkdownBlock.Code(lang, buffer.joinToString("\n"))
            } else if (line.trim() == "$$") {
                val buffer = mutableListOf<String>()
                i++
                while (i < lines.size && lines[i].trim() != "$$") { buffer += lines[i]; i++ }
                out += MarkdownBlock.Math(buffer.joinToString("\n"))
            } else if (line.startsWith("#")) {
                val level = line.takeWhile { it == '#' }.length.coerceIn(1, 6)
                out += MarkdownBlock.Heading(level, line.drop(level).trim())
            } else if (line.matches(Regex("^- \\[[ xX]] .*"))) {
                val checked = line.startsWith("- [x]", true)
                out += MarkdownBlock.Bullet(line.drop(6), checked)
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                out += MarkdownBlock.Bullet(line.drop(2))
            } else if (line.startsWith("> ")) {
                out += MarkdownBlock.Quote(line.drop(2))
            } else if (line.contains("|") && i + 1 < lines.size && lines[i + 1].matches(Regex("^\\s*\\|?\\s*:?-{3,}.*"))) {
                val rows = mutableListOf<List<String>>()
                fun cells(s: String) = s.trim().trim('|').split('|').map { it.trim() }
                rows += cells(line)
                i += 2
                while (i < lines.size && lines[i].contains("|")) { rows += cells(lines[i]); i++ }
                out += MarkdownBlock.Table(rows)
                continue
            } else if (line.isNotBlank()) {
                out += MarkdownBlock.Paragraph(line)
            }
            i++
        }
        return out
    }
}
