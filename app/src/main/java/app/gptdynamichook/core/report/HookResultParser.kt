package app.gptdynamichook.core.report

/**
 * Extracts bounded, key-only metadata from the textual shape of known request
 * and response objects. Callers must never persist or log the input string.
 */
object HookResultParser {
    private val classPattern = Regex("^[A-Za-z_$][A-Za-z0-9_$.]*$")
    private val memberPattern = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")
    private val statusPattern = Regex("^[1-5][0-9]{2}$")
    private val headerFieldPattern = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}$")
    private val authSchemePattern = Regex("^(Bearer|Basic|Digest|MAC|AWS4-HMAC-SHA256)$")

    fun parseKeyOnly(raw: String?): HookResult {
        val text = raw ?: return HookResult()
        if (text.isEmpty()) return HookResult()

        val firstLine = text.substringBefore('\n')
        val className = firstLine.substringBefore(' ')
        if (!classPattern.matches(className)) return HookResult(byteLength = text.length.toLong())

        // okhttp3.Request.toString() renders URL and headers. Only host, method
        // and header names are copied to the result; values are never retained.
        val urlIndex = text.indexOf("\\nurl=")
        val headerIndex = text.indexOf("\\nheaders=[")
        if (urlIndex > 0 && headerIndex > urlIndex) {
            val method = firstLine.substringAfter(' ', "")
            val host = extractUrlHost(text.substring(urlIndex + 5, headerIndex))
            val headers = extractHeaderFieldNames(text.substring(headerIndex + 10))
            return HookResult(
                shape = className,
                shapeClass = className,
                bodyKind = method.takeIf(memberPattern::matches),
                byteLength = text.length.toLong(),
                elementCount = headers.size.toLong(),
                urlHost = host,
                fieldNames = headers
            )
        }

        // okhttp3.Response.toString() starts with "okhttp3.Response{protocol=...,
        // code=200, ...}" in some versions and "okhttp3.Response status=200" in
        // diagnostic wrappers. Parse only a three-digit status token.
        val responseHeaderIndex = text.indexOf("\\nheaders=[")
        if (responseHeaderIndex > 0) {
            val statusToken = Regex("(?:^|\\\\s)status=([0-9]{3})(?:\\\\s|$)")
                .find(firstLine)?.groupValues?.getOrNull(1)
            val headers = extractHeaderFieldNames(text.substring(responseHeaderIndex + 10))
            return HookResult(
                shape = className,
                byteLength = text.length.toLong(),
                elementCount = headers.size.toLong(),
                status = statusToken?.takeIf(statusPattern::matches),
                fieldNames = headers
            )
        }

        return HookResult(shape = className.takeIf(classPattern::matches), byteLength = text.length.toLong())
    }

    private fun extractUrlHost(section: String): String? {
        val url = section.substringBefore('\n')
        val authorityStart = url.indexOf("://")
        val start = if (authorityStart >= 0) authorityStart + 3 else 0
        if (start >= url.length) return null
        val tail = url.substring(start)
        val host = tail.substringBefore('/').substringBefore('?').substringAfterLast('@')
        if (host.length > 253) return null
        if (!Regex("^[A-Za-z0-9.-]+(:[0-9]{1,5})?$").matches(host)) return null
        return host.substringBefore(':')
    }

    private fun extractHeaderFieldNames(section: String): List<String> {
        val end = section.lastIndexOf(']')
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
