package exh.recs.loved

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.EventListener
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.size.Dimension
import coil3.size.Size
import eu.kanade.presentation.browse.components.MangaItem
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.manga.model.MangaCover
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [30], qualifiers = "w360dp-h640dp-xhdpi")
@OptIn(coil3.annotation.DelicateCoilApi::class)
class RatedCoverRenderedRequestTest {
    @get:Rule
    val compose = createComposeRule()

    private fun cover(id: Long) = MangaCover(id, id, false, "/$id.jpg", 0L)

    @Test
    fun displayedRequestOwnsFallbackSizeAndCancellation() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val previousScope = Injekt
        Injekt = InjektScope(DefaultRegistrar()).apply {
            importModule(object : InjektModule {
                override fun InjektRegistrar.registerInjectables() {
                    addSingletonFactory<PreferenceStore> { AndroidPreferenceStore(context) }
                }
            })
        }
        val starts = CopyOnWriteArrayList<Long>()
        val sizes = ConcurrentHashMap<Long, Size>()
        val success = AtomicBoolean()
        val suspended = AtomicBoolean()
        val cancelled = AtomicBoolean()
        val loader = ImageLoader.Builder(context)
            .components {
                add(object : Fetcher.Factory<MangaCover> {
                    override fun create(data: MangaCover, options: Options, imageLoader: ImageLoader): Fetcher {
                        sizes[data.mangaId] = options.size
                        return Fetcher {
                            when (data.mangaId) {
                                1L -> throw IOException("primary cover unavailable")
                                3L -> {
                                    suspended.set(true)
                                    try {
                                        awaitCancellation()
                                    } finally {
                                        cancelled.set(true)
                                    }
                                }
                                else -> ImageFetchResult(Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888).asImage(), false, DataSource.MEMORY)
                            }
                        }
                    }
                })
            }
            .eventListener(object : EventListener() {
                override fun onStart(request: ImageRequest) {
                    starts += (request.data as MangaCover).mangaId
                }
                override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                    if ((request.data as MangaCover).mangaId == 2L) success.set(true)
                }
            })
            .build()
        SingletonImageLoader.setUnsafe(loader)
        var visible by mutableStateOf(true)
        var title by mutableStateOf("Rated title")
        var selection by mutableStateOf(RatedCoverSelection(listOf(cover(1), cover(1), cover(2))))
        var primaryFailure: Throwable? = null
        try {
            compose.setContent {
                MaterialTheme {
                    if (visible) {
                        val displayed = selection.current
                        Box(Modifier.testTag("card")) {
                            MangaItem(
                                title = title,
                                cover = displayed,
                                isFavorite = false,
                                onClick = {},
                                onLongClick = {},
                                usePanoramaCover = false,
                                onCoverError = { selection.onError(displayed) },
                            )
                        }
                    }
                }
            }
            compose.waitUntil(10_000) { success.get() }
            compose.runOnIdle { title = "Unrelated title change" }
            compose.waitForIdle()
            assertEquals(listOf(1L, 2L), starts.toList())
            val bounds = compose.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot
            listOf(1L, 2L).forEach { id ->
                val size = sizes.getValue(id)
                assertTrue(size.width is Dimension.Pixels && size.height is Dimension.Pixels)
                assertTrue((size.width as Dimension.Pixels).px in 1..bounds.width.toInt())
                assertTrue((size.height as Dimension.Pixels).px in 1..bounds.height.toInt())
            }
            compose.runOnIdle { selection = RatedCoverSelection(listOf(cover(3), cover(3), cover(4))) }
            compose.waitForIdle()
            compose.waitUntil(10_000) { suspended.get() }
            // Apply disposal before waiting for the suspended request to be cancelled.
            compose.runOnUiThread { visible = false }
            compose.waitForIdle()
            compose.waitUntil(10_000) { cancelled.get() }
            assertEquals(listOf(1L, 2L, 3L), starts.toList())
            assertEquals(cover(3), selection.current)
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            compose.runOnUiThread { visible = false }
            if (primaryFailure == null) compose.waitForIdle()
            loader.shutdown()
            SingletonImageLoader.reset()
            Injekt = previousScope
        }
    }
}
