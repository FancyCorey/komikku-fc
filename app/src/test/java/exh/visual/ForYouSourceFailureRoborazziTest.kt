package exh.visual

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import eu.kanade.presentation.browse.components.GlobalSearchErrorResultItem
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.Source
import exh.recs.ForYouSourceFailureActions
import exh.recs.ForYouSourceFailurePolicy
import exh.recs.SourceFitStats
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.net.UnknownHostException

// KMK v0.8.24: the failing-source suggestions under a For You row, for a one-off connection error, a source that keeps
// failing behind a browser check, and the same source after "Check source" found it broken with an outdated extension.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [30], qualifiers = "w360dp-h900dp-xhdpi")
class ForYouSourceFailureRoborazziTest {

    private val source = mockk<Source>(relaxed = true).also {
        every { it.id } returns 1L
        every { it.name } returns "Greed Scans"
    }
    private val keepsFailing = SourceFitStats(
        sourceId = 1, runCount = 163, shownCount = 25, noMatchCount = 0, filteredOutCount = 0, errorCount = 138,
        hiddenByDuplicateCount = 0, totalVisibleCandidates = 0, lastSuccessAt = 0, lastErrorAt = 0, updatedAt = 0,
    )

    @Test
    fun failureStates() = captureRoboImage {
        MaterialTheme {
            Surface {
                Column(Modifier.width(360.dp)) {
                    GlobalSearchErrorResultItem(message = "No connection") {
                        ForYouSourceFailureActions(
                            source = source,
                            advice = ForYouSourceFailurePolicy.advise(UnknownHostException(), null),
                            extensionObsolete = false, extensionHasUpdate = false, check = null,
                            onRetry = {}, onStopUsing = {}, onCheck = {}, onManageExtension = null,
                        )
                    }
                    HorizontalDivider()
                    GlobalSearchErrorResultItem(message = "HTTP error 403") {
                        ForYouSourceFailureActions(
                            source = source,
                            advice = ForYouSourceFailurePolicy.advise(HttpException(403), keepsFailing),
                            extensionObsolete = false, extensionHasUpdate = false, check = null,
                            onRetry = {}, onStopUsing = {}, onCheck = {}, onManageExtension = {},
                        )
                    }
                    HorizontalDivider()
                    GlobalSearchErrorResultItem(message = "HTTP error 403") {
                        ForYouSourceFailureActions(
                            source = source,
                            advice = ForYouSourceFailurePolicy.advise(HttpException(403), keepsFailing),
                            extensionObsolete = true, extensionHasUpdate = false,
                            check = ForYouSourceFailurePolicy.Check.BROKEN,
                            onRetry = {}, onStopUsing = {}, onCheck = {}, onManageExtension = {},
                        )
                    }
                }
            }
        }
    }
}
