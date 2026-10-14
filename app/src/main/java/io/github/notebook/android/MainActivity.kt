@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.notebook.android

import android.Manifest
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.animation.core.animate
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.notebook.android.data.NoteEntity
import io.github.notebook.android.data.NoteSummary
import io.github.notebook.android.data.TodoStepEntity
import io.github.notebook.android.data.FolderEntity
import io.github.notebook.android.data.TagEntity
import io.github.notebook.android.data.AssetEntity
import io.github.notebook.android.audio.AudioRecorder
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.notebook.android.reminder.Reminders
import io.github.notebook.android.sync.SshSettings
import io.github.notebook.android.sync.ApiSyncSettings
import io.github.notebook.android.sync.SyncBackend
import io.github.notebook.android.sync.SyncRepository
import io.github.notebook.android.sync.NoteSaveState
import io.github.notebook.android.sync.NoteRevisionSummary
import io.github.notebook.android.sync.parseSyncQrConfig
import io.github.notebook.android.sync.parseWebdavQrConfig
import io.github.notebook.android.sync.HostKeyChangedException
import io.github.notebook.android.sync.HostKeyConfirmationRequiredException
import io.github.notebook.android.sync.describeSyncError
import io.github.notebook.android.ui.NotebookTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import java.text.DateFormat
import java.util.*
import kotlin.math.roundToInt
import android.text.method.LinkMovementMethod
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.widget.TextView
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.github.notebook.android.update.AppUpdateViewModel
import io.github.notebook.android.update.DownloadProgress
import io.github.notebook.android.update.UpdatePhase
import io.github.notebook.android.diagnostics.DiagnosticLogger

class MainActivity : ComponentActivity() {
    private lateinit var repository:SyncRepository
    private val notificationTarget=mutableStateOf<String?>(null)
    private val incomingShare=mutableStateOf<IncomingShare?>(null)
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Reminders.createChannel(this)
        repository = (application as NotebookApp).repository
        notificationTarget.value=noteIdFromIntent(intent)
        if(state==null)incomingShare.value=incomingShareFromIntent(intent)
        setContent { NotebookTheme { NotebookScreen(repository,notificationTarget.value,{notificationTarget.value=null},incomingShare.value){incomingShare.value=null} } }
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);notificationTarget.value=noteIdFromIntent(intent);incomingShareFromIntent(intent)?.let{incomingShare.value=it}}
    override fun onStart(){super.onStart();repository.requestForegroundSync()}
    override fun onStop(){repository.onAppBackgrounded();super.onStop()}
}

/** Files or text handed to Notebook through "Open with" or "Share". */
internal data class IncomingShare(val uris:List<android.net.Uri>,val text:String?,val subject:String?)

internal fun incomingShareFromIntent(intent:Intent?):IncomingShare?{
    intent?:return null
    return when(intent.action){
        Intent.ACTION_VIEW->intent.data?.takeIf{it.scheme=="content"||it.scheme=="file"}?.let{IncomingShare(listOf(it),null,null)}
        Intent.ACTION_SEND->{
            @Suppress("DEPRECATION") val stream=intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            val text=intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf(String::isNotBlank)
            if(stream==null&&text==null)null else IncomingShare(listOfNotNull(stream),if(stream==null)text else null,intent.getStringExtra(Intent.EXTRA_SUBJECT))
        }
        Intent.ACTION_SEND_MULTIPLE->{
            @Suppress("DEPRECATION") val streams=intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM).orEmpty()
            streams.takeIf{it.isNotEmpty()}?.let{IncomingShare(it,null,null)}
        }
        else->null
    }
}

/** Only app-local page links are accepted; every resulting id is still authorized in NotebookScreen. */
internal fun noteIdFromIntent(intent:Intent?):String?{
    intent?.getStringExtra("noteId")?.takeIf(String::isNotBlank)?.let{return it}
    return noteIdFromAppLink(intent?.dataString)
}

internal fun noteIdFromAppLink(rawUri:String?):String?{
    val uri=runCatching{rawUri?.let { java.net.URI(it) }}.getOrNull()?:return null
    val path=uri.path?.removePrefix("/")?.takeIf(String::isNotBlank)?:return null
    return path.takeIf{uri.scheme=="notebook"&&uri.host=="page"&&!it.contains('/')}
}

/** A single policy used by lists, notifications, references and deep links. */
internal fun canExposeNote(note:NoteSummary?,encryptedUnlocked:Boolean,isEncrypted:(NoteSummary)->Boolean):Boolean=
    note!=null&&(!isEncrypted(note)||encryptedUnlocked)

internal fun visibleReferenceNotes(notes:List<NoteSummary>,currentId:String,encryptedUnlocked:Boolean,isEncrypted:(NoteSummary)->Boolean):List<NoteSummary> =
    notes.filter{it.id!=currentId&&it.deletedAt==null&&it.itemType=="note"&&canExposeNote(it,encryptedUnlocked,isEncrypted)}

internal enum class Destination(val title:String,val newItemType:String?="note") { Today("今天","todo"), Important("重要","todo"), Todos("全部待办","todo"), Completed("已完成",null), All("全部笔记"), Unfiled("未分类"), Conflicts("同步冲突"), Trash("回收站",null) }

internal fun newItemDraft(destination:Destination,folder:FolderEntity?,folderId:String?,tagId:String?,endOfToday:Long,id:String=UUID.randomUUID().toString()):NoteEntity {
    val itemType=when(folder?.type){"todoList"->"todo";"noteFolder","encryptedFolder"->"note";else->destination.newItemType?:"note"}
    return NoteEntity(id,itemType=itemType,important=itemType=="todo"&&destination==Destination.Important,dueAt=endOfToday.takeIf{itemType=="todo"&&destination==Destination.Today},folderId=folderId,folderName=folder?.name?:"未分类",tagIds=tagId.orEmpty(),viewMode="text")
}

