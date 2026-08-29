package io.github.xgy.zcoderemote.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteQrSelectorTest {
    @Test
    fun `skips unrelated QR codes and preserves the first trusted payload`() {
        val expected = "https://zcode.z.ai/remote/v4?sid=test-device&hash=test%2Bhash%3D&t=1800000000000&mid=test-mid&name=DESKTOP-TEST&app_version=9.9.9"

        val parsed = RemoteQrSelector.firstValid(
            listOf(
                "plain text",
                "https://example.com/not-remote",
                expected,
                "https://zcode.z.ai/remote/v4?sid=later&hash=later&t=1800000000001",
            ),
        )

        assertEquals(expected, parsed?.original)
        assertEquals("DESKTOP-TEST", parsed?.displayName)
    }

    @Test
    fun `returns null when an image contains no trusted remote payload`() {
        assertNull(RemoteQrSelector.firstValid(listOf("plain text", "https://example.com")))
    }
}
