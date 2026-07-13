package io.github.notebook.android.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import io.github.notebook.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class GithubRelease(val version:String,val title:String,val notes:String,val apkUrl:String,val checksumUrl:String?)

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
    suspend fun download(context:Context,release:GithubRelease):File=withContext(Dispatchers.IO){
        val target=File(context.cacheDir,"updates/notebook-${release.version}.apk");target.parentFile?.mkdirs();downloadTo(release.apkUrl,target)
        release.checksumUrl?.let{url->val expected=getText(url).trim().substringBefore(' ').lowercase();val actual=MessageDigest.getInstance("SHA-256").digest(target.readBytes()).joinToString(""){"%02x".format(it)};require(expected==actual){"安装包校验失败，请重新下载"}}
        target
    }
    fun install(context:Context,apk:File):Boolean{
        if(android.os.Build.VERSION.SDK_INT>=26&&!context.packageManager.canRequestPackageInstalls()){context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return false}
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",apk);context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK));return true
    }
    internal fun compareVersions(a:String,b:String):Int{val x=a.substringBefore('-').split('.').map{it.toIntOrNull()?:0};val y=b.substringBefore('-').split('.').map{it.toIntOrNull()?:0};for(i in 0 until maxOf(x.size,y.size)){val c=(x.getOrElse(i){0}).compareTo(y.getOrElse(i){0});if(c!=0)return c};return 0}
    private fun connection(url:String)=(URL(url).openConnection() as HttpURLConnection).apply{connectTimeout=15_000;readTimeout=30_000;setRequestProperty("Accept","application/vnd.github+json");setRequestProperty("User-Agent","Notebook-Android/${BuildConfig.VERSION_NAME}")}
    private fun getJson(url:String)=JsonParser.parseString(getText(url)).asJsonObject
    private fun getText(url:String):String=connection(url).run{try{require(responseCode in 200..299){"GitHub 请求失败：HTTP $responseCode"};inputStream.bufferedReader().use{it.readText()}}finally{disconnect()}}
    private fun downloadTo(url:String,file:File){connection(url).run{instanceFollowRedirects=true;try{require(responseCode in 200..299){"下载失败：HTTP $responseCode"};inputStream.use{input->file.outputStream().use{input.copyTo(it)}}}catch(e:Throwable){file.delete();throw e}finally{disconnect()}}}
}