internal fun swipeDeleteTargetOffset(offsetPx:Float,actionWidthPx:Float,velocityPxPerSecond:Float):Float {
    if(actionWidthPx<=0f)return 0f
    val shouldOpen=velocityPxPerSecond< -500f||
        (velocityPxPerSecond<=500f&&offsetPx<=-actionWidthPx*.35f)
    return if(shouldOpen)-actionWidthPx else 0f
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun NotebookScreen(repo:SyncRepository,notificationNoteId:String?=null,onNotificationConsumed:()->Unit={},incoming:IncomingShare?=null,onIncomingHandled:()->Unit={}) {
    val all by repo.notes.collectAsState(initial=emptyList())
    val folders by repo.folders.collectAsState(initial=emptyList())
    val tags by repo.tags.collectAsState(initial=emptyList())
    val saveError by repo.saveError.collectAsState()
    val scope=rememberCoroutineScope(); val context=LocalContext.current
    val focusManager=androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val drawer=rememberDrawerState(DrawerValue.Closed)
    var destination by rememberSaveable{mutableStateOf(Destination.All)}
    var folderId by rememberSaveable{mutableStateOf<String?>(null)}
    var tagId by rememberSaveable{mutableStateOf<String?>(null)};var manage by remember{mutableStateOf<String?>(null)}
    var query by rememberSaveable{mutableStateOf("")};var matchingNoteIds by remember{mutableStateOf<Set<String>?>(null)}; var editing by remember{mutableStateOf<NoteEntity?>(null)};var startInEditMode by rememberSaveable{mutableStateOf(false)};var loadingNoteId by remember{mutableStateOf<String?>(null)}
    val homeHolder=androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var activeDocumentId by rememberSaveable{mutableStateOf<String?>(null)}
    LaunchedEffect(Unit){if(activeDocumentId!=null&&editing==null){repo.flushAll();val restored=repo.loadNote(activeDocumentId!!);if(restored!=null&&!repo.isEncrypted(restored))editing=restored else{activeDocumentId=null;folderId=null;startInEditMode=false}}}
    LaunchedEffect(editing?.id){editing?.let{activeDocumentId=it.id;NoteNavigationHistory(context).visited(it.id)}}
    var encryptedUnlocked by remember{mutableStateOf(false)}
    var cardOrigin by remember{mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)}
    var predictiveProgress by remember{mutableFloatStateOf(0f)}
    val documentStack=remember{mutableListOf<Pair<String,Boolean>>()}
    val editorSurfaces=rememberSaveable(saver=EditorSurfaceStatesSaver){mutableMapOf<String,EditorSurfaceState>()}
    fun surfaceFor(id:String)=editorSurfaces.getOrPut(id){EditorSurfaceState()}
    fun closeDocument(){
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()){android.os.Handler(android.os.Looper.getMainLooper()).post{closeDocument()};return}
        editing?.id?.let{editorSurfaces.remove(it)}
        focusManager.clearFocus();keyboardController?.hide()
        predictiveProgress=0f
        if(documentStack.isEmpty()){editing=null;activeDocumentId=null;startInEditMode=false}
        else {val previous=documentStack.removeAt(documentStack.lastIndex);scope.launch{val note=repo.loadNote(previous.first);editing=note?.takeIf{!repo.isEncrypted(it)||encryptedUnlocked};startInEditMode=previous.second}}
    }
    var showSettings by remember{mutableStateOf(false)}; var status by remember{mutableStateOf<String?>(null)}
    var syncing by remember{mutableStateOf(false)};var changedKey by remember{mutableStateOf<HostKeyChangedException?>(null)};var newKey by remember{mutableStateOf<HostKeyConfirmationRequiredException?>(null)}
    var showAllFolders by remember{mutableStateOf(false)};var showAllTags by remember{mutableStateOf(false)}
    var pendingEncryptedFolder by remember{mutableStateOf<String?>(null)};var pendingPrivateNoteId by remember{mutableStateOf<String?>(null)};var pendingPrivateStartInEditMode by remember{mutableStateOf(false)};var deviceCredentialInProgress by remember{mutableStateOf(false)}
    val encryptedUnlocker=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->deviceCredentialInProgress=false;if(result.resultCode==android.app.Activity.RESULT_OK){encryptedUnlocked=true;pendingEncryptedFolder?.let{folderId=it;tagId=null;destination=Destination.All};pendingEncryptedFolder=null;scope.launch{drawer.close()}}else{pendingEncryptedFolder=null;pendingPrivateNoteId=null;pendingPrivateStartInEditMode=false;status="需要验证设备凭据才能查看私密内容"}}
    LaunchedEffect(folders,encryptedUnlocked){if(!encryptedUnlocked&&folders.firstOrNull{it.id==folderId}?.type=="encryptedFolder")folderId=null}
    val configuredTablet=LocalConfiguration.current.screenWidthDp>=840
    var documentTabletMode by rememberSaveable{mutableStateOf<Boolean?>(null)}
    val isTablet=if(editing!=null||activeDocumentId!=null)documentTabletMode?:configuredTablet else configuredTablet
    LaunchedEffect(editing?.id,activeDocumentId){if(editing!=null&&documentTabletMode==null)documentTabletMode=configuredTablet else if(editing==null&&activeDocumentId==null)documentTabletMode=null}
    val drawerScroll=androidx.compose.foundation.rememberScrollState()
    val appUpdate:AppUpdateViewModel=viewModel()
    val updateLifecycleOwner=LocalLifecycleOwner.current
    DisposableEffect(updateLifecycleOwner,appUpdate){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)appUpdate.checkForUpdate()};updateLifecycleOwner.lifecycle.addObserver(observer);if(updateLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))appUpdate.checkForUpdate();onDispose{updateLifecycleOwner.lifecycle.removeObserver(observer)}}
    UpdatePrompt(appUpdate)
    var pendingImport by remember{mutableStateOf<List<android.net.Uri>?>(null)}
    val textImporter=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->if(uris.isNotEmpty())pendingImport=uris}
    var showAi by remember{mutableStateOf(false)};var aiNoteId by remember{mutableStateOf<String?>(null)}
    fun lockPrivateContent(){
        encryptedUnlocked=false
        documentStack.clear()
        pendingEncryptedFolder=null
        pendingPrivateNoteId=null
        pendingPrivateStartInEditMode=false
        deviceCredentialInProgress=false
        if(editing?.let(repo::isEncrypted)==true){editing=null;activeDocumentId=null;startInEditMode=false}
        if(folders.firstOrNull{it.id==folderId}?.type=="encryptedFolder"){folderId=null;tagId=null;destination=Destination.All}
    }
    fun requestPrivateUnlock(folder:String?=null,noteId:String?=null,startEditing:Boolean=false){
        val manager=context.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        if(!manager.isDeviceSecure){status="请先在手机系统设置中设置锁屏密码或指纹";return}
        pendingEncryptedFolder=folder
        pendingPrivateNoteId=noteId
        pendingPrivateStartInEditMode=startEditing
        deviceCredentialInProgress=true
        encryptedUnlocker.launch(manager.createConfirmDeviceCredentialIntent("解锁私密笔记","验证手机锁屏密码或指纹后查看锁定内容"))
    }
    fun openNote(id:String,startEditing:Boolean=false){
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()){android.os.Handler(android.os.Looper.getMainLooper()).post{openNote(id,startEditing)};return}
        focusManager.clearFocus()
        if(loadingNoteId!=null)return
        val summary=all.firstOrNull{it.id==id}
        if(summary==null){status="笔记不存在或已被删除";return}
        if(!canExposeNote(summary,encryptedUnlocked,repo::isEncrypted)){
            requestPrivateUnlock(repo.encryptedFolderId(summary),id,startEditing)
            return
        }
        loadingNoteId=id
        val opening=editing==null&&!startEditing
        scope.launch{runCatching{repo.loadNote(id)}.onSuccess{note->
            if(note!=null&&(!repo.isEncrypted(note)||encryptedUnlocked)){
                // Read the page and its last reading position, and render the
                // Markdown off the main thread before the open motion starts, so
                // the first frame is the real page at the place it was left.
                if(opening)runCatching{prepareReader(context,repo,note)}
                editing?.takeIf{it.id!=note.id}?.let{documentStack.add(it.id to startInEditMode)};startInEditMode=startEditing;editing=note}
            else if(note!=null)requestPrivateUnlock(repo.encryptedFolderId(note),id,startEditing)
            else status="笔记不存在或已被删除"
        }.onFailure{status="无法加载笔记：${it.localizedMessage}"};loadingNoteId=null}
    }
    fun createChild(parent:NoteEntity){
        val draft=NoteEntity(UUID.randomUUID().toString(),folderId=parent.folderId,folderName=parent.folderName,parentPageId=parent.id,sortOrder=(all.filter{it.parentPageId==parent.id}.maxOfOrNull{it.sortOrder}?:0.0)+1000.0,viewMode="text")
        if(repo.isEncrypted(parent))repo.encryptedFolderId(parent)?.let{repo.markEncrypted(draft.id,it)}
        repo.markUnsaved(draft.id);documentStack.add(parent.id to false);editing=draft;startInEditMode=true
    }
    LaunchedEffect(encryptedUnlocked,pendingPrivateNoteId){
        if(encryptedUnlocked){
            val id=pendingPrivateNoteId?:return@LaunchedEffect
            val startEditing=pendingPrivateStartInEditMode
            pendingPrivateNoteId=null
            pendingPrivateStartInEditMode=false
            openNote(id,startEditing)
        }
    }
    var searchResult by remember{mutableStateOf<io.github.notebook.android.sync.NoteSearchResult?>(null)}
    LaunchedEffect(query){
        if(query.isBlank()){searchResult=null;matchingNoteIds=null}
        else{
            delay(180)
            val result=runCatching{repo.searchNotes(query,all,tags)}.getOrElse{status="搜索失败：${it.localizedMessage}";null}
            searchResult=result;matchingNoteIds=result?.ids?.toSet()?:emptySet()
            // Remember a query once the user pauses on it.
            if(result!=null&&result.ids.isNotEmpty()){delay(1_500);repo.rememberSearch(query)}
        }
    }
    LaunchedEffect(notificationNoteId,all){notificationNoteId?.let{id->
        all.firstOrNull{it.id==id}?.let{target->
            destination=if(target.itemType=="todo")Destination.Today else Destination.All
            folderId=null
            tagId=null
            openNote(id)
            onNotificationConsumed()
        }
    }}
    fun runSync(){
        if(syncing)return
        syncing=true
        scope.launch{
            changedKey=null;newKey=null;status="正在同步…"
            try{
                repo.sync{status=it}
                status=repo.syncCompletionMessage()
            }catch(error:kotlinx.coroutines.CancellationException){
                status="同步已取消";throw error
            }catch(error:Exception){
                when(error){
                    is HostKeyConfirmationRequiredException->{newKey=error;status="首次连接需要确认服务器身份"}
                    is HostKeyChangedException->{changedKey=error;status="服务器主机密钥发生变化，需要你确认"}
                    else->status="同步失败：${describeSyncError(error)}"
                }
            }finally{syncing=false}
        }
    }
    newKey?.let{key->AlertDialog(onDismissRequest={newKey=null},icon={Icon(Icons.Default.Security,null)},title={Text("确认服务器身份")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("首次连接不会自动信任服务器。请将下面的 SHA-256 指纹与桌面端或服务器管理员提供的值核对，确认一致后再继续。");Text(key.actual,style=MaterialTheme.typography.bodySmall)}},confirmButton={Button({repo.trustHostKey(key.actual);newKey=null;runSync()}){Text("指纹一致，信任并继续")}},dismissButton={TextButton({newKey=null}){Text("取消")}})}
    changedKey?.let{change->AlertDialog(onDismissRequest={changedKey=null},icon={Icon(Icons.Default.Security,null)},title={Text("服务器身份已变化")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("确认这是你的服务器后，可以信任新的主机指纹并继续同步。");Text("原指纹\n${change.expected}",style=MaterialTheme.typography.bodySmall);Text("新指纹\n${change.actual}",style=MaterialTheme.typography.bodySmall)}},confirmButton={Button({repo.trustHostKey(change.actual);changedKey=null;runSync()}){Text("信任并继续")}},dismissButton={TextButton({changedKey=null}){Text("取消")}})}

    val privacyLifecycleOwner=LocalLifecycleOwner.current
    DisposableEffect(privacyLifecycleOwner){
        val observer=LifecycleEventObserver{_,event->
            if(event==Lifecycle.Event.ON_STOP&&!deviceCredentialInProgress)lockPrivateContent()
        }
        privacyLifecycleOwner.lifecycle.addObserver(observer)
        onDispose{privacyLifecycleOwner.lifecycle.removeObserver(observer)}
    }

    pendingImport?.let{uris->ImportTargetDialog(uris.size,folders.filter{it.type=="noteFolder"||(it.type=="encryptedFolder"&&encryptedUnlocked)},folderId,editing?.takeIf{it.itemType!="todo"},{pendingImport=null}){folder,parent->
        pendingImport=null
        scope.launch{runCatching{repo.importFiles(uris,folder,parent?.id)}.onSuccess{report->status=report.message;if(report.imported.size==1&&parent==null){startInEditMode=false;editing=report.imported.first()}}.onFailure{status="导入失败：${it.localizedMessage}"}}
    }}
    incoming?.let{share->IncomingSheet(repo,share,folders.filter{it.type=="noteFolder"||(it.type=="encryptedFolder"&&encryptedUnlocked)},all.filter{it.deletedAt==null&&it.itemType!="todo"&&canExposeNote(it,encryptedUnlocked,repo::isEncrypted)},onDismiss=onIncomingHandled){inbox,folder,parent->
        onIncomingHandled()
        scope.launch{
            runCatching{
                val target=if(inbox)repo.inboxFolder() else folder
                val text=share.text
                if(text!=null)SyncRepository.ImportReport(listOf(repo.importSharedText(text,share.subject,target,parent?.id)),emptyList())
                else repo.importFiles(share.uris,target,parent?.id)
            }.onSuccess{report->
                status=if(inbox)"${report.message}，在“待处理”里" else report.message
                if(!inbox&&report.imported.size==1){startInEditMode=false;editing=report.imported.first()}
            }.onFailure{status="导入失败：${it.localizedMessage}"}
        }
    }}
    if(showAi){io.github.notebook.android.ai.AiAssistantScreen(repo,aiNoteId,{markdown,modifier->MarkdownView(markdown,modifier)},onOpenNote={id->showAi=false;openNote(id)},onBack={showAi=false});return}
    if(showSettings){ReferenceSettings(repo,{showSettings=false},{back->ServerSettings(repo,back)},{manage=it;showSettings=false});return}
    manage?.let{kind->ManagementScreen(kind,repo,folders,tags){manage=null};return}

    fun select(d:Destination,folder:String?=null,tag:String?=null){destination=d;folderId=folder;tagId=tag;scope.launch{drawer.close()}}
    fun openEncrypted(folder:String){if(encryptedUnlocked){select(Destination.All,folder)}else{requestPrivateUnlock(folder);scope.launch{drawer.close()}}}
    var todayKey by remember{mutableStateOf(Calendar.getInstance().get(Calendar.DAY_OF_YEAR) to Calendar.getInstance().get(Calendar.YEAR))}
    LaunchedEffect(Unit){while(true){delay(60_000);val now=Calendar.getInstance();todayKey=now.get(Calendar.DAY_OF_YEAR) to now.get(Calendar.YEAR)}}
    val endOfToday=remember(todayKey){Calendar.getInstance().apply{set(Calendar.HOUR_OF_DAY,23);set(Calendar.MINUTE,59);set(Calendar.SECOND,59);set(Calendar.MILLISECOND,999)}.timeInMillis}
    val visible=remember(all,folders,tagId,folderId,destination,encryptedUnlocked,endOfToday,query,matchingNoteIds){all.filter { n ->
        val encrypted=repo.isEncrypted(n);val selectedFolderType=folders.firstOrNull{it.id==folderId}?.type
        val section=when {
            tagId!=null -> !encrypted&&n.deletedAt==null&&n.tagIds.split(',').contains(tagId)
            folderId!=null&&selectedFolderType=="encryptedFolder" -> encryptedUnlocked&&n.deletedAt==null&&repo.encryptedFolderId(n)==folderId
            folderId!=null -> !encrypted&&n.deletedAt==null&&n.folderId==folderId&&(if(selectedFolderType=="todoList")n.itemType=="todo"&&n.completedAt==null else n.itemType=="note")
            destination==Destination.Today -> !encrypted&&n.itemType=="todo"&&n.deletedAt==null&&n.completedAt==null&&(n.dueAt?:Long.MAX_VALUE)<=endOfToday
            destination==Destination.Important -> !encrypted&&n.itemType=="todo"&&n.deletedAt==null&&n.completedAt==null&&n.important
            destination==Destination.Todos -> !encrypted&&n.itemType=="todo"&&n.deletedAt==null&&n.completedAt==null
            destination==Destination.Completed -> !encrypted&&n.itemType=="todo"&&n.deletedAt==null&&n.completedAt!=null
            destination==Destination.All -> !encrypted&&(n.itemType=="note"||query.isNotBlank())&&n.deletedAt==null
            destination==Destination.Unfiled -> !encrypted&&n.itemType=="note"&&n.deletedAt==null&&n.folderId==null
            destination==Destination.Conflicts -> !encrypted&&n.conflict
            else -> n.deletedAt!=null&&(!encrypted||encryptedUnlocked)
        }
        section&&(query.isBlank()||matchingNoteIds?.contains(n.id)==true)
    }}
    val drawerItems:@Composable ColumnScope.()->Unit={
            Text("Notebook",Modifier.padding(20.dp,24.dp,16.dp,12.dp),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
            Text("TODO",Modifier.padding(horizontal=20.dp,vertical=6.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            DrawerRow("今天",Icons.Default.Today,destination==Destination.Today&&folderId==null,all.count{it.itemType=="todo"&&it.deletedAt==null&&it.completedAt==null&&(it.dueAt?:Long.MAX_VALUE)<=endOfToday}){select(Destination.Today)}
            DrawerRow("重要",Icons.Default.Star,destination==Destination.Important&&folderId==null,all.count{it.itemType=="todo"&&it.deletedAt==null&&it.completedAt==null&&it.important}){select(Destination.Important)}
            DrawerRow("全部待办",Icons.Default.Checklist,destination==Destination.Todos&&folderId==null,all.count{it.itemType=="todo"&&it.deletedAt==null&&it.completedAt==null}){select(Destination.Todos)}
            DrawerRow("已完成",Icons.Default.TaskAlt,destination==Destination.Completed&&folderId==null,all.count{it.itemType=="todo"&&it.deletedAt==null&&it.completedAt!=null}){select(Destination.Completed)}
            Row(Modifier.fillMaxWidth().padding(20.dp,18.dp,12.dp,6.dp)){Text("提醒文件夹",Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Text("管理",Modifier.clickable{manage="todoList"},style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)}
            folders.filter{it.type=="todoList"}.sortedBy{it.sortOrder}.forEach{f->DrawerRow(f.name,Icons.Default.ListAlt,folderId==f.id,all.count{it.itemType=="todo"&&it.deletedAt==null&&it.completedAt==null&&it.folderId==f.id}){select(Destination.Todos,f.id)}}
            HorizontalDivider(Modifier.padding(vertical=8.dp))
            Text("笔记",Modifier.padding(horizontal=20.dp,vertical=6.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            DrawerRow("全部笔记",Icons.Default.Inbox,destination==Destination.All&&folderId==null,all.count{it.itemType=="note"&&it.deletedAt==null}){select(Destination.All)}
            DrawerRow("未分类",Icons.Default.Description,destination==Destination.Unfiled&&folderId==null){select(Destination.Unfiled)}
            DrawerRow("同步冲突",Icons.Default.Warning,destination==Destination.Conflicts&&folderId==null){select(Destination.Conflicts)}
            DrawerRow("回收站",Icons.Default.Delete,destination==Destination.Trash&&folderId==null){select(Destination.Trash)}
            Row(Modifier.fillMaxWidth().padding(20.dp,18.dp,12.dp,6.dp)){Text("文件夹",Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Text("管理",Modifier.clickable{manage="folder"},style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)}
            val noteFolders=folders.filter{it.type=="noteFolder"}.sortedBy{it.sortOrder};noteFolders.take(if(showAllFolders)noteFolders.size else 6).forEach{f->DrawerRow(f.name,Icons.Default.Folder,folderId==f.id,all.count{it.itemType=="note"&&it.deletedAt==null&&it.folderId==f.id}){select(Destination.All,f.id)}}
            if(noteFolders.size>6)Text(if(showAllFolders)"收起文件夹" else "查看全部文件夹（${noteFolders.size}）",Modifier.fillMaxWidth().clickable{showAllFolders=!showAllFolders}.padding(start=28.dp,top=6.dp,bottom=8.dp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
            Row(Modifier.fillMaxWidth().padding(20.dp,18.dp,12.dp,6.dp)){Text("私密文件夹",Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Icon(if(encryptedUnlocked)Icons.Default.LockOpen else Icons.Default.Lock,null,Modifier.size(16.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)}
            val encryptedFolders=folders.filter{it.type=="encryptedFolder"}.sortedBy{it.sortOrder};if(encryptedFolders.isNotEmpty()){if(!encryptedUnlocked)DrawerRow("点击解锁私密内容",Icons.Default.Lock,false){openEncrypted(encryptedFolders.first().id)}else encryptedFolders.forEach{f->DrawerRow(f.name,Icons.Default.Lock,folderId==f.id,all.count{it.deletedAt==null&&repo.encryptedFolderId(it)==f.id}){openEncrypted(f.id)}}}
            Row(Modifier.fillMaxWidth().padding(20.dp,18.dp,12.dp,6.dp)){Text("标签",Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Text("管理",Modifier.clickable{manage="tag"},style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)}
            tags.take(if(showAllTags)tags.size else 6).forEach{tag->DrawerRow(tag.name,Icons.Default.Label,tagId==tag.id){select(Destination.All,tag=tag.id)}}
            if(tags.size>6)Text(if(showAllTags)"收起标签" else "查看全部标签（${tags.size}）",Modifier.fillMaxWidth().clickable{showAllTags=!showAllTags}.padding(start=28.dp,top=6.dp,bottom=8.dp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(20.dp));HorizontalDivider();DrawerRow("AI 助手",Icons.Default.AutoAwesome,false){aiNoteId=null;showAi=true;scope.launch{drawer.close()}};DrawerRow("设置与同步",Icons.Default.Settings,false){showSettings=true;scope.launch{drawer.close()}};Spacer(Modifier.height(12.dp))
    }
    val mainContent:@Composable ()->Unit={
        homeHolder.SaveableStateProvider("home"){ReferenceHome(
            notes=visible,all=all,folders=folders,tags=tags,repo=repo,destination=destination,folderId=folderId,tagId=tagId,
            query=query,onQuery={query=it},searchOrder=searchResult?.ids,searchSnippets=searchResult?.snippets.orEmpty(),searchTerms=searchResult?.terms.orEmpty(),loadingId=loadingNoteId,status=status?.takeIf(String::isNotBlank),syncing=syncing,error=saveError,
            onOpen={openNote(it)},onCreate={
                val folder=folders.firstOrNull{it.id==folderId}
                val draft=newItemDraft(destination,folder,folderId,tagId,endOfToday)
                if(folder?.type=="encryptedFolder")repo.markEncrypted(draft.id,folder.id)
                repo.markUnsaved(draft.id);cardOrigin=null;startInEditMode=true;editing=draft
            },onSelect={d,f,t->select(d,f,t)},onNavigation={scope.launch{drawer.open()}},onSettings={showSettings=true},onSync={runSync()},
            onImport={textImporter.launch(io.github.notebook.android.data.TextImport.MIME_TYPES)},onAiAssistant={aiNoteId=null;showAi=true},onStatus={status=it},onCardBounds={cardOrigin=it},
            encryptedUnlocked=encryptedUnlocked,onCreateChild={id->cardOrigin=null;scope.launch{repo.loadNote(id)?.let{createChild(it)}}},onHierarchyOpen={cardOrigin=null;openNote(it)},
            tabletEditor=if(isTablet){{editing?.let{note->key(note.id){EditorPane(note,repo,surfaceFor(note.id),initiallyEditing=startInEditMode,encryptedUnlocked=encryptedUnlocked,onOpenNote={openNote(it)},onNewChild={createChild(it)},onEditModeChanged={startInEditMode=it},onDone={closeDocument()})}}?:EmptyNotes("选择一篇笔记","在左侧打开笔记开始阅读")}}else null
        )}
    }
    if(isTablet)PermanentNavigationDrawer(drawerContent={PermanentDrawerSheet(Modifier.width(280.dp),drawerContainerColor=MaterialTheme.colorScheme.surfaceVariant){Column(Modifier.fillMaxHeight().verticalScroll(drawerScroll).testTag("drawer-scroll"),content=drawerItems)}},content=mainContent)
    else {
        var displayed by remember{mutableStateOf<Pair<NoteEntity,Boolean>?>(null)}
        if(!encryptedUnlocked&&displayed?.first?.let(repo::isEncrypted)==true)displayed=null
        editing?.let{displayed=it to startInEditMode}
        Box(Modifier.fillMaxSize()){
            Box(Modifier.fillMaxSize().then(if(editing!=null)Modifier.clearAndSetSemantics{}else Modifier)){
                ModalNavigationDrawer(drawerState=drawer,drawerContent={ModalDrawerSheet(Modifier.width(300.dp),drawerContainerColor=MaterialTheme.colorScheme.surfaceVariant){Column(Modifier.fillMaxHeight().verticalScroll(drawerScroll).testTag("drawer-scroll"),content=drawerItems)}},content=mainContent)
            }
            DocumentContainer(editing!=null,cardOrigin,predictiveProgress){displayed?.let{(note,edit)->key(note.id){Editor(note,repo,surfaceFor(note.id),edit,encryptedUnlocked,{openNote(it)},{closeDocument()},{predictiveProgress=it},{createChild(it)},{startInEditMode=it})}}}
        }
    }

}

@Composable private fun DrawerRow(label:String,icon:androidx.compose.ui.graphics.vector.ImageVector,selected:Boolean,count:Int?=null,onClick:()->Unit){NavigationDrawerItem(label={Row{Text(label);Spacer(Modifier.weight(1f));count?.let{Text(it.toString(),style=MaterialTheme.typography.labelMedium)}}},icon={Icon(icon,null)},selected=selected,onClick=onClick,modifier=Modifier.padding(horizontal=8.dp,vertical=1.dp))}

@Composable private fun SyncStatusCard(status:String?,syncing:Boolean,onRetry:()->Unit){
    val message=status?:return
    val failed=message.startsWith("同步失败")
    Card(Modifier.fillMaxWidth().padding(start=12.dp,end=12.dp,bottom=4.dp).testTag("sync-status"),colors=CardDefaults.cardColors(containerColor=when{failed->MaterialTheme.colorScheme.errorContainer;syncing->MaterialTheme.colorScheme.secondaryContainer;else->MaterialTheme.colorScheme.primaryContainer})){Row(Modifier.padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){if(syncing)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)else Icon(if(failed)Icons.Default.ErrorOutline else Icons.Default.CheckCircle,if(failed)"同步失败" else "同步状态",tint=if(failed)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary);Text(message,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall);if(failed)TextButton(onRetry,modifier=Modifier.minimumInteractiveComponentSize()){Text("重试")}}}
}

internal data class VisibleNoteTreeRow(val note:NoteSummary,val depth:Int,val hasChildren:Boolean)

internal fun flattenNoteTree(notes:List<NoteSummary>,expanded:Map<String,Boolean>):List<VisibleNoteTreeRow>{
    val byId=notes.associateBy{it.id}
    val children=notes.groupBy{note->note.parentPageId?.takeIf(byId::containsKey)}
    fun ordered(parent:String?)=children[parent].orEmpty().sortedWith(compareBy<NoteSummary>{it.sortOrder}.thenBy{it.title})
    val result=mutableListOf<VisibleNoteTreeRow>();val visiting=mutableSetOf<String>()
    fun visit(parent:String?,depth:Int){ordered(parent).forEach{note->
        if(!visiting.add(note.id))return@forEach
        val hasChildren=children[note.id].orEmpty().isNotEmpty()
        result+=VisibleNoteTreeRow(note,depth,hasChildren)
        if(hasChildren&&expanded[note.id]!=false)visit(note.id,depth+1)
        visiting-=note.id
    }}
    visit(null,0);return result
}

@Composable internal fun MovePageDialog(page:NoteSummary,notes:List<NoteSummary>,onDismiss:()->Unit,onMove:(String?)->Unit){
    var query by rememberSaveable{mutableStateOf("")}
    val index=remember(notes){NoteHierarchyIndex(notes)}
    val descendants=remember(page.id,index){index.descendants(page.id)+page.id}
    val targets=remember(notes,query,descendants,index){notes.filter{it.itemType=="note"&&it.deletedAt==null&&it.id !in descendants&&(query.isBlank()||query.trim().split(Regex("\\s+")).all{term->index.path(it.id).contains(term,true)})}.sortedWith(compareBy<NoteSummary>{it.folderName}.thenBy{it.title}).take(200)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("移动页面")},text={Column(Modifier.fillMaxWidth().heightIn(max=540.dp)){OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),singleLine=true,label={Text("搜索目标页面")});LazyColumn(Modifier.fillMaxWidth().weight(1f,false)){item{ListItem(headlineContent={Text("当前分区的顶层")},leadingContent={Icon(Icons.Default.Home,null)},modifier=Modifier.clickable{onMove(null)})};items(targets,key={it.id}){target->ListItem(headlineContent={Text(target.title.ifBlank{"无标题"})},supportingContent={Text(index.path(target.id,false),maxLines=2)},leadingContent={Icon(Icons.Default.Description,null)},modifier=Modifier.clickable{onMove(target.id)}.testTag("move-target-${target.id}"))}}}},confirmButton={},dismissButton={TextButton(onDismiss){Text("取消")}})
}

@Composable private fun Editor(original:NoteEntity,repo:SyncRepository,surface:EditorSurfaceState,initiallyEditing:Boolean,encryptedUnlocked:Boolean,onOpenNote:(String)->Unit,onBack:()->Unit,onBackProgress:(Float)->Unit={},onNewChild:(NoteEntity)->Unit={},onEditModeChanged:(Boolean)->Unit={}){EditorPane(original,repo,surface,showBack=true,initiallyEditing=initiallyEditing,encryptedUnlocked=encryptedUnlocked,onOpenNote=onOpenNote,onBackProgress=onBackProgress,onNewChild=onNewChild,onEditModeChanged=onEditModeChanged,onDone={onBack()})}

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable private fun EditorPane(original:NoteEntity,repo:SyncRepository,surface:EditorSurfaceState,showBack:Boolean=false,initiallyEditing:Boolean=false,encryptedUnlocked:Boolean=false,onOpenNote:(String)->Unit={},onBackProgress:(Float)->Unit={},onNewChild:(NoteEntity)->Unit={},onEditModeChanged:(Boolean)->Unit={},onDone:(NoteEntity)->Unit){
    var n by remember(original.id,original.version){mutableStateOf(original)}
    val latestDraft=remember(original.id,original.version){mutableStateOf(original)}
    var caretStart by surface.caretStart
    var caretEnd by surface.caretEnd
    var bodyValue by remember(original.id,original.version){mutableStateOf(TextFieldValue(original.body,TextRange(caretStart.coerceIn(0,original.body.length),caretEnd.coerceIn(0,original.body.length))))}
    LaunchedEffect(bodyValue.selection){caretStart=bodyValue.selection.start;caretEnd=bodyValue.selection.end}
    val undoHistory=remember(original.id){EditorUndoHistory()}
    var historyTick by remember{mutableIntStateOf(0)}
    var moreMenu by remember{mutableStateOf(false)}
    var tagsSheet by remember{mutableStateOf(false)}
    var exportSheet by rememberSaveable{mutableStateOf(false)}
    var libraryMove by remember{mutableStateOf(false)}
    var pageMove by remember{mutableStateOf(false)}
    var editorOutline by remember{mutableStateOf(false)}
    var permanentConfirm by remember{mutableStateOf(false)}
    var editorMessage by remember{mutableStateOf<String?>(null)}
    var readerAction by remember{mutableStateOf<String?>(null)}
    var showHierarchy by rememberSaveable{mutableStateOf(false)}
    // Outline headings only; parsed off the main thread after typing pauses.
    var editorDocument by remember(original.id){mutableStateOf<ParsedMarkdownDocument?>(null)}
    LaunchedEffect(n.body){delay(300);editorDocument=withContext(Dispatchers.Default){MarkdownDocumentParser.parse(n.body)}}
    val attachments by repo.assets(n.id).collectAsState(initial=emptyList());val scope=rememberCoroutineScope()
    val allNotes by repo.notes.collectAsState(initial=emptyList())
    // Derived once per library change, not on every keystroke.
    val hierarchyNotes=remember(allNotes,encryptedUnlocked){allNotes.filter{canExposeNote(it,encryptedUnlocked,repo::isEncrypted)}}
    val linkTitles=remember(hierarchyNotes){hierarchyNotes.associate{it.id to it.title}}
    val saveError by repo.saveError.collectAsState()
    val saveStates by repo.noteSaveStates.collectAsState();val saveState=saveStates[n.id]
    val context=LocalContext.current;val lifecycleOwner=LocalLifecycleOwner.current;val audioRecorder=remember{AudioRecorder(context)};var recording by remember{mutableStateOf(false)};var recordingFile by remember{mutableStateOf<java.io.File?>(null)}
    val editorFocus=androidx.compose.ui.platform.LocalFocusManager.current
    val folders by repo.folders.collectAsState(initial=emptyList());val tags by repo.tags.collectAsState(initial=emptyList());var folderMenu by remember{mutableStateOf(false)};var parentMenu by remember{mutableStateOf(false)}
    val steps by repo.steps(n.id).collectAsState(initial=emptyList());var newStep by remember{mutableStateOf("")}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->uri?.let{source->scope.launch{
        runCatching{repo.flushDraft(latestDraft.value).join();check(repo.noteSaveStates.value[n.id]!=NoteSaveState.Failed){"请先保存笔记"};repo.attach(n.id,source)}.onSuccess{asset->
            if(asset.kind=="image"){
                val syntax="\n\n${NotebookAssetSyntax.imageMarkdown(asset.id,asset.filename)}\n\n"
                val start=bodyValue.selection.min
                val end=bodyValue.selection.max
                val next=bodyValue.text.substring(0,start)+syntax+bodyValue.text.substring(end)
                undoHistory.record(EditorSnapshot(n.title,bodyValue));historyTick++
                bodyValue=TextFieldValue(next,TextRange(start+syntax.length))
                val updated=n.copy(body=next)
                latestDraft.value=updated
                n=updated
                repo.queueDraft(updated)
            }
        }.onFailure{editorMessage="添加附件失败：${it.localizedMessage}"}
    }}}
    val microphonePermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        if(granted)scope.launch{runCatching{repo.flushDraft(latestDraft.value).join();check(repo.noteSaveStates.value[n.id]!=NoteSaveState.Failed){"请先保存笔记"};repo.recordingFile(n.id).also{recordingFile=it;audioRecorder.start(it);recording=true}}.onFailure{editorMessage="无法开始录音：${it.localizedMessage}"}}
        else editorMessage="未获得麦克风权限，录音未开始"
    }
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}

    var reminderDialog by remember{mutableStateOf(false)};var deleteDialog by remember{mutableStateOf(false)};var historyDialog by remember{mutableStateOf(false)};var historyRevisions by remember(original.id){mutableStateOf<List<NoteRevisionSummary>>(emptyList())};var showSavedCheck by remember(original.id){mutableStateOf(false)}
    var referencePicker by remember(original.id){mutableStateOf(false)}
    var editMode by remember(original.id){mutableStateOf(initiallyEditing)}
    LaunchedEffect(editMode){onEditModeChanged(editMode)}
    val initialEditableKey=remember(original.id,original.version){editableKey(original)}
    LaunchedEffect(saveState){if(saveState==NoteSaveState.Saved){showSavedCheck=true;delay(600);showSavedCheck=false;repo.acknowledgeSaved(n.id)}else showSavedCheck=false}
    fun updateNote(next:NoteEntity){latestDraft.value=next;n=next;repo.queueDraft(next)}
    fun changeBody(next:TextFieldValue,typing:Boolean=false){
        if(next.text!=bodyValue.text){undoHistory.record(EditorSnapshot(n.title,bodyValue),typing);historyTick++}
        bodyValue=next
        if(next.text!=n.body)updateNote(n.copy(body=next.text))
    }
    fun changeTitle(next:String){if(next!=n.title){undoHistory.record(EditorSnapshot(n.title,bodyValue),true,field="title");historyTick++;updateNote(n.copy(title=next))}}
    fun restoreEditor(snapshot:EditorSnapshot?){snapshot?.let{bodyValue=it.value;updateNote(n.copy(title=it.title,body=it.value.text));historyTick++}}
    fun stopRecording():kotlinx.coroutines.Job?{
        if(!recording)return null
        val ok=audioRecorder.stop();recording=false
        return if(ok)recordingFile?.let{repo.commitRecordedAudioAsync(n.id,it)}else{recordingFile?.delete();editorMessage="录音时间太短，未生成附件";null}
    }
    val editorKeyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun finishEditing(){scope.launch{
        stopRecording()?.join();withContext(Dispatchers.Main.immediate){editorKeyboard?.hide()}
        if(n.title.isBlank()&&n.body.isBlank()&&attachments.isEmpty()&&steps.isEmpty()&&!repo.hasStoredNote(n.id)){repo.discardUnsavedMarker(n.id);onDone(n);return@launch}
        repo.flushDraft(latestDraft.value).join();if(repo.noteSaveStates.value[n.id]!=NoteSaveState.Failed)editMode=false
    }}
    DisposableEffect(audioRecorder){onDispose{stopRecording();audioRecorder.release()}}
    DisposableEffect(lifecycleOwner,original.id,original.version){
        val observer=LifecycleEventObserver{_,event->
            val latest=latestDraft.value
            if(event==Lifecycle.Event.ON_STOP){stopRecording();if(editableKey(latest)!=initialEditableKey)repo.flushDraft(latest)}
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose{
            lifecycleOwner.lifecycle.removeObserver(observer)
            val latest=latestDraft.value
            // Closing or switching notes is the natural moment to upload.
            repo.closeNote(latest.takeIf{editableKey(it)!=initialEditableKey})
        }
    }
    var leaving by remember(original.id){mutableStateOf(false)}
    fun flushLatestAndFinish(){
        if(leaving)return
        leaving=true
        scope.launch{
            stopRecording()?.join()
            val latest=latestDraft.value
            repo.flushDraft(latest).join()
            if(repo.noteSaveStates.value[latest.id]==NoteSaveState.Failed){leaving=false;return@launch}
            onDone(latest)
        }
    }
    BackHandler{if(editMode)finishEditing()else flushLatestAndFinish()}
    androidx.activity.compose.PredictiveBackHandler(enabled=showBack&&!editMode){events->
        try{events.collect{onBackProgress(it.progress)};flushLatestAndFinish()}
        catch(cancelled:kotlinx.coroutines.CancellationException){onBackProgress(0f);throw cancelled}
    }
    Column(Modifier.fillMaxSize().background(notePageBackground()).statusBarsPadding().navigationBarsPadding()){
        saveError?.let{Text(it,Modifier.padding(16.dp).clickable{repo.clearSaveError()},color=MaterialTheme.colorScheme.error)}
        editorMessage?.let{Text(it,Modifier.padding(16.dp).clickable{editorMessage=null},color=MaterialTheme.colorScheme.error)}
        if(n.conflict){Surface(color=MaterialTheme.colorScheme.errorContainer){Column(Modifier.padding(12.dp)){Text("此项目在本机和服务器上都被修改");Row{TextButton({scope.launch{repo.keepLocal(n.id);repo.loadNote(n.id)?.let{updateNote(it)}}}){Text("保留本机")};TextButton({scope.launch{repo.acceptRemote(n.id);repo.loadNote(n.id)?.let{latestDraft.value=it;n=it;bodyValue=TextFieldValue(it.body)}}}){Text("采用服务器版本")}}}}}
        Row(Modifier.fillMaxWidth().padding(start=12.dp,end=12.dp,top=0.dp,bottom=4.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
            if(showBack)RoundTool(Icons.Default.ArrowBack,"返回",{if(editMode)finishEditing()else flushLatestAndFinish()},enabled=!leaving)
            if(editMode){
                RoundTool(Icons.Default.Toc,"目录",{editorOutline=true})
                Spacer(Modifier.width(24.dp))
                val tick=historyTick
                RoundTool(Icons.Default.Undo,"撤销",{restoreEditor(undoHistory.undo(EditorSnapshot(n.title,bodyValue)))},enabled=undoHistory.canUndo&&tick>=0)
                RoundTool(Icons.Default.Redo,"重做",{restoreEditor(undoHistory.redo(EditorSnapshot(n.title,bodyValue)))},enabled=undoHistory.canRedo&&tick>=0)
                Spacer(Modifier.weight(1f))
                // Saving is continuous and local; only a failure needs attention.
                if(saveState==NoteSaveState.Failed)SaveStatusAction(saveState,showSavedCheck){repo.flushDraft(latestDraft.value)}
                TextButton({stopRecording();finishEditing()},Modifier.testTag("preview-mode").semantics{contentDescription="完成"},shape=androidx.compose.foundation.shape.CircleShape,colors=ButtonDefaults.textButtonColors(containerColor=MaterialTheme.colorScheme.surface,contentColor=MaterialTheme.colorScheme.onSurface)){Text("完成")}
            }else{
                if(n.deletedAt!=null)Text("最近删除",Modifier.weight(1f).padding(start=8.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)else Spacer(Modifier.weight(1f))
                RoundTool(Icons.Default.Toc,"目录",{readerAction="outline"})
                RoundTool(Icons.Default.IosShare,"分享与导出",{exportSheet=true})
                Box{RoundTool(Icons.Default.MoreVert,"笔记更多",{moreMenu=true});DropdownMenu(moreMenu,{moreMenu=false}){
                    DropdownMenuItem({Text("文档内查找")},{moreMenu=false;readerAction="find"})
                    DropdownMenuItem({Text("反向链接")},{moreMenu=false;readerAction="backlinks"},leadingIcon={Icon(Icons.Default.Link,null)})
                    DropdownMenuItem({Text("阅读设置")},{moreMenu=false;readerAction="settings"},leadingIcon={Icon(Icons.Default.TextFields,null)})
                    DropdownMenuItem({Text("版本历史")},{moreMenu=false;scope.launch{repo.flushDraft(n).join();historyRevisions=repo.revisions(n.id);historyDialog=true}},leadingIcon={Icon(Icons.Default.History,"版本历史")})
                    if(n.deletedAt==null){
                        if(!repo.isEncrypted(n))DropdownMenuItem({Text("标签")},{moreMenu=false;tagsSheet=true})
                        DropdownMenuItem({Text("笔记库与上级页面")},{moreMenu=false;folderMenu=true})
                        if(n.itemType=="note")DropdownMenuItem({Text("新建子页面")},{moreMenu=false;scope.launch{repo.flushDraft(n).join();onNewChild(n)}})
                        DropdownMenuItem({Text("创建副本")},{moreMenu=false;scope.launch{runCatching{repo.duplicateNote(n.id)}.onSuccess{onOpenNote(it.id)}.onFailure{editorMessage=it.localizedMessage}}})
                        DropdownMenuItem({Text("删除笔记")},{moreMenu=false;deleteDialog=true},modifier=Modifier.testTag("delete-item"),leadingIcon={Icon(Icons.Default.DeleteOutline,null)})
                    }
                }}
            }
        }
        if(n.itemType=="note"&&n.deletedAt==null)NoteBreadcrumb(n,hierarchyNotes,{onOpenNote(it)},{editorKeyboard?.hide();editorFocus.clearFocus();scope.launch{repo.flushDraft(latestDraft.value).join();if(repo.noteSaveStates.value[n.id]!=NoteSaveState.Failed){showHierarchy=true}}})
        if(!editMode){
            ReadingPane(n,repo,steps,attachments,encryptedUnlocked,onOpenNote,Modifier.fillMaxWidth().weight(1f).padding(horizontal=24.dp).testTag("markdown-view"),onUpdateBody={changeBody(TextFieldValue(it))},readerAction=readerAction,onActionConsumed={readerAction=null},onTags={tagsSheet=true},onTable={from,to->surface.tableStart.intValue=from;surface.tableEnd.intValue=to})
            FloatingPill(Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally).padding(horizontal=24.dp,vertical=16.dp)){
                if(n.deletedAt==null){TextButton({editMode=true;bodyValue=TextFieldValue(n.body,TextRange(n.body.length))},Modifier.testTag("edit-mode")){Icon(Icons.Default.Edit,null);Text(if(n.itemType=="todo")"编辑计划" else "编辑笔记",Modifier.padding(horizontal=16.dp))}}
                else {TextButton({scope.launch{runCatching{repo.restore(n.id);repo.loadNote(n.id)?.let{latestDraft.value=it;n=it}}.onFailure{editorMessage=it.localizedMessage}}}){Text("恢复")};TextButton({permanentConfirm=true}){Text("彻底删除",color=MaterialTheme.colorScheme.error)}}
            }
        }else Box(Modifier.weight(1f)){
            ReferenceEditorBody(n.title,::changeTitle,bodyValue,{changeBody(it,true)},{referencePicker=true},{picker.launch("image/*")},{picker.launch("*/*")},{if(recording)stopRecording()else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)},recording,surface=surface,onTable={from,to->surface.tableStart.intValue=from;surface.tableEnd.intValue=to},onCommand={changeBody(it,false)},assets=attachments,timestamp=java.text.SimpleDateFormat("yyyy/M/d HH:mm",Locale.CHINA).format(Date(n.createdAt)),afterTitle={if(!repo.isEncrypted(n))ReferenceMetaChip(if(n.tagIds.isBlank())"添加标签" else "标签",Icons.Default.Add){tagsSheet=true}},titles=linkTitles,onCaret={at->bodyValue=bodyValue.copy(selection=TextRange(at.coerceIn(0,bodyValue.text.length)),composition=null)}){
                if(n.itemType=="todo"){
                    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Checkbox(n.completedAt!=null,{updateNote(n.copy(completedAt=if(it)System.currentTimeMillis()else null))});Text("已完成");Checkbox(n.important,{updateNote(n.copy(important=it))});Text("重要")}
                    steps.forEach{step->Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Checkbox(step.checked,{checked->scope.launch{repo.saveStep(step.copy(checked=checked))}});Text(step.text,Modifier.weight(1f));IconButton({scope.launch{repo.deleteStep(step.id)}}){Icon(Icons.Default.Close,"删除步骤")}}}
                    Row{TextField(newStep,{newStep=it},Modifier.weight(1f),singleLine=true,placeholder={Text("添加步骤")});IconButton({if(newStep.isNotBlank()){val text=newStep.trim();newStep="";scope.launch{repo.saveStep(TodoStepEntity(UUID.randomUUID().toString(),n.id,text,sortOrder=steps.size))}}}){Icon(Icons.Default.Add,"添加步骤")}}
                }
                attachments.filter{it.id !in NotebookAssetSyntax.images(n.body).map{image->image.assetId}}.forEach{AttachmentView(it)}
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    ReferenceMetaChip(folders.firstOrNull{it.id==n.folderId}?.name?:n.folderName,Icons.Default.Folder){folderMenu=true}

                    ReferenceMetaChip(if(n.reminderAt==null)"提醒" else "已设提醒",Icons.Default.NotificationsNone){reminderDialog=true}
                    if(recording)AssistChip({stopRecording()},{Text("正在录音 · 点击停止")},leadingIcon={Icon(Icons.Default.Mic,null,tint=MaterialTheme.colorScheme.error)})
                }

            }
        }
    }
    if(showHierarchy)ReferenceHierarchySheet(hierarchyNotes,folders,repo,currentId=n.id,onDismiss={showHierarchy=false},onOpen=onOpenNote,onCreateChild={id->scope.launch{repo.flushDraft(latestDraft.value).join();repo.loadNote(id)?.let(onNewChild)}},onMoved={scope.launch{repo.loadNote(n.id)?.let{moved->val updated=n.copy(folderId=moved.folderId,folderName=moved.folderName,parentPageId=moved.parentPageId,sortOrder=moved.sortOrder,treeUpdatedAt=moved.treeUpdatedAt);latestDraft.value=updated;n=updated}}},onStatus={editorMessage=it})
    val tableFrom=surface.tableStart.intValue
    val tableTo=surface.tableEnd.intValue
    if(tableFrom>=0&&tableTo in tableFrom..n.body.length)TableEditorDialog(n.body.substring(tableFrom,tableTo),n.deletedAt!=null,{surface.tableStart.intValue=-1}){markdown->
        surface.tableStart.intValue=-1
        changeBody(TextFieldValue(n.body.replaceRange(tableFrom,tableTo,markdown),TextRange(tableFrom+markdown.length)),false)
    }
    if(folderMenu)ModalBottomSheet(onDismissRequest={folderMenu=false}){
        SheetTitle("笔记库与上级页面",{folderMenu=false})
        val encrypted=repo.isEncrypted(n)
        if(!encrypted)ListItem(headlineContent={Text("未分类")},modifier=Modifier.clickable{updateNote(n.copy(folderId=null,folderName="未分类",parentPageId=null,treeUpdatedAt=maxOf(System.currentTimeMillis(),n.treeUpdatedAt.coerceAtMost(Long.MAX_VALUE-1)+1)));folderMenu=false})
        LazyColumn(Modifier.heightIn(max=320.dp)){items(folders.filter{it.type==if(encrypted)"encryptedFolder" else if(n.itemType=="todo")"todoList" else "noteFolder"},key={it.id}){folder->ListItem(headlineContent={Text(folder.name)},leadingContent={LibraryCover(libraryColor(folder.color),Modifier.size(44.dp,38.dp),encrypted)},modifier=Modifier.clickable{if(encrypted)repo.moveEncrypted(n.id,folder.id);updateNote(n.copy(folderId=folder.id,folderName=folder.name,parentPageId=null,treeUpdatedAt=maxOf(System.currentTimeMillis(),n.treeUpdatedAt.coerceAtMost(Long.MAX_VALUE-1)+1)));folderMenu=false})}}
        if(n.itemType=="note")TextButton({folderMenu=false;pageMove=true}){Icon(Icons.Default.AccountTree,null);Text("移动到上级页面…")};Spacer(Modifier.height(24.dp))
    }
    if(pageMove)allNotes.firstOrNull{it.id==n.id}?.let{summary->MovePageDialog(summary,allNotes.filter{repo.isEncrypted(it)==repo.isEncrypted(n)},{pageMove=false}){parent->pageMove=false;scope.launch{repo.flushDraft(n).join();runCatching{repo.movePage(n.id,parent)}.onSuccess{latestDraft.value=it;n=it}.onFailure{editorMessage=it.localizedMessage}}}}
    if(tagsSheet)NoteTagsSheet(tags,n.tagIds,{tagsSheet=false},{updateNote(n.copy(tagIds=it))}){name->scope.launch{val id=UUID.randomUUID().toString();repo.saveTag(name,id=id);updateNote(n.copy(tagIds=(n.tagIds.split(',').filter(String::isNotBlank)+id).joinToString(",")))}}
    if(exportSheet)ExportSheet(n,{exportSheet=false}){repo.flushDraft(latestDraft.value).join();check(repo.noteSaveStates.value[n.id]!=NoteSaveState.Failed){"请先重试保存笔记"}}
    if(editorOutline)ModalBottomSheet(onDismissRequest={editorOutline=false}){SheetTitle("目录",{editorOutline=false});val headings=editorDocument?.headings.orEmpty();if(headings.isEmpty())Text("添加标题后，这里会出现目录",Modifier.padding(24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)else LazyColumn(Modifier.heightIn(max=440.dp)){items(headings,key={it.blockId}){heading->ListItem(headlineContent={Text(heading.title)},modifier=Modifier.padding(start=((heading.level-1)*14).dp).clickable{bodyValue=bodyValue.copy(selection=TextRange(heading.sourceOffsetUtf16));editorOutline=false})}};Spacer(Modifier.height(24.dp))}
    if(permanentConfirm)AlertDialog(onDismissRequest={permanentConfirm=false},title={Text("彻底删除笔记？")},text={Text("此操作无法恢复。")},confirmButton={Button({permanentConfirm=false;scope.launch{runCatching{repo.deletePermanently(n.id)}.onSuccess{onDone(n)}.onFailure{editorMessage="彻底删除失败：${it.localizedMessage?:"请重试"}"}}},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text("彻底删除")}},dismissButton={TextButton({permanentConfirm=false}){Text("取消")}})
    if(referencePicker)ReferencePickerDialog(
        notes=visibleReferenceNotes(allNotes,n.id,encryptedUnlocked,repo::isEncrypted),
        onDismiss={referencePicker=false},
        onSelect={target,embed->
            val syntax="${if(embed)"!" else ""}[[page:${target.id}]]"
            val start=bodyValue.selection.min
            val end=bodyValue.selection.max
            val next=bodyValue.text.substring(0,start)+syntax+bodyValue.text.substring(end)
            changeBody(TextFieldValue(next,TextRange(start+syntax.length)))
            referencePicker=false
        }
    )
    if(historyDialog)RevisionHistoryDialog(
        revisions=historyRevisions,
        onDismiss={historyDialog=false},
        onRestore={revision->
            scope.launch{
                runCatching{repo.restoreRevision(n.id,revision.id)}.onSuccess{restored->
                    latestDraft.value=restored
                    n=restored
                    bodyValue=TextFieldValue(restored.body)
                    editMode=false
                    historyDialog=false
                }
            }
        }
    )
    if(reminderDialog)ReminderDialog(n.reminderAt,n.recurrence,{reminderDialog=false},{at,rule->updateNote(n.copy(reminderAt=at,recurrence=rule));if(at!=null&&android.os.Build.VERSION.SDK_INT>=33)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);reminderDialog=false})
    if(deleteDialog){val itemLabel=if(n.itemType=="todo")"计划" else "笔记";val hasChildren=allNotes.any{it.parentPageId==n.id};AlertDialog(onDismissRequest={deleteDialog=false},icon={Icon(Icons.Default.Delete,null,tint=MaterialTheme.colorScheme.error)},title={Text("删除$itemLabel？")},text={Text(if(hasChildren)"此页面及全部子页面会一起移入回收站，可以稍后恢复。" else "删除后会移入回收站，可以稍后恢复。")},confirmButton={Button({deleteDialog=false;scope.launch{repo.flushDraft(n).join();repo.moveToTrash(n.id);onDone(n.copy(deletedAt=System.currentTimeMillis()))}},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text("删除")}},dismissButton={TextButton({deleteDialog=false}){Text("取消")}})}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun RevisionHistoryDialog(revisions:List<NoteRevisionSummary>,onDismiss:()->Unit,onRestore:(NoteRevisionSummary)->Unit){
    var selected by remember(revisions){mutableStateOf<NoteRevisionSummary?>(null)}
    var confirmRestore by remember{mutableStateOf(false)}
    ModalBottomSheet(onDismissRequest=onDismiss){
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal=20.dp)){
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Text("版本历史",style=MaterialTheme.typography.titleLarge);Spacer(Modifier.weight(1f));selected?.let{Button({confirmRestore=true}){Text("恢复此版本")}}}
            when {
                selected==null -> {
                    val items=revisions
                    if(items.isEmpty())Box(Modifier.fillMaxSize(),contentAlignment=androidx.compose.ui.Alignment.Center){Text("还没有可恢复的历史版本",color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    else LazyColumn(Modifier.fillMaxSize()){items(items,key={it.id}){revision->ListItem(headlineContent={Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(revision.createdAt)))},supportingContent={Text(when(revision.reason){"manual"->"手动快照";"import"->"同步或导入";else->"自动保存"})},leadingContent={Icon(Icons.Default.Restore,null)},modifier=Modifier.testTag("history-revision-${revision.id}").clickable{selected=revision})}}
                }
                else -> Column(Modifier.fillMaxSize()){TextButton({selected=null}){Icon(Icons.Default.ArrowBack,null);Text("返回版本列表")};MarkdownView(selected!!.markdown,Modifier.fillMaxWidth().weight(1f).testTag("history-preview").verticalScroll(androidx.compose.foundation.rememberScrollState()))}
            }
        }
    }
    if(confirmRestore)AlertDialog(onDismissRequest={confirmRestore=false},title={Text("恢复这个版本？")},text={Text("当前正文会先在下一次同步时进入历史，然后以所选版本创建新的当前版本。")},confirmButton={Button({confirmRestore=false;selected?.let(onRestore)}){Text("恢复")}},dismissButton={TextButton({confirmRestore=false}){Text("取消")}})
}

@Composable private fun ReferencePickerDialog(notes:List<NoteSummary>,onDismiss:()->Unit,onSelect:(NoteSummary,Boolean)->Unit){
    var query by rememberSaveable{mutableStateOf("")}
    var embed by remember{mutableStateOf(false)}
    val visible=remember(notes,query){notes.filter{query.isBlank()||it.title.contains(query,true)||it.preview.contains(query,true)}.take(100)}
    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text("插入双链")},
        text={Column(Modifier.fillMaxWidth().heightIn(max=520.dp)){
            OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),singleLine=true,label={Text("搜索页面标题或内容")})
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Switch(embed,{embed=it});Text(if(embed)"嵌入整篇内容" else "只显示页面链接",Modifier.padding(start=8.dp))}
            LazyColumn(Modifier.fillMaxWidth().weight(1f,false)){items(visible,key={it.id}){target->ListItem(headlineContent={Text(target.title.ifBlank{"无标题"},maxLines=1)},supportingContent={Text(target.preview,maxLines=2)},leadingContent={Icon(Icons.Default.Description,null)},modifier=Modifier.clickable{onSelect(target,embed)})}}
        }},
        confirmButton={},
        dismissButton={TextButton(onDismiss){Text("取消")}}
    )
}

