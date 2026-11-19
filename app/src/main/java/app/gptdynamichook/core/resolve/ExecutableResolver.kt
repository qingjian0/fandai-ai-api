package app.gptdynamichook.core.resolve

import java.lang.reflect.Method

/**
 * Resolves an exact method signature without installing a hook or changing
 * accessibility. Class resolution is constrained to the supplied loader.
 */
object ExecutableResolver {
    private val primitives = mapOf(
        "boolean" to Boolean::class.javaPrimitiveType!!,
        "byte" to Byte::class.javaPrimitiveType!!,
        "char" to Char::class.javaPrimitiveType!!,
        "short" to Short::class.javaPrimitiveType!!,
        "int" to Int::class.javaPrimitiveType!!,
        "long" to Long::class.javaPrimitiveType!!,
        "float" to Float::class.javaPrimitiveType!!,
        "double" to Double::class.javaPrimitiveType!!,
        "void" to Void.TYPE
    )

    fun resolveMethod(
        classLoader: ClassLoader,
        className: String,
        methodName: String,
        parameterTypeNames: List<String>
    ): Method? {
        val owner = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
            ?: return null
        val parameterTypes = parameterTypeNames.map { name ->
            primitives[name] ?: runCatching { Class.forName(name, false, classLoader) }.getOrNull()
                ?: return null
        }.toTypedArray()

        var current: Class<*>? = owner
        while (current != null) {
            val match = current.declaredMethods.firstOrNull { method ->
                method.name == methodName && method.parameterTypes.contentEquals(parameterTypes)
            }
            if (match != null) return match
            current = current.superclass
        }
        return null
    }
}
