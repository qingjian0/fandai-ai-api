package app.gptdynamichook.core.report

/** Extracts metadata without calling toString() on target-app objects. */
object HookResultParser {
    private val classPattern = Regex("^[A-Za-z_$][A-Za-z0-9_$.]*$")
    private val memberPattern = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")
    private val hostPattern = Regex("^[A-Za-z0-9.-]{1,253}$")
    private val headerPattern = Regex("^[A-Za-z0-9!#$%&'*+.^_\u0060|~-]{1,64}$")
    private val statusPattern = Regex("^[1-5][0-9]{2}$")

    /**
     * Reads only documented, value-free accessors from OkHttp Request/Response.
     * Unknown objects are represented by their class name only.
     */
    fun parseObject(value: Any?): HookResult {
        if (value == null) return HookResult()
        if (value is ByteArray) return HookResult(byteLength = value.size.toLong())

        val className = value.javaClass.name
        if (!classPattern.matches(className)) return HookResult()
        return when (className) {
            "okhttp3.Request" -> {
                val method = invokeNoArg(value, "method") as? String
                val url = invokeNoArg(value, "url")
                val host = url?.let { invokeNoArg(it, "host") as? String }
                val headers = invokeNoArg(value, "headers")
                requestMetadata(className, method, host, readHeaderNames(headers))
            }
            "okhttp3.Response" -> {
                val code = (invokeNoArg(value, "code") as? Number)?.toInt()?.toString()
                val headers = invokeNoArg(value, "headers")
                responseMetadata(className, code, readHeaderNames(headers))
            }
            else -> HookResult(shape = className)
        }
    }

    fun requestMetadata(
        className: String,
        method: String?,
        host: String?,
        headerNames: Iterable<String>
    ): HookResult {
        val safeNames = sanitizeHeaderNames(headerNames)
        return HookResult(
            shape = className.takeIf(classPattern::matches),
            shapeClass = className.takeIf(classPattern::matches),
            bodyKind = method?.takeIf(memberPattern::matches),
            elementCount = safeNames.size.toLong(),
            urlHost = host?.takeIf(hostPattern::matches),
            fieldNames = safeNames
        )
    }

    fun responseMetadata(
        className: String,
        status: String?,
        headerNames: Iterable<String>
    ): HookResult {
        val safeNames = sanitizeHeaderNames(headerNames)
        return HookResult(
            shape = className.takeIf(classPattern::matches),
            status = status?.takeIf(statusPattern::matches),
            elementCount = safeNames.size.toLong(),
            fieldNames = safeNames
        )
    }

    private fun invokeNoArg(target: Any, name: String): Any? =
        runCatching { target.javaClass.getMethod(name).invoke(target) }.getOrNull()

    private fun readHeaderNames(headers: Any?): List<String> {
        if (headers == null) return emptyList()
        val size = (invokeNoArg(headers, "size") as? Number)?.toInt()
            ?.coerceIn(0, MAX_HEADER_SCAN) ?: return emptyList()
        val nameMethod = headers.javaClass.methods.firstOrNull {
            it.name == "name" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: return emptyList()
        return (0 until size).mapNotNull { index ->
            runCatching { nameMethod.invoke(headers, index) as? String }.getOrNull()
        }
    }

    private fun sanitizeHeaderNames(names: Iterable<String>): List<String> =
        names.asSequence()
            .filter(headerPattern::matches)
            .distinct()
            .take(HookEventCollector.MAX_FIELDS)
            .toList()

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

    private const val MAX_HEADER_SCAN = 64
}
