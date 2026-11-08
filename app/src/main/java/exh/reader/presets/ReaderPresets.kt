package exh.reader.presets

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import exh.recs.matching.CrossSourceIdentityAuthorizationResolver
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.taste.interactor.GetCrossSourceMangaLinks
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// KMK v0.8.25 -->
/** A named bundle of reader settings. [values] maps a [ReaderPresetSettings] id to the saved value. */
@Serializable
data class ReaderPreset(val name: String, val values: Map<String, String>)

/** One reader setting a preset can hold: read it as text, write it back from text. */
class ReaderPresetSetting(val id: String, val read: () -> String, val write: (String) -> Unit)

/**
 * The reader settings a preset holds. Only settings that describe how a series should look and turn pages are here; app
 * wide behaviour (keep screen on, volume keys, the reading schedule, filters, brightness) stays global on purpose.
 * Reading mode and orientation are the global defaults: a series that has its own reading mode or orientation still wins.
 */
object ReaderPresetSettings {
    fun all(p: ReaderPreferences): List<ReaderPresetSetting> = listOf(
        bool("cropBorders", p.cropBorders()),
        bool("cropBordersWebtoon", p.cropBordersWebtoon()),
        bool("cropBordersContinuousVertical", p.cropBordersContinuousVertical()),
        int("imageScaleType", p.imageScaleType()),
        enum("webtoonScaleType", p.webtoonScaleType(), ReaderPreferences.WebtoonScaleType.entries),
        int("zoomStart", p.zoomStart()),
        bool("landscapeZoom", p.landscapeZoom()),
        enum("landscapeZoomType", p.landscapeZoomType(), ReaderPreferences.LandscapeZoomScaleType.entries),
        int("readerTheme", p.readerTheme()),
        int("pageLayout", p.pageLayout()),
        bool("invertDoublePages", p.invertDoublePages()),
        int("navigationModePager", p.navigationModePager()),
        int("navigationModeWebtoon", p.navigationModeWebtoon()),
        enum("pagerNavInverted", p.pagerNavInverted(), ReaderPreferences.TappingInvertMode.entries),
        enum("webtoonNavInverted", p.webtoonNavInverted(), ReaderPreferences.TappingInvertMode.entries),
        bool("dualPageSplitPaged", p.dualPageSplitPaged()),
        bool("dualPageSplitWebtoon", p.dualPageSplitWebtoon()),
        int("webtoonSidePadding", p.webtoonSidePadding()),
        int("defaultReadingMode", p.defaultReadingMode()),
        int("defaultOrientationType", p.defaultOrientationType()),
    )

    private fun bool(id: String, pref: Preference<Boolean>) =
        ReaderPresetSetting(id, { pref.get().toString() }, { v -> v.toBooleanStrictOrNull()?.let(pref::set) })

    private fun int(id: String, pref: Preference<Int>) =
        ReaderPresetSetting(id, { pref.get().toString() }, { v -> v.toIntOrNull()?.let(pref::set) })

    private fun <T : Enum<T>> enum(id: String, pref: Preference<T>, entries: List<T>) =
        ReaderPresetSetting(id, { pref.get().name }, { v -> entries.firstOrNull { it.name == v }?.let(pref::set) })
}

/**
 * Applies a preset for as long as a reader is open and puts the previous values back afterwards. The previous values are
 * saved through [setPending] before anything is changed, so if the app is killed while reading, the next [restore] still
 * puts the global settings back instead of leaving the series' look in place everywhere.
 */
class ReaderPresetSession(
    private val settings: List<ReaderPresetSetting>,
    private val getPending: () -> String,
    private val setPending: (String) -> Unit,
) {
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

    fun begin(values: Map<String, String>) {
        restore()
        val known = settings.associateBy { it.id }
        val snapshot = values.keys.mapNotNull { id -> known[id]?.let { id to it.read() } }.toMap()
        if (snapshot.isEmpty()) return
        setPending(Json.encodeToString(mapSerializer, snapshot))
        values.forEach { (id, value) -> known[id]?.write?.invoke(value) }
    }

    fun end() = restore()

    /** Puts back whatever a previous session saved, if it never got to finish. */
    fun restore() {
        val raw = getPending()
        if (raw.isBlank()) return
        val snapshot = try {
            Json.decodeFromString(mapSerializer, raw)
        } catch (_: Exception) {
            emptyMap()
        }
        val known = settings.associateBy { it.id }
        snapshot.forEach { (id, value) -> known[id]?.write?.invoke(value) }
        setPending("")
    }
}

