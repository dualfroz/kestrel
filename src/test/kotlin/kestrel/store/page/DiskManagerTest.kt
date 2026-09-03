package kestrel.store.page

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DiskManagerTest {
    private fun tempFile() = Files.createTempFile("kestrel-disk", ".db").toFile().also { it.deleteOnExit() }

    @Test
    fun allocatedPagesStartZeroedAndGrowSequentially() {
        val manager = DiskManager(tempFile())
        val first = manager.allocatePage()
        val second = manager.allocatePage()
        assertEquals(0L, first)
        assertEquals(1L, second)
        assertEquals(2L, manager.allocatedPageCount())
        manager.close()
    }

    @Test
    fun writtenPageContentSurvivesReadBack() {
        val manager = DiskManager(tempFile())
        val id = manager.allocatePage()
        val page = Page(id)
        page.data[0] = 0x42
        page.data[Page.PAGE_SIZE - 1] = 0x24
        manager.writePage(page)
        val reloaded = manager.readPage(id)
        assertEquals(0x42, reloaded.data[0])
        assertEquals(0x24, reloaded.data[Page.PAGE_SIZE - 1])
        manager.close()
    }

    @Test
    fun stateSurvivesCloseAndReopen() {
        val file = tempFile()
        val manager = DiskManager(file)
        val id = manager.allocatePage()
        val page = Page(id)
        page.data.fill(7)
        manager.writePage(page)
        manager.close()

        val reopened = DiskManager(file)
        assertEquals(1L, reopened.allocatedPageCount())
        val reloaded = reopened.readPage(id)
        assertTrue(reloaded.data.all { it == 7.toByte() })
        reopened.close()
    }

    @Test
    fun freedPagesAreRecycledBeforeGrowingTheFile() {
        val manager = DiskManager(tempFile())
        val a = manager.allocatePage()
        val b = manager.allocatePage()
        manager.freePage(a)
        val reused = manager.allocatePage()
        assertEquals(a, reused)
        assertEquals(2L, manager.allocatedPageCount())
        val fresh = manager.allocatePage()
        assertEquals(2L, fresh)
        assertTrue(b != reused)
        manager.close()
    }

    @Test
    fun freeListSurvivesReopenAndPopsMostRecentlyFreedFirst() {
        val file = tempFile()
        val manager = DiskManager(file)
        val a = manager.allocatePage()
        val b = manager.allocatePage()
        manager.freePage(a)
        manager.freePage(b)
        manager.close()

        val reopened = DiskManager(file)
        assertEquals(b, reopened.allocatePage())
        assertEquals(a, reopened.allocatePage())
        reopened.close()
    }

    @Test
    fun corruptedPageBytesAreDetectedOnRead() {
        val file = tempFile()
        val manager = DiskManager(file)
        val id = manager.allocatePage()
        val page = Page(id)
        page.data.fill(9)
        manager.writePage(page)
        manager.close()

        // Flip a byte inside the payload region of the physical slot for `id`, after the header slot and checksum.
        val slotSize = 4 + Page.PAGE_SIZE
        val offset = (id + 1) * slotSize + 4 + 10
        val raf = java.io.RandomAccessFile(file, "rw")
        raf.seek(offset)
        val original = raf.read()
        raf.seek(offset)
        raf.writeByte((original xor 0xFF) and 0xFF)
        raf.close()

        val reopened = DiskManager(file)
        assertFailsWith<CorruptPageException> { reopened.readPage(id) }
        reopened.close()
    }

    @Test
    fun readingUnallocatedPageIsRejected() {
        val manager = DiskManager(tempFile())
        assertFailsWith<IllegalArgumentException> { manager.readPage(0) }
        manager.close()
    }
}
