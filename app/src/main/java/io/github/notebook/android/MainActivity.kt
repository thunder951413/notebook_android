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
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.notebook.android.reminder.Reminders
import io.github.notebook.android.sync.SshSettings
import io.github.notebook.android.sync.SyncRepository
import io.github.notebook.android.sync.parseSyncQrConfig
import io.github.notebook.android.sync.HostKeyChangedException
import io.github.notebook.android.ui.NotebookTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.*
import kotlin.math.roundToInt
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.github.notebook.android.update.AppUpdater
import io.github.notebook.android.update.DownloadProgress
import io.github.notebook.android.update.GithubRelease

class MainActivity : ComponentActivity() {
    private lateinit var repository:SyncRepository
    private val notificationTarget=mutableStateOf<String?>(null)
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Reminders.createChannel(this)
        repository = (application as NotebookApp).repository
        notificationTarget.value=intent.getStringExtra("noteId")
        setContent { NotebookTheme { NotebookScreen(repository,notificationTarget.value){notificationTarget.value=null} } }
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);notificationTarget.value=intent.getStringExtra("noteId")}
    override fun onStop(){repository.flushAllAsync();super.onStop()}
}

private enum class Destination(val title:String) { Today("今天"), Important("重要"), Todos("全部待办"), Completed("已完成"), All("全部笔记"), Unfiled("未分类"), Conflicts("同步冲突"), Trash("回收站") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun NotebookScreen(repo:SyncRepository,notificationNoteId:String?=null,onNotificationConsumed:()->Unit={}) {
    val all by repo.notes.collectAsState(initial=emptyList())
    val folders by repo.folders.collectAsState(initial=emptyList())
    val tags by repo.tags.collectAsState(initial=emptyList())
    val saveError by repo.saveError.collectAsState()
    val scope=rememberCoroutineScope(); val context=LocalContext.current
    val drawer=rememberDrawerState(DrawerValue.Closed)
    var destination by remember{mutableStateOf(Destination.All)}
    var folderId by remember{mutableStateOf<String?>(null)}
    var tagId by remember{mutableStateOf<String?>(null)};var manage by remember{mutableStateOf<String?>(null)}
    var query by remember{mutableStateOf("")};var matchingNoteIds by remember{mutableStateOf<Set<String>?>(null)}; var editing by remember{mutableStateOf<NoteEntity?>(null)};var startInEditMode by remember{mutableStateOf(false)};var loadingNoteId by remember{mutableStateOf<String?>(null)}
    var showSettings by remember{mutableStateOf(false)}; var status by remember{mutableStateOf<String?>(null)}
    var syncing by remember{mutableStateOf(false)};var changedKey by remember{mutableStateOf<HostKeyChangedException?>(null)}
    var encryptedUnlocked by remember{mutableStateOf(false)};var pendingEncryptedFolder by remember{mutableStateOf<String?>(null)}
    val encryptedUnlocker=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->if(result.resultCode==android.app.Activity.RESULT_OK){encryptedUnlocked=true;pendingEncryptedFolder?.let{folderId=it;tagId=null;destination=Destination.All};pendingEncryptedFolder=null;scope.launch{drawer.close()}}}
    val isTablet=LocalConfiguration.current.screenWidthDp>=840
    val drawerScroll=androidx.compose.foundation.rememberScrollState()
    val appUpdate=rememberAppUpdateState()
    UpdatePrompt(appUpdate)
    fun openNote(id:String,startEditing:Boolean=false){if(loadingNoteId!=null)return;loadingNoteId=id;scope.launch{runCatching{repo.loadNote(id)}.onSuccess{note->if(note!=null){startInEditMode=startEditing;editing=note}else status="笔记不存在或已被删除"}.onFailure{status="无法加载笔记：${it.localizedMessage}"};loadingNoteId=null}}
    LaunchedEffect(query){if(query.isBlank())matchingNoteIds=null else{delay(180);matchingNoteIds=runCatching{repo.searchNoteIds(query.trim())}.getOrElse{status="搜索失败：${it.localizedMessage}";emptySet()}}}
    LaunchedEffect(notificationNoteId,all){notificationNoteId?.let{id->all.firstOrNull{it.id==id}?.let{target->destination=if(target.itemType=="todo")Destination.Today else Destination.All;folderId=null;tagId=null;openNote(id);onNotificationConsumed()}}}
    fun runSync(){if(syncing)return;scope.launch{syncing=true;changedKey=null;status="正在同步…";runCatching{repo.sync()}.onSuccess{status="同步完成"}.onFailure{error->if(error is HostKeyChangedException){changedKey=error;status="服务器主机密钥发生变化，需要你确认"}else status=error.localizedMessage?:error.message?:"同步失败"};syncing=false}}
    changedKey?.let{change->AlertDialog(onDismissRequest={changedKey=null},icon={Icon(Icons.Default.Security,null)},title={Text("服务器身份已变化")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("确认这是你的服务器后，可以信任新的主机指纹并继续同步。");Text("原指纹\n${change.expected}",style=MaterialTheme.typography.bodySmall);Text("新指纹\n${change.actual}",style=MaterialTheme.typography.bodySmall)}},confirmButton={Button({repo.trustHostKey(change.actual);changedKey=null;runSync()}){Text("信任并继续")}},dismissButton={TextButton({changedKey=null}){Text("取消")}})}

    if(!isTablet) editing?.let { note -> Editor(note,repo,startInEditMode){editing=null};return }
    if(showSettings){ServerSettings(repo){showSettings=false};return}
    manage?.let{kind->ManagementScreen(kind,repo,folders,tags){manage=null};return}

    fun select(d:Destination,folder:String?=null,tag:String?=null){destination=d;folderId=folder;tagId=tag;scope.launch{drawer.close()}}
    fun openEncrypted(folder:String){if(encryptedUnlocked){select(Destination.All,folder)}else{val manager=context.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager;if(!manager.isDeviceSecure){status="请先在手机系统设置中设置锁屏密码或指纹";scope.launch{drawer.close()}}else{pendingEncryptedFolder=folder;encryptedUnlocker.launch(manager.createConfirmDeviceCredentialIntent("解锁加密文件夹","验证手机锁屏密码或指纹后查看锁定内容"))}}}
    val endOfToday=remember{Calendar.getInstance().apply{set(Calendar.HOUR_OF_DAY,23);set(Calendar.MINUTE,59);set(Calendar.SECOND,59);set(Calendar.MILLISECOND,999)}.timeInMillis}
    val visible=all.filter { n ->
        val encrypted=repo.isEncrypted(n);val selectedFolderType=folders.firstOrNull{it.id==folderId}?.type
        val section=when {
            tagId!=null -> !encrypted&&n.deletedAt==null&&n.tagIds.split(',').contains(tagId)
            folderId!=null&&selectedFolderType=="encryptedFolder" -> encryptedUnlocked&&n.deletedAt==null&&repo.encryptedFolderId(n)==folderId
            folderId!=null -> !encrypted&&n.deletedAt==null&&n.folderId==folderId&&(if(selectedFolderType=="todoList")n.itemType=="todo"&&n.completedAt==null else n.itemType=="note")
            destination==Destination.Today -> !encrypted&&n.itemType=="todo"&&n.deletedAt==null&&n.completedAt==null&&(n.dueAt?:Long.MAX_VALUE)<=endOfToday
            destination==Destination.Important -> !encrypted&&n.itemType=="todo"&&n.deletedAt==null&&n.completedAt==null&&n.important
            destination==Destination.Todos -> !encrypted&&n.itemType=="todo"&&n.deletedAt==null&&n.completedAt==null
            destination==Destination.Completed -> !encrypted&&n.itemType=="todo"&&n.deletedAt==null&&n.completedAt!=null
            destination==Destination.All -> !encrypted&&n.itemType=="note"&&n.deletedAt==null
            destination==Destination.Unfiled -> !encrypted&&n.itemType=="note"&&n.deletedAt==null&&n.folderId==null
            destination==Destination.Conflicts -> !encrypted&&n.conflict
            else -> n.deletedAt!=null&&(!encrypted||encryptedUnlocked)
        }
        section&&(query.isBlank()||matchingNoteIds?.contains(n.id)==true)
    }
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
            folders.filter{it.type=="noteFolder"}.sortedBy{it.sortOrder}.forEach{f->DrawerRow(f.name,Icons.Default.Folder,folderId==f.id,all.count{it.itemType=="note"&&it.deletedAt==null&&it.folderId==f.id}){select(Destination.All,f.id)}}
            Row(Modifier.fillMaxWidth().padding(20.dp,18.dp,12.dp,6.dp)){Text("加密文件夹",Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Icon(if(encryptedUnlocked)Icons.Default.LockOpen else Icons.Default.Lock,null,Modifier.size(16.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)}
            val encryptedFolders=folders.filter{it.type=="encryptedFolder"}.sortedBy{it.sortOrder};if(encryptedFolders.isNotEmpty()){if(!encryptedUnlocked)DrawerRow("点击解锁",Icons.Default.Lock,false){openEncrypted(encryptedFolders.first().id)}else encryptedFolders.forEach{f->DrawerRow(f.name,Icons.Default.Lock,folderId==f.id,all.count{it.deletedAt==null&&repo.encryptedFolderId(it)==f.id}){openEncrypted(f.id)}}}
            Row(Modifier.fillMaxWidth().padding(20.dp,18.dp,12.dp,6.dp)){Text("标签",Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Text("管理",Modifier.clickable{manage="tag"},style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)}
            tags.forEach{tag->DrawerRow(tag.name,Icons.Default.Label,tagId==tag.id){select(Destination.All,tag=tag.id)}}
            Spacer(Modifier.height(20.dp));HorizontalDivider();DrawerRow("设置与同步",Icons.Default.Settings,false){showSettings=true;scope.launch{drawer.close()}};Spacer(Modifier.height(12.dp))
    }
    val mainContent:@Composable ()->Unit={
        Scaffold(topBar={TopAppBar(title={Text(tagId?.let{id->tags.firstOrNull{it.id==id}?.name}?:folderId?.let{id->folders.firstOrNull{it.id==id}?.name}?:destination.title)},navigationIcon={if(!isTablet)IconButton({scope.launch{drawer.open()}}){Icon(Icons.Default.Menu,"导航")}},actions={UpdateAction(appUpdate);IconButton({runSync()},enabled=!syncing){if(syncing)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp) else Icon(Icons.Default.Sync,"同步")}})},floatingActionButton={if(destination!=Destination.Trash&&destination!=Destination.Completed)FloatingActionButton({val folder=folders.firstOrNull{it.id==folderId};val todo=destination in setOf(Destination.Today,Destination.Important,Destination.Todos)||folder?.type=="todoList";val id=UUID.randomUUID().toString();if(folder?.type=="encryptedFolder")repo.markEncrypted(id,folder.id);startInEditMode=true;editing=NoteEntity(id,itemType=if(todo)"todo" else "note",important=destination==Destination.Important,folderId=folderId,folderName=folder?.name?:"未分类",tagIds=tagId?:"")},Modifier.testTag("new-item")){Icon(Icons.Default.Add,"新建")}}){pad->
            Column(Modifier.padding(pad).fillMaxSize().background(MaterialTheme.colorScheme.background)){
                OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().padding(12.dp),singleLine=true,shape=MaterialTheme.shapes.large,placeholder={Text("搜索全部笔记")},leadingIcon={Icon(Icons.Default.Search,null)})
                status?.let{Text(it,Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.primary)}
                loadingNoteId?.let{LinearProgressIndicator(Modifier.fillMaxWidth())}
                saveError?.let{Text(it,Modifier.padding(horizontal=16.dp).clickable{repo.clearSaveError()},color=MaterialTheme.colorScheme.error)}
                Row(Modifier.fillMaxSize()){
                    Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surface)){if(visible.isEmpty())Box(Modifier.fillMaxSize().padding(32.dp)){Text("这里还没有内容",color=MaterialTheme.colorScheme.onSurfaceVariant)}else VirtualNoteList(visible,loadingNoteId){openNote(it)}}
                    if(isTablet){VerticalDivider();Box(Modifier.weight(1.65f).fillMaxHeight().background(MaterialTheme.colorScheme.background)){editing?.let{note->EditorPane(note,repo,initiallyEditing=startInEditMode,onDone={editing=null})}?:Box(Modifier.fillMaxSize().padding(40.dp)){Text("选择一篇笔记开始阅读",color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
                }
            }
        }
    }
    if(isTablet)PermanentNavigationDrawer(drawerContent={PermanentDrawerSheet(Modifier.width(280.dp).verticalScroll(drawerScroll).testTag("drawer-scroll"),drawerContainerColor=MaterialTheme.colorScheme.surfaceVariant,content=drawerItems)},content=mainContent)
    else ModalNavigationDrawer(drawerState=drawer,drawerContent={ModalDrawerSheet(Modifier.width(300.dp).verticalScroll(drawerScroll).testTag("drawer-scroll"),drawerContainerColor=MaterialTheme.colorScheme.surfaceVariant,content=drawerItems)},content=mainContent)
}

@Composable private fun DrawerRow(label:String,icon:androidx.compose.ui.graphics.vector.ImageVector,selected:Boolean,count:Int?=null,onClick:()->Unit){NavigationDrawerItem(label={Row{Text(label);Spacer(Modifier.weight(1f));count?.let{Text(it.toString(),style=MaterialTheme.typography.labelMedium)}}},icon={Icon(icon,null)},selected=selected,onClick=onClick,modifier=Modifier.padding(horizontal=8.dp,vertical=1.dp))}

@Composable private fun VirtualNoteList(notes:List<NoteSummary>,loadingNoteId:String?,onOpen:(String)->Unit){
    val state=rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize()){
        LazyColumn(Modifier.fillMaxSize(),state=state){items(notes,key={it.id}){n->NoteRow(n,loadingNoteId==n.id){onOpen(n.id)}}}
        val visibleCount=state.layoutInfo.visibleItemsInfo.size
        if(notes.size>visibleCount&&visibleCount>0){
            val thumbFraction=(visibleCount.toFloat()/notes.size).coerceIn(.08f,1f)
            val progress=(state.firstVisibleItemIndex.toFloat()/(notes.size-visibleCount).coerceAtLeast(1)).coerceIn(0f,1f)
            val thumbHeight=maxHeight*thumbFraction
            Box(Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(end=2.dp).width(4.dp).height(thumbHeight).offset{IntOffset(0,((maxHeight-thumbHeight).toPx()*progress).roundToInt())}.background(MaterialTheme.colorScheme.primary.copy(alpha=.45f),MaterialTheme.shapes.small))
        }
    }
}

