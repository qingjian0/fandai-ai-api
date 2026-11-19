package app.gptdynamichook.core.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HookResultParserTest {
    private val request = """
        okhttp3.Request POST
        url=https://chat.example.com/v1/conversations/abc?token=SECRET
        headers=[Authorization=Bearer SECRET, Content-Type=application/json, x-request-id=SECRET]
        method=POST
    """.trimIndent()

    private val response = """
        okhttp3.Response status=200
        headers=[content-type=text/event-stream, set-cookie=SECRET]
    """.trimIndent()

    @Test
    fun requestExtractsShapeHostAndHeaderNamesOnly() {
        val result = HookResultParser.parseKeyOnly(request)
        assertEquals("okhttp3.Request", result.shape)
        assertEquals("chat.example.com", result.urlHost)
        assertEquals(listOf("Authorization", "Content-Type", "x-request-id"), result.fieldNames)
        assertEquals(request.length.toLong(), result.byteLength)
    }

    @Test
    fun responseExtractsStatusAndHeaderNames() {
        val result = HookResultParser.parseKeyOnly(response)
        assertEquals("okhttp3.Response", result.shape)
        assertEquals("200", result.status)
        assertEquals(listOf("content-type", "set-cookie"), result.fieldNames)
    }

    @Test
    fun valuesNeverAppearInExtractedMetadata() {
        val nested = "okhttp3.Request POST\\nurl=https://h/SECRET\\nheaders=[Cookie=SECRET]"
        val result = HookResultParser.parseKeyOnly(nested)
        val rendered = result.toString()
        assertTrue(!rendered.contains("SECRET"))
        assertEquals(listOf("Cookie"), result.fieldNames)
    }

    @Test
    fun opaqueAndEmptyInputsYieldEmptyResult() {
        assertNull(HookResultParser.parseKeyOnly(null).shape)
        assertTrue(HookResultParser.parseKeyOnly("plain text body").fieldNames.isEmpty())
        assertNull(HookResultParser.parseKeyOnly("plain text body").shape)
    }

    @Test
    fun headerNamesAreBoundedAndDeduplicated() {
        val many = (1..40).joinToString(", ") { "h$it=v" }
        val result = HookResultParser.parseKeyOnly("okhttp3.Request\\nurl=https://a.b/\\nheaders=[$many]")
        assertEquals(HookEventCollector.MAX_FIELDS, result.fieldNames.size)
    }
}
