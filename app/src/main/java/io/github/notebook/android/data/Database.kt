package io.github.notebook.android.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String = "",
    val body: String = "",
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

@Entity(tableName = "folders") data class FolderEntity(@PrimaryKey val id:String, val name:String, val sortOrder:Int=0, val type:String="noteFolder", val updatedAt:Long=System.currentTimeMillis())
@Entity(tableName = "tags") data class TagEntity(@PrimaryKey val id:String, val name:String, val color:String="gray", val updatedAt:Long=System.currentTimeMillis())
@Entity(tableName="todo_steps",foreignKeys=[ForeignKey(entity=NoteEntity::class,parentColumns=["id"],childColumns=["noteId"],onDelete=ForeignKey.CASCADE)],indices=[Index("noteId")])
data class TodoStepEntity(@PrimaryKey val id:String,val noteId:String,val text:String,val checked:Boolean=false,val sortOrder:Int=0,val createdAt:Long=System.currentTimeMillis())
@Entity(tableName="assets",foreignKeys=[ForeignKey(entity=NoteEntity::class,parentColumns=["id"],childColumns=["noteId"],onDelete=ForeignKey.CASCADE)],indices=[Index("noteId")])
data class AssetEntity(@PrimaryKey val id:String,val noteId:String,val kind:String,val filename:String,val mimeType:String,val relativePath:String,val localPath:String?=null,val contentHash:String="",val size:Long=0,val dirty:Boolean=false)
@Entity(tableName="tombstones") data class TombstoneEntity(@PrimaryKey val itemKey:String,val itemId:String,val itemType:String,val deletedAt:Long,val deviceId:String)
@Entity(tableName="drafts") data class DraftEntity(@PrimaryKey val noteId:String,val payloadJson:String,val updatedAt:Long=System.currentTimeMillis())

@Dao interface NotebookDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC") fun observeNotes(): Flow<List<NoteEntity>>
    @Query("SELECT id,title,substr(body,1,240) AS preview,createdAt,updatedAt,folderId,folderName,reminderAt,recurrence,version,tagIds,deletedAt,itemType,dueAt,completedAt,important,dirty,conflict,lastSyncedVersion FROM notes ORDER BY updatedAt DESC") fun observeNoteSummaries():Flow<List<NoteSummary>>
    @Query("SELECT * FROM notes WHERE id=:id") suspend fun get(id:String): NoteEntity?
    @Query("SELECT id,title,body,createdAt,updatedAt,folderId,folderName,reminderAt,recurrence,version,tagIds,deletedAt,itemType,dueAt,completedAt,important,dirty,conflict,NULL AS snapshotJson,NULL AS conflictSnapshotJson,lastSyncedVersion FROM notes WHERE id=:id") suspend fun getForEditing(id:String):NoteEntity?
    @Query("SELECT snapshotJson FROM notes WHERE id=:id") suspend fun snapshot(id:String):String?
    @Query("SELECT conflictSnapshotJson FROM notes WHERE id=:id") suspend fun conflictSnapshot(id:String):String?
    @Query("SELECT id FROM notes") suspend fun allNoteIds():List<String>
    @Query("SELECT id FROM notes WHERE dirty=1") suspend fun dirtyNoteIds():List<String>
    @Query("SELECT id FROM notes WHERE title LIKE '%' || :query || '%' OR body LIKE '%' || :query || '%'") suspend fun searchNoteIds(query:String):List<String>
    @Query("SELECT * FROM notes") suspend fun allNotes(): List<NoteEntity>
    @Query("SELECT * FROM notes WHERE dirty=1") suspend fun dirtyNotes(): List<NoteEntity>
    @Query("SELECT id,title,'' AS body,createdAt,updatedAt,folderId,folderName,reminderAt,recurrence,version,tagIds,deletedAt,itemType,dueAt,completedAt,important,dirty,conflict,NULL AS snapshotJson,NULL AS conflictSnapshotJson,lastSyncedVersion FROM notes WHERE deletedAt IS NULL AND completedAt IS NULL AND reminderAt IS NOT NULL") suspend fun reminders():List<NoteEntity>
    @Upsert suspend fun put(note:NoteEntity)
    @Upsert suspend fun putNotes(notes:List<NoteEntity>)
    @Update(entity=NoteEntity::class) suspend fun updateEditable(note:NoteEditableUpdate)
    @Query("UPDATE notes SET dirty=0 WHERE id=:id") suspend fun markClean(id:String)
    @Query("UPDATE notes SET dirty=0,lastSyncedVersion=:version,conflict=0,conflictSnapshotJson=NULL WHERE id=:id AND version=:version") suspend fun markSynced(id:String,version:Long)
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
    @Query("SELECT * FROM assets WHERE noteId=:noteId") fun observeAssets(noteId:String):Flow<List<AssetEntity>>
    @Query("SELECT * FROM assets WHERE dirty=1") suspend fun dirtyAssets():List<AssetEntity>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putAssets(items:List<AssetEntity>)
    @Query("SELECT * FROM tombstones") suspend fun tombstones():List<TombstoneEntity>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putTombstone(item:TombstoneEntity)
    @Upsert suspend fun putDraft(draft:DraftEntity)
    @Query("SELECT * FROM drafts") suspend fun allDrafts():List<DraftEntity>
    @Query("DELETE FROM drafts WHERE noteId=:noteId") suspend fun deleteDraft(noteId:String)
}

@Database(entities=[NoteEntity::class,FolderEntity::class,TagEntity::class,TodoStepEntity::class,AssetEntity::class,TombstoneEntity::class,DraftEntity::class], version=3, exportSchema=false)
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
        fun create(c:Context)=Room.databaseBuilder(c,NotebookDatabase::class.java,"notebook.db").addMigrations(MIGRATION_1_2,MIGRATION_2_3).build()
    }
}
