// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork.online

import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.fork.ui.ForkUi
import helium314.keyboard.fork.ui.GroupTitle
import helium314.keyboard.fork.ui.SettingsGroup
import helium314.keyboard.fork.ui.Switch
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.preferences.Preference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Fork: translate with the user's LibreTranslate server and GIF search with KLIPY. Both are off until turned on here. */
@Composable
fun OnlineScreen(onClickBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.prefs()
    val scope = rememberCoroutineScope()

    var translateOn by remember { mutableStateOf(OnlineSettings.translateOn(context)) }
    var server by remember { mutableStateOf(OnlineSettings.translateServer(context)) }
    var translateKey by remember { mutableStateOf(OnlineSettings.translateKey(context)) }
    var translateStatus by remember { mutableStateOf<String?>(null) }
    var checkingTranslate by remember { mutableStateOf(false) }

    var gifsOn by remember { mutableStateOf(OnlineSettings.gifsOn(context)) }
    var gifKey by remember { mutableStateOf(OnlineSettings.gifKey(context)) }
    var gifStatus by remember { mutableStateOf<String?>(null) }
    var checkingGifs by remember { mutableStateOf(false) }

    val checkTranslate = {
        val address = server.trim().trimEnd('/').let { if (it.isNotEmpty() && "://" !in it) "https://$it" else it }
        server = address
        prefs.edit { putString(OnlineSettings.PREF_TRANSLATE_SERVER, address) }
        OnlineSettings.setTranslateKey(context, translateKey)
        checkingTranslate = true
        translateStatus = null
        scope.launch {
            translateStatus = try {
                val languages = LibreTranslate.languages(address, true)
                OnlineSettings.saveLanguages(prefs, languages)
                // a server can list its languages and still refuse to translate, for example without a key
                val probe = languages.firstOrNull { it.code != "en" }?.code ?: "en"
                LibreTranslate.translate(address, translateKey, "Hello", probe, true)
                context.getString(R.string.fork_online_translate_ok, languages.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LibreTranslate.Failure) {
                context.getString(translateProblem(e.problem))
            } catch (e: Exception) {
                context.getString(R.string.fork_translate_err_failed)
            }
            checkingTranslate = false
        }
        Unit
    }
    val checkGifs = {
        OnlineSettings.setGifKey(context, gifKey)
        checkingGifs = true
        gifStatus = null
        scope.launch {
            gifStatus = try {
                Klipy.page(gifKey, "", 1, true)
                context.getString(R.string.fork_online_gifs_ok)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Klipy.Failure) {
                context.getString(gifProblem(e.problem))
            } catch (e: Exception) {
                context.getString(R.string.fork_gif_err_failed)
            }
            checkingGifs = false
        }
        Unit
    }

    SearchSettingsScreen(onClickBack = onClickBack, title = stringResource(R.string.fork_online), settings = emptyList()) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(Modifier.verticalScroll(rememberScrollState()).padding(innerPadding)) {
                Spacer(Modifier.heightIn(min = 8.dp))
                GroupTitle(stringResource(R.string.translate))
                SettingsGroup({
                    Preference(
                        name = stringResource(R.string.fork_online_translate),
                        description = stringResource(R.string.fork_online_translate_summary),
                        onClick = {
                            translateOn = !translateOn
                            prefs.edit { putBoolean(OnlineSettings.PREF_TRANSLATE, translateOn) }
                        },
                        icon = R.drawable.ic_fork_translate,
                    ) {
                        Switch(checked = translateOn, onCheckedChange = {
                            translateOn = it
                            prefs.edit { putBoolean(OnlineSettings.PREF_TRANSLATE, it) }
                        })
                    }
                }, if (translateOn) {{
                    Column(Modifier.fillMaxWidth().padding(ForkUi.RowPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(value = server, onValueChange = { server = it.trim() }, singleLine = true,
                            label = { Text(stringResource(R.string.fork_online_server)) },
                            placeholder = { Text("https://translate.example.org") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = translateKey, onValueChange = { translateKey = it.trim() }, singleLine = true,
                            label = { Text(stringResource(R.string.fork_online_translate_key)) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth())
                        CheckRow(status = translateStatus, checking = checkingTranslate, enabled = server.isNotBlank(), onCheck = checkTranslate)
                    }
                }} else null)

                GroupTitle(stringResource(R.string.gif))
                SettingsGroup({
                    Preference(
                        name = stringResource(R.string.fork_online_gifs),
                        description = stringResource(R.string.fork_online_gifs_summary),
                        onClick = {
                            gifsOn = !gifsOn
                            prefs.edit { putBoolean(OnlineSettings.PREF_GIFS, gifsOn) }
                        },
                        icon = R.drawable.ic_fork_gif,
                    ) {
                        Switch(checked = gifsOn, onCheckedChange = {
                            gifsOn = it
                            prefs.edit { putBoolean(OnlineSettings.PREF_GIFS, it) }
                        })
                    }
                }, if (gifsOn) {{
                    Column(Modifier.fillMaxWidth().padding(ForkUi.RowPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.fork_online_gifs_key_help), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(value = gifKey, onValueChange = { gifKey = it.trim() }, singleLine = true,
                            label = { Text(stringResource(R.string.fork_online_gifs_key)) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth())
                        CheckRow(status = gifStatus, checking = checkingGifs, enabled = gifKey.isNotBlank(), onCheck = checkGifs) {
                            TextButton(onClick = {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KLIPY_KEYS))) }
                            }) { Text(stringResource(R.string.fork_online_gifs_get_key)) }
                        }
                    }
                }} else null)

                Text(stringResource(R.string.fork_online_footer), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = ForkUi.ScreenPadding + ForkUi.RowPadding, vertical = 16.dp))
                Spacer(Modifier.heightIn(min = 24.dp))
            }
        }
    }
}

/** The result of the last check on the left, the buttons on the right. */
@Composable
private fun CheckRow(status: String?, checking: Boolean, enabled: Boolean, onCheck: () -> Unit, extra: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (checking) stringResource(R.string.fork_online_checking) else status.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        extra()
        Button(onClick = onCheck, enabled = enabled && !checking) { Text(stringResource(R.string.fork_online_check)) }
    }
}

@StringRes
private fun translateProblem(problem: LibreTranslate.Problem) = when (problem) {
    LibreTranslate.Problem.NOT_SET_UP -> R.string.fork_online_err_address
    LibreTranslate.Problem.UNREACHABLE -> R.string.fork_translate_err_unreachable
    LibreTranslate.Problem.KEY -> R.string.fork_translate_err_key
    LibreTranslate.Problem.BUSY -> R.string.fork_translate_err_busy
    LibreTranslate.Problem.TOO_LONG, LibreTranslate.Problem.FAILED -> R.string.fork_online_err_not_libretranslate
}

@StringRes
private fun gifProblem(problem: Klipy.Problem) = when (problem) {
    Klipy.Problem.NOT_SET_UP, Klipy.Problem.KEY -> R.string.fork_gif_err_key
    Klipy.Problem.UNREACHABLE -> R.string.fork_gif_err_unreachable
    Klipy.Problem.BUSY -> R.string.fork_gif_err_busy
    Klipy.Problem.FAILED -> R.string.fork_gif_err_failed
}

private const val KLIPY_KEYS = "https://partner.klipy.com/api-keys"
