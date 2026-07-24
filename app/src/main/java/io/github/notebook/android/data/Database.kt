package io.github.notebook.android.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String = "",
    val body: String = "",
    val previewText: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val folderId: String? = null,
    val folderName: String = "未分类",
    val reminderAt: Long? = null,
    val recurrence: String = "none",
    val version: Long = 1,
    val tagIds: String = "",
    val deletedAt: Long? = null,
    val itemType: String = "note",
    val dueAt: Long? = null,
    val completedAt: Long? = null,
    val important: Boolean = false,
    /** Last per-note presentation mode. Unknown/legacy values fall back to Markdown preview. */
    val viewMode: String = "preview",
    val dirty: Boolean = true,
    val conflict: Boolean = false,
    /** Full currentSnapshot JSON. Preserves unsupported macOS blocks losslessly. */
    val snapshotJson: String? = null,
    /** Remote snapshot retained until the user resolves a concurrent edit. */
    val conflictSnapshotJson: String? = null,
    val lastSyncedVersion: Long = 0
)

/** Lightweight row used by navigation, filtering and the virtualized note list. */
data class NoteSummary(
    val id:String,
    val title:String,
    val preview:String,
    val createdAt:Long,
    val updatedAt:Long,
    val folderId:String?,
    val folderName:String,
    val reminderAt:Long?,
    val recurrence:String,
    val version:Long,
    val tagIds:String,
    val deletedAt:Long?,
    val itemType:String,
    val dueAt:Long?,
    val completedAt:Long?,
    val important:Boolean,
    val dirty:Boolean,
    val conflict:Boolean,
    val lastSyncedVersion:Long
)

/** Partial Room update: deliberately leaves large sync snapshots untouched. */
data class NoteEditableUpdate(
    val id:String,
    val title:String,
    val body:String,
    val previewText:String,
    val createdAt:Long,
    val updatedAt:Long,
    val folderId:String?,
    val folderName:String,
    val reminderAt:Long?,
    val recurrence:String,
    val version:Long,
    val tagIds:String,
    val deletedAt:Long?,
    val itemType:String,
    val dueAt:Long?,
    val completedAt:Long?,
    val important:Boolean,
    val viewMode:String,
    val dirty:Boolean,
    val conflict:Boolean,
    val lastSyncedVersion:Long
)

@Entity(tableName = "folders") data class FolderEntity(@PrimaryKey val id:String, val name:String, val sortOrder:Int=0, val type:String="noteFolder", val updatedAt:Long=System.currentTimeMillis(),val notebookId:String="personal",val parentSectionId:String?=null,val color:String?=null)
@Entity(tableName = "tags") data class TagEntity(@PrimaryKey val id:String, val name:String, val color:String="gray", val updatedAt:Long=System.currentTimeMillis())
@Entity(tableName="todo_steps",foreignKeys=[ForeignKey(entity=NoteEntity::class,parentColumns=["id"],childColumns=["noteId"],onDelete=ForeignKey.CASCADE)],indices=[Index("noteId")])
data class TodoStepEntity(@PrimaryKey val id:String,val noteId:String,val text:String,val checked:Boolean=false,val sortOrder:Int=0,val createdAt:Long=System.currentTimeMillis())
@Entity(tableName="assets",foreignKeys=[ForeignKey(entity=NoteEntity::class,parentColumns=["id"],childColumns=["noteId"],onDelete=ForeignKey.CASCADE)],indices=[Index("noteId")])
data class AssetEntity(@PrimaryKey val id:String,val noteId:String,val kind:String,val filename:String,val mimeType:String,val relativePath:String,val localPath:String?=null,val contentHash:String="",val size:Long=0,val dirty:Boolean=false)
@Entity(tableName="tombstones") data class TombstoneEntity(@PrimaryKey val itemKey:String,val itemId:String,val itemType:String,val deletedAt:Long,val deviceId:String)
@Entity(tableName="drafts") data class DraftEntity(@PrimaryKey val noteId:String,val payloadJson:String,val updatedAt:Long=System.currentTimeMillis())
@Entity(
    tableName="reading_positions",
    foreignKeys=[ForeignKey(entity=NoteEntity::class,parentColumns=["id"],childColumns=["noteId"],onDelete=ForeignKey.CASCADE)]
)
data class ReadingPositionEntity(
    @PrimaryKey val noteId:String,
    val anchorUtf16Offset:Int,
    val viewportOffsetFraction:Double,
    val updatedAt:Long,
    val deviceId:String
)

