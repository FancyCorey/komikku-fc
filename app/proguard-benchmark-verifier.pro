# Benchmark-only ABI for the separate continuity verifier APK.
# Release shrinking stays enabled; preserve only the exact externally called members.
-keep class tachiyomi.domain.category.model.Category {
  public long getFlags();
  public boolean getHidden();
  public java.lang.String getName();
  public long getOrder();
}
-keep interface tachiyomi.domain.category.repository.CategoryRepository {
  public java.lang.Object getAll(kotlin.coroutines.Continuation);
  public java.lang.Object getCategoriesByMangaId(long, kotlin.coroutines.Continuation);
}
-keep class tachiyomi.domain.chapter.model.Chapter {
  public boolean getBookmark();
  public double getChapterNumber();
  public long getDateFetch();
  public long getDateUpload();
  public long getId();
  public long getLastModifiedAt();
  public long getLastPageRead();
  public java.lang.String getName();
  public boolean getRead();
  public long getSourceOrder();
  public java.lang.String getUrl();
}
-keep interface tachiyomi.domain.chapter.repository.ChapterRepository {
  public static java.lang.Object getChapterByMangaId$default(tachiyomi.domain.chapter.repository.ChapterRepository, long, boolean, kotlin.coroutines.Continuation, int, java.lang.Object);
}
-keep class tachiyomi.domain.history.model.History {
  public long getChapterId();
  public java.util.Date getReadAt();
  public long getReadDuration();
}
-keep interface tachiyomi.domain.history.repository.HistoryRepository {
  public java.lang.Object getHistoryByMangaId(long, kotlin.coroutines.Continuation);
}
-keep class tachiyomi.domain.manga.model.Manga {
  public long getDateAdded();
  public boolean getFavorite();
  public java.lang.Long getFavoriteModifiedAt();
  public long getId();
  public boolean getInitialized();
  public long getLastModifiedAt();
  public long getLastUpdate();
  public java.lang.String getOgArtist();
  public java.lang.String getOgAuthor();
  public java.lang.String getOgDescription();
  public java.util.List getOgGenre();
  public java.lang.String getOgThumbnailUrl();
  public java.lang.String getOgTitle();
  public long getSource();
  public java.lang.String getUrl();
}
-keep interface tachiyomi.domain.manga.repository.MangaRepository {
  public java.lang.Object getAll(kotlin.coroutines.Continuation);
}
-keep class tachiyomi.domain.taste.model.CrossSourceGroupPrimary {
  public java.lang.String getGroupId();
  public long getSource();
  public long getUpdatedAt();
  public java.lang.String getUrl();
}
-keep class tachiyomi.domain.taste.model.CrossSourceMangaLink {
  public long getCreatedAt();
  public java.lang.String getGroupId();
  public long getSource();
  public java.lang.String getTitle();
  public long getUpdatedAt();
  public java.lang.String getUrl();
}
-keep enum tachiyomi.domain.taste.model.MangaRating {
  public static kotlin.enums.EnumEntries getEntries();
  public int getValue();
  tachiyomi.domain.taste.model.MangaRating DISLIKE;
  tachiyomi.domain.taste.model.MangaRating LIKE;
  tachiyomi.domain.taste.model.MangaRating LOVE;
  tachiyomi.domain.taste.model.MangaRating NOT_INTERESTED;
}
-keep class tachiyomi.domain.taste.model.MangaTaste {
  public long getCreatedAt();
  public long getMangaId();
  public int getRating();
  public long getSource();
  public java.lang.String getTitle();
  public long getUpdatedAt();
  public java.lang.String getUrl();
}
-keep class tachiyomi.domain.taste.model.TagAlias {
  public java.lang.String getAlias();
  public java.lang.String getDisplayName();
  public java.lang.String getGroupKey();
  public java.lang.String getNormalizedAlias();
}
-keep class tachiyomi.domain.taste.model.TagTaste {
  public long getCreatedAt();
  public java.lang.String getDisplayName();
  public java.lang.String getNormalizedTag();
  public int getPreference();
  public long getUpdatedAt();
}
-keep interface tachiyomi.domain.taste.repository.TasteRepository {
  public java.lang.Object getAllCrossSourceGroupPrimaries(kotlin.coroutines.Continuation);
  public java.lang.Object getAllCrossSourceMangaLinks(kotlin.coroutines.Continuation);
  public java.lang.Object getAllMangaTastes(kotlin.coroutines.Continuation);
  public java.lang.Object getAllTagAliases(kotlin.coroutines.Continuation);
  public java.lang.Object getAllTagTastes(kotlin.coroutines.Continuation);
}
-keep class tachiyomi.domain.tracker.model.LocalTrackedWork {
  public long getCreatedAt();
  public java.lang.Long getFinishDate();
  public java.lang.String getId();
  public java.lang.String getLastChapterLabel();
  public java.lang.Double getLastChapterNumber();
  public java.lang.Long getLastChapterSource();
  public java.lang.String getLastChapterUrl();
  public java.lang.Long getLastProgressAt();
  public java.lang.String getNormalizedTitle();
  public java.lang.Double getScore();
  public java.lang.Long getStartDate();
  public tachiyomi.domain.tracker.model.LocalTrackedWorkStatus getStatus();
  public java.lang.String getTitle();
  public long getUpdatedAt();
}
-keep class tachiyomi.domain.tracker.model.LocalTrackedWorkSource {
  public long getSource();
  public java.lang.String getTitle();
  public java.lang.String getUrl();
}
-keep enum tachiyomi.domain.tracker.model.LocalTrackedWorkStatus {
  public static kotlin.enums.EnumEntries getEntries();
}
-keep interface tachiyomi.domain.tracker.repository.LocalTrackerRepository {
  public kotlinx.coroutines.flow.Flow getAllWorksAsFlow();
  public java.lang.Object getSources(java.lang.String, kotlin.coroutines.Continuation);
}
-keep class androidx.tracing.Trace {
  public static void beginSection(java.lang.String);
  public static void endSection();
  public static void forceEnableAppTracing();
}

# Exact app-owned ABI referenced by ForYouBenchmarkInputControlTest in the separate test APK.
# Keep the resolver and enum names stable across the measured target's release shrinking.
-keep class eu.kanade.domain.source.service.SourcePreferences {
  public tachiyomi.core.common.preference.Preference forYouFixtureMode();
}
-keep interface tachiyomi.core.common.preference.Preference {
  public abstract boolean commit(java.lang.Object);
  public abstract java.lang.Object get();
  public abstract void set(java.lang.Object);
}
-keep class exh.recs.ForYouDebugFixture {
  public static final exh.recs.ForYouDebugFixture INSTANCE;
  public final exh.recs.ForYouDebugFixtureMode resolveMode(boolean, java.lang.String);
}
-keep enum exh.recs.ForYouDebugFixtureMode {
  public static final exh.recs.ForYouDebugFixtureMode TOP_PICKS;
  public final java.lang.String getPrefValue();
}
