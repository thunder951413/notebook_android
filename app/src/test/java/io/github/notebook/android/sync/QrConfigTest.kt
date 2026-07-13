package io.github.notebook.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrConfigTest {
    @Test fun parsesVersionOneConfig(){
        val settings=parseSyncQrConfig("""{"type":"notebook-sync","version":1,"host":"192.168.1.8","port":2222,"username":"me","password":"pw","path":"/sync","fingerprint":"SHA256:abc"}""")
        assertEquals("192.168.1.8",settings.host);assertEquals(2222,settings.port);assertEquals("me",settings.username);assertEquals("pw",settings.password);assertEquals("/sync",settings.path);assertEquals("SHA256:abc",settings.fingerprint)
    }
    @Test fun allowsConfigWithoutFingerprintAndUsesDefaults(){
        val settings=parseSyncQrConfig("""{"type":"notebook-sync","version":1,"host":"mac.local","user":"me"}""")
        assertEquals(22,settings.port);assertEquals("~/NotebookSync",settings.path);assertTrue(settings.fingerprint.isEmpty())
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsWrongType(){parseSyncQrConfig("""{"type":"other","version":1,"host":"x","username":"me"}""")}
}
