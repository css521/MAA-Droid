package com.maadroid.app.diagnostics

import java.io.OutputStream
import java.io.Writer

/** Callers must pass short, selected facts, never serialized task/configuration objects. */
internal object DiagnosticText {
    private val credentials = Regex(
        """(?i)([\w.-]*(?:token|password|passwd|secret|api[_-]?key|authorization|cookie|口令|密码)[\w.-]*[\"']?\s*[:=]\s*)[^\r\n]*""",
    )
    private val bearer = Regex("(?i)\\b(Bearer|Basic)\\s+[^\\s,;]+")
    private val urlCredentials = Regex("(https?://)[^/\\s@]+@")
    private val jsonField = Regex("""^\s*\"[^\"]+\"\s*:""")
    private val configDump = Regex("(?i)\\b\\w*(?:config|configuration|taskparams)\\w*\\s*[=(]")

    fun clean(value: String): String {
        if (value.contains('{') || value.trimStart().startsWith('[') || jsonField.containsMatchIn(value) || configDump.containsMatchIn(value)) {
            return "[structured data omitted]"
        }
        return credentials.replace(
            urlCredentials.replace(bearer.replace(value, "$1[redacted]"), "$1[redacted]@"),
        ) { "${it.groupValues[1]}[redacted]" }
    }

    fun field(value: String, limit: Int = 512): String =
        clean(value.take(limit)).replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')

    fun utf8Prefix(value: String, limit: Int): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size <= limit) return bytes
        var end = limit
        while (end > 0 && (bytes[end].toInt() and 0xc0) == 0x80) end--
        return bytes.copyOf(end)
    }
}

/** Streams printStackTrace without constructing an unbounded StringWriter. */
internal class DiagnosticStackWriter(
    private val output: OutputStream,
    private val maxBytes: Int,
) : Writer() {
    private val line = StringBuilder()
    private var bytesWritten = 0
    private var longLine = false
    private var truncated = false
    private val marker = "\n[stack truncated at diagnostic size limit]\n".toByteArray()

    override fun write(chars: CharArray, offset: Int, length: Int) {
        for (index in offset until offset + length) {
            val char = chars[index]
            if (char == '\n') flushLine() else if (line.length < 8192) line.append(char)
            else longLine = true
        }
    }

    private fun flushLine() {
        if (!truncated) {
            val text = if (longLine) "[oversized stack line omitted]" else DiagnosticText.clean(line.toString())
            val bytes = (text + "\n").toByteArray(Charsets.UTF_8)
            if (bytesWritten + bytes.size > maxBytes - marker.size) {
                output.write(marker)
                truncated = true
            } else {
                output.write(bytes)
                bytesWritten += bytes.size
            }
        }
        line.setLength(0)
        longLine = false
    }

    override fun flush() {
        if (line.isNotEmpty() || longLine) flushLine()
        output.flush()
    }

    override fun close() = flush()
}