private fun editableKey(n:NoteEntity)=listOf<Any?>(n.title,n.body,n.folderId,n.folderName,n.icon,n.parentPageId,n.sortOrder,n.treeUpdatedAt,n.reminderAt,n.recurrence,n.tagIds,n.deletedAt,n.itemType,n.dueAt,n.completedAt,n.important,n.viewMode)

@Composable private fun SaveStatusAction(state:NoteSaveState?,showSavedCheck:Boolean,onSave:()->Unit){
    when(state){
        NoteSaveState.Pending->IconButton(onSave,modifier=Modifier.testTag("save-status")){Icon(Icons.Default.Save,"保存",tint=MaterialTheme.colorScheme.primary)}
        NoteSaveState.Saving->Box(Modifier.size(48.dp).testTag("save-status"),contentAlignment=androidx.compose.ui.Alignment.Center){CircularProgressIndicator(Modifier.size(21.dp),strokeWidth=2.5.dp)}
        NoteSaveState.Saved->Box(Modifier.size(48.dp).testTag("save-status"),contentAlignment=androidx.compose.ui.Alignment.Center){if(showSavedCheck)Icon(Icons.Default.CheckCircle,"已保存",tint=Color(0xFF34A853))}
        NoteSaveState.Failed->IconButton(onSave,modifier=Modifier.testTag("save-status")){Icon(Icons.Default.Error,"保存失败，点击重试",tint=MaterialTheme.colorScheme.error)}
        null->Spacer(Modifier.size(48.dp).testTag("save-status"))
    }
}

