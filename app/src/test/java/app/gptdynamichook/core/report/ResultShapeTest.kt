package app.gptdynamichook.core.report

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultShapeTest {
    @Test
    fun mapReportsKeysAndValueTypesWithoutValues() {
        val shape = ResultShape.describe(mapOf("url" to "https://secret.example/a", "n" to 3))
        assertTrue(shape.startsWith("Map(2)"))
        assertTrue(shape.contains("url:String(len=24)"))
        assertTrue(shape.contains("n:Integer"))
        assertFalse(shape.contains("secret"))
    }

    @Test
    fun listReportsSizeAndElementType() {
        val shape = ResultShape.describe(listOf("a" to 1, "b" to 2))
        assertTrue(shape.contains("List(2)"))
        assertTrue(shape.contains("<"))
    }

    @Test
    fun beanReportsClassNameOnly() {
        val shape = ResultShape.describe(Sample())
        assertTrue(shape.contains("Sample"))
        assertTrue(shape.contains("getUrl:String") || shape.contains("Sample"))
        assertFalse(shape.contains("https://"))
    }

    @Test
    fun nullAndScalarsAreSafe() {
        assertTrue(ResultShape.describe(null) == "null")
        assertTrue(ResultShape.describe("abc") == "String(len=3)")
        assertTrue(ResultShape.describe(42) == "Integer")
        assertTrue(ResultShape.describe(true) == "Boolean")
    }

    @Test
    fun deepNestingIsBounded() {
        var nested: Any? = "leaf"
        repeat(10) { nested = mapOf("k$it" to nested) }
        val shape = ResultShape.describe(nested)
        assertTrue(shape.length < 2000)
    }

    @Test
    fun throwingAccessorDoesNotBreakShape() {
        assertTrue(ResultShape.describe(Broken()).isNotEmpty())
    }

    private class Sample {
        fun getUrl(): String = "https://x.y"
        fun isReady(): Boolean = true
    }

    private class Broken {
        fun getBoom(): String = throw IllegalStateException("nope")
    }
}
