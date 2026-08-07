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
        val settings=parseSyncQrConfig("""{"type":"notebook-sync","version":1,"host":"mac.local","user":"me","password":"pw"}""")
        assertEquals(22,settings.port);assertEquals("~/NotebookSync",settings.path);assertTrue(settings.fingerprint.isEmpty())
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsWrongType(){parseSyncQrConfig("""{"type":"other","version":1,"host":"x","username":"me"}""")}
}

class WebdavQrConfigTest {
    @Test fun parsesWebdavConfig(){
        val settings=parseWebdavQrConfig("""{"type":"notebook-webdav","version":1,"baseUrl":"https://dav.jianguoyun.com/dav/","username":"user@example.com","appPassword":"app-pw","remotePath":"notebook_backup","syncPassword":"sync-pw"}""")
        assertEquals("https://dav.jianguoyun.com/dav/",settings.baseUrl)
        assertEquals("user@example.com",settings.username)
        assertEquals("app-pw",settings.appPassword)
        assertEquals("notebook_backup",settings.remotePath)
        assertEquals("sync-pw",settings.syncPassword)
    }
    @Test fun parsesWebdavConfigWithoutSyncPassword(){
        val settings=parseWebdavQrConfig("""{"type":"notebook-webdav","version":1,"baseUrl":"https://dav.jianguoyun.com/dav/","username":"me","appPassword":"pw","remotePath":"dir"}""")
        assertEquals("dir",settings.remotePath);assertTrue(settings.syncPassword.isEmpty())
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsWebdavConfigWithoutAppPassword(){parseWebdavQrConfig("""{"type":"notebook-webdav","version":1,"baseUrl":"https://dav.jianguoyun.com/dav/","username":"me","remotePath":"dir"}""")}
    @Test(expected=IllegalArgumentException::class) fun rejectsWebdavConfigWithBadUrl(){parseWebdavQrConfig("""{"type":"notebook-webdav","version":1,"baseUrl":"ftp://x","username":"me","appPassword":"pw","remotePath":"dir"}""")}
    @Test(expected=IllegalArgumentException::class) fun rejectsWebdavConfigWithWrongType(){parseWebdavQrConfig("""{"type":"notebook-sync","version":1,"host":"x","username":"me"}""")}
}