private data class ReaderStyle(val fontSizeSp:Float=17f,val lineHeight:Float=1.28f,val maxWidthDp:Float=820f)

@Composable private fun rememberReaderStyle():Pair<ReaderStyle,(ReaderStyle)->Unit>{
    val context=LocalContext.current;val preferences=remember(context){context.getSharedPreferences("reader_preferences",android.content.Context.MODE_PRIVATE)}
    var style by remember{mutableStateOf(ReaderStyle(preferences.getFloat("fontSizeSp",17f),preferences.getFloat("lineHeight",1.28f),preferences.getFloat("maxWidthDp",820f)))}
    val update:(ReaderStyle)->Unit={next->style=next;preferences.edit().putFloat("fontSizeSp",next.fontSizeSp).putFloat("lineHeight",next.lineHeight).putFloat("maxWidthDp",next.maxWidthDp).apply()}
    return style to update
}

/** One Markdown renderer for the whole app; also usable off the main thread. */
internal object ReaderRenderer {
    @Volatile var onNotebookLink:((String)->Unit)?=null
    @Volatile private var instance:Markwon?=null
    fun get(context:android.content.Context):Markwon=instance?:synchronized(this){instance?:createMarkdownRenderer(context.applicationContext){onNotebookLink?.invoke(it)}.also{instance=it}}
}

