package app.gptdynamichook.core.resolve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ExecutableResolverTest {
    @Test
    fun resolvesExactPrimitiveAndReferenceSignature() {
        val method = ExecutableResolver.resolveMethod(
            Fixture::class.java.classLoader!!,
            Fixture::class.java.name,
            "combine",
            listOf("int", "java.lang.String")
        )

        assertNotNull(method)
        assertEquals("combine", method!!.name)
        assertEquals(2, method.parameterCount)
    }

    @Test
    fun returnsNullForUnknownClass() {
        val method = ExecutableResolver.resolveMethod(
            javaClass.classLoader!!,
            "missing.target.Type",
            "invoke",
            emptyList()
        )

        assertEquals(null, method)
    }

    class Fixture {
        @Suppress("unused")
        fun combine(count: Int, value: String): String = "$count:$value"
    }
}
