package app.gptdynamichook.core.channel

import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Executes operator commands inside the target process and returns JSON.
 *
 * The module is compiled once; everything afterwards is a command in a file.
 * That is the whole point: exploring an obfuscated client requires hundreds of
 * "what is inside this object" questions, and recompiling for each one is not a
 * loop anyone can work in.
 *
 * Boundaries:
 *  - Every command comes from a file only the target app (and a root operator)
 *    can write. There is no network and no cross-process surface.
 *  - [Command.maxOps] and [Command.maxDepth] bound the work so a bad request
 *    cannot spin the target process.
 *  - Nothing is persisted except the JSON result the operator asked for.
 */
object ReflectiveChannel {
    private const val MAX_DEPTH_LIMIT = 6
    private const val MAX_OPS_LIMIT = 2000
    private const val MAX_STRING = 4096

    data class Command(
        val type: String,
        val target: String? = null,
        val path: String? = null,
        val name: String? = null,
        val value: String? = null,
        val args: List<String> = emptyList(),
        val depth: Int = 2,
        val maxOps: Int = 400,
        val emitValues: Boolean = false,
        val method: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null
    )

    /** Live objects the operator has asked to keep addressable by name. */
    private val registry = LinkedHashMap<String, Any>()
    private var ops = 0

    fun register(name: String, value: Any?) {
        if (value == null) return
        if (registry.size > 64) registry.remove(registry.keys.first())
        registry[name] = value
    }

    fun execute(command: Command): JSONObject {
        ops = 0
        val out = JSONObject()
        out.put("type", command.type)
        try {
            when (command.type) {
                "list" -> out.put("registered", JSONArray(registry.keys.toList()))
                "http" -> out.put("result", httpCommand(command))
                "methods" -> out.put("result", methodList(resolve(command)))
                "fields" -> out.put("result", fieldList(resolve(command)))
                "dump" -> out.put("result", dump(resolve(command), command.depth, command.emitValues, 0))
                "get" -> out.put("result", getPath(resolve(command), command.path, command.emitValues))
                "set" -> out.put("result", setPath(resolve(command), command.path, command.value))
                "call" -> out.put("result",
                    scalar(callMethod(resolve(command), command.name, command.args), command.emitValues))
                else -> out.put("error", "unknown_type")
            }
        } catch (t: Throwable) {
            out.put("error", t.javaClass.simpleName + ": " + (t.message?.take(200) ?: ""))
        }
        out.put("ops", ops)
        return out
    }

    /**
     * Lists the runtime methods of an object or class.
     *
     * Static analysis cannot see classes that arrive through a dynamic class
     * loader, so the only way to learn a method's real signature is to ask the
     * live process.
     */
    private fun methodList(root: Any?): Any {
        val clazz = if (root is Class<*>) root else root?.javaClass
            ?: throw IllegalStateException("no_target")
        val arr = JSONArray()
        clazz.declaredMethods.take(200).forEach { m ->
            val params = m.parameterTypes.joinToString(",") { it.name }
            arr.put(m.name + "(" + params + "):" + m.returnType.name)
        }
        return arr
    }

