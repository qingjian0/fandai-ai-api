package app.gptdynamichook.hook

import dalvik.system.DexFile

/**
 * Enumerates every class name reachable from a class loader's dex elements.
 *
 * Reads `BaseDexClassLoader.pathList -> dexElements -> dexFile` reflectively.
 * Those names are AOSP-internal and may move between releases, so every step is
 * guarded and a failure yields an empty list: a sweep that cannot enumerate does
 * nothing rather than installing a partial rule set.
 */
object ClassEnumerator {
    @Volatile private var cached: List<String>? = null

    fun names(classLoader: ClassLoader): List<String> {
        cached?.let { return it }
        val result = runCatching {
            val pathListField = Class.forName("dalvik.system.BaseDexClassLoader")
                .getDeclaredField("pathList")
                .apply { isAccessible = true }
            val pathList = pathListField.get(classLoader) ?: return@runCatching emptyList<String>()
            val elementsField = pathList.javaClass.getDeclaredField("dexElements")
                .apply { isAccessible = true }
            val elements = elementsField.get(pathList) as? Array<*> ?: return@runCatching emptyList<String>()

            val out = ArrayList<String>(4096)
            for (element in elements) {
                if (element == null) continue
                val dexFile = runCatching {
                    element.javaClass.getDeclaredField("dexFile")
                        .apply { isAccessible = true }
                        .get(element) as? DexFile
                }.getOrNull() ?: continue
                val entries = runCatching { dexFile.entries() }.getOrNull() ?: continue
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement()
                    if (name.isNotEmpty()) out.add(name)
                }
            }
            out
        }.getOrElse { emptyList() }
        if (result.isNotEmpty()) cached = result
        return result
    }

    fun clear() {
        cached = null
    }
}
