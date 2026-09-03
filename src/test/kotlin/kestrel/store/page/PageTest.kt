package kestrel.store.page

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PageTest {
    @Test
    fun freshPageIsZeroedAndClean() {
        val page = Page(3)
        assertEquals(3, page.id)
        assertEquals(Page.PAGE_SIZE, page.data.size)
        assertTrue(page.data.all { it == 0.toByte() })
        assertFalse(page.isDirty)
        assertEquals(0, page.pinCount)
    }

    @Test
    fun resetClearsDataAndDirtyFlagButKeepsId() {
        val page = Page(1)
        page.data[0] = 5
        page.isDirty = true
        page.reset()
        assertTrue(page.data.all { it == 0.toByte() })
        assertFalse(page.isDirty)
        assertEquals(1, page.id)
    }
}