/** Parse and style a note body for the reader (background thread). */
private fun renderReaderDocument(body:String,markwon:Markwon):ReaderDocumentState.Ready{
    val document=MarkdownDocumentParser.parse(body)
    val spans=HashMap<String,android.text.Spanned>()
    var budget=300_000
    for(block in document.blocks){
        if(budget<=0)break
        if(block.kind==MarkdownBlockKind.Blank||block.kind==MarkdownBlockKind.Table)continue
        if(NotebookReferenceSyntax.parse(block.text).isNotEmpty())continue
        val display=NotebookAssetSyntax.withoutImages(block.text)
        if(display.isBlank()||spans.containsKey(display))continue
        runCatching{markwon.toMarkdown(display)}.getOrNull()?.let{spans[display]=it}
        budget-=display.length
    }
    return ReaderDocumentState.Ready(document,spans)
}

/** Everything the reader needs for its first frame, prepared before opening. */
private suspend fun prepareReader(context:android.content.Context,repo:SyncRepository,note:NoteEntity){
    val position=repo.readingPosition(note.id)
    withContext(Dispatchers.Default){
        // Page pictures from 0.4.9 are no longer used.
        java.io.File(context.cacheDir,"page-snapshots").takeIf{it.exists()}?.deleteRecursively()
        if(ReaderDocumentCache.get(note.id,note.body)==null)ReaderDocumentCache.put(note.id,note.body,renderReaderDocument(note.body,ReaderRenderer.get(context)))
    }
    ReaderDocumentCache.putStart(note.id,position?.anchorUtf16Offset?.coerceIn(0,note.body.length))
}

private object ReaderDocumentCache {
    private val starts=HashMap<String,Int>()
    @Synchronized fun putStart(id:String,anchor:Int?){if(anchor==null)starts.remove(id)else starts[id]=anchor}
    @Synchronized fun takeStart(id:String)=starts.remove(id)
    private val entries=object:LinkedHashMap<String,Pair<String,ReaderDocumentState.Ready>>(16,.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Pair<String,ReaderDocumentState.Ready>>?)=size>8}
    @Synchronized fun get(id:String,body:String)=entries[id]?.takeIf{it.first==body}?.second
    @Synchronized fun put(id:String,body:String,ready:ReaderDocumentState.Ready){entries[id]=body to ready}
}