@Composable private fun NoteRow(note:NoteSummary,loading:Boolean,onClick:()->Unit){ListItem(headlineContent={Row{Text(note.title.ifBlank{"无标题"},fontWeight=FontWeight.Medium);Spacer(Modifier.weight(1f));if(loading)CircularProgressIndicator(Modifier.size(15.dp),strokeWidth=2.dp)else if(note.reminderAt!=null)Icon(Icons.Default.Notifications,null,Modifier.size(15.dp),tint=MaterialTheme.colorScheme.primary)}},supportingContent={Column{Text(note.preview,maxLines=2);Spacer(Modifier.height(5.dp));Text("${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(note.updatedAt))}  ·  ${note.folderName}",style=MaterialTheme.typography.labelSmall)}},leadingContent={if(note.itemType=="todo")Icon(Icons.Default.CheckCircle,null,tint=if(note.completedAt==null)MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary)},modifier=Modifier.clickable(enabled=!loading,onClick=onClick).padding(horizontal=4.dp));HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)}

@Composable private fun Editor(original:NoteEntity,repo:SyncRepository,initiallyEditing:Boolean,onBack:()->Unit){Scaffold(containerColor=MaterialTheme.colorScheme.background){p->Box(Modifier.padding(p)){EditorPane(original,repo,showBack=true,initiallyEditing=initiallyEditing,onDone={onBack()})}}}

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable private fun EditorPane(original:NoteEntity,repo:SyncRepository,showBack:Boolean=false,initiallyEditing:Boolean=false,onDone:(NoteEntity)->Unit){
    var n by remember(original.id,original.version){mutableStateOf(original)}
    var bodyValue by remember(original.id,original.version){mutableStateOf(TextFieldValue(original.body))}
    val attachments by repo.assets(n.id).collectAsState(initial=emptyList());val scope=rememberCoroutineScope()
    val saveError by repo.saveError.collectAsState()
    val context=LocalContext.current;val lifecycleOwner=LocalLifecycleOwner.current;val audioRecorder=remember{AudioRecorder(context)};var recording by remember{mutableStateOf(false)};var recordingFile by remember{mutableStateOf<java.io.File?>(null)}
    val folders by repo.folders.collectAsState(initial=emptyList());val tags by repo.tags.collectAsState(initial=emptyList());var folderMenu by remember{mutableStateOf(false)}
    val steps by repo.steps(n.id).collectAsState(initial=emptyList());var newStep by remember{mutableStateOf("")}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->uri?.let{scope.launch{repo.attach(n.id,it)}}}
    val microphonePermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted){runCatching{repo.recordingFile(n.id).also{recordingFile=it;audioRecorder.start(it);recording=true}}}}
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
    DisposableEffect(Unit){onDispose{audioRecorder.release()}}
    var reminderDialog by remember{mutableStateOf(false)}
    var editMode by remember(original.id){mutableStateOf(initiallyEditing)}
    val initialEditableKey=remember(original.id,original.version){editableKey(original)}
    var hasEdited by remember(original.id,original.version){mutableStateOf(false)}
    val currentEditableKey=editableKey(n)
    val latestNote by rememberUpdatedState(n)
    fun updateNote(next:NoteEntity){n=next;repo.queueDraft(next)}
    LaunchedEffect(currentEditableKey){
        if(currentEditableKey!=initialEditableKey)hasEdited=true
        if(hasEdited)repo.queueDraft(n)
    }
    DisposableEffect(lifecycleOwner,original.id,original.version){
        val observer=LifecycleEventObserver{_,event->
            if(event==Lifecycle.Event.ON_STOP&&editableKey(latestNote)!=initialEditableKey)repo.flushDraft(latestNote)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose{
            lifecycleOwner.lifecycle.removeObserver(observer)
            if(editableKey(latestNote)!=initialEditableKey)repo.flushDraft(latestNote)
        }
    }
    BackHandler{repo.flushDraft(n);onDone(n)}
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp,vertical=12.dp)){
        saveError?.let{Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)){Row(Modifier.padding(10.dp)){Text(it,Modifier.weight(1f));TextButton({repo.clearSaveError()}){Text("知道了")}}};Spacer(Modifier.height(6.dp))}
        if(n.conflict){Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)){Column(Modifier.padding(12.dp)){Text("此项目在本机和服务器上都被修改",fontWeight=FontWeight.SemiBold);Row{TextButton({scope.launch{repo.keepLocal(n.id)}}){Text("保留本机")};TextButton({scope.launch{repo.acceptRemote(n.id)}}){Text("采用服务器版本")}}}};Spacer(Modifier.height(8.dp))}
        if(n.deletedAt!=null){Card{Row(Modifier.padding(12.dp)){Text("此项目位于回收站",Modifier.weight(1f));TextButton({scope.launch{repo.restore(n.id)}}){Text("恢复")};TextButton({scope.launch{repo.deletePermanently(n.id)}}){Text("彻底删除",color=MaterialTheme.colorScheme.error)}}};Spacer(Modifier.height(8.dp))}
        Row{if(showBack)IconButton({repo.flushDraft(n);onDone(n)}){Icon(Icons.Default.ArrowBack,"返回")};Text(if(editMode)"编辑笔记" else "阅读笔记",Modifier.padding(top=12.dp),style=MaterialTheme.typography.titleMedium);Spacer(Modifier.weight(1f));IconButton({if(editMode)repo.flushDraft(n);editMode=!editMode},Modifier.testTag(if(editMode)"preview-mode" else "edit-mode")){Icon(if(editMode)Icons.Default.Visibility else Icons.Default.Edit,if(editMode)"预览 Markdown" else "编辑")}}
        if(!editMode){
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("markdown-view")){
                item(key="note-title"){Text(n.title.ifBlank{"无标题"},style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(vertical=12.dp))}
                if(n.folderName.isNotBlank()||n.tagIds.isNotBlank())item(key="note-folder"){Text(n.folderName,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                item(key="note-markdown"){MarkdownView(n.body.ifBlank{"*暂无正文*"},Modifier.fillMaxWidth().padding(vertical=12.dp))}
                if(n.itemType=="todo"&&steps.isNotEmpty()){item(key="todo-divider"){HorizontalDivider()};items(steps,key={"read-step-${it.id}"}){step->Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Icon(if(step.checked)Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,null);Text(step.text,Modifier.padding(8.dp))}}}
                items(attachments,key={"read-asset-${it.id}"}){AttachmentView(it)}
            }
        }else{
        OutlinedTextField(n.title,{updateNote(n.copy(title=it))},Modifier.fillMaxWidth().testTag("title-field"),textStyle=MaterialTheme.typography.headlineSmall,label={Text("标题")})
        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){Box{AssistChip(onClick={folderMenu=true},label={Text(n.folderName)},leadingIcon={Icon(Icons.Default.Folder,null)});DropdownMenu(folderMenu,{folderMenu=false}){DropdownMenuItem({Text("未分类")},{updateNote(n.copy(folderId=null,folderName="未分类"));folderMenu=false});folders.filter{it.type==if(n.itemType=="todo")"todoList" else "noteFolder"}.forEach{f->DropdownMenuItem({Text(f.name)},{updateNote(n.copy(folderId=f.id,folderName=f.name));folderMenu=false})}}};tags.forEach{tag->val selected=n.tagIds.split(',').contains(tag.id);FilterChip(selected,{val ids=n.tagIds.split(',').filter{it.isNotBlank()}.toMutableSet();if(selected)ids.remove(tag.id)else ids.add(tag.id);updateNote(n.copy(tagIds=ids.joinToString(",")))},{Text(tag.name)})}}
        Spacer(Modifier.height(8.dp))
        fun wrap(before:String,after:String=before){val start=bodyValue.selection.min;val end=bodyValue.selection.max;val next=bodyValue.text.substring(0,start)+before+bodyValue.text.substring(start,end)+after+bodyValue.text.substring(end);bodyValue=TextFieldValue(next,TextRange(end+before.length+after.length));updateNote(n.copy(body=next))}
        Row(horizontalArrangement=Arrangement.spacedBy(2.dp)){IconButton({wrap("**")}){Text("B",fontWeight=FontWeight.Bold)};IconButton({wrap("_")}){Text("I")};IconButton({wrap("# ","")}){Text("H")};IconButton({wrap("- ","")}){Icon(Icons.Default.FormatListBulleted,"列表")};IconButton({wrap("- [ ] ","")}){Icon(Icons.Default.CheckBox,"清单")}}
        OutlinedTextField(bodyValue,{bodyValue=it;updateNote(n.copy(body=it.text))},Modifier.fillMaxWidth().weight(1f).testTag("body-field"),label={Text("开始记录…")})
        if(n.itemType=="todo"){Row{Checkbox(n.completedAt!=null,{updateNote(n.copy(completedAt=if(it)System.currentTimeMillis() else null))});Text("已完成",Modifier.padding(top=12.dp));Checkbox(n.important,{updateNote(n.copy(important=it))});Text("重要",Modifier.padding(top=12.dp))};LazyColumn(Modifier.heightIn(max=180.dp)){items(steps,key={it.id}){step->Row{Checkbox(step.checked,{checked->scope.launch{repo.saveStep(step.copy(checked=checked))}});Text(step.text,Modifier.weight(1f).padding(top=12.dp));IconButton({scope.launch{repo.deleteStep(step.id)}}){Icon(Icons.Default.Close,"删除步骤")}}}};Row{OutlinedTextField(newStep,{newStep=it},Modifier.weight(1f),singleLine=true,label={Text("添加步骤")});IconButton({if(newStep.isNotBlank()){scope.launch{repo.saveStep(TodoStepEntity(UUID.randomUUID().toString(),n.id,newStep.trim(),sortOrder=steps.size))};newStep=""}}){Icon(Icons.Default.Add,"添加")}}}
        if(attachments.isNotEmpty())LazyColumn(Modifier.heightIn(max=220.dp)){items(attachments,key={it.id}){a->AttachmentView(a)}}
        FlowRow{TextButton({picker.launch("*/*")}){Icon(Icons.Default.AttachFile,null);Text("附件")};TextButton({if(recording){val ok=audioRecorder.stop();recording=false;if(ok)recordingFile?.let{file->scope.launch{repo.addRecordedAudio(n.id,file)}}}else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)}){Icon(if(recording)Icons.Default.Stop else Icons.Default.Mic,null,tint=if(recording)MaterialTheme.colorScheme.error else LocalContentColor.current);Text(if(recording)"停止录音" else "录音")};TextButton({reminderDialog=true}){Icon(Icons.Default.Notifications,null);Text(if(n.reminderAt==null)"提醒" else DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(n.reminderAt!!)))};TextButton({updateNote(n.copy(deletedAt=System.currentTimeMillis()))}){Icon(Icons.Default.Delete,null);Text("删除")}}
        }
    }
    if(reminderDialog)ReminderDialog(n.reminderAt,n.recurrence,{reminderDialog=false},{at,rule->updateNote(n.copy(reminderAt=at,recurrence=rule));if(at!=null&&android.os.Build.VERSION.SDK_INT>=33)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);reminderDialog=false})
}

