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
    fun resolvesPrimitiveAndReferenceArraySignatures() {
        val method = ExecutableResolver.resolveMethod(
            Fixture::class.java.classLoader!!,
            Fixture::class.java.name,
            "arrays",
            listOf("java.lang.String[]", "int[]", "java.lang.String[][]")
        )

        assertNotNull(method)
        assertEquals(3, method!!.parameterCount)
        assertEquals("arrays", method.name)
    }

    @Test
    fun rejectsVoidArrayParameters() {
        val method = ExecutableResolver.resolveMethod(
            Fixture::class.java.classLoader!!,
            Fixture::class.java.name,
            "combine",
            listOf("void[]", "java.lang.String")
        )
        assertEquals(null, method)
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

    @Test
    fun prefersConcreteMethodOverCovariantReturnBridge() {
        val method = ExecutableResolver.resolveMethod(
            CovariantFixture::class.java.classLoader!!,
            CovariantFixture::class.java.name,
            "create",
            emptyList()
        )

        assertNotNull(method)
        assertEquals(String::class.java, method!!.returnType)
        assertEquals(false, method.isBridge)
        assertEquals(false, method.isSynthetic)
    }

    open class CovariantBase {
        @Suppress("unused")
        open fun create(): Any = Any()
    }

    class CovariantFixture : CovariantBase() {
        override fun create(): String = "concrete"
    }

    class Fixture {
        @Suppress("unused")
        fun combine(count: Int, value: String): String = "$count:$value"

        @Suppress("unused")
        fun arrays(values: Array<String>, counts: IntArray, matrix: Array<Array<String>>): Int =
            values.size + counts.size + matrix.size
    }
}
