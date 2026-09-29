package tachiyomi.data.chapter

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler

class ChapterRepositoryCancellationTest {

    @Test
    fun `chapter deletion propagates coroutine cancellation`() {
        val handler = mockk<DatabaseHandler>()
        coEvery {
            handler.await(
                inTransaction = true,
                block = any<suspend Database.() -> Unit>(),
            )
        } throws CancellationException("scope cancelled")

        val repository = ChapterRepositoryImpl(handler)

        assertThrows(CancellationException::class.java) {
            runBlocking { repository.removeChaptersWithIds(listOf(1L)) }
        }
    }

    @Test
    fun `chapter deletion propagates ordinary database failures`() {
        val handler = mockk<DatabaseHandler>()
        coEvery {
            handler.await(
                inTransaction = true,
                block = any<suspend Database.() -> Unit>(),
            )
        } throws IllegalStateException("database unavailable")

        val repository = ChapterRepositoryImpl(handler)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.removeChaptersWithIds(listOf(1L, 2L)) }
        }
    }
}
