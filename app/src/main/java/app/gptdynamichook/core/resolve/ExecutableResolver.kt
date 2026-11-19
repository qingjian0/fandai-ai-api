package app.gptdynamichook.core.resolve

import java.lang.reflect.Array
import java.lang.reflect.Method

/**
 * Resolves an exact method signature without installing a hook or changing
 * accessibility. Class resolution is constrained to the supplied loader.
 * Parameter names accept Java source-style array suffixes (for example int[]).
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
            resolveType(classLoader, name) ?: return null
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

    private fun resolveType(classLoader: ClassLoader, name: String): Class<*>? {
        if (name.isBlank()) return null
        if (name.endsWith("[]")) {
            val componentName = name.dropLast(2)
            val component = resolveType(classLoader, componentName) ?: return null
            if (component == Void.TYPE) return null
            return runCatching { Array.newInstance(component, 0).javaClass }.getOrNull()
        }
        return primitives[name]
            ?: runCatching { Class.forName(name, false, classLoader) }.getOrNull()
    }
}