private fun editableKey(n:NoteEntity)=listOf<Any?>(n.title,n.body,n.folderId,n.folderName,n.reminderAt,n.recurrence,n.tagIds,n.deletedAt,n.itemType,n.dueAt,n.completedAt,n.important)

@Composable private fun MarkdownView(markdown:String,modifier:Modifier=Modifier){val context=LocalContext.current;val color=MaterialTheme.colorScheme.onBackground.toArgb();val markwon=remember(context){Markwon.builder(context).usePlugin(StrikethroughPlugin.create()).usePlugin(TablePlugin.create(context)).build()};AndroidView(factory={TextView(it).apply{setTextSize(TypedValue.COMPLEX_UNIT_SP,17f);setLineSpacing(0f,1.18f);movementMethod=LinkMovementMethod.getInstance();setTextIsSelectable(true)}},update={view->view.setTextColor(color);markwon.setMarkdown(view,markdown)},modifier=modifier)}

@Composable private fun AttachmentView(asset:AssetEntity){val context=LocalContext.current;val file=asset.localPath?.let{java.io.File(it)};fun open(){if(file?.isFile!=true)return;val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file);runCatching{context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,asset.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}};if(asset.kind=="image"&&file?.isFile==true)Card(Modifier.fillMaxWidth().padding(vertical=4.dp).clickable{open()}){AsyncImage(file,asset.filename,Modifier.fillMaxWidth().heightIn(max=180.dp))}else ListItem(headlineContent={Text(asset.filename,maxLines=1)},supportingContent={Text(if(file?.isFile==true)"${asset.size/1024} KB" else "等待下载")},leadingContent={Icon(if(asset.kind=="audio")Icons.Default.AudioFile else Icons.Default.AttachFile,null)},modifier=Modifier.clickable{open()})}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ReminderDialog(current:Long?,currentRule:String,onDismiss:()->Unit,onConfirm:(Long?,String)->Unit){val base=current?:System.currentTimeMillis()+3_600_000;val cal=remember(base){Calendar.getInstance().apply{timeInMillis=base}};val date=rememberDatePickerState(initialSelectedDateMillis=base);val time=rememberTimePickerState(cal.get(Calendar.HOUR_OF_DAY),cal.get(Calendar.MINUTE),true);var rule by remember{mutableStateOf(currentRule)};AlertDialog(onDismissRequest=onDismiss,title={Text("设置提醒")},text={Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())){DatePicker(date);TimePicker(time);Text("重复",fontWeight=FontWeight.Medium);SingleChoiceSegmentedButtonRow{listOf("none" to "一次","daily" to "每天","weekly" to "每周","monthly" to "每月").forEachIndexed{i,p->SegmentedButton(selected=rule==p.first,onClick={rule=p.first},shape=SegmentedButtonDefaults.itemShape(i,4)){Text(p.second)}}}}},confirmButton={Button({val selected=date.selectedDateMillis;if(selected==null)onConfirm(null,"none")else{val c=Calendar.getInstance().apply{timeInMillis=selected;set(Calendar.HOUR_OF_DAY,time.hour);set(Calendar.MINUTE,time.minute);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)};onConfirm(c.timeInMillis,rule)}}){Text("确定")}},dismissButton={Row{TextButton({onConfirm(null,"none")}){Text("清除")};TextButton(onDismiss){Text("取消")}}})}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ManagementScreen(kind:String,repo:SyncRepository,folders:List<FolderEntity>,tags:List<TagEntity>,onBack:()->Unit){val scope=rememberCoroutineScope();var name by remember{mutableStateOf("")};val isTag=kind=="tag";val folderType=if(kind=="todoList")"todoList" else "noteFolder";val title=when(kind){"todoList"->"管理提醒文件夹";"tag"->"管理标签";else->"管理笔记文件夹"};Scaffold(topBar={TopAppBar(title={Text(title)},navigationIcon={IconButton(onBack){Icon(Icons.Default.ArrowBack,null)}})}){p->Column(Modifier.padding(p).padding(16.dp)){Row{OutlinedTextField(name,{name=it},Modifier.weight(1f),singleLine=true,label={Text(if(isTag)"新标签名称" else if(folderType=="todoList")"新提醒文件夹名称" else "新文件夹名称")});IconButton({if(name.isNotBlank()){scope.launch{if(isTag)repo.saveTag(name.trim())else repo.saveFolder(name.trim(),folderType)};name=""}}){Icon(Icons.Default.Add,"添加")}};Spacer(Modifier.height(12.dp));LazyColumn{if(!isTag)items(folders.filter{it.type==folderType},key={it.id}){item->ListItem(headlineContent={Text(item.name)},leadingContent={Icon(if(folderType=="todoList")Icons.Default.ListAlt else Icons.Default.Folder,null)},trailingContent={IconButton({scope.launch{repo.deleteFolder(item.id)}}){Icon(Icons.Default.Delete,"删除")}})}else items(tags,key={it.id}){item->ListItem(headlineContent={Text(item.name)},leadingContent={Icon(Icons.Default.Label,null)},trailingContent={IconButton({scope.launch{repo.deleteTag(item.id)}}){Icon(Icons.Default.Delete,"删除")}})}}}}}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ServerSettings(repo:SyncRepository,onBack:()->Unit){
    var s by remember{mutableStateOf(repo.settings())};var message by remember{mutableStateOf<String?>(null)};var isError by remember{mutableStateOf(false)};var syncing by remember{mutableStateOf(false)};var changedKey by remember{mutableStateOf<HostKeyChangedException?>(null)};val scope=rememberCoroutineScope()
    suspend fun syncNow(){syncing=true;message="正在连接服务器并同步…";isError=false;changedKey=null;runCatching{repo.sync()}.onSuccess{message="连接成功，同步已完成"}.onFailure{error->if(error is HostKeyChangedException){changedKey=error;message="服务器主机密钥发生变化，需要你确认"}else{message="连接或同步失败：${error.localizedMessage?:error.javaClass.simpleName}"};isError=true};syncing=false}
    val scanner=rememberLauncherForActivityResult(ScanContract()){result->result.contents?.let{payload->runCatching{parseSyncQrConfig(payload)}.onSuccess{config->s=config;repo.saveSettings(config);scope.launch{syncNow()}}.onFailure{message=it.message?:"无法读取配置二维码";isError=true}}}
    changedKey?.let{change->AlertDialog(onDismissRequest={changedKey=null},icon={Icon(Icons.Default.Security,null)},title={Text("服务器身份已变化")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("这可能是服务器重装、SSH 密钥更新，也可能存在安全风险。确认这是你的服务器后才能继续。");Text("原指纹\n${change.expected}",style=MaterialTheme.typography.bodySmall);Text("新指纹\n${change.actual}",style=MaterialTheme.typography.bodySmall)}},confirmButton={Button({repo.trustHostKey(change.actual);s=repo.settings();changedKey=null;scope.launch{syncNow()}}){Text("信任并继续")}},dismissButton={TextButton({changedKey=null}){Text("取消")}})}
    Scaffold(topBar={TopAppBar(title={Text("设置与同步")},navigationIcon={IconButton(onBack){Icon(Icons.Default.ArrowBack,null)}})}){p->
        Column(Modifier.padding(p).padding(20.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            ReminderPermissionCard()
            Button({scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("扫描 Mac 上的 Notebook 配置二维码").setBeepEnabled(false).setOrientationLocked(false))},Modifier.fillMaxWidth()){Icon(Icons.Default.QrCodeScanner,null);Spacer(Modifier.width(8.dp));Text("扫描配置二维码")}
            message?.let{Card(colors=CardDefaults.cardColors(containerColor=if(isError)MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer),modifier=Modifier.fillMaxWidth()){Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){if(syncing)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp);Icon(if(isError)Icons.Default.ErrorOutline else Icons.Default.CheckCircle,null);Text(it,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)}}}
            HorizontalDivider()
            fun update(host:String=s.host,port:Int=s.port,user:String=s.username,password:String=s.password,path:String=s.path){s=SshSettings(host,port,user,password,path,s.fingerprint)}
            OutlinedTextField(s.host,{update(host=it)},label={Text("服务器地址")});OutlinedTextField(s.port.toString(),{update(port=it.toIntOrNull()?:22)},label={Text("端口")});OutlinedTextField(s.username,{update(user=it)},label={Text("用户名")});OutlinedTextField(s.password,{update(password=it)},label={Text("密码")});OutlinedTextField(s.path,{update(path=it)},label={Text("远程目录")})
            Button({repo.saveSettings(s);scope.launch{syncNow()}},Modifier.fillMaxWidth(),enabled=!syncing){Text(if(syncing)"正在同步…" else "保存并同步")}
            Text("推荐扫描 Mac 端显示的二维码自动配置。服务器配置和密码使用 Android 加密存储。",style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun ReminderPermissionCard(){val context=LocalContext.current;val owner=LocalLifecycleOwner.current;var refresh by remember{mutableIntStateOf(0)};val notificationLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){refresh++};DisposableEffect(owner){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)refresh++};owner.lifecycle.addObserver(observer);onDispose{owner.lifecycle.removeObserver(observer)}};val notifications=refresh.let{android.os.Build.VERSION.SDK_INT<33||context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED};val alarm=context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager;val exact=android.os.Build.VERSION.SDK_INT<31||alarm.canScheduleExactAlarms();Card(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("提醒权限",fontWeight=FontWeight.SemiBold);Text("通知：${if(notifications)"已允许" else "未允许"}    精确提醒：${if(exact)"已允许" else "将使用非精确提醒"}",style=MaterialTheme.typography.bodySmall);FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){if(!notifications)OutlinedButton({notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)}){Text("允许通知")};if(!exact&&android.os.Build.VERSION.SDK_INT>=31)OutlinedButton({val intent=Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,android.net.Uri.parse("package:${context.packageName}"));runCatching{context.startActivity(intent)}}){Text("允许精确提醒")}}}}}