/** Raw TipTap JSON is retained separately from the Markdown projection used by the native reader/editor. */
@Entity(tableName="api_documents")
data class ApiDocumentEntity(@PrimaryKey val pageId:String,val tiptapJson:String,val schemaVersion:Int=1,val updatedAt:Long=System.currentTimeMillis())
@Entity(tableName="api_pages")
data class ApiPageEntity(@PrimaryKey val pageId:String,val payloadJson:String,val updatedAt:Long=System.currentTimeMillis())
@Entity(tableName="api_notebooks")
data class ApiNotebookEntity(@PrimaryKey val id:String,val workspaceId:String,val name:String,val emoji:String?=null,val color:String?=null,val sortOrder:Int=0,val createdAt:Long=System.currentTimeMillis(),val updatedAt:Long=System.currentTimeMillis(),val deletedAt:Long?=null)
@Entity(tableName="api_sync_versions")
data class ApiSyncVersionEntity(@PrimaryKey val id:String,val version:Long)
@Entity(tableName="api_sync_cursors")
data class ApiSyncCursorEntity(@PrimaryKey val workspaceId:String,val cursor:Long)
@Entity(tableName="api_sync_outbox",indices=[Index(value=["entityType","entityId"],unique=true)])
data class ApiSyncOutboxEntity(
    @PrimaryKey val id:String,
    val workspaceId:String,
    val entityType:String,
    val entityId:String,
    val expectedVersion:Long,
    val operation:String,
    val payloadJson:String,
    val createdAt:Long=System.currentTimeMillis()
)

