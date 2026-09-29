package eu.kanade.presentation.util

import android.content.Context
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.RecoverableSourceRuntimeException
import eu.kanade.tachiyomi.util.system.isOnline
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.source.model.SourceNotInstalledException
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import java.io.IOException
import java.net.UnknownHostException
import java.util.Collections
import java.util.IdentityHashMap

internal enum class ReaderErrorMessageKind {
    HTTP,
    NETWORK,
    FILE_ACCESS,
    EXTENSION_INCOMPATIBLE,
    SOURCE_UNAVAILABLE,
    PAGE_LOAD,
}

internal object ReaderErrorMessagePolicy {
    fun classify(error: Throwable): ReaderErrorMessageKind {
        val cause = error.readerRootCause()
        return when (cause) {
            is HttpException -> ReaderErrorMessageKind.HTTP
            is java.io.FileNotFoundException -> ReaderErrorMessageKind.FILE_ACCESS
            is UnknownHostException, is IOException -> ReaderErrorMessageKind.NETWORK
            is LinkageError -> ReaderErrorMessageKind.EXTENSION_INCOMPATIBLE
            is SourceNotInstalledException -> ReaderErrorMessageKind.SOURCE_UNAVAILABLE
            else -> ReaderErrorMessageKind.PAGE_LOAD
        }
    }
}

context(context: Context)
val Throwable.readerErrorMessage: String
    get() {
        val cause = readerRootCause()
        return when (ReaderErrorMessagePolicy.classify(this)) {
            ReaderErrorMessageKind.HTTP -> context.stringResource(
                MR.strings.exception_http,
                (cause as HttpException).code,
            )
            ReaderErrorMessageKind.NETWORK -> if (cause is UnknownHostException && !context.isOnline()) {
                context.stringResource(MR.strings.exception_offline)
            } else {
                context.stringResource(KMR.strings.rec_error_network)
            }
            ReaderErrorMessageKind.FILE_ACCESS ->
                context.stringResource(KMR.strings.rec_error_file_access)
            ReaderErrorMessageKind.EXTENSION_INCOMPATIBLE ->
                context.stringResource(KMR.strings.rec_error_extension_incompatible)
            ReaderErrorMessageKind.SOURCE_UNAVAILABLE ->
                context.stringResource(MR.strings.loader_not_implemented_error)
            ReaderErrorMessageKind.PAGE_LOAD ->
                context.stringResource(KMR.strings.reader_page_load_error)
        }
    }

private fun Throwable.readerRootCause(): Throwable {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current = this
    while (visited.add(current)) {
        val next = if (current is RecoverableSourceRuntimeException) {
            current.sourceRuntimeFailure
        } else {
            current.cause
        }
        if (next == null || next in visited) return current
        current = next
    }
    return current
}
