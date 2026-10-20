package exh.visual

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import exh.recs.FocusPickerLayout
import exh.recs.FocusTagPicker
import exh.recs.canonicalFocusGroup
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// KMK v0.8.24: the Focus picker with a realistic, messy tag list from several sources
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [30], qualifiers = "w360dp-h900dp-xhdpi")
class FocusTagPickerRoborazziTest {

    @Test
    fun sections() = captureRoboImage {
        val raw = listOf(
            "Action", "Adventure", "Adventure (157)", "Adventure 158", "Adventure 27", "Adventurer", "Comedy", "Drama",
            "Fantasy", "Murim", "Martial Arts", "Romance", "Reincarnation", "Regression", "Isekai", "Webtoon", "Manhwa",
            "Shounen", "Seinen", "Monster", "Monsters", "System", "Tower", "Dungeon", "Revenge", "Slice of Life", "Harem",
        )
        val merged = FocusPickerLayout.withPluralMerges(emptyMap(), raw.map { canonicalFocusGroup(it, emptyMap()) }.toSet())
        val options = raw.map { canonicalFocusGroup(it, merged) }.distinct().sorted()
        val layout = FocusPickerLayout.build(
            options = options,
            preferred = setOf("murim", "martial arts", "regression"),
            shownNow = setOf("action", "fantasy", "adventure", "system", "dungeon"),
            selected = setOf("murim", "harem"),
        )
        MaterialTheme {
            Surface {
                FocusTagPicker(
                    layout = layout,
                    include = setOf("murim"),
                    exclude = setOf("harem"),
                    searching = false,
                    onCycle = {},
                    modifier = Modifier.width(320.dp),
                )
            }
        }
    }
}
