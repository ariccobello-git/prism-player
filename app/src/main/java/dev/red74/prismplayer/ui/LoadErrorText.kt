package dev.red74.prismplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.red74.prismplayer.LoadError
import dev.red74.prismplayer.R

/** Full explanation of why a list could not be loaded. Never contains the address. */
@Composable
fun loadErrorText(error: LoadError, httpCode: Int?): String = when (error) {
    LoadError.NETWORK -> stringResource(R.string.load_error_network)
    LoadError.HTTP -> stringResource(R.string.load_error_http, httpCode ?: 0)
    LoadError.TOO_LARGE -> stringResource(R.string.load_error_too_large)
    LoadError.EMPTY -> stringResource(R.string.load_error_empty)
    LoadError.UNKNOWN -> stringResource(R.string.load_error_unknown)
    LoadError.TIMEOUT -> stringResource(R.string.load_error_timeout)
    LoadError.DNS -> stringResource(R.string.load_error_dns)
    LoadError.REFUSED -> stringResource(R.string.load_error_refused)
    LoadError.TLS -> stringResource(R.string.load_error_tls)
    LoadError.NOT_A_PLAYLIST -> stringResource(R.string.load_error_not_a_playlist)
}

/** One-line reason for the home list, from the failure name saved with the playlist; null when unknown. */
@Composable
fun loadErrorShort(savedName: String, httpCode: Int): String? {
    val error = LoadError.entries.firstOrNull { it.name == savedName } ?: return null
    return when (error) {
        LoadError.NETWORK -> stringResource(R.string.err_short_network)
        LoadError.HTTP -> stringResource(R.string.err_short_http, httpCode)
        LoadError.TOO_LARGE -> stringResource(R.string.err_short_too_large)
        LoadError.EMPTY -> stringResource(R.string.err_short_empty)
        LoadError.UNKNOWN -> stringResource(R.string.err_short_unknown)
        LoadError.TIMEOUT -> stringResource(R.string.err_short_timeout)
        LoadError.DNS -> stringResource(R.string.err_short_dns)
        LoadError.REFUSED -> stringResource(R.string.err_short_refused)
        LoadError.TLS -> stringResource(R.string.err_short_tls)
        LoadError.NOT_A_PLAYLIST -> stringResource(R.string.err_short_not_a_playlist)
    }
}
