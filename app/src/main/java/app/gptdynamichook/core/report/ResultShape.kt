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
    /** Runtime options: nothing here is compile-time fixed, so tuning never needs a rebuild. */
    data class Options(
        val maxMembers: Int = 24,
        val maxDepth: Int = 3,
        val maxNameLength: Int = 64,
        /** Emit the same class name for a class instead of expanding it reflectively. */
        val collapseClasses: Boolean = true
    ) {
        companion object {
            val DEFAULT = Options()
        }
    }

    private const val MAX_STRING_PREVIEW = 0

    fun describe(value: Any?, options: Options = Options.DEFAULT): String =
        buildString(256) { describeInto(value, 0, options) }

    private fun StringBuilder.describeInto(value: Any?, depth: Int, options: Options) {
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
                if (depth >= options.maxDepth) return
                append('{')
                var n = 0
                for ((k, v) in value) {
                    if (n >= options.maxMembers) { append(",..."); break }
                    if (n > 0) append(',')
                    append(clean(k?.toString(), options))
                    append(':')
                    describeInto(v, depth + 1, options)
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
                    if (size > options.maxMembers) break
                }
                append(size).append(')')
                if (size > 0 && depth < options.maxDepth) {
                    append('<')
                    describeInto(first, depth + 1, options)
                    append('>')
                }
            }
            is Array<*> -> {
                append("Array(").append(value.size).append(')')
                if (value.isNotEmpty() && depth < options.maxDepth) {
                    append('<')
                    describeInto(value[0], depth + 1, options)
                    append('>')
                }
            }
            is Class<*> -> append("Class(").append(value.name.substringAfterLast('.')).append(')')
            is Method, is java.lang.reflect.Field, is java.lang.reflect.Constructor<*> ->
                append(value.javaClass.simpleName)
            else -> {
                appendSimpleType(value)
                if (depth < options.maxDepth) describeBean(value, depth, options)
            }
        }
    }

    /** Reads public no-arg accessors to reveal the bean's field shape. */
    private fun StringBuilder.describeBean(value: Any, depth: Int, options: Options) {
        val methods = runCatching {
            value.javaClass.methods.filter {
                it.parameterCount == 0 &&
                    it.returnType != Void.TYPE &&
                    (it.name.startsWith("get") || it.name.startsWith("is") || it.name.length == 1)
            }.take(options.maxMembers)
        }.getOrElse { return }
        if (methods.isEmpty()) return
        append('{')
        methods.forEachIndexed { index, method ->
            if (index > 0) append(',')
            append(clean(method.name, options))
            val type = method.returnType
            append(':').append(type.name.substringAfterLast('.'))
        }
        append('}')
    }

    private fun StringBuilder.appendSimpleType(value: Any) {
        val name = value.javaClass.name
        append(name.substringAfterLast('.'))
    }

    private fun clean(name: String?, options: Options): String {
        if (name == null) return "null"
        val trimmed = name.replace('\n', ' ').replace('\r', ' ').replace('"', '\'')
        return if (trimmed.length <= options.maxNameLength) trimmed else trimmed.take(options.maxNameLength)
    }
}
