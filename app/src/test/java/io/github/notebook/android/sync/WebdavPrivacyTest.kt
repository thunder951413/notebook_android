package io.github.notebook.android.sync

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class WebdavPrivacyTest {
    @Test fun `matches Electron Unicode encryption vector`() {
        val cipher=WebdavPrivacyCipher("interop-key-测试🔐")
        val text="隐私正文\n表格 | 附件\n"
        val encrypted=cipher.encrypt("document:interop-page",text.toByteArray(),ByteArray(16){it.toByte()},ByteArray(12){(it+16).toByte()})
        assertEquals("vPxcbI4sRF1znlle9H8zvnxoVYzYcW406m0cI3o8b37tK7Ix7tQCc/c5AnIm",encrypted[WebdavPrivacyCipher.MARKER].asJsonObject["ciphertext"].asString)
        assertEquals(text,String(cipher.decrypt("document:interop-page",encrypted)))
    }
    @Test fun `rejects missing wrong keys tampering and record rebinding`() {
        val cipher=WebdavPrivacyCipher("test-private-key")
        val payload=JsonObject().apply{addProperty("markdown","PRIVATE BODY")}
        val first=cipher.encryptPayload("document","page-a",payload)
        assertNotEquals(first,cipher.encryptPayload("document","page-a",payload))
        assertFalse(first.toString().contains("PRIVATE BODY"))
        assertEquals(payload,cipher.decryptPayload("document","page-a",first))
        assertTrue(runCatching{cipher.decryptPayload("document","page-b",first)}.isFailure)
        assertTrue(runCatching{cipher.decryptPayload("document","page-a",first,"delete")}.isFailure)
        assertTrue(runCatching{cipher.decryptPayload("document","page-a",first,"upsert",9)}.isFailure)
        assertTrue(runCatching{WebdavPrivacyCipher("wrong").decryptPayload("document","page-a",first)}.isFailure)
        assertTrue(runCatching{WebdavPrivacyCipher("").decryptPayload("document","page-a",first)}.isFailure)
        val changed=first.deepCopy();val box=changed[WebdavPrivacyCipher.MARKER].asJsonObject
        val old=box["ciphertext"].asString;box.addProperty("ciphertext",(if(old[0]=='A')"B" else "A")+old.substring(1))
        assertTrue(runCatching{cipher.decryptPayload("document","page-a",changed)}.isFailure)
    }
}
