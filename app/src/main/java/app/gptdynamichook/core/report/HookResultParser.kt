package app.gptdynamichook.core.report

/**
 * Extracts a method result into bounded, key-only JSON metadata.
 *
 * Only names and shapes are extracted. Values, characters, collection elements
 * and exception messages never leave the target process, so a captured report
 * cannot carry request bodies, tokens or user content.
 */
object HookResultParser {
    private const val TYPE_NAME_PREFIX = "L"
    private val idPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$")
    private val classPattern = Regex("^[A-Za-z_$][A-Za-z0-9_$.]*$")
    private val memberPattern = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")
    private val parameterTypePattern = Regex(
        "^(boolean|byte|char|short|int|long|float|double|" +
            "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*)" +
            "(\\[\\])*$"
    )
    private val headerFieldPattern = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}$")
    private val authSchemePattern = Regex("^(Bearer|Basic|Digest|MAC|AWS4-HMAC-SHA256)$")

    fun parseKeyOnly(raw: String?): HookResult {
        val text = raw ?: return HookResult()
        if (text.isEmpty()) return HookResult()

        // --- okhttp3.Request: shape + header names only ---
        val urlIndex = text.indexOf("\nurl=")
        val headerIndex = text.indexOf("\nheaders=[")
        if (urlIndex > 0 && headerIndex > urlIndex) {
            val prefix = text.substring(0, urlIndex)
            val method = prefix.substringBefore(' ')
            val host = extractUrlHost(text.substring(urlIndex + 5, headerIndex))
            val headers = extractHeaderFieldNames(text.substring(headerIndex + 10))
            return HookResult(
                shape = if (classPattern.matches(shapeOf(text.substringBefore("\n", prefix)))) {
                    text.substringBefore("\n", prefix).substringBefore(' ')
                } else {
                    "Request"
                },
                shapeClass = if (classPattern.matches(text.substringBefore("\n", prefix))) {
                    text.substringBefore("\n", prefix)
                } else {
                    null
                },
                bodyKind = if (memberPattern.matches(method)) method else null,
                byteLength = text.length.toLong(),
                elementCount = headers.size.toLong(),
                urlHost = host,
                fieldNames = headers
            )
        }

        // --- okhttp3.Response: shape + header names only ---
        val responseHeaderIndex = text.indexOf("\nheaders=[")
        if (responseHeaderIndex > 0) {
            val prefix = text.substring(0, responseHeaderIndex).substringBefore("\n")
            val status = text.substring(0, responseHeaderIndex).substringAfter(' ', "")
            val headers = extractHeaderFieldNames(text.substring(responseHeaderIndex + 10))
            return HookResult(
                shape = if (classPattern.matches(prefix)) prefix else "Response",
                shapeClass = null,
                bodyKind = null,
                byteLength = text.length.toLong(),
                elementCount = headers.size.toLong(),
                status = if (memberPattern.matches(status)) status else null,
                fieldNames = headers
            )
        }

        return HookResult(byteLength = text.length.toLong())
    }

    private fun shapeOf(prefix: String): String = prefix.substringBefore('\n').substringBefore(' ')

    private fun extractUrlHost(section: String): String? {
        val url = section.substringBefore('\n')
        val authorityStart = url.indexOf("://")
        val start = if (authorityStart >= 0) authorityStart + 3 else 0
        if (start >= url.length) return null
        val tail = url.substring(start)
        val host = tail.substringBefore('/').substringBefore('?').substringAfterLast('@')
        if (host.length > 253) return null
        if (!Regex("^[A-Za-z0-9.-]+(:[0-9]{1,5})?$").matches(host)) return null
        return host
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
            .take(16)
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