@Dao interface NotebookDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC") fun observeNotes(): Flow<List<NoteEntity>>
    @Query("SELECT id,title,previewText AS preview,createdAt,updatedAt,folderId,folderName,reminderAt,recurrence,version,tagIds,deletedAt,itemType,dueAt,completedAt,important,dirty,conflict,lastSyncedVersion FROM notes ORDER BY updatedAt DESC") fun observeNoteSummaries():Flow<List<NoteSummary>>
    @Query("SELECT * FROM notes WHERE id=:id") suspend fun get(id:String): NoteEntity?
    @Query("SELECT id,title,'' AS body,previewText,createdAt,updatedAt,folderId,folderName,reminderAt,recurrence,version,tagIds,deletedAt,itemType,dueAt,completedAt,important,viewMode,dirty,conflict,NULL AS snapshotJson,NULL AS conflictSnapshotJson,lastSyncedVersion FROM notes WHERE id=:id") suspend fun getNoteHeader(id:String):NoteEntity?
    @Query("SELECT length(body) FROM notes WHERE id=:id") suspend fun bodyLength(id:String):Int?
    @Query("SELECT substr(body,:start,:length) FROM notes WHERE id=:id") suspend fun bodyChunk(id:String,start:Int,length:Int):String?
    @Query("SELECT length(snapshotJson) FROM notes WHERE id=:id") suspend fun snapshotLength(id:String):Int?
    @Query("SELECT substr(snapshotJson,:start,:length) FROM notes WHERE id=:id") suspend fun snapshotChunk(id:String,start:Int,length:Int):String?
    @Query("SELECT length(conflictSnapshotJson) FROM notes WHERE id=:id") suspend fun conflictSnapshotLength(id:String):Int?
    @Query("SELECT substr(conflictSnapshotJson,:start,:length) FROM notes WHERE id=:id") suspend fun conflictSnapshotChunk(id:String,start:Int,length:Int):String?
    @Query("SELECT id FROM notes") suspend fun allNoteIds():List<String>
    @Query("SELECT id FROM notes WHERE dirty=1") suspend fun dirtyNoteIds():List<String>
    @Query("SELECT id FROM notes WHERE title LIKE '%' || :query || '%' OR body LIKE '%' || :query || '%'") suspend fun searchNoteIds(query:String):List<String>
    @Query("SELECT * FROM notes") suspend fun allNotes(): List<NoteEntity>
    @Query("SELECT * FROM notes WHERE dirty=1") suspend fun dirtyNotes(): List<NoteEntity>
    @Query("SELECT id,title,'' AS body,previewText,createdAt,updatedAt,folderId,folderName,reminderAt,recurrence,version,tagIds,deletedAt,itemType,dueAt,completedAt,important,viewMode,dirty,conflict,NULL AS snapshotJson,NULL AS conflictSnapshotJson,lastSyncedVersion FROM notes WHERE deletedAt IS NULL AND completedAt IS NULL AND reminderAt IS NOT NULL") suspend fun reminders():List<NoteEntity>
    @Upsert suspend fun put(note:NoteEntity)
    @Upsert suspend fun putNotes(notes:List<NoteEntity>)
    @Update(entity=NoteEntity::class) suspend fun updateEditable(note:NoteEditableUpdate)
    @Query("UPDATE notes SET dirty=0 WHERE id=:id") suspend fun markClean(id:String)
    @Query("UPDATE notes SET dirty=0,lastSyncedVersion=:version,conflict=0,conflictSnapshotJson=NULL,snapshotJson=COALESCE(:snapshotJson,snapshotJson) WHERE id=:id AND version=:version") suspend fun markSynced(id:String,version:Long,snapshotJson:String?=null)
    @Query("DELETE FROM notes WHERE id=:id") suspend fun deleteNotePermanently(id:String)
    @Query("SELECT * FROM folders ORDER BY sortOrder") fun observeFolders():Flow<List<FolderEntity>>
    @Query("SELECT * FROM folders") suspend fun allFolders():List<FolderEntity>
    @Query("SELECT * FROM folders WHERE id=:id") suspend fun getFolder(id:String):FolderEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putFolders(items:List<FolderEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putFolder(item:FolderEntity)
    @Query("DELETE FROM folders WHERE id=:id") suspend fun deleteFolder(id:String)
    @Query("SELECT * FROM tags") suspend fun allTags():List<TagEntity>
    @Query("SELECT * FROM tags WHERE id=:id") suspend fun getTag(id:String):TagEntity?
    @Query("SELECT * FROM tags ORDER BY name") fun observeTags():Flow<List<TagEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTags(items:List<TagEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTag(item:TagEntity)
    @Query("DELETE FROM tags WHERE id=:id") suspend fun deleteTag(id:String)
    @Query("SELECT * FROM todo_steps WHERE noteId=:noteId ORDER BY sortOrder") fun observeSteps(noteId:String):Flow<List<TodoStepEntity>>
    @Query("SELECT * FROM todo_steps WHERE noteId=:noteId ORDER BY sortOrder") suspend fun steps(noteId:String):List<TodoStepEntity>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putStep(step:TodoStepEntity)
    @Query("SELECT * FROM todo_steps WHERE id=:id") suspend fun getStep(id:String):TodoStepEntity?
    @Query("DELETE FROM todo_steps WHERE id=:id") suspend fun deleteStep(id:String)
    @Query("SELECT * FROM assets WHERE noteId=:noteId") suspend fun assets(noteId:String):List<AssetEntity>
    @Query("SELECT * FROM assets WHERE id=:id") suspend fun getAsset(id:String):AssetEntity?
    @Query("SELECT * FROM assets WHERE noteId=:noteId") fun observeAssets(noteId:String):Flow<List<AssetEntity>>
    @Query("SELECT * FROM assets WHERE dirty=1") suspend fun dirtyAssets():List<AssetEntity>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putAssets(items:List<AssetEntity>)
    @Query("DELETE FROM assets WHERE id=:id") suspend fun deleteAsset(id:String)
    @Query("SELECT * FROM tombstones") suspend fun tombstones():List<TombstoneEntity>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putTombstone(item:TombstoneEntity)
    @Upsert suspend fun putDraft(draft:DraftEntity)
    @Query("SELECT * FROM drafts") suspend fun allDrafts():List<DraftEntity>
    @Query("SELECT noteId FROM drafts ORDER BY updatedAt") suspend fun draftNoteIds():List<String>
    @Query("SELECT updatedAt FROM drafts WHERE noteId=:noteId") suspend fun draftUpdatedAt(noteId:String):Long?
    @Query("SELECT length(payloadJson) FROM drafts WHERE noteId=:noteId") suspend fun draftPayloadLength(noteId:String):Int?
    @Query("SELECT substr(payloadJson,:start,:length) FROM drafts WHERE noteId=:noteId") suspend fun draftPayloadChunk(noteId:String,start:Int,length:Int):String?
    @Query("DELETE FROM drafts WHERE noteId=:noteId") suspend fun deleteDraft(noteId:String)
    @Query("SELECT * FROM reading_positions WHERE noteId=:noteId") suspend fun readingPosition(noteId:String):ReadingPositionEntity?
    @Query("SELECT * FROM reading_positions") suspend fun allReadingPositions():List<ReadingPositionEntity>
    @Upsert suspend fun putReadingPosition(position:ReadingPositionEntity)
    @Query("DELETE FROM reading_positions WHERE noteId=:noteId") suspend fun deleteReadingPosition(noteId:String)
    @Query("SELECT id FROM notes WHERE deletedAt IS NULL") suspend fun nonDeletedNoteIds():List<String>
    @Query("SELECT * FROM api_documents WHERE pageId=:pageId") suspend fun apiDocument(pageId:String):ApiDocumentEntity?
    @Upsert suspend fun putApiDocument(document:ApiDocumentEntity)
    @Query("DELETE FROM api_documents WHERE pageId=:pageId") suspend fun deleteApiDocument(pageId:String)
    @Query("SELECT * FROM api_pages WHERE pageId=:pageId") suspend fun apiPage(pageId:String):ApiPageEntity?
    @Upsert suspend fun putApiPage(page:ApiPageEntity)
    @Query("DELETE FROM api_pages WHERE pageId=:pageId") suspend fun deleteApiPage(pageId:String)
    @Query("SELECT * FROM api_notebooks ORDER BY sortOrder") suspend fun apiNotebooks():List<ApiNotebookEntity>
    @Query("SELECT * FROM api_notebooks WHERE id=:id") suspend fun apiNotebook(id:String):ApiNotebookEntity?
    @Upsert suspend fun putApiNotebook(notebook:ApiNotebookEntity)
    @Query("DELETE FROM api_notebooks WHERE id=:id") suspend fun deleteApiNotebook(id:String)
    @Query("SELECT * FROM api_sync_outbox WHERE workspaceId=:workspaceId ORDER BY createdAt LIMIT :limit") suspend fun apiOutbox(workspaceId:String,limit:Int=200):List<ApiSyncOutboxEntity>
    @Query("SELECT * FROM api_sync_outbox WHERE entityType=:entityType AND entityId=:entityId LIMIT 1") suspend fun apiOutboxItem(entityType:String,entityId:String):ApiSyncOutboxEntity?
    @Upsert suspend fun putApiOutbox(item:ApiSyncOutboxEntity)
    @Query("DELETE FROM api_sync_outbox WHERE entityType=:entityType AND entityId=:entityId") suspend fun deleteApiOutbox(entityType:String,entityId:String)
    @Query("DELETE FROM api_sync_outbox WHERE id=:id") suspend fun deleteApiOutboxById(id:String)
    @Query("SELECT * FROM api_sync_versions WHERE id=:id") suspend fun apiVersion(id:String):ApiSyncVersionEntity?
    @Upsert suspend fun putApiVersion(version:ApiSyncVersionEntity)
    @Query("SELECT cursor FROM api_sync_cursors WHERE workspaceId=:workspaceId") suspend fun apiCursor(workspaceId:String):Long?
    @Upsert suspend fun putApiCursor(cursor:ApiSyncCursorEntity)
}

@Database(entities=[NoteEntity::class,FolderEntity::class,TagEntity::class,TodoStepEntity::class,AssetEntity::class,TombstoneEntity::class,DraftEntity::class,ReadingPositionEntity::class,ApiDocumentEntity::class,ApiPageEntity::class,ApiNotebookEntity::class,ApiSyncVersionEntity::class,ApiSyncCursorEntity::class,ApiSyncOutboxEntity::class], version=7, exportSchema=false)
abstract class NotebookDatabase:RoomDatabase(){ abstract fun dao():NotebookDao
    companion object {
        private val MIGRATION_1_2=object:androidx.room.migration.Migration(1,2){override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){
            db.execSQL("ALTER TABLE notes ADD COLUMN snapshotJson TEXT")
            db.execSQL("ALTER TABLE notes ADD COLUMN conflictSnapshotJson TEXT")
            db.execSQL("ALTER TABLE notes ADD COLUMN lastSyncedVersion INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE TABLE IF NOT EXISTS todo_steps (id TEXT NOT NULL, noteId TEXT NOT NULL, text TEXT NOT NULL, checked INTEGER NOT NULL, sortOrder INTEGER NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(noteId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_todo_steps_noteId ON todo_steps(noteId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS assets (id TEXT NOT NULL, noteId TEXT NOT NULL, kind TEXT NOT NULL, filename TEXT NOT NULL, mimeType TEXT NOT NULL, relativePath TEXT NOT NULL, localPath TEXT, contentHash TEXT NOT NULL, size INTEGER NOT NULL, dirty INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(noteId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_assets_noteId ON assets(noteId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS tombstones (itemKey TEXT NOT NULL, itemId TEXT NOT NULL, itemType TEXT NOT NULL, deletedAt INTEGER NOT NULL, deviceId TEXT NOT NULL, PRIMARY KEY(itemKey))")
        }}
        private val MIGRATION_2_3=object:androidx.room.migration.Migration(2,3){override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){db.execSQL("CREATE TABLE IF NOT EXISTS drafts (noteId TEXT NOT NULL, payloadJson TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(noteId))")}}
        private val MIGRATION_3_4=object:androidx.room.migration.Migration(3,4){override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){
            db.execSQL("ALTER TABLE notes ADD COLUMN previewText TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE notes SET previewText=trim(replace(replace(substr(body,1,300),char(13),' '),char(10),' ')) WHERE previewText='' AND body!=''")
        }}
        private val MIGRATION_4_5=object:androidx.room.migration.Migration(4,5){override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){
            db.execSQL("CREATE TABLE IF NOT EXISTS reading_positions (noteId TEXT NOT NULL, anchorUtf16Offset INTEGER NOT NULL, viewportOffsetFraction REAL NOT NULL, updatedAt INTEGER NOT NULL, deviceId TEXT NOT NULL, PRIMARY KEY(noteId), FOREIGN KEY(noteId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        }}
        private val MIGRATION_5_6=object:androidx.room.migration.Migration(5,6){override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){
            db.execSQL("ALTER TABLE notes ADD COLUMN viewMode TEXT NOT NULL DEFAULT 'preview'")
        }}
        private val MIGRATION_6_7=object:androidx.room.migration.Migration(6,7){override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){
            db.execSQL("ALTER TABLE folders ADD COLUMN notebookId TEXT NOT NULL DEFAULT 'personal'")
            db.execSQL("ALTER TABLE folders ADD COLUMN parentSectionId TEXT")
            db.execSQL("ALTER TABLE folders ADD COLUMN color TEXT")
            db.execSQL("CREATE TABLE IF NOT EXISTS api_documents (pageId TEXT NOT NULL, tiptapJson TEXT NOT NULL, schemaVersion INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(pageId))")
            db.execSQL("CREATE TABLE IF NOT EXISTS api_pages (pageId TEXT NOT NULL, payloadJson TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(pageId))")
            db.execSQL("CREATE TABLE IF NOT EXISTS api_notebooks (id TEXT NOT NULL, workspaceId TEXT NOT NULL, name TEXT NOT NULL, emoji TEXT, color TEXT, sortOrder INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, deletedAt INTEGER, PRIMARY KEY(id))")
            db.execSQL("CREATE TABLE IF NOT EXISTS api_sync_versions (id TEXT NOT NULL, version INTEGER NOT NULL, PRIMARY KEY(id))")
            db.execSQL("CREATE TABLE IF NOT EXISTS api_sync_cursors (workspaceId TEXT NOT NULL, cursor INTEGER NOT NULL, PRIMARY KEY(workspaceId))")
            db.execSQL("CREATE TABLE IF NOT EXISTS api_sync_outbox (id TEXT NOT NULL, workspaceId TEXT NOT NULL, entityType TEXT NOT NULL, entityId TEXT NOT NULL, expectedVersion INTEGER NOT NULL, operation TEXT NOT NULL, payloadJson TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_api_sync_outbox_entityType_entityId ON api_sync_outbox(entityType,entityId)")
        }}
        fun create(c:Context)=Room.databaseBuilder(c,NotebookDatabase::class.java,"notebook.db").addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5,MIGRATION_5_6,MIGRATION_6_7).build()
    }
}
