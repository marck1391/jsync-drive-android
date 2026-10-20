package dev.marck.jsyncdrive.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlanTest {
    private fun s(size: Long, mtime: Long = 1) = Sig(size, mtime)
    private fun both(size: Long) = Base(s(size), s(size))

    @Test fun firstSyncUploadsWhatOnlyExistsHereAndDownloadsWhatOnlyExistsThere() {
        val p = plan(local = mapOf("a" to s(1)), remote = mapOf("b" to s(2)), base = emptyMap())
        assertEquals(listOf<Action>(Action.Upload("a"), Action.Download("b")), p.actions)
    }

    @Test fun firstSyncOfTheSameSizedFileIsNotAConflict() {
        assertTrue(plan(mapOf("a" to s(5)), mapOf("a" to s(5)), emptyMap()).actions.isEmpty())
    }

    @Test fun firstSyncOfDifferentSizedFileIsAConflict() {
        assertEquals(listOf<Action>(Action.Conflict("a")), plan(mapOf("a" to s(5)), mapOf("a" to s(6)), emptyMap()).actions)
    }

    @Test fun unchangedMeansNothingToDo() {
        val p = plan(mapOf("a" to s(1)), mapOf("a" to s(1)), mapOf("a" to both(1)))
        assertTrue(p.actions.isEmpty())
    }

    @Test fun aLocalEditIsUploadedAndARemoteEditDownloaded() {
        val base = mapOf("a" to both(1), "b" to both(1))
        val p = plan(local = mapOf("a" to s(9), "b" to s(1)), remote = mapOf("a" to s(1), "b" to s(7)), base = base)
        assertEquals(listOf<Action>(Action.Upload("a"), Action.Download("b")), p.actions)
    }

    @Test fun aDeletionPropagatesOnlyWhenTheOtherSideIsUnchanged() {
        val base = mapOf("a" to both(1), "b" to both(1))
        val p = plan(local = mapOf("b" to s(1)), remote = mapOf("a" to s(1)), base = base)
        // a deleted here, untouched there -> delete there; b deleted there, untouched here -> delete here
        assertEquals(listOf<Action>(Action.DeleteRemote("a"), Action.DeleteLocal("b")), p.actions)
    }

    @Test fun anEditBeatsADeletionOnTheOtherSide() {
        val base = mapOf("a" to both(1), "b" to both(1))
        val p = plan(local = mapOf("b" to s(5)), remote = mapOf("a" to s(5)), base = base)
        // a: deleted here, edited there -> download; b: edited here, deleted there -> upload
        assertEquals(listOf<Action>(Action.Download("a"), Action.Upload("b")), p.actions)
    }

    @Test fun bothEditingIsAConflictAndNothingIsLost() {
        val p = plan(mapOf("a" to s(2, 5)), mapOf("a" to s(3, 6)), mapOf("a" to both(1)))
        assertEquals(listOf<Action>(Action.Conflict("a")), p.actions)
    }

    @Test fun bothDeletingJustForgets() {
        assertTrue(plan(emptyMap(), emptyMap(), mapOf("a" to both(1))).actions.isEmpty())
    }

    @Test fun anEmptySideDoesNotWipeTheOther() {
        // 10 files known; the local folder suddenly looks empty (permission revoked, card removed...).
        val base = (1..10).associate { "f$it" to both(1) }
        val remote = (1..10).associate { "f$it" to s(1) }
        val p = plan(local = emptyMap(), remote = remote, base = base)
        assertNotNull(p.abort)
        assertTrue(p.actions.isEmpty())
    }

    @Test fun fewDeletionsAreFineEvenInABigFolder() {
        val base = (1..20).associate { "f$it" to both(1) }
        val remote = (1..20).associate { "f$it" to s(1) }
        val local = remote - "f3" - "f4"
        val p = plan(local, remote, base)
        assertNull(p.abort)
        assertEquals(2, p.actions.size)
    }
}
