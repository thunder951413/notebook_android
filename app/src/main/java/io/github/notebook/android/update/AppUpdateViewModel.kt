package io.github.notebook.android.update

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.notebook.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class UpdatePhase{Checking,Available,Downloading,Ready,Failed}

class AppUpdateViewModel(application:Application):AndroidViewModel(application){
    var release by mutableStateOf<GithubRelease?>(null);private set
    var phase by mutableStateOf(UpdatePhase.Checking);private set
    var progress by mutableStateOf(DownloadProgress(0,-1));private set
    var downloaded by mutableStateOf<File?>(null);private set
    var message by mutableStateOf<String?>(null);private set
    var showDialog by mutableStateOf(false)
    private var downloadJob:Job?=null
    private var checkJob:Job?=null
    private var lastCheckStartedAt:Long?=null

    init{if(BuildConfig.DEBUG)phase=UpdatePhase.Failed else checkForUpdate(force=true)}

    /** Launch and every resume, at most every [UPDATE_CHECK_INTERVAL_MS] (GitHub allows 60 anonymous API calls per hour). */
    fun checkForUpdate(force:Boolean=false){if(BuildConfig.DEBUG)return;val now=SystemClock.elapsedRealtime();if(checkJob?.isActive==true||!updateCheckDue(lastCheckStartedAt,now,force,UPDATE_CHECK_INTERVAL_MS))return;lastCheckStartedAt=now
        checkJob=viewModelScope.launch{runCatching{AppUpdater.latestRelease()}.onSuccess{available->
            val previousVersion=release?.version;release=available
            if(available!=null){
                downloaded=AppUpdater.cachedDownload(getApplication(),available)
                phase=if(downloaded!=null)UpdatePhase.Ready else UpdatePhase.Available
                message=if(downloaded!=null)"新版本已下载并校验，点击安装即可完成升级" else null
                when{
                    // Automatic update: fetch quietly on Wi-Fi, then ask once to install
                    // (Android always confirms installs of sideloaded apps).
                    downloaded==null&&onUnmeteredNetwork()->download(autoInstall=false,announce=true)
                    previousVersion!=available.version->showDialog=true
                }
            }
            else{downloaded=null;message=null}
        }.onFailure{if(release==null)phase=UpdatePhase.Failed}}
    }

    fun downloadOrInstall(){if(downloaded!=null){install();return};download(autoInstall=true,announce=false)}

    private fun download(autoInstall:Boolean,announce:Boolean){val available=release?:return;if(downloadJob?.isActive==true)return
        downloadJob=viewModelScope.launch{phase=UpdatePhase.Downloading;message="正在下载更新，可旋转屏幕或关闭窗口，任务会继续"
            runCatching{AppUpdater.download(getApplication(),available){value->withContext(Dispatchers.Main.immediate){progress=value}}}.onSuccess{downloaded=it;phase=UpdatePhase.Ready
                if(autoInstall){message="下载和校验完成，正在打开系统安装程序";install()}
                else{message="新版本已下载并校验，点击安装即可完成升级";if(announce)showDialog=true}
            }.onFailure{error->phase=UpdatePhase.Failed;message="下载中断（${error.javaClass.simpleName}）：${error.localizedMessage?:"未知错误"}。再次点击将从已下载位置继续";if(announce)showDialog=true}
        }
    }

    private fun onUnmeteredNetwork():Boolean=runCatching{
        val connectivity=getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        val capabilities=connectivity.getNetworkCapabilities(connectivity.activeNetwork)?:return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)

    fun install(){val apk=downloaded?:return;if(AppUpdater.install(getApplication(),apk))message="正在打开系统安装程序…" else message="请允许安装未知应用，返回后点击顶部升级图标继续安装"}
}

internal const val UPDATE_CHECK_INTERVAL_MS=30*60_000L

internal fun updateCheckDue(lastCheckStartedAt:Long?,now:Long,force:Boolean,cooldownMs:Long=60_000L)=force||lastCheckStartedAt==null||now-lastCheckStartedAt>=cooldownMs