private enum class UpdatePhase{Checking,Available,Downloading,Ready,Failed}
@Stable private class AppUpdateState{
    var release by mutableStateOf<GithubRelease?>(null)
    var phase by mutableStateOf(UpdatePhase.Checking)
    var progress by mutableStateOf(DownloadProgress(0,-1))
    var downloaded by mutableStateOf<java.io.File?>(null)
    var message by mutableStateOf<String?>(null)
    var showDialog by mutableStateOf(false)
}

@Composable private fun rememberAppUpdateState():AppUpdateState{
    val context=LocalContext.current;val state=remember{AppUpdateState()}
    LaunchedEffect(Unit){runCatching{AppUpdater.latestRelease()}.onSuccess{release->
        state.release=release
        if(release!=null){state.downloaded=AppUpdater.cachedDownload(context,release);state.phase=if(state.downloaded!=null)UpdatePhase.Ready else UpdatePhase.Available;state.message=if(state.downloaded!=null)"安装包已下载并校验，可以继续安装" else null;state.showDialog=true}
    }.onFailure{state.phase=UpdatePhase.Failed}}
    return state
}

@Composable private fun UpdateAction(state:AppUpdateState){
    if(state.release==null)return
    IconButton({state.showDialog=true}){
        if(state.phase==UpdatePhase.Downloading){val fraction=state.progress.fraction;if(fraction!=null)CircularProgressIndicator(progress={fraction},modifier=Modifier.size(22.dp),strokeWidth=2.5.dp)else CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.5.dp)}
        else Icon(if(state.phase==UpdatePhase.Ready)Icons.Default.InstallMobile else Icons.Default.SystemUpdate,if(state.phase==UpdatePhase.Ready)"继续安装更新" else "有可用更新",tint=MaterialTheme.colorScheme.primary)
    }
}

