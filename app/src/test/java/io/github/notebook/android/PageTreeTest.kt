package io.github.notebook.android

import io.github.notebook.android.data.NoteSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class PageTreeTest {
    private fun note(id:String,parentPageId:String?=null,sortOrder:Double=1000.0)=NoteSummary(
        id=id,title=id,preview="",createdAt=1,updatedAt=1,folderId="folder",folderName="folder",
        icon=null,parentPageId=parentPageId,sortOrder=sortOrder,treeUpdatedAt=1,reminderAt=null,recurrence="none",
        version=1,tagIds="",deletedAt=null,itemType="note",dueAt=null,completedAt=null,important=false,
        dirty=false,conflict=false,lastSyncedVersion=0
    )

    @Test fun `orders arbitrary depth and respects collapsed parents`() {
        val notes=listOf(note("root-b",sortOrder=2000.0),note("child","root-a"),note("root-a",sortOrder=1000.0),note("grandchild","child"))
        assertEquals(listOf("root-a","child","grandchild","root-b"),flattenNoteTree(notes,emptyMap()).map{it.note.id})
        assertEquals(listOf("root-a","root-b"),flattenNoteTree(notes,mapOf("root-a" to false)).map{it.note.id})
        assertEquals(listOf(0,1,2,0),flattenNoteTree(notes,emptyMap()).map{it.depth})
    }

    @Test fun `repairs missing parents as roots for display`() {
        assertEquals(listOf("orphan"),flattenNoteTree(listOf(note("orphan","missing")),emptyMap()).map{it.note.id})
    }
}