private sealed interface ReaderDocumentState { data object Loading:ReaderDocumentState;data class Ready(val document:ParsedMarkdownDocument,val spans:Map<String,android.text.Spanned> = emptyMap()):ReaderDocumentState;data class Failed(val message:String):ReaderDocumentState }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ReadingPane(note:NoteEntity,repo:SyncRepository,steps:List<TodoStepEntity>,attachments:List<AssetEntity>,encryptedUnlocked:Boolean,onOpenNote:(String)->Unit,modifier:Modifier=Modifier,onUpdateBody:(String)->Unit={},readerAction:String?=null,onActionConsumed:()->Unit={},onTags:()->Unit={},onTable:(Int,Int)->Unit={_,_->}){
    val allNotes by repo.notes.collectAsState(initial=emptyList())
    val noteTags by repo.tags.collectAsState(initial=emptyList())
    val readerFolders by repo.folders.collectAsState(initial=emptyList())
    val backlinks by repo.backlinks(note.id).collectAsState(initial=emptyList())
    val visibleBacklinks=backlinks.filter{link->canExposeNote(allNotes.firstOrNull{it.id==link.sourceNoteId},encryptedUnlocked,repo::isEncrypted)}
    val references=remember(note.body){NotebookReferenceSyntax.parse(note.body)}
    val referencedAssetIds=remember(note.body){NotebookAssetSyntax.images(note.body).map{it.assetId}.toSet()}
    var referenceTargets by remember(note.id){mutableStateOf<Map<String,NoteEntity>>(emptyMap())}
    var referenceAssets by remember(note.id){mutableStateOf<Map<String,AssetEntity>>(emptyMap())}
    LaunchedEffect(note.id,references.map{it.pageId}.distinct(),encryptedUnlocked){
        val targetIds=references.map{it.pageId}.distinct()
        referenceTargets=targetIds.mapNotNull{id->repo.loadNote(id)?.takeIf{target->!repo.isEncrypted(target)||encryptedUnlocked}?.let{id to it}}.toMap()
        referenceAssets=referenceTargets.keys.flatMap{repo.assetList(it)}.associateBy{it.id}
    }
    // `[[标题]]` resolves against the library like on the desktop.
    val resolveTitle=remember(allNotes){NotebookReferenceSyntax.titleResolver(allNotes.filter{it.deletedAt==null}.map{WikiLinkCandidate(it.id,it.title,it.itemType=="todo",it.updatedAt)})}
    val missingLinkContext=LocalContext.current
    fun openReference(uri:String){
        val parsed=android.net.Uri.parse(uri)
        if(parsed.host=="title"){
            val title=parsed.pathSegments.firstOrNull()?:return
            val target=resolveTitle(title)
            if(target!=null)onOpenNote(target)else android.widget.Toast.makeText(missingLinkContext,"还没有“$title”这篇笔记",android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val targetId=parsed.pathSegments.firstOrNull()?:return
        onOpenNote(targetId)
    }
    val readerContext=LocalContext.current;val latestOpenReference by rememberUpdatedState<(String)->Unit>(::openReference)
    // Building a Markwon instance (with the table plugin) per paragraph was a
    // large part of opening a long note; one renderer serves every block.
    val markwon=remember(readerContext){ReaderRenderer.get(readerContext)}
    DisposableEffect(Unit){val handler:(String)->Unit={latestOpenReference(it)};ReaderRenderer.onNotebookLink=handler;onDispose{if(ReaderRenderer.onNotebookLink===handler)ReaderRenderer.onNotebookLink=null}}
    // Reopening a note skips the loading frame: parsed documents are cached.
    var documentState by remember(note.id,note.body) { mutableStateOf<ReaderDocumentState>(ReaderDocumentCache.get(note.id,note.body)?:ReaderDocumentState.Loading) }
    LaunchedEffect(note.id,note.body) {
        if(documentState is ReaderDocumentState.Ready)return@LaunchedEffect
        val parsed = runCatching {
            withContext(Dispatchers.Default) { renderReaderDocument(note.body,markwon).also{ReaderDocumentCache.put(note.id,note.body,it)} }
        }
        documentState = parsed.fold(
            onSuccess = { it },
            onFailure = { ReaderDocumentState.Failed(it.localizedMessage ?: "Markdown 解析失败") }
        )
    }
    val ready=documentState as? ReaderDocumentState.Ready
    if(ready==null){Box(modifier,contentAlignment=androidx.compose.ui.Alignment.Center){when(val current=documentState){ReaderDocumentState.Loading->Column(horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally){CircularProgressIndicator();Spacer(Modifier.height(10.dp));Text("正在准备长文阅读视图…",color=MaterialTheme.colorScheme.onSurfaceVariant)};is ReaderDocumentState.Failed->Column(horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally){Icon(Icons.Default.ErrorOutline,null,tint=MaterialTheme.colorScheme.error);Text(current.message,color=MaterialTheme.colorScheme.error)};is ReaderDocumentState.Ready->Unit}};return}
    val showBody=LocalDocumentSettled.current
    val document=ready.document;val blocks=if(showBody)document.blocks else emptyList();val hasMetadata=note.folderName.isNotBlank()||note.tagIds.isNotBlank();val bodyStart=if(hasMetadata)3 else 2;val state=remember(note.id){val anchor=ReaderDocumentCache.takeStart(note.id);androidx.compose.foundation.lazy.LazyListState(anchor?.let{a->bodyStart+document.blocks.indexOfLast{it.startUtf16<=a}.coerceAtLeast(0)}?:0)};val scope=rememberCoroutineScope();val views=remember(note.id,note.body){mutableMapOf<Int,TextView>()}
    val (readerStyle,updateReaderStyle)=rememberReaderStyle();var showingOutline by remember(note.id){mutableStateOf(false)};var showingBacklinks by remember(note.id){mutableStateOf(false)};var showingSettings by remember{mutableStateOf(false)};var showingSearch by remember(note.id){mutableStateOf(false)};var searchQuery by remember(note.id){mutableStateOf("")};var searchIndex by remember(note.id){mutableIntStateOf(0)}
    val searchMatches=remember(note.body,searchQuery){if(searchQuery.isBlank())emptyList()else buildList{var start=0;while(size<10_000){val found=note.body.indexOf(searchQuery,start,ignoreCase=true);if(found<0)break;add(found);start=found+searchQuery.length.coerceAtLeast(1)}}}
    LaunchedEffect(readerAction){when(readerAction){"outline"->showingOutline=true;"find"->showingSearch=true;"backlinks"->showingBacklinks=true;"settings"->showingSettings=true};if(readerAction!=null)onActionConsumed()}
    fun navigateSearch(delta:Int){if(searchMatches.isEmpty())return;searchIndex=(searchIndex+delta).mod(searchMatches.size);val offset=searchMatches[searchIndex];val blockIndex=blocks.indexOfLast{it.startUtf16<=offset}.coerceAtLeast(0);launchScroll(scope,state,bodyStart+blockIndex)}
    var restored by remember(note.id){mutableStateOf(false)};val latestRestored by rememberUpdatedState(restored)
    fun capturedPosition():Pair<Int,Double>{
        val first=state.firstVisibleItemIndex;val viewport=state.layoutInfo.viewportSize.height.coerceAtLeast(1);if(first<bodyStart)return 0 to 0.0
        val blockIndex=first-bodyStart;if(blockIndex !in blocks.indices)return note.body.length to 0.0;val block=blocks[blockIndex];val view=views[blockIndex];val layout=view?.layout
        if(layout==null||view.text.isEmpty())return block.startUtf16 to 0.0
        val localY=state.firstVisibleItemScrollOffset.coerceIn(0,maxOf(0,layout.height-1));val line=layout.getLineForVertical(localY);val renderedOffset=layout.getLineStart(line);val sourceOffset=(renderedOffset.toDouble()/view.text.length.coerceAtLeast(1)*block.text.length).roundToInt().coerceIn(0,block.text.length);val fraction=((layout.getLineTop(line)-localY).toDouble()/viewport).coerceIn(-1.0,1.0)
        return (block.startUtf16+sourceOffset).coerceIn(0,note.body.length) to fraction
    }
    LaunchedEffect(note.id,note.body,showBody){
        if(!showBody)return@LaunchedEffect
        val position=repo.readingPosition(note.id);if(position!=null&&note.body.isNotEmpty()){
            val anchor=position.anchorUtf16Offset.coerceIn(0,note.body.length);val blockIndex=blocks.indexOfLast{it.startUtf16<=anchor}.coerceAtLeast(0);val itemIndex=bodyStart+blockIndex;withContext(Dispatchers.Main.immediate){state.scrollToItem(itemIndex)}
            for(attempt in 0 until 30){val view=views[blockIndex];val layout=view?.layout;if(layout!=null&&view.text.isNotEmpty()){val block=blocks[blockIndex];val sourceLocal=(anchor-block.startUtf16).coerceIn(0,block.text.length);val rendered=(sourceLocal.toDouble()/block.text.length.coerceAtLeast(1)*view.text.length).roundToInt().coerceIn(0,maxOf(0,view.text.length-1));val line=layout.getLineForOffset(rendered);val viewport=state.layoutInfo.viewportSize.height.coerceAtLeast(1);val desired=(layout.getLineTop(line)-position.viewportOffsetFraction*viewport).roundToInt().coerceAtLeast(0);withContext(Dispatchers.Main.immediate){state.scrollToItem(itemIndex,desired)};break};delay(16)}
        }
        restored=true
    }
    LaunchedEffect(note.id,restored,blocks){if(restored)snapshotFlow{state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset}.collectLatest{delay(450);val (anchor,fraction)=capturedPosition();repo.recordReadingPosition(note.id,anchor,fraction)}}
    DisposableEffect(note.id){onDispose{if(latestRestored){val (anchor,fraction)=capturedPosition();repo.recordReadingPosition(note.id,anchor,fraction)}}}
    Box(modifier){LazyColumn(Modifier.fillMaxSize(),state=state,contentPadding=PaddingValues(top=if(showingSearch)64.dp else 0.dp,bottom=36.dp),horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally){
        item(key="reader-tools"){Text(java.text.SimpleDateFormat("yyyy/M/d HH:mm",Locale.CHINA).format(Date(note.createdAt)),Modifier.fillMaxWidth().padding(top=8.dp),fontSize=13.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item(key="note-title"){Text(note.title.ifBlank{"无标题"},fontSize=32.sp,lineHeight=42.sp,fontWeight=FontWeight.Bold,modifier=Modifier.fillMaxWidth().widthIn(max=readerStyle.maxWidthDp.dp).padding(top=8.dp,bottom=4.dp).testTag("reader-note-${note.id}"))}
        if(hasMetadata)item(key="note-folder"){FlowRow(Modifier.fillMaxWidth().widthIn(max=readerStyle.maxWidthDp.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.Center){Text(readerFolders.firstOrNull{it.id==note.folderId}?.name?:note.folderName,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=8.dp));noteTags.filter{it.id in note.tagIds.split(',')}.forEach{tag->ReferenceMetaChip(tag.name,null,onTags)};if(note.tagIds.isBlank()&&!repo.isEncrypted(note))ReferenceMetaChip("添加标签",Icons.Default.Add,onTags)}}
        if(blocks.isEmpty()&&showBody)item(key="empty-note"){Text("暂无正文",Modifier.fillMaxWidth().widthIn(max=readerStyle.maxWidthDp.dp).padding(vertical=24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
        items(blocks.size,key={"note-markdown-${blocks[it].id}"}){index->
            val block=blocks[index]
            if(block.kind==MarkdownBlockKind.Blank)Spacer(Modifier.fillMaxWidth().height(8.dp))else{
                val expandedMarkdown=remember(block.text,referenceTargets,allNotes,encryptedUnlocked,resolveTitle){NotebookReferenceSyntax.forDisplay(block.text,pageTitle={id->allNotes.firstOrNull{it.id==id}?.takeIf{canExposeNote(it,encryptedUnlocked,repo::isEncrypted)}?.title},targetContent={reference->referenceTargets[reference.pageId]?.let{NotebookReferenceSyntax.extractTarget(it.body,reference)}},resolveTitle=resolveTitle)}
                val imageRefs=remember(expandedMarkdown){NotebookAssetSyntax.images(expandedMarkdown)}
                val displayMarkdown=remember(expandedMarkdown){NotebookAssetSyntax.withoutImages(expandedMarkdown)}
                Column(Modifier.fillMaxWidth().widthIn(max=readerStyle.maxWidthDp.dp).padding(vertical=if(index==0)12.dp else 0.dp)){
                    if(block.kind==MarkdownBlockKind.Table){
                        Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceVariant,modifier=Modifier.fillMaxWidth().clickable{onTable(block.startUtf16,block.endUtf16)}){Column(Modifier.padding(16.dp)){Row{Text("表格",Modifier.weight(1f),fontWeight=FontWeight.SemiBold);IconButton({onTable(block.startUtf16,block.endUtf16)}){Icon(Icons.Default.OpenInFull,"全屏表格")}};val table=remember(displayMarkdown){runCatching{MarkdownTable.parse(displayMarkdown)}.getOrNull()?.takeIf{it.rows.isNotEmpty()}};if(table!=null)ReaderTable(table,readerStyle.fontSizeSp,Modifier.fillMaxWidth().padding(top=10.dp))else MarkdownView(displayMarkdown,Modifier.fillMaxWidth().padding(top=10.dp),style=readerStyle,renderer=markwon)}}

                    }else if(block.kind==MarkdownBlockKind.List&&block.text.lines().all{it.isBlank()||Regex("^[ \t]*[-+*] \\[[ xX]] .*").matches(it)}){
                        var lineOffset=block.startUtf16
                        block.text.lines().filter(String::isNotBlank).forEach{line->val at=lineOffset;lineOffset+=line.length+1;val match=Regex("^[ \t]*[-+*] \\[([ xX])] (.*)").find(line)!!;val checked=match.groupValues[1].equals("x",true);Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Checkbox(checked,{next->val marker=at+line.indexOf('[')+1;onUpdateBody(note.body.replaceRange(marker,marker+1,if(next)"x" else " "))},enabled=note.deletedAt==null);Text(match.groupValues[2],textDecoration=if(checked)androidx.compose.ui.text.style.TextDecoration.LineThrough else null,color=if(checked)MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)}}
                    }else if(displayMarkdown.isNotBlank())MarkdownView(markdown=displayMarkdown,modifier=Modifier.fillMaxWidth(),style=readerStyle,highlightQuery=searchQuery,onNotebookLink=::openReference,onViewReady={views[index]=it},onViewReleased={if(views[index]===it)views.remove(index)},renderer=markwon,prerendered=ready.spans[displayMarkdown])
                    val blockReferences=references.filter{it.start in block.startUtf16 until block.endUtf16}
                    if(blockReferences.isNotEmpty())FlowRow(Modifier.padding(top=6.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        blockReferences.forEach{reference->
                            val title=allNotes.firstOrNull{it.id==reference.pageId}?.takeIf{canExposeNote(it,encryptedUnlocked,repo::isEncrypted)}?.title?.ifBlank{"无标题"}?:reference.alias?:"关联页面"
                            AssistChip(onClick={onOpenNote(reference.pageId)},label={Text(if(reference.embed)"打开嵌入：$title" else "打开：$title",maxLines=1)},leadingIcon={Icon(Icons.Default.OpenInNew,null)},modifier=Modifier.testTag("open-reference-${reference.pageId}"))
                        }
                    }
                    imageRefs.forEach{image->(attachments.firstOrNull{it.id==image.assetId}?:referenceAssets[image.assetId])?.let{asset->AttachmentView(asset,caption=image.title?:asset.caption?:image.alt.takeIf(String::isNotBlank))}}
                }
            }
        }
        if(showBody&&note.itemType=="todo"&&steps.isNotEmpty()){item(key="todo-divider"){HorizontalDivider(Modifier.widthIn(max=readerStyle.maxWidthDp.dp))};items(steps,key={"read-step-${it.id}"}){step->Row(Modifier.fillMaxWidth().widthIn(max=readerStyle.maxWidthDp.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Icon(if(step.checked)Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,null);Text(step.text,Modifier.padding(8.dp))}}}
        if(showBody)items(attachments.filter{it.id !in referencedAssetIds},key={"read-asset-${it.id}"}){Box(Modifier.fillMaxWidth().widthIn(max=readerStyle.maxWidthDp.dp)){AttachmentView(it)}}
    };ReaderScrollIndicator(state);if(showingSearch)Surface(Modifier.align(androidx.compose.ui.Alignment.TopCenter).fillMaxWidth().widthIn(max=readerStyle.maxWidthDp.dp).padding(vertical=4.dp),shape=MaterialTheme.shapes.large,tonalElevation=4.dp){Row(Modifier.padding(horizontal=10.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){OutlinedTextField(searchQuery,{searchQuery=it;searchIndex=0},Modifier.weight(1f),singleLine=true,label={Text("搜索当前文档")});Text(if(searchMatches.isEmpty())"0/0" else "${searchIndex+1}/${searchMatches.size}",Modifier.padding(horizontal=8.dp),style=MaterialTheme.typography.labelMedium);IconButton({navigateSearch(-1)},enabled=searchMatches.isNotEmpty()){Icon(Icons.Default.KeyboardArrowUp,"上一个")};IconButton({navigateSearch(1)},enabled=searchMatches.isNotEmpty()){Icon(Icons.Default.KeyboardArrowDown,"下一个")};IconButton({showingSearch=false;searchQuery="";searchIndex=0}){Icon(Icons.Default.Close,"关闭搜索")}}}}
    if(showingOutline)ModalBottomSheet(onDismissRequest={showingOutline=false}){if(document.headings.isEmpty())Text("添加标题后，这里会出现目录",Modifier.padding(24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant);Text("目录",Modifier.padding(horizontal=20.dp,vertical=8.dp),style=MaterialTheme.typography.titleLarge);LazyColumn(Modifier.fillMaxWidth().heightIn(max=520.dp)){items(document.headings,key={it.blockId}){heading->ListItem(headlineContent={Text(heading.title,maxLines=2)},modifier=Modifier.clickable{val blockIndex=blocks.indexOfFirst{it.id==heading.blockId};if(blockIndex>=0)launchScroll(scope,state,bodyStart+blockIndex);showingOutline=false}.padding(start=((heading.level-1)*14).dp))}};Spacer(Modifier.height(24.dp))}
    if(showingBacklinks)ModalBottomSheet(onDismissRequest={showingBacklinks=false}){Text("链接到此页",Modifier.padding(horizontal=20.dp,vertical=8.dp),style=MaterialTheme.typography.titleLarge);if(visibleBacklinks.isEmpty())Text("还没有其他可查看的页面引用这里",Modifier.padding(20.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)else LazyColumn(Modifier.fillMaxWidth().heightIn(max=520.dp)){items(visibleBacklinks,key={it.id}){link->val source=allNotes.firstOrNull{it.id==link.sourceNoteId};ListItem(headlineContent={Text(source?.title?.ifBlank{"无标题"}?:"已删除页面")},supportingContent={Text(when{link.id.contains(":title:")->"以标题链接到此页";link.targetKind=="block"->"引用了内容块";link.targetKind=="group"->"引用了连续内容";else->"引用了此页面"})},leadingContent={Icon(if(link.embed)Icons.Default.DynamicFeed else Icons.Default.Link,null)},modifier=Modifier.clickable{showingBacklinks=false;openReference("notebook://page/${link.sourceNoteId}")})}};Spacer(Modifier.height(24.dp))}
    if(showingSettings)ReaderSettingsDialog(readerStyle,{showingSettings=false}){updateReaderStyle(it);showingSettings=false}
}

@Composable private fun ReaderSettingsDialog(current:ReaderStyle,onDismiss:()->Unit,onConfirm:(ReaderStyle)->Unit){
    var fontSize by remember(current){mutableFloatStateOf(current.fontSizeSp)};var lineHeight by remember(current){mutableFloatStateOf(current.lineHeight)};var width by remember(current){mutableFloatStateOf(current.maxWidthDp)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("阅读设置")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("字号 ${fontSize.roundToInt()} sp");Slider(fontSize,{fontSize=it},valueRange=13f..30f,steps=16);Text("行高 ${String.format(Locale.ROOT,"%.2f",lineHeight)}");Slider(lineHeight,{lineHeight=it},valueRange=1.05f..1.8f);Text("正文最大宽度 ${width.roundToInt()} dp");Slider(width,{width=it},valueRange=480f..1200f,steps=8)}},confirmButton={Button({onConfirm(ReaderStyle(fontSize,lineHeight,width))}){Text("保存")}},dismissButton={TextButton(onDismiss){Text("取消")}})
}

private fun launchScroll(scope:kotlinx.coroutines.CoroutineScope,state:androidx.compose.foundation.lazy.LazyListState,item:Int){scope.launch{state.scrollToItem(item)}}

/**
 * Fixed-size thumb positioned by an estimated pixel offset. Sizing the thumb by
 * "visible items / total items" made it grow and shrink while scrolling past
 * images and paragraphs of different heights. The position is read only in the
 * layout phase, so scrolling does not recompose the reader.
 */
@Composable private fun BoxScope.ReaderScrollIndicator(state:androidx.compose.foundation.lazy.LazyListState){
    val scrollable by remember(state){derivedStateOf{state.canScrollForward||state.canScrollBackward}}
    if(!scrollable)return
    val progress=remember(state){derivedStateOf{
        val info=state.layoutInfo;val items=info.visibleItemsInfo
        if(items.isEmpty())0f
        else if(!state.canScrollForward)1f
        else if(!state.canScrollBackward)0f
        else{
            val average=items.sumOf{it.size}.toFloat()/items.size
            val viewport=(info.viewportEndOffset-info.viewportStartOffset).toFloat().coerceAtLeast(1f)
            val estimatedTotal=(average*info.totalItemsCount).coerceAtLeast(viewport+1f)
            ((state.firstVisibleItemIndex*average+state.firstVisibleItemScrollOffset)/(estimatedTotal-viewport)).coerceIn(0f,1f)
        }
    }}
    var active by remember{mutableStateOf(false)}
    val scrolling=state.isScrollInProgress
    LaunchedEffect(scrolling){if(scrolling)active=true else{delay(1_000);active=false}}
    val alpha by androidx.compose.animation.core.animateFloatAsState(if(active)1f else 0f,androidx.compose.animation.core.tween(if(active)120 else 400),label="reader-scroll")
    val thumb=36.dp
    BoxWithConstraints(Modifier.align(androidx.compose.ui.Alignment.CenterEnd).fillMaxHeight().width(7.dp).padding(end=2.dp,top=4.dp,bottom=4.dp).graphicsLayer{this.alpha=alpha}){
        val travel=maxHeight-thumb
        Box(Modifier.align(androidx.compose.ui.Alignment.TopEnd).width(4.dp).height(thumb).offset{IntOffset(0,(travel.toPx()*progress.value).roundToInt())}.background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.5f),MaterialTheme.shapes.small))
    }
}

internal fun createMarkdownRenderer(context:android.content.Context,onNotebookLink:(String)->Unit={}):Markwon=Markwon.builder(context)
    .usePlugin(SoftBreakAddsNewLinePlugin.create())
    .usePlugin(StrikethroughPlugin.create())
    .usePlugin(TablePlugin.create(context))
    .usePlugin(object:io.noties.markwon.AbstractMarkwonPlugin(){
        override fun configureTheme(builder:io.noties.markwon.core.MarkwonTheme.Builder){builder.headingBreakHeight(0);builder.headingTextSizeMultipliers(floatArrayOf(26f/17f,22f/17f,18f/17f,1f,1f,1f))}
        override fun configureConfiguration(builder:io.noties.markwon.MarkwonConfiguration.Builder){
            val defaultResolver=io.noties.markwon.LinkResolverDef()
            builder.linkResolver{view,link->if(link.startsWith("notebook://page/"))onNotebookLink(link)else defaultResolver.resolve(view,link)}
        }
    })
    .build()

internal fun configureMarkdownTextView(view:TextView){
    view.isSingleLine=false
    view.setHorizontallyScrolling(false)
    // setTextIsSelectable installs ArrowKeyMovementMethod. Apply the link
    // movement method afterwards so links remain tappable while long-press
    // selection and copying still work.
    view.setTextIsSelectable(true)
    view.linksClickable=true
    view.movementMethod=LinkMovementMethod.getInstance()
    view.importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
}

@Composable private fun MarkdownView(markdown:String,modifier:Modifier=Modifier,style:ReaderStyle=ReaderStyle(),highlightQuery:String="",onNotebookLink:(String)->Unit={},onViewReady:(TextView)->Unit={},onViewReleased:(TextView)->Unit={},renderer:Markwon?=null,prerendered:android.text.Spanned?=null){val context=LocalContext.current;val color=MaterialTheme.colorScheme.onBackground.toArgb();val highlight=MaterialTheme.colorScheme.tertiaryContainer.toArgb();val latestNotebookLink by rememberUpdatedState(onNotebookLink);val markwon=renderer?:remember(context){createMarkdownRenderer(context){latestNotebookLink(it)}};val holder=remember{arrayOfNulls<TextView>(1)};DisposableEffect(Unit){onDispose{holder[0]?.let(onViewReleased)}};AndroidView(factory={TextView(it).apply{holder[0]=this;configureMarkdownTextView(this)}},update={view->view.setTextColor(color);view.setTextSize(TypedValue.COMPLEX_UNIT_SP,style.fontSizeSp);view.setLineSpacing(0f,style.lineHeight);val renderKey=Triple(markdown,style.fontSizeSp,highlightQuery);if(view.tag!=renderKey){if(prerendered!=null&&highlightQuery.isBlank())markwon.setParsedMarkdown(view,prerendered)else markwon.setMarkdown(view,markdown);if(highlightQuery.isNotBlank()){val highlighted=SpannableString(view.text);var start=0;while(start<highlighted.length){val found=highlighted.toString().indexOf(highlightQuery,start,ignoreCase=true);if(found<0)break;highlighted.setSpan(BackgroundColorSpan(highlight),found,found+highlightQuery.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);start=found+highlightQuery.length.coerceAtLeast(1)};view.text=highlighted};view.tag=renderKey};onViewReady(view)},modifier=modifier)}

/** Known image proportions, from metadata or a bounds-only decode (cached). */
private val imageRatioCache=java.util.concurrent.ConcurrentHashMap<String,Float>()
internal fun imageAspectRatio(asset:AssetEntity,file:java.io.File):Float?{
    val w=asset.width;val h=asset.height
    if(w!=null&&h!=null&&w>0&&h>0)return w.toFloat()/h
    val key=file.absolutePath+":"+file.length()
    imageRatioCache[key]?.let{return it.takeIf{r->r>0f}}
    val options=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
    runCatching{android.graphics.BitmapFactory.decodeFile(file.absolutePath,options)}
    val ratio=if(options.outWidth>0&&options.outHeight>0)options.outWidth.toFloat()/options.outHeight else 0f
    imageRatioCache[key]=ratio
    return ratio.takeIf{it>0f}
}

@Composable internal fun AttachmentView(asset:AssetEntity,caption:String?=asset.caption){val context=LocalContext.current;val file=asset.localPath?.let{java.io.File(it)};fun open(){if(file?.isFile!=true)return;val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file);runCatching{context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,asset.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}};if(asset.kind=="image"&&file?.isFile==true)Column(Modifier.fillMaxWidth().padding(vertical=6.dp),horizontalAlignment=when(asset.alignment){"center"->androidx.compose.ui.Alignment.CenterHorizontally;"right"->androidx.compose.ui.Alignment.End;else->androidx.compose.ui.Alignment.Start}){AsyncImage(file,asset.filename,Modifier.fillMaxWidth().then(asset.displayWidth?.let{Modifier.widthIn(max=it.coerceIn(80,1200).dp)}?:Modifier).heightIn(max=720.dp).then(imageAspectRatio(asset,file)?.let{Modifier.aspectRatio(it)}?:Modifier).clickable{open()},contentScale=androidx.compose.ui.layout.ContentScale.Fit);caption?.takeIf(String::isNotBlank)?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))}}else ListItem(headlineContent={Text(asset.filename,maxLines=1)},supportingContent={Text(if(file?.isFile==true)formatBytes(asset.size) else "等待下载")},leadingContent={Icon(if(asset.kind=="audio")Icons.Default.AudioFile else Icons.Default.AttachFile,null)},modifier=Modifier.clickable{open()})}

private fun formatBytes(bytes:Long)=when{bytes<1024->"$bytes B";bytes<1024*1024->String.format(Locale.getDefault(),"%.1f KB",bytes/1024.0);else->String.format(Locale.getDefault(),"%.1f MB",bytes/(1024.0*1024.0))}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ReminderDialog(current:Long?,currentRule:String,onDismiss:()->Unit,onConfirm:(Long?,String)->Unit){val base=current?:System.currentTimeMillis()+3_600_000;val cal=remember(base){Calendar.getInstance().apply{timeInMillis=base}};val date=rememberDatePickerState(initialSelectedDateMillis=base);val time=rememberTimePickerState(cal.get(Calendar.HOUR_OF_DAY),cal.get(Calendar.MINUTE),true);var rule by remember{mutableStateOf(currentRule)};AlertDialog(onDismissRequest=onDismiss,title={Text("设置提醒")},text={Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())){DatePicker(date);TimePicker(time);Text("重复",fontWeight=FontWeight.Medium);SingleChoiceSegmentedButtonRow{listOf("none" to "一次","daily" to "每天","weekly" to "每周","monthly" to "每月").forEachIndexed{i,p->SegmentedButton(selected=rule==p.first,onClick={rule=p.first},shape=SegmentedButtonDefaults.itemShape(i,4)){Text(p.second)}}}}},confirmButton={Button({val selected=date.selectedDateMillis;if(selected==null)onConfirm(null,"none")else{val c=Calendar.getInstance().apply{timeInMillis=selected;set(Calendar.HOUR_OF_DAY,time.hour);set(Calendar.MINUTE,time.minute);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)};onConfirm(c.timeInMillis,rule)}}){Text("确定")}},dismissButton={Row{TextButton({onConfirm(null,"none")}){Text("清除")};TextButton(onDismiss){Text("取消")}}})}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ManagementScreen(kind:String,repo:SyncRepository,folders:List<FolderEntity>,tags:List<TagEntity>,onBack:()->Unit){BackHandler(onBack=onBack);val scope=rememberCoroutineScope();var name by remember{mutableStateOf("")};var pendingDelete by remember{mutableStateOf<Pair<String,String>?>(null)};val isTag=kind=="tag";val folderType=if(kind=="todoList")"todoList" else "noteFolder";val title=when(kind){"todoList"->"管理提醒文件夹";"tag"->"管理标签";else->"管理笔记文件夹"};Scaffold(topBar={TopAppBar(title={Text(title)},navigationIcon={IconButton(onBack,Modifier.minimumInteractiveComponentSize()){Icon(Icons.Default.ArrowBack,"返回")}})}){p->Column(Modifier.padding(p).padding(16.dp)){Row{OutlinedTextField(name,{name=it},Modifier.weight(1f),singleLine=true,label={Text(if(isTag)"新标签名称" else if(folderType=="todoList")"新提醒文件夹名称" else "新文件夹名称")});IconButton({if(name.isNotBlank()){scope.launch{if(isTag)repo.saveTag(name.trim())else repo.saveFolder(name.trim(),folderType)};name=""}},Modifier.minimumInteractiveComponentSize()){Icon(Icons.Default.Add,"添加")}};Spacer(Modifier.height(12.dp));LazyColumn{if(!isTag)items(folders.filter{it.type==folderType},key={it.id}){item->ListItem(headlineContent={Text(item.name)},leadingContent={Icon(if(folderType=="todoList")Icons.Default.ListAlt else Icons.Default.Folder,null)},trailingContent={IconButton({pendingDelete=item.id to item.name},Modifier.minimumInteractiveComponentSize()){Icon(Icons.Default.Delete,"删除 ${item.name}")}})}else items(tags,key={it.id}){item->ListItem(headlineContent={Text(item.name)},leadingContent={Icon(Icons.Default.Label,null)},trailingContent={IconButton({pendingDelete=item.id to item.name},Modifier.minimumInteractiveComponentSize()){Icon(Icons.Default.Delete,"删除 ${item.name}")}})}}}};pendingDelete?.let{(id,label)->AlertDialog(onDismissRequest={pendingDelete=null},icon={Icon(Icons.Default.Warning,null,tint=MaterialTheme.colorScheme.error)},title={Text("删除 $label？")},text={Text("此操作会影响使用该${if(isTag)"标签" else "文件夹"}的内容。")},confirmButton={Button({scope.launch{if(isTag)repo.deleteTag(id)else repo.deleteFolder(id)};pendingDelete=null},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text("删除")}},dismissButton={TextButton({pendingDelete=null}){Text("取消")}})}}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ServerSettings(repo:SyncRepository,onBack:()->Unit){
    BackHandler(onBack=onBack)
    val context=LocalContext.current
    var backend by remember{mutableStateOf(repo.syncBackend())}
    var w by remember{mutableStateOf(repo.webdavSettings())}
    var s by remember{mutableStateOf(repo.settings())};var message by remember{mutableStateOf<String?>(null)};var isError by remember{mutableStateOf(false)};var syncing by remember{mutableStateOf(false)};var changedKey by remember{mutableStateOf<HostKeyChangedException?>(null)};var newKey by remember{mutableStateOf<HostKeyConfirmationRequiredException?>(null)};val scope=rememberCoroutineScope()
    var showLegacySync by remember{mutableStateOf(backend==SyncBackend.SSH||backend==SyncBackend.API)}
    var diagnosticMessage by remember{mutableStateOf<String?>(null)}
    suspend fun syncNow(){
        if(syncing)return
        syncing=true;message="正在连接服务器并同步…";isError=false;changedKey=null;newKey=null
        try{
            repo.sync{message=it}
            message=repo.syncCompletionMessage()
        }catch(error:kotlinx.coroutines.CancellationException){
            message="同步已取消";throw error
        }catch(error:Exception){
            when(error){
                is HostKeyConfirmationRequiredException->{newKey=error;message="首次连接需要确认服务器身份"}
                is HostKeyChangedException->{changedKey=error;message="服务器主机密钥发生变化，需要你确认"}
                else->message="连接或同步失败：${describeSyncError(error)}"
            }
            isError=true
        }finally{syncing=false}
    }
    fun testWebdavNow(){scope.launch{syncing=true;message="正在检测连接…";isError=false;runCatching{repo.testWebdav()}.onSuccess{result->if(result.ok)message=if(result.remotePathExists)"连接正常（${result.latencyMs} ms），远端目录可访问。" else "连接正常（${result.latencyMs} ms）；远端目录尚不存在，首次同步时会自动创建。" else{message="连接失败：${result.message}";isError=true}}.onFailure{error->message="连接失败：${error.localizedMessage?:error.message}";isError=true};syncing=false}}
    val scanner=rememberLauncherForActivityResult(ScanContract()){result->result.contents?.let{payload->runCatching{
        val type=runCatching{com.google.gson.JsonParser.parseString(payload).asJsonObject.get("type")?.asString}.getOrNull()
        when(type){
            "notebook-webdav"->{val config=parseWebdavQrConfig(payload);repo.saveWebdavSettings(config);repo.setSyncBackend(SyncBackend.WEBDAV);backend=SyncBackend.WEBDAV;w=config;message="已从二维码导入坚果云配置，正在同步…"}
            else->{val config=parseSyncQrConfig(payload);s=config;repo.saveSettings(config)}
        }
    }.onSuccess{scope.launch{syncNow()}}.onFailure{message=it.message?:"无法读取配置二维码";isError=true}}}
    var privateKeyPassphrase by remember{mutableStateOf(s.privateKeyPassphrase)}
    val keyPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let{source->scope.launch{runCatching{repo.importPrivateKey(source,privateKeyPassphrase)}.onSuccess{s=repo.settings();message="私钥已导入"}.onFailure{message=it.message?:"无法导入私钥";isError=true}}}}
    newKey?.let{key->AlertDialog(onDismissRequest={newKey=null},icon={Icon(Icons.Default.Security,null)},title={Text("确认服务器身份")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("首次连接不会自动信任服务器。请将下面的 SHA-256 指纹与桌面端或服务器管理员提供的值核对，确认一致后再继续。");Text(key.actual,style=MaterialTheme.typography.bodySmall)}},confirmButton={Button({repo.trustHostKey(key.actual);s=repo.settings();newKey=null;scope.launch{syncNow()}}){Text("指纹一致，信任并继续")}},dismissButton={TextButton({newKey=null}){Text("取消")}})}
    changedKey?.let{change->AlertDialog(onDismissRequest={changedKey=null},icon={Icon(Icons.Default.Security,null)},title={Text("服务器身份已变化")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("这可能是服务器重装、SSH 密钥更新，也可能存在安全风险。确认这是你的服务器后才能继续。");Text("原指纹\n${change.expected}",style=MaterialTheme.typography.bodySmall);Text("新指纹\n${change.actual}",style=MaterialTheme.typography.bodySmall)}},confirmButton={Button({repo.trustHostKey(change.actual);s=repo.settings();changedKey=null;scope.launch{syncNow()}}){Text("信任并继续")}},dismissButton={TextButton({changedKey=null}){Text("取消")}})}
    Scaffold(topBar={TopAppBar(title={Text("设置与同步")},navigationIcon={IconButton(onBack){Icon(Icons.Default.ArrowBack,null)}})}){p->
        Column(Modifier.padding(p).padding(20.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            ReminderPermissionCard()
            Text("正式同步",fontWeight=FontWeight.SemiBold)
            Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer),modifier=Modifier.fillMaxWidth()){
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Icon(Icons.Default.CloudSync,"坚果云 WebDAV");Spacer(Modifier.width(8.dp));Text("坚果云 WebDAV",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold);Spacer(Modifier.weight(1f));if(backend==SyncBackend.WEBDAV)AssistChip(onClick={},label={Text("当前使用")})}
                    Text("唯一推荐的正式同步方式。支持可靠的跨设备同步与冲突处理。",style=MaterialTheme.typography.bodySmall)
                    if(backend!=SyncBackend.WEBDAV)OutlinedButton({backend=SyncBackend.WEBDAV;showLegacySync=false},Modifier.minimumInteractiveComponentSize()){Text("使用坚果云 WebDAV")}
                }
            }
            message?.let{Card(colors=CardDefaults.cardColors(containerColor=if(isError)MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer),modifier=Modifier.fillMaxWidth()){Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){if(syncing)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp);Icon(if(isError)Icons.Default.ErrorOutline else Icons.Default.CheckCircle,null);Text(it,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)}}}
            HorizontalDivider()
            if(backend==SyncBackend.WEBDAV){
                Text("坚果云 WebDAV",fontWeight=FontWeight.SemiBold)
                OutlinedTextField(w.baseUrl,{w=w.copy(baseUrl=it)},label={Text("服务器地址")},placeholder={Text("https://dav.jianguoyun.com/dav/")})
                OutlinedTextField(w.username,{w=w.copy(username=it)},label={Text("用户名")})
                OutlinedTextField(w.appPassword,{w=w.copy(appPassword=it)},label={Text("应用密码")},visualTransformation=PasswordVisualTransformation())
                OutlinedTextField(w.remotePath,{w=w.copy(remotePath=it)},label={Text("远程目录")})
                OutlinedTextField(w.syncPassword,{w=w.copy(syncPassword=it)},label={Text("隐私同步密钥")},visualTransformation=PasswordVisualTransformation())
                Text("坚果云需先在网页版“安全选项”中生成应用密码。正文和一般变更停止 30 秒后合并同步；应用进入前台或后台时立即同步。阅读位置只在进入后台时发布。配置与电脑相同的隐私同步密钥后，私密笔记、附件及历史以密文同步；密钥只保存在设备上。",style=MaterialTheme.typography.bodySmall)
                Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer),modifier=Modifier.fillMaxWidth()){
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                        Text("私密文件夹说明",fontWeight=FontWeight.SemiBold)
                        Text("私密文件夹通过设备锁保护访问。本地数据依赖 Android 系统存储加密；云端内容使用隐私同步密钥加密。请在已有设备上保留密钥，恢复到新设备时需要同一密钥。未设置密钥的私密内容仍只保存在本机。",style=MaterialTheme.typography.bodySmall)
                    }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    OutlinedButton({testWebdavNow()},Modifier.weight(1f),enabled=!syncing){Text(if(syncing)"正在检测…" else "检测连接")}
                    Button({runCatching{repo.saveWebdavSettings(w)}.onSuccess{backend=SyncBackend.WEBDAV;scope.launch{syncNow()}}.onFailure{message=it.localizedMessage?:"同步配置保存失败，请重试";isError=true}},Modifier.weight(1f),enabled=!syncing){Text(if(syncing)"正在同步…" else "保存并同步")}
                }
                SyncHealthCard(repo,syncing,message){kind->scope.launch{
                    runCatching{if(kind=="reread")repo.resetSyncReadPositions() else repo.republishAll()}
                        .onSuccess{syncNow()}
                        .onFailure{message="修复失败：${it.localizedMessage?:it.javaClass.simpleName}";isError=true}
                }}
            }
            OutlinedCard(onClick={showLegacySync=!showLegacySync},modifier=Modifier.fillMaxWidth()){
                Row(Modifier.padding(14.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Icon(Icons.Default.History,"旧同步方式与迁移");Spacer(Modifier.width(8.dp));Column(Modifier.weight(1f)){Text("旧同步方式与迁移",fontWeight=FontWeight.SemiBold);Text("仅用于迁移已有 SSH 或 API 仓库，不建议持续同步。",style=MaterialTheme.typography.bodySmall)};Icon(if(showLegacySync)Icons.Default.ExpandLess else Icons.Default.ExpandMore,if(showLegacySync)"收起旧同步方式" else "展开旧同步方式")}
            }
            OutlinedButton({scope.launch{runCatching{repo.exportSyncDiagnostics()}.onSuccess{message="同步诊断已保存（仅实体标识与校验值，不含正文、标题或密钥）"}.onFailure{message="诊断导出失败：${it.message}";isError=true}}},enabled=!syncing){Text("导出同步诊断")}
            if(showLegacySync){
                Text("SSH/SFTP 旧仓库迁移",fontWeight=FontWeight.SemiBold)
                Text("旧配置会保留，方便完成迁移；请先同步并导入到坚果云 WebDAV，之后将正式同步切回 WebDAV。HTTP API 也仅作为旧部署迁移兼容，不作为新的客户端同步入口。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(backend!=SyncBackend.SSH)OutlinedButton({backend=SyncBackend.SSH},Modifier.minimumInteractiveComponentSize()){Text("打开 SSH 旧配置")}
            }
            if(showLegacySync&&backend==SyncBackend.SSH){
                Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant),modifier=Modifier.fillMaxWidth()){
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text("SSH/SFTP 同步（仅迁移）",fontWeight=FontWeight.SemiBold)
                Button({scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("扫描 Notebook 配置二维码").setBeepEnabled(false).setOrientationLocked(false))},Modifier.fillMaxWidth()){Icon(Icons.Default.QrCodeScanner,null);Spacer(Modifier.width(8.dp));Text("扫描配置二维码")}
                fun update(host:String=s.host,port:Int=s.port,user:String=s.username,password:String=s.password,path:String=s.path,privateKeyPath:String=s.privateKeyPath){s=SshSettings(host,port,user,password,path,s.fingerprint,privateKeyPath,privateKeyPassphrase)}
                OutlinedTextField(s.host,{update(host=it)},label={Text("服务器地址")});OutlinedTextField(s.port.toString(),{update(port=it.toIntOrNull()?:22)},label={Text("端口")});OutlinedTextField(s.username,{update(user=it)},label={Text("用户名")});OutlinedTextField(s.password,{update(password=it)},label={Text("密码（可选）")},visualTransformation=PasswordVisualTransformation());OutlinedTextField(s.path,{update(path=it)},label={Text("远程目录")})
                HorizontalDivider();Text("SSH 私钥",fontWeight=FontWeight.SemiBold)
                if(s.privateKeyPath.isNotBlank())Text("已导入：${s.privateKeyPath}",style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(privateKeyPassphrase,{privateKeyPassphrase=it;update()},label={Text("私钥口令（可选）")},visualTransformation=PasswordVisualTransformation())
                Button({keyPicker.launch(arrayOf("*/*"))},Modifier.fillMaxWidth(),enabled=!syncing){Icon(Icons.Default.Key,null);Spacer(Modifier.width(8.dp));Text(if(s.privateKeyPath.isBlank())"导入私钥文件" else "重新导入私钥")}
                if(s.privateKeyPath.isNotBlank())OutlinedButton({repo.clearPrivateKey();s=repo.settings()}){Text("清除私钥")}
                if(s.fingerprint.isNotBlank())Text("已信任主机指纹：${s.fingerprint}",style=MaterialTheme.typography.bodySmall)
                Text("仅用于读取和迁移旧 SSH JSON 仓库。迁移完成后请改用上方的坚果云 WebDAV；不要把 SSH 作为持续同步方案。",style=MaterialTheme.typography.bodySmall)
                Button({repo.saveSettings(s);scope.launch{syncNow()}},Modifier.fillMaxWidth(),enabled=!syncing){Text(if(syncing)"正在同步…" else "保存旧配置并迁移")}
                    }
                }
            }
            HorizontalDivider()
            Text("诊断日志",fontWeight=FontWeight.SemiBold)
            Text("导出最近约 2 MiB 的结构化运行日志。日志不含笔记标题、正文、密码、令牌、原始网址或服务器响应。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick={scope.launch{
                runCatching{
                        val file=withContext(Dispatchers.IO){DiagnosticLogger.exportArchive(context)}
                        val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
                        val share=Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(share,"分享 Notebook 诊断日志"))
                    }.onSuccess{diagnosticMessage=null}.onFailure{error->
                        if(error is CancellationException)throw error
                        diagnosticMessage="诊断日志导出失败"
                    }
            }},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Share,null);Spacer(Modifier.width(8.dp));Text("导出并分享诊断日志")}
            diagnosticMessage?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        }
    }
}

