package io.github.notebook.android.update
import org.junit.Assert.*
import org.junit.Test
class AppUpdaterTest{
    @Test fun comparesSemanticVersions(){assertTrue(AppUpdater.compareVersions("0.2.0","0.1.9")>0);assertEquals(0,AppUpdater.compareVersions("1.0","1.0.0"));assertTrue(AppUpdater.compareVersions("1.0.0","1.0.1")<0)}
}
