package io.github.notebook.android.update

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    init{viewModelScope.launch{runCatching{AppUpdater.latestRelease()}.onSuccess{available->
        release=available
        if(available!=null){downloaded=AppUpdater.cachedDownload(getApplication(),available);phase=if(downloaded!=null)UpdatePhase.Ready else UpdatePhase.Available;message=if(downloaded!=null)"安装包已下载并校验，可以继续安装" else null;showDialog=true}
    }.onFailure{phase=UpdatePhase.Failed}}}

    fun downloadOrInstall(){if(downloaded!=null){install();return};val available=release?:return;if(downloadJob?.isActive==true)return
        downloadJob=viewModelScope.launch{phase=UpdatePhase.Downloading;message="正在下载更新，可旋转屏幕或关闭窗口，任务会继续"
            runCatching{AppUpdater.download(getApplication(),available){value->withContext(Dispatchers.Main.immediate){progress=value}}}.onSuccess{downloaded=it;phase=UpdatePhase.Ready;message="下载和校验完成，正在打开系统安装程序";install()}.onFailure{error->phase=UpdatePhase.Failed;message="下载中断（${error.javaClass.simpleName}）：${error.localizedMessage?:"未知错误"}。再次点击将从已下载位置继续"}
        }
    }

    fun install(){val apk=downloaded?:return;if(AppUpdater.install(getApplication(),apk))message="正在打开系统安装程序…" else message="请允许安装未知应用，返回后点击顶部升级图标继续安装"}
}
