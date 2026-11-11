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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        selectLatestRelease(
            getText("https://api.github.com/repos/${BuildConfig.GITHUB_REPOSITORY}/releases?per_page=100"),
            BuildConfig.VERSION_NAME,
        )
    }

    internal fun selectLatestRelease(releasesJson:String,currentVersion:String):GithubRelease?{
        val releases=JsonParser.parseString(releasesJson).asJsonArray
            .map{it.asJsonObject}
            .filter{
                val tag=it["tag_name"]?.takeUnless{el->el.isJsonNull}?.asString.orEmpty()
                it["draft"]?.takeUnless{el->el.isJsonNull}?.asBoolean!=true&&
                    it["prerelease"]?.takeUnless{el->el.isJsonNull}?.asBoolean!=true&&
                    tag.startsWith("android-v")&&
                    isValidReleaseVersion(tag.removePrefix("android-v"))
            }
        val root=releases.maxWithOrNull(Comparator{a,b->compareVersions(a["tag_name"].asString.removePrefix("android-v"),b["tag_name"].asString.removePrefix("android-v"))})?:return null
        val tag=root["tag_name"].asString
        val version=tag.removePrefix("android-v")
        if(!isValidReleaseVersion(version)||compareVersions(version,currentVersion)<=0)return null
        val assets=mutableListOf<com.google.gson.JsonObject>()
        root["assets"]?.takeIf{it.isJsonArray}?.asJsonArray?.forEach{assets+=it.asJsonObject}
        val apk=assets.firstOrNull{it["name"]?.asString=="notebook-android.apk"}?:assets.firstOrNull{it["name"]?.asString.orEmpty().endsWith(".apk")}?:return null
        val apkName=apk["name"]?.asString.orEmpty()
        val apkBrowserUrl=apk["browser_download_url"]?.takeUnless{it.isJsonNull}?.asString?.trim().orEmpty()
        if(apkBrowserUrl.isEmpty())return null
        val checksum=assets.firstOrNull{it["name"]?.asString=="$apkName.sha256"}
            ?:assets.firstOrNull{it["name"]?.asString.orEmpty().endsWith(".sha256")}
            ?:return null
        val checksumBrowserUrl=checksum["browser_download_url"]?.takeUnless{it.isJsonNull}?.asString?.trim().orEmpty()
        val resolvedChecksumUrl=preferredAssetUrl(checksum["url"]?.takeUnless{it.isJsonNull}?.asString,checksumBrowserUrl).trim()
        if(resolvedChecksumUrl.isEmpty())return null
        val resolvedApkUrl=preferredAssetUrl(apk["url"]?.takeUnless{it.isJsonNull}?.asString,apkBrowserUrl).trim()
        if(resolvedApkUrl.isEmpty())return null
        val title=root["name"]?.takeUnless{it.isJsonNull}?.asString?.ifBlank{tag}?:tag
        val notes=root["body"]?.takeUnless{it.isJsonNull}?.asString.orEmpty()
        return GithubRelease(version,title,notes,resolvedApkUrl,resolvedChecksumUrl)
    }

    suspend fun cachedDownload(context:Context,release:GithubRelease):File?=withContext(Dispatchers.IO){
        if(!isValidReleaseVersion(release.version)||release.checksumUrl.isNullOrBlank())return@withContext null
        val target=targetFile(context,release);val marker=markerFile(target)
        target.takeIf{it.isFile&&marker.isFile&&parseSha256Checksum(marker.readText())==sha256(it)}
    }

    suspend fun download(context:Context,release:GithubRelease,onProgress:suspend (DownloadProgress)->Unit={}):File=withContext(Dispatchers.IO){
        require(isValidReleaseVersion(release.version)){"无效的更新版本号：${release.version}"}
        val target=targetFile(context,release)
        val partial=File(target.parentFile,"${target.name}.part")
        val checksumUrl=release.checksumUrl?.trim()?.takeIf(String::isNotEmpty)
        if(checksumUrl==null){
            partial.delete();target.delete();markerFile(target).delete()
            throw IllegalArgumentException("安装包缺少 SHA-256 校验文件，已拒绝下载")
        }
        cachedDownload(context,release)?.let{onProgress(DownloadProgress(it.length(),it.length()));return@withContext it}
        target.parentFile?.mkdirs();target.delete();markerFile(target).delete()
        downloadTo(release.apkUrl,partial,onProgress)
        val actual=sha256(partial)
        val rawChecksum=try{
            getText(checksumUrl)
        }catch(error:CancellationException){
            throw error
        }catch(error:Throwable){
            partial.delete();target.delete();markerFile(target).delete()
            throw IllegalArgumentException("无法下载安装包 SHA-256 校验文件",error)
        }
        val expected=parseSha256Checksum(rawChecksum)
        if(expected==null||expected!=actual){
            partial.delete();target.delete();markerFile(target).delete()
            throw IllegalArgumentException("安装包校验失败，请重新下载")
        }
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
    private fun connection(url:String,binary:Boolean=false)=(URL(url).openConnection() as HttpURLConnection).apply{connectTimeout=12_000;readTimeout=120_000;instanceFollowRedirects=true;setRequestProperty("Accept",if(binary)"application/octet-stream" else "application/vnd.github+json");setRequestProperty("Accept-Encoding","identity");setRequestProperty("User-Agent","Notebook-Android/${BuildConfig.VERSION_NAME}");setRequestProperty("X-GitHub-Api-Version","2022-11-28")}
    private fun getText(url:String):String=connection(url,isReleaseAssetUrl(url)).run{try{require(responseCode in 200..299){"GitHub 请求失败：HTTP $responseCode"};inputStream.bufferedReader().use{it.readText()}}finally{disconnect()}}
    private fun sha256(file:File):String{val digest=MessageDigest.getInstance("SHA-256");file.inputStream().buffered().use{input->val buffer=ByteArray(DEFAULT_BUFFER_SIZE);while(true){val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count)}};return digest.digest().joinToString(""){"%02x".format(it)}}

    private suspend fun downloadTo(url:String,file:File,onProgress:suspend (DownloadProgress)->Unit){
        val total=runCatching{probeRangeTotal(url)}.getOrNull()
        if(total!=null&&total>=1024L*1024L){downloadInRanges(url,file,total,onProgress);return}
        downloadSingleTo(url,file,onProgress)
    }

    private fun probeRangeTotal(url:String):Long?=connection(url,true).run{setRequestProperty("Range","bytes=0-0");try{if(responseCode!=HttpURLConnection.HTTP_PARTIAL)return@run null;val parsed=parseContentRange(getHeaderField("Content-Range"))?:return@run null;inputStream.use{it.read()};parsed.total}finally{disconnect()}}

    private suspend fun downloadInRanges(url:String,file:File,total:Long,onProgress:suspend (DownloadProgress)->Unit)=coroutineScope{
        if(file.isFile&&file.length()==total){onProgress(DownloadProgress(total,total));return@coroutineScope}
        val ranges=byteRanges(total);val segments=ranges.indices.map{File(file.parentFile,"${file.name}.segment-$it")};segments.zip(ranges).forEach{(segment,range)->if(segment.length()>range.last-range.first+1)segment.delete()};val progressLock=Mutex();var lastPercent=-1
        suspend fun report(){progressLock.withLock{val downloaded=segments.sumOf{it.length()}.coerceAtMost(total);val percent=(downloaded*100/total).toInt();if(percent!=lastPercent){lastPercent=percent;onProgress(DownloadProgress(downloaded,total))}}}
        report()
        ranges.mapIndexed{index,range->async(Dispatchers.IO){downloadRange(url,segments[index],range,total){report()}}}.awaitAll()
        FileOutputStream(file,false).buffered().use{output->segments.forEach{segment->segment.inputStream().buffered().use{it.copyTo(output)}}}
        require(file.length()==total){"分段下载合并长度不一致"};segments.forEach{it.delete()};onProgress(DownloadProgress(total,total))
    }

    private suspend fun downloadRange(url:String,file:File,range:LongRange,total:Long,onProgress:suspend ()->Unit){
        val expected=range.last-range.first+1;if(file.length()>expected)file.delete()
        var lastFailure:Throwable?=null
        repeat(3){attempt->try{
            val existing=file.takeIf{it.isFile}?.length()?:0L;if(existing==expected){onProgress();return}
            val start=range.first+existing
            connection(url,true).run{setRequestProperty("Range","bytes=$start-${range.last}");try{require(responseCode==HttpURLConnection.HTTP_PARTIAL){"服务器未返回分段内容：HTTP $responseCode"};val received=parseContentRange(getHeaderField("Content-Range"));require(received!=null&&received.start==start&&received.end==range.last&&received.total==total){"服务器返回了无效的 Content-Range"};inputStream.use{input->FileOutputStream(file,true).buffered().use{output->val buffer=ByteArray(64*1024);while(true){val count=input.read(buffer);if(count<0)break;output.write(buffer,0,count);onProgress()}}}}finally{disconnect()}}
            require(file.length()==expected){"分段下载长度不一致"};return
        }catch(error:Throwable){if(error is CancellationException)throw error;lastFailure=error;if(attempt<2)delay((attempt+1)*1_500L)}}
        throw lastFailure?:IllegalStateException("分段下载失败")
    }

    private suspend fun downloadSingleTo(url:String,file:File,onProgress:suspend (DownloadProgress)->Unit){
        var existing=file.takeIf{it.isFile}?.length()?:0L
        suspend fun transfer(allowRetry:Boolean){connection(url,true).run{
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

private val STRICT_RELEASE_VERSION=Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")
private val SHA256_DIGEST_PATTERN=Regex("^[0-9a-f]{64}$")

internal fun isValidReleaseVersion(version:String):Boolean=STRICT_RELEASE_VERSION.matches(version)

internal fun parseSha256Checksum(raw:String?):String?{
    val token=raw?.trim()?.lineSequence()?.firstOrNull()?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.lowercase()?:return null
    return token.takeIf{SHA256_DIGEST_PATTERN.matches(it)}
}

internal fun releaseVersion(tag:String)=tag.removePrefix("android-v").removePrefix("v")
internal fun newestVersion(versions:List<String>)=versions.filter(::isValidReleaseVersion).maxWithOrNull(Comparator(AppUpdater::compareVersions))

internal fun preferredAssetUrl(apiUrl:String?,browserUrl:String):String{
    val trustedApi=apiUrl?.takeIf(::isTrustedAssetApiUrl)
    return trustedApi?:browserUrl
}

internal fun isTrustedAssetApiUrl(candidate:String):Boolean=runCatching{val parsed=URL(candidate);parsed.protocol.equals("https",true)&&parsed.host.equals("api.github.com",true)&&parsed.path.startsWith("/repos/")&&parsed.path.contains("/releases/assets/")}.getOrDefault(false)

internal fun isReleaseAssetUrl(candidate:String):Boolean=isTrustedAssetApiUrl(candidate)||runCatching{val parsed=URL(candidate);parsed.protocol.equals("https",true)&&parsed.host.equals("github.com",true)&&parsed.path.contains("/releases/download/")}.getOrDefault(false)

internal data class ParsedContentRange(val start:Long,val end:Long,val total:Long)
internal fun parseContentRange(value:String?):ParsedContentRange?{val match=Regex("bytes (\\d+)-(\\d+)/(\\d+)",RegexOption.IGNORE_CASE).matchEntire(value.orEmpty())?:return null;val (start,end,total)=match.destructured;return ParsedContentRange(start.toLong(),end.toLong(),total.toLong()).takeIf{it.start<=it.end&&it.end<it.total}}
internal fun byteRanges(total:Long,count:Int=4):List<LongRange>{require(total>0&&count>0);val actual=minOf(total,count.toLong()).toInt();val base=total/actual;val extra=total%actual;var start=0L;return List(actual){index->val length=base+if(index<extra)1 else 0;val range=start..(start+length-1);start=range.last+1;range}}