@Composable private fun SyncHealthCard(repo:SyncRepository,syncing:Boolean,refreshKey:Any?,onRepair:(String)->Unit){
    var pending by remember{mutableStateOf<Int?>(null)};var copies by remember{mutableStateOf<Int?>(null)}
    var confirm by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(syncing,refreshKey){if(!syncing){pending=runCatching{repo.pendingUploadCount()}.getOrNull();copies=runCatching{repo.conflictCopyCount()}.getOrNull()}}
    val last=repo.lastSyncSuccessAt()
    OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
        Text("同步状态与修复",fontWeight=FontWeight.SemiBold)
        Text("上次成功同步：${last?.let{android.text.format.DateUtils.getRelativeTimeSpanString(it,System.currentTimeMillis(),android.text.format.DateUtils.MINUTE_IN_MILLIS)}?:"尚未成功同步"}",style=MaterialTheme.typography.bodySmall)
        Text("等待上传：${pending?.let{"$it 条变更"}?:"…"}    冲突副本：${copies?.let{if(it==0)"无" else "$it 篇"}?:"…"}",style=MaterialTheme.typography.bodySmall)
        Text("多台设备同时修改同一篇笔记时，同步不会中断：保留本机版本，另一版本另存为标题含“冲突副本”的笔记，可对比后删除。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedButton({confirm="reread"},Modifier.weight(1f),enabled=!syncing){Text("重新读取云端",maxLines=1)}
            OutlinedButton({confirm="republish"},Modifier.weight(1f),enabled=!syncing){Text("重新上传本机",maxLines=1)}
        }
        Text("别的设备的修改没出现：重新读取云端。云端目录被清空或新设备缺笔记：重新上传本机。两者都不会删除内容，可以放心重复执行。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }}
    confirm?.let{kind->AlertDialog(onDismissRequest={confirm=null},
        title={Text(if(kind=="reread")"重新读取全部云端记录？" else "重新上传本机全部内容？")},
        text={Text(if(kind=="reread")"忽略本机记住的读取位置，从头核对坚果云上所有设备的记录。已有内容不会被删除或重复，记录多时会多用一些流量。" else "把本机所有笔记、附件和设置重新排队上传，作为当前版本的后续版本。其他设备会自动合并，不会产生重复笔记。")},
        confirmButton={Button({confirm=null;onRepair(kind)}){Text("开始并同步")}},
        dismissButton={TextButton({confirm=null}){Text("取消")}})}
}

@Composable private fun ReminderPermissionCard(){val context=LocalContext.current;val owner=LocalLifecycleOwner.current;var refresh by remember{mutableIntStateOf(0)};val notificationLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){refresh++};DisposableEffect(owner){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)refresh++};owner.lifecycle.addObserver(observer);onDispose{owner.lifecycle.removeObserver(observer)}};val notifications=refresh.let{android.os.Build.VERSION.SDK_INT<33||context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED};val alarm=context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager;val exact=android.os.Build.VERSION.SDK_INT<31||alarm.canScheduleExactAlarms();Card(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("提醒权限",fontWeight=FontWeight.SemiBold);Text("通知：${if(notifications)"已允许" else "未允许"}    精确提醒：${if(exact)"已允许" else "将使用非精确提醒"}",style=MaterialTheme.typography.bodySmall);FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){if(!notifications)OutlinedButton({notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)}){Text("允许通知")};if(!exact&&android.os.Build.VERSION.SDK_INT>=31)OutlinedButton({val intent=Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,android.net.Uri.parse("package:${context.packageName}"));runCatching{context.startActivity(intent)}}){Text("允许精确提醒")}}}}}