@Composable private fun UpdatePrompt(state:AppUpdateState){
    val context=LocalContext.current;val scope=rememberCoroutineScope();val release=state.release?:return
    fun install(){val apk=state.downloaded?:return;if(AppUpdater.install(context,apk)){state.message="正在打开系统安装程序…"}else state.message="请允许安装未知应用，返回后点击顶部升级图标继续安装"}
    fun download(){if(state.phase==UpdatePhase.Downloading)return;scope.launch{state.phase=UpdatePhase.Downloading;state.message="正在下载更新，可关闭窗口后从顶部升级图标查看";runCatching{AppUpdater.download(context,release){progress->withContext(Dispatchers.Main.immediate){state.progress=progress}}}.onSuccess{state.downloaded=it;state.phase=UpdatePhase.Ready;state.message="下载和校验完成，正在打开系统安装程序";install()}.onFailure{state.phase=UpdatePhase.Failed;state.message="下载中断：${it.localizedMessage}。再次点击可从已下载位置继续"}}}
    if(!state.showDialog)return
    val action:()->Unit=if(state.downloaded==null){{download()}}else{{install()}}
    AlertDialog(onDismissRequest={state.showDialog=false},icon={Icon(if(state.phase==UpdatePhase.Ready)Icons.Default.InstallMobile else Icons.Default.SystemUpdate,null)},title={Text("发现新版本 ${release.version}")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("当前版本：${BuildConfig.VERSION_NAME}")
        release.notes.take(600).takeIf{it.isNotBlank()}?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
        if(state.phase==UpdatePhase.Downloading){val fraction=state.progress.fraction;if(fraction!=null)LinearProgressIndicator(progress={fraction},modifier=Modifier.fillMaxWidth())else LinearProgressIndicator(Modifier.fillMaxWidth());Text(downloadLabel(state.progress),style=MaterialTheme.typography.bodySmall)}
        state.message?.let{Text(it,color=if(state.phase==UpdatePhase.Failed)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)}
    }},confirmButton={Button(onClick=action,enabled=state.phase!=UpdatePhase.Downloading){Text(if(state.downloaded==null)if(state.phase==UpdatePhase.Failed)"继续下载" else "下载并升级" else "继续安装")}},dismissButton={TextButton({state.showDialog=false}){Text(if(state.phase==UpdatePhase.Downloading)"后台下载" else "稍后")}})
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
