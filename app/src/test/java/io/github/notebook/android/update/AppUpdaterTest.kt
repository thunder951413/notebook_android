package io.github.notebook.android.update
import org.junit.Assert.*
import org.junit.Test
class AppUpdaterTest{
    @Test fun comparesSemanticVersions(){assertTrue(AppUpdater.compareVersions("0.2.0","0.1.9")>0);assertEquals(0,AppUpdater.compareVersions("1.0","1.0.0"));assertTrue(AppUpdater.compareVersions("1.0.0","1.0.1")<0)}
    @Test fun newestReleaseDoesNotDependOnGithubListOrder(){assertEquals("0.1.10",newestVersion(listOf("0.1.9","0.1.8","0.1.10","0.1.1")));assertEquals("0.1.10",releaseVersion("android-v0.1.10"))}
    @Test fun reportsBoundedDownloadProgress(){assertEquals(.5f,DownloadProgress(50,100).fraction!!,.001f);assertEquals(1f,DownloadProgress(120,100).fraction!!,.001f);assertNull(DownloadProgress(10,-1).fraction)}
    @Test fun foregroundChecksAreThrottledButCanBeForced(){assertTrue(updateCheckDue(null,1_000,false));assertFalse(updateCheckDue(1_000,30_000,false));assertTrue(updateCheckDue(1_000,61_000,false));assertTrue(updateCheckDue(60_999,61_000,true))}
    @Test fun splitsEveryByteIntoBalancedRanges(){val ranges=byteRanges(10);assertEquals(listOf(0L..2L,3L..5L,6L..7L,8L..9L),ranges);assertEquals((0L..9L).toList(),ranges.flatMap{it.toList()})}
    @Test fun validatesContentRange(){assertEquals(ParsedContentRange(10,19,100),parseContentRange("bytes 10-19/100"));assertNull(parseContentRange("bytes 20-10/100"));assertNull(parseContentRange("invalid"))}
}
