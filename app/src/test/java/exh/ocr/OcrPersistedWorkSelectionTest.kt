package exh.ocr

import androidx.work.WorkInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.UUID

class OcrPersistedWorkSelectionTest {

    @Test
    fun `running work wins over newer terminal work`() {
        val terminal = workInfo(WorkInfo.State.SUCCEEDED, generation = 8, attempt = 2)
        val running = workInfo(WorkInfo.State.RUNNING, generation = 1, attempt = 0)

        assertSame(running, selectPersistedWorkInfo(listOf(terminal, running)))
    }

    @Test
    fun `newer generation wins within the same active state`() {
        val older = workInfo(WorkInfo.State.ENQUEUED, generation = 2, attempt = 3)
        val newer = workInfo(WorkInfo.State.ENQUEUED, generation = 3, attempt = 0)

        assertSame(newer, selectPersistedWorkInfo(listOf(older, newer)))
    }

    @Test
    fun `empty work history has no selected work`() {
        assertNull(selectPersistedWorkInfo(emptyList()))
    }

    private fun workInfo(
        state: WorkInfo.State,
        generation: Int,
        attempt: Int,
    ): WorkInfo = mockk {
        every { this@mockk.state } returns state
        every { this@mockk.generation } returns generation
        every { this@mockk.runAttemptCount } returns attempt
        every { this@mockk.id } returns UUID.randomUUID()
    }
}
