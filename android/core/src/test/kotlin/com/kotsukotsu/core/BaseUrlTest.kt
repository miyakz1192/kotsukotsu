package com.kotsukotsu.core

import com.kotsukotsu.core.api.normalizeBaseUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BaseUrlTest {
    @Test
    fun normalize() {
        assertEquals("http://192.168.1.10:8000/", normalizeBaseUrl(" 192.168.1.10:8000 "))
        assertEquals("http://okozukai.local:8000/", normalizeBaseUrl("http://okozukai.local:8000"))
        assertNull(normalizeBaseUrl(""))
        assertNull(normalizeBaseUrl("http://"))
    }
}
