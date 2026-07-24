package io.github.notebook.android.sync

import io.github.notebook.android.data.NoteEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class PageTreeMergeTest {
    @Test fun `newer structural metadata merges independently from the body`() {
        val local=NoteEntity(id="page",title="local",body="local body",folderId="old",parentPageId="old-parent",sortOrder=1000.0,treeUpdatedAt=10)
        val remote=NoteEntity(id="page",title="remote",body="remote body",folderId="new",parentPageId="new-parent",sortOrder=2000.0,treeUpdatedAt=20)
        val merged=mergeNewestTree(local,remote)
        assertEquals("local body",merged.body)
        assertEquals("new-parent",merged.parentPageId)
        assertEquals("new",merged.folderId)
        assertEquals(2000.0,merged.sortOrder,0.0)
    }

    @Test fun `older client cannot flatten a newer local tree`() {
        val local=NoteEntity(id="page",parentPageId="parent",sortOrder=2000.0,treeUpdatedAt=20)
        val oldClient=NoteEntity(id="page",parentPageId=null,sortOrder=0.0,treeUpdatedAt=10)
        assertEquals("parent",mergeNewestTree(local,oldClient).parentPageId)
    }

    @Test fun `newer page icon metadata is preserved while local body wins`() {
        val local=NoteEntity(id="page",body="local body",icon=null,updatedAt=10,treeUpdatedAt=20)
        val remote=NoteEntity(id="page",body="remote body",icon="🚀",updatedAt=30,treeUpdatedAt=10)
        val merged=mergeNewestTree(local,remote)
        assertEquals("local body",merged.body)
        assertEquals("🚀",merged.icon)
    }
}
