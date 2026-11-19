package app.gptdynamichook.core.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HookResultParserTest {
    @Test
    fun requestMetadataContainsOnlyValidatedMethodHostAndHeaderNames() {
        val result = HookResultParser.requestMetadata(
            "okhttp3.Request",
            "POST",
            "chat.example.com",
            listOf("Authorization", "Content-Type", "x-request-id", "bad header", "Authorization")
        )
        assertEquals("okhttp3.Request", result.shape)
        assertEquals("chat.example.com", result.urlHost)
        assertEquals("POST", result.bodyKind)
        assertEquals(listOf("Authorization", "Content-Type", "x-request-id"), result.fieldNames)
        assertEquals(3L, result.elementCount)
    }

    @Test
    fun responseMetadataContainsOnlyStatusAndHeaderNames() {
        val result = HookResultParser.responseMetadata(
            "okhttp3.Response",
            "200",
            listOf("content-type", "set-cookie")
        )
        assertEquals("okhttp3.Response", result.shape)
        assertEquals("200", result.status)
        assertEquals(listOf("content-type", "set-cookie"), result.fieldNames)
    }

    @Test
    fun invalidMetadataIsRejected() {
        val result = HookResultParser.requestMetadata(
            "not a class",
            "POST /secret",
            "host/path?token=SECRET",
            listOf("Authorization: Bearer SECRET")
        )
        assertNull(result.shape)
        assertNull(result.bodyKind)
        assertNull(result.urlHost)
        assertTrue(result.fieldNames.isEmpty())
        assertTrue(!result.toString().contains("SECRET"))
    }

    @Test
    fun parserNeverCallsArbitraryToString() {
        val result = HookResultParser.parseObject(ExplosiveToString())
        assertEquals(ExplosiveToString::class.java.name, result.shape)
    }

    @Test
    fun byteArraysAreRepresentedByLengthOnly() {
        val result = HookResultParser.parseObject(byteArrayOf(1, 2, 3, 4))
        assertEquals(4L, result.byteLength)
        assertNull(result.shape)
    }


    @Test
    fun argumentsExposeOnlyRuntimeTypesAndCount() {
        val secret = ExplosiveToString()
        val result = HookResultParser.parseArguments(listOf("Bearer SECRET", 42, null, secret))
        assertEquals("arguments", result.shape)
        assertEquals(4L, result.elementCount)
        assertEquals(
            listOf("java.lang.String", "java.lang.Integer", "null", ExplosiveToString::class.java.name),
            result.argumentTypes
        )
        assertTrue(!result.toString().contains("SECRET"))
    }

    @Test
    fun argumentTypeListIsBounded() {
        val result = HookResultParser.parseArguments((0..40).toList())
        assertEquals(41L, result.elementCount)
        assertEquals(16, result.argumentTypes.size)
    }

    private class ExplosiveToString {
        override fun toString(): String = error("must not be called")
    }
}