/** Presets, which series uses which, and the live session. Assignments follow a series to its confirmed versions. */
class ReaderPresets(
    private val readerPreferences: ReaderPreferences = Injekt.get(),
    preferenceStore: PreferenceStore = Injekt.get(),
    private val getCrossSourceMangaLinks: GetCrossSourceMangaLinks = Injekt.get(),
    private val identityResolver: CrossSourceIdentityAuthorizationResolver = CrossSourceIdentityAuthorizationResolver(),
) {
    private val presetsPref = preferenceStore.getString("reader_presets", "")
    private val assignmentsPref = preferenceStore.getString("reader_preset_assignments", "")
    private val pendingPref = preferenceStore.getString(Preference.appStateKey("reader_preset_pending_restore"), "")
    private val settings = ReaderPresetSettings.all(readerPreferences)
    private val session = ReaderPresetSession(settings, { pendingPref.get() }, { pendingPref.set(it) })
    private val presetListSerializer = ListSerializer(ReaderPreset.serializer())
    private val assignmentSerializer = MapSerializer(String.serializer(), String.serializer())

    fun presets(): List<ReaderPreset> = decode(presetsPref.get(), presetListSerializer, emptyList())

    private fun assignments(): Map<String, String> = decode(assignmentsPref.get(), assignmentSerializer, emptyMap())

    private fun <T> decode(raw: String, serializer: kotlinx.serialization.KSerializer<T>, fallback: T): T =
        if (raw.isBlank()) {
            fallback
        } else {
            try {
                Json.decodeFromString(serializer, raw)
            } catch (_: Exception) {
                fallback
            }
        }

    fun currentValues(): Map<String, String> = settings.associate { it.id to it.read() }

    fun saveAs(name: String, values: Map<String, String> = currentValues()): ReaderPreset {
        val trimmed = name.trim()
        val preset = ReaderPreset(trimmed, values)
        presetsPref.set(Json.encodeToString(presetListSerializer, presets().filterNot { it.name == trimmed } + preset))
        return preset
    }

    fun delete(name: String) {
        presetsPref.set(Json.encodeToString(presetListSerializer, presets().filterNot { it.name == name }))
        val kept = assignments().filterValues { it != name }
        assignmentsPref.set(Json.encodeToString(assignmentSerializer, kept))
    }

    /** The preset this series uses, found on the series itself or on one of its confirmed versions. */
    suspend fun presetNameFor(manga: Manga): String? {
        val assigned = assignments()
        if (assigned.isEmpty()) return null
        assigned[keyOf(manga)]?.let { return it }
        return confirmedVersionKeys(manga).firstNotNullOfOrNull { assigned[it] }
    }

    /**
     * Chooses the preset for the series: this title and every version confirmed as the same manga. Choosing a preset and choosing
     * Usual settings have the same reach, so what the reader shows is what every version gets. (Before v0.8.25 a preset was
     * stored on one version and the others only inherited it unless they had their own.)
     */
    suspend fun assign(manga: Manga, name: String?) {
        val updated = assignments().toMutableMap()
        val keys = listOf(keyOf(manga)) + confirmedVersionKeys(manga)
        if (name == null) keys.forEach(updated::remove) else keys.forEach { updated[it] = name }
        assignmentsPref.set(Json.encodeToString(assignmentSerializer, updated))
    }

    /** How many other versions a preset choice here also applies to, shown under the preset row. */
    suspend fun linkedVersionCount(manga: Manga): Int = (confirmedVersionKeys(manga).toSet() - keyOf(manga)).size

    private suspend fun confirmedVersionKeys(manga: Manga): List<String> {
        val own = getCrossSourceMangaLinks.awaitBySourceUrl(manga.source, manga.url) ?: return emptyList()
        return identityResolver.confirmedGroupMembers(
            manga.source,
            manga.url,
            getCrossSourceMangaLinks.awaitByGroupId(own.groupId),
        ).map { "${it.source}|${it.url}" }
    }

    /** Called while a reader opens a series, before its viewer is built. Never throws. */
    suspend fun beginFor(manga: Manga) {
        try {
            session.end()
            val name = presetNameFor(manga) ?: return
            val preset = presets().firstOrNull { it.name == name } ?: return
            session.begin(preset.values)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // a preset problem must never stop a chapter from opening
        }
    }

    fun endSession() = session.end()

    /** Puts back global settings left behind by a reader that was killed. */
    fun restorePending() = session.restore()

    private fun keyOf(manga: Manga) = "${manga.source}|${manga.url}"
}
// KMK <--