@Composable private fun UpdateAction(state:AppUpdateViewModel){
    if(state.release==null)return
    IconButton({state.showDialog=true}){
        if(state.phase==UpdatePhase.Downloading){val fraction=state.progress.fraction;if(fraction!=null)CircularProgressIndicator(progress={fraction},modifier=Modifier.size(22.dp),strokeWidth=2.5.dp)else CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.5.dp)}
        else Icon(if(state.phase==UpdatePhase.Ready)Icons.Default.InstallMobile else Icons.Default.SystemUpdate,if(state.phase==UpdatePhase.Ready)"继续安装更新" else "有可用更新",tint=MaterialTheme.colorScheme.primary)
    }
}

@Composable private fun UpdatePrompt(state:AppUpdateViewModel){
    val release=state.release?:return
    if(!state.showDialog)return
    AlertDialog(onDismissRequest={state.showDialog=false},icon={Icon(if(state.phase==UpdatePhase.Ready)Icons.Default.InstallMobile else Icons.Default.SystemUpdate,null)},title={Text("发现新版本 ${release.version}")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("当前版本：${BuildConfig.VERSION_NAME}")
        release.notes.take(600).takeIf{it.isNotBlank()}?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
        if(state.phase==UpdatePhase.Downloading){val fraction=state.progress.fraction;if(fraction!=null)LinearProgressIndicator(progress={fraction},modifier=Modifier.fillMaxWidth())else LinearProgressIndicator(Modifier.fillMaxWidth());Text(downloadLabel(state.progress),style=MaterialTheme.typography.bodySmall)}
        state.message?.let{Text(it,color=if(state.phase==UpdatePhase.Failed)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)}
    }},confirmButton={Button(onClick=state::downloadOrInstall,enabled=state.phase!=UpdatePhase.Downloading){Text(if(state.downloaded==null)if(state.phase==UpdatePhase.Failed)"继续下载" else "下载并升级" else "继续安装")}},dismissButton={TextButton({state.showDialog=false}){Text(if(state.phase==UpdatePhase.Downloading)"后台下载" else "稍后")}})
}

private fun downloadLabel(progress:DownloadProgress):String{
    fun bytes(value:Long):String=when{
        value>=1024L*1024L -> "%.1f MB".format(Locale.US,value/1024.0/1024.0)
        value>=1024L -> "%.0f KB".format(Locale.US,value/1024.0)
        else -> "$value B"
    }
    val percent=progress.fraction?.let{"${(it*100).roundToInt()}% · "}.orEmpty()
    return if(progress.totalBytes>0)"$percent${bytes(progress.downloadedBytes)} / ${bytes(progress.totalBytes)}" else "$percent${bytes(progress.downloadedBytes)}"
}

/** Where imported files go: a folder's top level, or under the open note. */
@Composable private fun ImportTargetDialog(count:Int,folders:List<FolderEntity>,currentFolderId:String?,openNote:NoteEntity?,onDismiss:()->Unit,onImport:(FolderEntity?,NoteEntity?)->Unit){
    val options=buildList<Pair<String,Pair<FolderEntity?,NoteEntity?>>>{
        openNote?.let{note->add("“${note.title.ifBlank{"当前笔记"}}”下面" to (folders.firstOrNull{it.id==note.folderId} to note))}
        folders.sortedBy{it.sortOrder}.forEach{add(it.name to (it to null))}
        add("未分类" to (null to null))
    }
    var selected by remember{mutableStateOf(options.indexOfFirst{it.second.second==null&&it.second.first?.id==currentFolderId}.takeIf{it>=0}?:0)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("导入 $count 个文件到")},text={Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())){
        options.forEachIndexed{index,option->Row(Modifier.fillMaxWidth().clickable{selected=index}.padding(vertical=4.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){RadioButton(selected==index,{selected=index});Text(option.first,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)}}
        Text("支持 Markdown、.txt 和网页（.html）。.txt 保留原样换行；网页只保留结构。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp))
    }},confirmButton={Button({val target=options[selected].second;onImport(target.first,target.second)}){Text("导入")}},dismissButton={TextButton(onDismiss){Text("取消")}})
}

/**
 * A file or text arrived through "Open with" / "Share": put it in 待处理 to
 * sort out later, or choose where it becomes a note.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun IncomingSheet(repo:SyncRepository,share:IncomingShare,folders:List<FolderEntity>,notes:List<NoteSummary>,onDismiss:()->Unit,onConfirm:(inbox:Boolean,folder:FolderEntity?,parent:NoteSummary?)->Unit){
    var preview by remember(share){mutableStateOf<List<Pair<String,String>>?>(null)}
    LaunchedEffect(share){preview=if(share.text!=null)listOf((share.subject?.takeIf(String::isNotBlank)?:"分享的文字") to share.text.lineSequence().map(String::trim).filter(String::isNotEmpty).take(3).joinToString(" · ").take(160)) else repo.previewIncoming(share.uris)}
    var choosing by remember{mutableStateOf(false)}
    var folder by remember{mutableStateOf<FolderEntity?>(null)}
    var parent by remember{mutableStateOf<NoteSummary?>(null)}
    var query by remember{mutableStateOf("")}
    val supported=share.text!=null||preview?.any{it.second!="不支持的格式"}==true
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)){
        Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(bottom=24.dp)){
            Text(if(share.text!=null)"收到一段文字" else "用 Notebook 打开 ${share.uris.size} 个文件",style=MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            val items=preview
            if(items==null)LinearProgressIndicator(Modifier.fillMaxWidth()) else items.take(4).forEach{(name,snippet)->
                Surface(shape=androidx.compose.foundation.shape.RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceContainerHigh,modifier=Modifier.fillMaxWidth().padding(vertical=3.dp)){Column(Modifier.padding(12.dp)){Text(name,fontWeight=FontWeight.Medium,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis);if(snippet.isNotBlank())Text(snippet,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)}}
            }
            if((items?.size?:0)>4)Text("还有 ${items!!.size-4} 个文件",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            if(!choosing){
                Button({onConfirm(true,null,null)},enabled=supported,modifier=Modifier.fillMaxWidth().height(52.dp).testTag("incoming-inbox")){Icon(Icons.Default.Inbox,null);Spacer(Modifier.width(8.dp));Text("放到待处理")}
                Text("先存进“待处理”笔记库，以后再整理。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp,bottom=10.dp))
                OutlinedButton({choosing=true},enabled=supported,modifier=Modifier.fillMaxWidth().height(52.dp).testTag("incoming-choose")){Icon(Icons.Default.DriveFileMove,null);Spacer(Modifier.width(8.dp));Text("选择位置，变成笔记")}
            }else{
                Text("放到笔记库",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                Column(Modifier.heightIn(max=200.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())){
                    (listOf<FolderEntity?>(null)+folders.sortedBy{it.sortOrder}).forEach{item->Row(Modifier.fillMaxWidth().clickable{folder=item;parent=null}.padding(vertical=2.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){RadioButton(parent==null&&folder?.id==item?.id,{folder=item;parent=null});Text(item?.name?:"未分类")}}
                }
                Text("或放在某篇笔记下面",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary,modifier=Modifier.padding(top=10.dp))
                OutlinedTextField(query,{query=it},placeholder={Text("搜索笔记标题")},singleLine=true,modifier=Modifier.fillMaxWidth())
                val matches=remember(query,notes){if(query.isBlank())emptyList() else notes.filter{it.title.contains(query.trim(),ignoreCase=true)}.take(30)}
                Column(Modifier.heightIn(max=200.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())){
                    (listOfNotNull(parent?.takeIf{p->matches.none{it.id==p.id}})+matches).forEach{note->Row(Modifier.fillMaxWidth().clickable{parent=note;folder=folders.firstOrNull{it.id==note.folderId}}.padding(vertical=2.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){RadioButton(parent?.id==note.id,{parent=note;folder=folders.firstOrNull{it.id==note.folderId}});Column{Text(note.title.ifBlank{"无标题"},maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis);Text(note.folderName,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
                }
                Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.End){TextButton({choosing=false}){Text("返回")};Spacer(Modifier.width(8.dp));Button({onConfirm(false,folder,parent)}){Text(if(parent!=null)"放到“${parent!!.title.take(10)}”下面" else "放到“${folder?.name?:"未分类"}”")}}
            }
        }
    }
}
