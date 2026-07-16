package io.github.notebook.android.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import io.github.notebook.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class GithubRelease(val version:String,val title:String,val notes:String,val apkUrl:String,val checksumUrl:String?)
data class DownloadProgress(val downloadedBytes:Long,val totalBytes:Long){val fraction:Float? get()=totalBytes.takeIf{it>0}?.let{(downloadedBytes.toDouble()/it).coerceIn(0.0,1.0).toFloat()}}

object AppUpdater {
    suspend fun latestRelease():GithubRelease?=withContext(Dispatchers.IO){
        val root=JsonParser.parseString(getText("https://api.github.com/repos/${BuildConfig.GITHUB_REPOSITORY}/releases?per_page=30")).asJsonArray.map{it.asJsonObject}.firstOrNull{it["draft"]?.asBoolean!=true&&it["prerelease"]?.asBoolean!=true&&it["tag_name"]?.asString.orEmpty().startsWith("android-v")}?:return@withContext null
        val tag=root["tag_name"]?.asString.orEmpty();val version=tag.removePrefix("android-v").removePrefix("v")
        val assets=mutableListOf<com.google.gson.JsonObject>();root["assets"]?.takeIf{it.isJsonArray}?.asJsonArray?.forEach{assets+=it.asJsonObject}
        val apk=assets.firstOrNull{it["name"].asString=="notebook-android.apk"}?:assets.firstOrNull{it["name"].asString.endsWith(".apk")}
        if(apk==null||compareVersions(version,BuildConfig.VERSION_NAME)<=0)return@withContext null
        val checksum=assets.firstOrNull{it["name"].asString.endsWith(".sha256")}
        GithubRelease(version,root["name"]?.asString?.ifBlank{tag}?:tag,root["body"]?.asString.orEmpty(),apk["browser_download_url"].asString,checksum?.get("browser_download_url")?.asString)
    }

    suspend fun cachedDownload(context:Context,release:GithubRelease):File?=withContext(Dispatchers.IO){
        val target=targetFile(context,release);val marker=markerFile(target)
        target.takeIf{it.isFile&&marker.isFile&&marker.readText().trim().equals(sha256(it),ignoreCase=true)}
    }

    suspend fun download(context:Context,release:GithubRelease,onProgress:suspend (DownloadProgress)->Unit={}):File=withContext(Dispatchers.IO){
        cachedDownload(context,release)?.let{onProgress(DownloadProgress(it.length(),it.length()));return@withContext it}
        val target=targetFile(context,release);target.parentFile?.mkdirs();target.delete();markerFile(target).delete()
        val partial=File(target.parentFile,"${target.name}.part")
        downloadTo(release.apkUrl,partial,onProgress)
        val actual=sha256(partial)
        release.checksumUrl?.let{url->val expected=getText(url).trim().substringBefore(' ').lowercase();if(expected!=actual){partial.delete();throw IllegalArgumentException("安装包校验失败，请重新下载")}}
        if(!partial.renameTo(target)){partial.copyTo(target,overwrite=true);partial.delete()}
        markerFile(target).writeText(actual)
        target
    }

    fun install(context:Context,apk:File):Boolean{
        if(android.os.Build.VERSION.SDK_INT>=26&&!context.packageManager.canRequestPackageInstalls()){context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return false}
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",apk);context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK));return true
    }

    internal fun compareVersions(a:String,b:String):Int{val x=a.substringBefore('-').split('.').map{it.toIntOrNull()?:0};val y=b.substringBefore('-').split('.').map{it.toIntOrNull()?:0};for(i in 0 until maxOf(x.size,y.size)){val c=(x.getOrElse(i){0}).compareTo(y.getOrElse(i){0});if(c!=0)return c};return 0}
    private fun targetFile(context:Context,release:GithubRelease)=File(context.cacheDir,"updates/notebook-${release.version}.apk")
    private fun markerFile(target:File)=File(target.parentFile,"${target.name}.sha256")
    private fun connection(url:String)=(URL(url).openConnection() as HttpURLConnection).apply{connectTimeout=30_000;readTimeout=120_000;instanceFollowRedirects=true;setRequestProperty("Accept","application/octet-stream, application/vnd.github+json");setRequestProperty("User-Agent","Notebook-Android/${BuildConfig.VERSION_NAME}")}
    private fun getText(url:String):String=connection(url).run{try{require(responseCode in 200..299){"GitHub 请求失败：HTTP $responseCode"};inputStream.bufferedReader().use{it.readText()}}finally{disconnect()}}
    private fun sha256(file:File):String{val digest=MessageDigest.getInstance("SHA-256");file.inputStream().buffered().use{input->val buffer=ByteArray(DEFAULT_BUFFER_SIZE);while(true){val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count)}};return digest.digest().joinToString(""){"%02x".format(it)}}

    private suspend fun downloadTo(url:String,file:File,onProgress:suspend (DownloadProgress)->Unit){
        var existing=file.takeIf{it.isFile}?.length()?:0L
        suspend fun transfer(allowRetry:Boolean){connection(url).run{
            existing=file.takeIf{it.isFile}?.length()?:0L
            if(existing>0)setRequestProperty("Range","bytes=$existing-")
            try{
                val code=responseCode
                if(code==416&&allowRetry){disconnect();file.delete();existing=0;transfer(false);return}
                require(code in 200..299){"下载失败：HTTP $code"}
                val append=code==HttpURLConnection.HTTP_PARTIAL&&existing>0
                if(!append)existing=0
                val content=contentLengthLong
                val total=if(content>0)existing+content else -1L
                var downloaded=existing;var lastPercent=-1
                onProgress(DownloadProgress(downloaded,total))
                inputStream.use{input->FileOutputStream(file,append).buffered().use{output->
                    val buffer=ByteArray(64*1024)
                    while(true){val count=input.read(buffer);if(count<0)break;output.write(buffer,0,count);downloaded+=count
                        val percent=if(total>0)(downloaded*100/total).toInt() else -1
                        if(percent!=lastPercent){lastPercent=percent;onProgress(DownloadProgress(downloaded,total))}
                    }
                }}
                onProgress(DownloadProgress(downloaded,total.takeIf{it>0}?:downloaded))
            }finally{disconnect()}
        }}
        var lastFailure:Throwable?=null
        repeat(3){attempt->try{transfer(true);return}catch(error:Throwable){if(error is CancellationException)throw error;lastFailure=error;if(attempt<2)delay((attempt+1)*1_500L)}}
        throw lastFailure?:IllegalStateException("下载失败")
    }
}
