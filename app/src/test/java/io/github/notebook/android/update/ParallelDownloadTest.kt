package io.github.notebook.android.update

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,application=Application::class)
class ParallelDownloadTest {
    private val context:Context=ApplicationProvider.getApplicationContext()

    @Test fun `range capable server downloads concurrently and verifies checksum`()=runBlocking {
        val payload=ByteArray(2*1024*1024){(it%251).toByte()}
        val requests=Collections.synchronizedList(mutableListOf<String>())
        val checksum=MessageDigest.getInstance("SHA-256").digest(payload).joinToString(""){"%02x".format(it)}
        val server=MockWebServer();server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{
            if(request.path=="/checksum")return MockResponse().setBody("$checksum  notebook-android.apk\n")
            if(request.path=="/asset")return MockResponse().setResponseCode(302).addHeader("Location",server.url("/cdn"))
            val header=request.getHeader("Range").orEmpty();requests+=header;val match=Regex("bytes=(\\d+)-(\\d+)").matchEntire(header)?:error("missing range")
            val start=match.groupValues[1].toInt();val end=minOf(match.groupValues[2].toInt(),payload.lastIndex);val body=Buffer().write(payload,start,end-start+1)
            return MockResponse().setResponseCode(206).addHeader("Content-Range","bytes $start-$end/${payload.size}").setBody(body).throttleBody(8*1024,1,TimeUnit.MILLISECONDS)
        }}
        server.start()
        try{
            val progress=mutableListOf<DownloadProgress>();val file=AppUpdater.download(context,GithubRelease("test-${System.nanoTime()}","test","",server.url("/asset").toString(),server.url("/checksum").toString())){progress+=it}
            assertArrayEquals(payload,file.readBytes());assertEquals(5,requests.size);assertEquals("bytes=0-0",requests.first());assertEquals(4,requests.drop(1).distinct().size);assertEquals(payload.size.toLong(),progress.last().downloadedBytes)
        }finally{server.shutdown()}
    }

    @Test fun `server without range support safely falls back to single download`()=runBlocking {
        val payload=ByteArray(1024*1024+1){(it%239).toByte()};val assetHeaders=Collections.synchronizedList(mutableListOf<String?>())
        val checksum=MessageDigest.getInstance("SHA-256").digest(payload).joinToString(""){"%02x".format(it)};val server=MockWebServer()
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=if(request.path=="/checksum")MockResponse().setBody("$checksum  notebook-android.apk\n")else{assetHeaders+=request.getHeader("Range");MockResponse().setBody(Buffer().write(payload))}}
        server.start()
        try{
            val file=AppUpdater.download(context,GithubRelease("fallback-${System.nanoTime()}","test","",server.url("/asset").toString(),server.url("/checksum").toString()))
            assertArrayEquals(payload,file.readBytes());assertEquals(listOf("bytes=0-0",null),assetHeaders)
        }finally{server.shutdown()}
    }
}