    /** Lists the runtime fields of an object or class. */
    private fun fieldList(root: Any?): Any {
        val clazz = if (root is Class<*>) root else root?.javaClass
            ?: throw IllegalStateException("no_target")
        val arr = JSONArray()
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            c.declaredFields.take(200).forEach { f ->
                arr.put(c!!.name + "#" + f.name + ":" + f.type.name)
            }
            c = c.superclass
        }
        return arr
    }

    /**
     * Issues a request from inside the target process.
     *
     * The client authenticates with DPoP, whose signing key never leaves the
     * process, so a request forged elsewhere cannot be authenticated. Only the
     * operator supplied URL, headers and body are used; nothing is read from app
     * state.
     */
    private fun httpCommand(command: Command): Any {
        val url = command.target ?: throw IllegalStateException("target_required_as_url")
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = (command.method ?: "GET").uppercase()
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        command.headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        val body = command.body
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val result = JSONObject()
        val code = runCatching { connection.responseCode }.getOrDefault(-1)
        result.put("status", code)
        val stream = runCatching {
            if (code in 200..299) connection.inputStream else connection.errorStream
        }.getOrNull()
        val text = stream?.let { input ->
            runCatching { input.bufferedReader().use { it.readText().take(4096) } }.getOrNull()
        }
        result.put("body", text ?: "")
        runCatching { connection.disconnect() }
        return result
    }

    private fun resolve(command: Command): Any {
        command.target?.let { name ->
            registry[name]?.let { return it }
            if (name.startsWith("0x")) {
                throw IllegalStateException("address lookup is not supported")
            }
            val clazz = Class.forName(name, false, registryClassLoader())
            return clazz
        }
        throw IllegalStateException("no_target")
    }

    @Volatile private var classLoader: ClassLoader? = null

    fun bindClassLoader(loader: ClassLoader) {
        classLoader = loader
    }

    private fun registryClassLoader(): ClassLoader =
        classLoader ?: ClassLoader.getSystemClassLoader()

    // ---- read ------------------------------------------------------------

    fun dump(value: Any?, depth: Int, emitValues: Boolean, level: Int): Any {
        if (++ops > MAX_OPS_LIMIT) return "(ops_limit)"
        if (value == null) return JSONObject.NULL
        val d = depth.coerceIn(0, MAX_DEPTH_LIMIT)

        return when (value) {
            is String -> if (emitValues) value.take(MAX_STRING) else "String(len=${value.length})"
            // ByteArray is the body of every ktor response; without this branch a
            // captured payload is indistinguishable from an empty one.
            is ByteArray -> if (emitValues) {
                "ByteArray(" + value.size + ")" + String(value, Charsets.UTF_8).take(MAX_STRING)
            } else {
                "ByteArray(" + value.size + ")"
            }
            is Number, is Boolean -> value
            is Char -> value.toString()
            is Class<*> -> value.name
            is Map<*, *> -> {
                val o = JSONObject()
                var n = 0
                for ((k, v) in value) {
                    if (n++ > 64) { o.put("...", "(truncated)"); break }
                    o.put(k?.toString() ?: "null", dump(v, d, emitValues, level + 1))
                }
                o
            }
            is Iterable<*> -> {
                val a = JSONArray()
                for (item in value) {
                    if (a.length() > 64) { a.put("(truncated)"); break }
                    a.put(dump(item, d, emitValues, level + 1))
                }
                a
            }
            is Array<*> -> dump(value.toList(), d, emitValues, level)
            else -> dumpBean(value, d, emitValues, level)
        }
    }

    private fun dumpBean(value: Any, depth: Int, emitValues: Boolean, level: Int): Any {
        val fields = runCatching { collectFields(value.javaClass) }.getOrDefault(emptyList())
        if (fields.isEmpty() || level >= depth) return value.javaClass.name
        val o = JSONObject()
        o.put("__class", value.javaClass.name)
        for ((name, field) in fields) {
            if (++ops > MAX_OPS_LIMIT) { o.put("(ops)", true); break }
            val v = runCatching {
                field.isAccessible = true
                field.get(value)
            }.getOrNull()
            o.put(name, dump(v, depth, emitValues, level + 1))
        }
        return o
    }

    private fun collectFields(clazz: Class<*>): List<Pair<String, Field>> {
        val out = ArrayList<Pair<String, Field>>()
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                if (out.size >= 64) return out
                out.add(f.name to f)
            }
            c = c.superclass
        }
        return out
    }

    // ---- paths -----------------------------------------------------------

    fun getPath(root: Any?, path: String?, emitValues: Boolean): Any {
        if (path.isNullOrBlank()) return dump(root, 2, emitValues, 0)
        var current: Any? = root
        for (segment in path.split('.').filter { it.isNotEmpty() }) {
            current = step(current, segment, emitValues)
        }
        return dump(current, 2, emitValues, 0)
    }

    private fun step(current: Any?, segment: String, emitValues: Boolean): Any? = when {
        current == null -> null
        current is Map<*, *> -> current[segment]
        current is List<*> -> segment.toIntOrNull()?.let { current.getOrNull(it) }
        else -> readField(current, segment)
    }

    private fun readField(target: Any, name: String): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null && c != Any::class.java) {
            val f = c.declaredFields.firstOrNull { it.name == name }
            if (f != null) {
                f.isAccessible = true
                return f.get(target)
            }
            val getter = c.methods.firstOrNull {
                it.parameterCount == 0 && (it.name == "get" + name.replaceFirstChar { ch -> ch.uppercase() } ||
                    it.name == "is" + name.replaceFirstChar { ch -> ch.uppercase() } || it.name == name)
            }
            if (getter != null) return getter.invoke(target)
            c = c.superclass
        }
        return null
    }

    fun setPath(root: Any?, path: String?, literal: String?): Any {
        if (path.isNullOrBlank()) throw IllegalStateException("path_required")
        val segments = path.split('.').filter { it.isNotEmpty() }
        var current: Any? = root
        for (i in 0 until segments.size - 1) {
            current = step(current, segments[i], true) ?: return "path_broken"
        }
        val last = segments.last()
        val holder = current ?: return "path_broken"
        val field = findField(holder, last) ?: return "field_not_found"
        field.isAccessible = true
        val converted = convert(literal, field.type)
        field.set(holder, converted)
        return "set:$last=${describe(converted)}"
    }

    private fun findField(target: Any, name: String): Field? {
        var c: Class<*>? = target.javaClass
        while (c != null && c != Any::class.java) {
            c.declaredFields.firstOrNull { it.name == name }?.let { return it }
            c = c.superclass
        }
        return null
    }

    // ---- calls -----------------------------------------------------------

    fun callMethod(root: Any?, name: String?, argLiterals: List<String>): Any {
        if (name.isNullOrBlank()) throw IllegalStateException("name_required")
        val clazz = if (root is Class<*>) root else root?.javaClass
            ?: throw IllegalStateException("no_target")
        val candidates = clazz.declaredMethods.filter {
            it.name == name && it.parameterCount == argLiterals.size
        }
        if (candidates.isEmpty()) return "method_not_found:$name/${argLiterals.size}"
        val method: Method = candidates.first()
        method.isAccessible = true
        val args = method.parameterTypes.mapIndexed { i, t -> convert(argLiterals[i], t) }.toTypedArray()
        return method.invoke(if (root is Class<*>) null else root, *args) ?: JSONObject.NULL
    }

    // ---- conversion ------------------------------------------------------

    private fun convert(literal: String?, type: Class<*>): Any? = when {
        literal == null || literal == "null" -> null
        type == java.lang.String::class.java || type == CharSequence::class.java -> literal
        type == Integer.TYPE || type == java.lang.Integer::class.java -> literal.toIntOrNull()
        type == java.lang.Long.TYPE || type == java.lang.Long::class.java -> literal.toLongOrNull()
        type == java.lang.Boolean.TYPE || type == java.lang.Boolean::class.java -> literal.toBoolean()
        type == java.lang.Float.TYPE -> literal.toFloatOrNull()
        type == java.lang.Double.TYPE -> literal.toDoubleOrNull()
        else -> registry[literal]?.takeIf { type.isInstance(it) } ?: literal
    }

    private fun scalar(value: Any?, emitValues: Boolean): Any =
        if (value == null) JSONObject.NULL else dump(value, 1, emitValues, 0)

    private fun describe(value: Any?): String = when (value) {
        null -> "null"
        is String -> "String(len=${value.length})"
        else -> value.javaClass.simpleName
    }
}
