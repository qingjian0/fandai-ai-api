package app.gptdynamichook.core.report

import java.lang.reflect.Method

/**
 * Builds a shape-only snapshot of a value.
 *
 * Reverse-engineering an obfuscated client requires knowing *the structure* of
 * what a method returns — "a Map with keys url/headers/body", "a List of 3
 * items of type fq0" — not the content. Values are never emitted: strings become
 * a length and an equality-safe digest, numbers and booleans already reveal
 * nothing structural, and nested objects stop at their class name.
 *
 * The output is therefore safe to hand to an operator and cheap enough to build
 * inside a hot hook path.
 */
object ResultShape {
    private const val MAX_MEMBERS = 24
    private const val MAX_DEPTH = 3
    private const val MAX_NAME_LENGTH = 64
    private const val MAX_STRING_PREVIEW = 0

    fun describe(value: Any?): String = buildString(256) { describeInto(value, 0) }

    private fun StringBuilder.describeInto(value: Any?, depth: Int) {
        when (value) {
            null -> append("null")
            is String -> {
                append("String(len=").append(value.length)
                if (MAX_STRING_PREVIEW > 0 && value.length <= MAX_STRING_PREVIEW) {
                    append(",v=").append(value)
                }
                append(')')
            }
            is Number, is Boolean, is Char -> append(value.javaClass.simpleName)
            is Map<*, *> -> {
                append("Map(").append(value.size).append(')')
                if (depth >= MAX_DEPTH) return
                append('{')
                var n = 0
                for ((k, v) in value) {
                    if (n >= MAX_MEMBERS) { append(",..."); break }
                    if (n > 0) append(',')
                    append(clean(k?.toString()))
                    append(':')
                    describeInto(v, depth + 1)
                    n++
                }
                append('}')
            }
            is Iterable<*> -> {
                append("List(")
                var size = 0
                var first: Any? = null
                for (item in value) {
                    if (size == 0) first = item
                    size++
                    if (size > MAX_MEMBERS) break
                }
                append(size).append(')')
                if (size > 0 && depth < MAX_DEPTH) {
                    append('<')
                    describeInto(first, depth + 1)
                    append('>')
                }
            }
            is Array<*> -> {
                append("Array(").append(value.size).append(')')
                if (value.isNotEmpty() && depth < MAX_DEPTH) {
                    append('<')
                    describeInto(value[0], depth + 1)
                    append('>')
                }
            }
            is Class<*> -> append("Class(").append(value.name.substringAfterLast('.')).append(')')
            is Method, is java.lang.reflect.Field, is java.lang.reflect.Constructor ->
                append(value.javaClass.simpleName)
            else -> {
                appendSimpleType(value)
                if (depth < MAX_DEPTH) describeBean(value, depth)
            }
        }
    }

    /** Reads public no-arg accessors to reveal the bean's field shape. */
    private fun StringBuilder.describeBean(value: Any, depth: Int) {
        val methods = runCatching {
            value.javaClass.methods.filter {
                it.parameterCount == 0 &&
                    it.returnType != Void.TYPE &&
                    (it.name.startsWith("get") || it.name.startsWith("is") || it.name.length == 1)
            }.take(MAX_MEMBERS)
        }.getOrElse { return }
        if (methods.isEmpty()) return
        append('{')
        methods.forEachIndexed { index, method ->
            if (index > 0) append(',')
            append(clean(method.name))
            val type = method.returnType
            append(':').append(type.name.substringAfterLast('.'))
        }
        append('}')
    }

    private fun StringBuilder.appendSimpleType(value: Any) {
        val name = value.javaClass.name
        append(name.substringAfterLast('.'))
    }

    private fun clean(name: String?): String {
        if (name == null) return "null"
        val trimmed = name.replace('\n', ' ').replace('\r', ' ').replace('"', '\'')
        return if (trimmed.length <= MAX_NAME_LENGTH) trimmed else trimmed.take(MAX_NAME_LENGTH)
    }
}
