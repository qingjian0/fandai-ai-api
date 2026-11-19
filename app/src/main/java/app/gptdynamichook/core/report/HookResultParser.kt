package app.gptdynamichook.core.report

/**
 * Extracts bounded, key-only metadata from a hooked call result.
 *
 * Names and shapes only. Header values, URLs' query strings, bodies, cookies
 * and credentials never enter the returned metadata, so a captured report
 * cannot carry request content or secrets.
 */
object HookResultParser {
    private val classPattern = Regex("^[A-Za-z_$][A-Za-z0-9_$.]*$")
    private val memberPattern = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")
    private val headerFieldPattern = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}$")
    private val hostPattern = Regex("^[A-Za-z0-9.-]+(:[0-9]{1,5})?$")
    private val authSchemePattern = Regex("^(Bearer|Basic|Digest|MAC|AWS4-HMAC-SHA256)$")

    fun parseKeyOnly(raw: String?): HookResult {
        val text = raw ?: return HookResult()
        if (text.isEmpty()) return HookResult()

        val headerIndex = text.indexOf("\nheaders=[")
        if (headerIndex <= 0) return HookResult(byteLength = text.length.toLong())

        val head = text.substring(0, headerIndex)
        val firstLine = head.substringBefore('\n')
        val headFields = head.substringAfter('\n', "")
        val className = firstLine.substringBefore(' ')
        val inlineArg = firstLine.substringAfter(' ', "").trim()
        val tail = text.substring(headerIndex + "\nheaders=[".length)

        val fieldNames = extractHeaderFieldNames(tail)
        val isRequest = headFields.contains("url=")
        val url = if (isRequest) headFields.lineSequence()
            .firstOrNull { it.startsWith("url=") }
            ?.removePrefix("url=") else null
        val method = if (isRequest) {
            val afterHeaders = tail.substringAfter(']', "").lineSequence()
                .firstOrNull { it.startsWith("method=") }
                ?.removePrefix("method=")
        } else {
            inlineArg.substringAfter("status=", "")
        }

        return HookResult(
            shape = if (classPattern.matches(className)) className else null,
            shapeClass = null,
            bodyKind = if (method != null && memberPattern.matches(method)) method else null,
            byteLength = text.length.toLong(),
            elementCount = fieldNames.size.toLong(),
            status = if (!isRequest && method != null && memberPattern.matches(method)) method else null,
            urlHost = url?.let(::extractHost),
            fieldNames = fieldNames
        )
    }

    private fun extractHost(url: String): String? {
        val authorityStart = url.indexOf("://")
        val start = if (authorityStart >= 0) authorityStart + 3 else 0
        if (start >= url.length) return null
        val host = url.substring(start)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')
        if (host.isEmpty() || host.length > 253) return null
        return if (hostPattern.matches(host)) host else null
    }

    /** Header names only, in wire order, deduplicated and bounded. */
    private fun extractHeaderFieldNames(section: String): List<String> {
        val end = section.indexOf(']')
        val inner = if (end >= 0) section.substring(0, end) else section
        return inner.split(',')
            .map { it.trim() }
            .filter { it.contains('=') }
            .map { it.substringBefore('=').trim() }
            .filter { headerFieldPattern.matches(it) && !authSchemePattern.matches(it) }
            .distinct()
            .take(HookEventCollector.MAX_FIELDS)
    }

    data class HookResult(
        val shape: String? = null,
        val shapeClass: String? = null,
        val bodyKind: String? = null,
        val byteLength: Long? = null,
        val elementCount: Long? = null,
        val status: String? = null,
        val urlHost: String? = null,
        val fieldNames: List<String> = emptyList()
    )
}
