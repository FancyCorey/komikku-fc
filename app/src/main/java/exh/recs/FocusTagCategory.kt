package exh.recs

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.kmk.KMR

// KMK -->
/**
 * v0.8.24: what kind of tag a Focus genre is, so the picker can group hundreds of source tags instead of listing them in
 * one block. A small built-in vocabulary of common manga tags; a tag matches when it is one of the [phrases] or contains
 * one of the [words]. Anything unrecognized is [OTHER]. Presentation only: matching and saved modes are unchanged.
 */
internal enum class FocusTagCategory(
    val titleRes: StringResource,
    private val phrases: Set<String>,
    private val words: Set<String>,
) {
    MATURE(
        KMR.strings.rec_focus_category_mature,
        phrases = setOf(
            "boys love", "girls love", "shounen ai", "shoujo ai", "reverse harem", "gender bender", "adult", "mature",
            "bl", "gl", "18",
        ),
        words = setOf("harem", "ecchi", "smut", "yaoi", "yuri", "gore", "hentai", "erotica", "nsfw", "shotacon"),
    ),
    STORY(
        KMR.strings.rec_focus_category_story,
        phrases = setOf("time travel", "second chance", "op mc", "overpowered mc", "weak to strong", "rags to riches"),
        words = setOf(
            "reincarnation", "reincarnated", "regression", "regressor", "revenge", "system", "leveling", "level", "transmigration",
            "transmigrated", "overpowered", "op", "survival", "returner", "return", "rebirth", "reborn", "cheat", "skill",
            "contract", "betrayal", "redemption", "comeback", "genius", "hidden", "sealed",
        ),
    ),
    SETTING(
        KMR.strings.rec_focus_category_setting,
        phrases = setOf(
            "martial arts",
            "virtual reality",
            "post apocalyptic",
            "high school",
            "another world",
            "different world",
            "modern day",
            "parallel world",
        ),
        words = setOf(
            "academy", "school", "isekai", "murim", "wuxia", "xianxia", "xuanhuan", "cultivation", "dungeon", "dungeons",
            "tower", "game", "gaming", "vr", "apocalypse", "apocalyptic", "military", "war", "office", "space", "royalty",
            "royal", "palace", "noble", "nobility", "kingdom", "empire", "medieval", "village", "city", "hospital", "prison",
            "magic", "mafia", "gang", "workplace", "college", "university",
        ),
    ),
    CHARACTERS(
        KMR.strings.rec_focus_category_characters,
        phrases = setOf("female lead", "male lead", "anti hero", "strong lead", "child lead", "office worker"),
        words = setOf(
            "demon", "demons", "vampire", "vampires", "elf", "elves", "dragon", "dragons", "monster", "monsters", "ghost",
            "ghosts", "zombie", "zombies", "villain", "villainess", "hero", "heroes", "heroine", "hunter", "hunters", "maid",
            "idol", "ninja", "samurai", "knight", "knights", "witch", "witches", "wizard", "mage", "god", "gods", "goddess",
            "beast", "beasts", "animal", "animals", "alien", "aliens", "robot", "robots", "mecha", "angel", "angels",
            "princess", "prince", "emperor", "empress", "king", "queen", "assassin", "doctor", "chef", "delinquent", "delinquents",
            "student", "students", "teacher", "otaku", "tsundere", "yandere", "kuudere",
        ),
    ),
    GENRE(
        KMR.strings.rec_focus_category_genre,
        phrases = setOf("slice of life", "science fiction", "sci fi", "school life"),
        words = setOf(
            "action", "adventure", "comedy", "drama", "fantasy", "horror", "mystery", "romance", "romantic", "thriller",
            "tragedy", "psychological", "supernatural", "historical", "history", "sports", "sport", "crime", "detective",
            "music", "cooking", "food", "medical", "suspense", "scifi", "mythology", "philosophical", "parody", "fluff",
            "wholesome", "dark", "gourmet", "superhero", "superpower", "superpowers", "magical",
        ),
    ),
    OTHER(KMR.strings.rec_focus_category_other, phrases = emptySet(), words = emptySet()),
    ;

    companion object {
        /** Categories in the order they are listed; [MATURE] before others so "Yaoi School" stays with mature tags. */
        private val matchOrder = listOf(MATURE, GENRE, STORY, SETTING, CHARACTERS)

        /** Display order in the picker. */
        val displayOrder = listOf(GENRE, STORY, SETTING, CHARACTERS, MATURE, OTHER)

        fun classify(group: String): FocusTagCategory {
            matchOrder.firstOrNull { group in it.phrases }?.let { return it }
            val tagWords = group.split(" ").filter { it.isNotEmpty() }
            matchOrder.firstOrNull { category -> tagWords.any { it in category.words } }?.let { return it }
            return OTHER
        }
    }
}
// KMK <--
