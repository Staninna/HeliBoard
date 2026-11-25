// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork.ai

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.LifecycleResumeEffect
import helium314.keyboard.fork.ForkSettings
import helium314.keyboard.fork.net.NetworkGate
import helium314.keyboard.fork.ui.ForkUi
import helium314.keyboard.fork.ui.GroupTitle
import helium314.keyboard.fork.ui.SettingsGroup
import helium314.keyboard.fork.ui.Switch
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import helium314.keyboard.settings.preferences.Preference

/** Fork: pairing with SmolChat Server, the proofread chip, and the prompts the rewrite panel offers. */
@Composable
fun LocalAiScreen(onClickBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.prefs()
    // re-read when the screen comes back, for example after installing SmolChat Server
    var version by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { version++; onPauseOrDispose {} }
    val installed = remember(version) { SmolChat.isInstalled(context) }
    var paired by remember(version) { mutableStateOf(LocalAiPairing.isPaired(context)) }
    var proofread by remember { mutableStateOf(prefs.getBoolean(ForkSettings.PREF_PROOFREAD_CHIP, ForkSettings.DEFAULT_PROOFREAD_CHIP)) }
    var custom by remember { mutableStateOf(RewritePrompts.custom(prefs)) }
    var editing by rememberSaveable { mutableStateOf<Int?>(null) } // index in custom, or -1 for a new one

    val pairLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val token = result.data?.getStringExtra(SmolChat.EXTRA_TOKEN)
        val baseUrl = result.data?.getStringExtra(SmolChat.EXTRA_BASE_URL)?.trimEnd('/')
        val loopback = baseUrl != null && runCatching { NetworkGate.check(NetworkGate.Feature.LOCAL_AI, baseUrl, true) }.isSuccess
        if (result.resultCode == Activity.RESULT_OK && !token.isNullOrBlank() && loopback) {
            LocalAiPairing.save(context, LocalAiPairing.Pairing(token, baseUrl!!))
            paired = true
        } else {
            Toast.makeText(context, R.string.fork_local_ai_pair_failed, Toast.LENGTH_SHORT).show()
        }
    }
    val pair = {
        runCatching {
            pairLauncher.launch(Intent(SmolChat.ACTION_PAIR).setPackage(SmolChat.PACKAGE).putExtra(SmolChat.EXTRA_CLIENT_NAME, "HeliBoard"))
        }
        Unit
    }

    SearchSettingsScreen(onClickBack = onClickBack, title = stringResource(R.string.fork_local_ai), settings = emptyList()) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(Modifier.verticalScroll(rememberScrollState()).padding(innerPadding)) {
                Spacer(Modifier.heightIn(min = 8.dp))
                SettingsGroup({
                    StatusCard(
                        installed = installed,
                        paired = paired,
                        onPair = pair,
                        onUnpair = { LocalAiPairing.clear(context); paired = false },
                        onGet = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Staninna/SmolChatServer/releases"))) }
                        },
                    )
                }, if (paired) {{
                    Preference(
                        name = stringResource(R.string.fork_local_ai_proofread),
                        description = stringResource(R.string.fork_local_ai_proofread_summary),
                        onClick = {
                            proofread = !proofread
                            prefs.edit { putBoolean(ForkSettings.PREF_PROOFREAD_CHIP, proofread) }
                        },
                        icon = R.drawable.ic_fork_rewrite,
                    ) {
                        Switch(checked = proofread, onCheckedChange = {
                            proofread = it
                            prefs.edit { putBoolean(ForkSettings.PREF_PROOFREAD_CHIP, it) }
                        })
                    }
                }} else null)

                GroupTitle(stringResource(R.string.fork_local_ai_presets))
                SettingsGroup(*RewritePrompts.presets.map { p -> @Composable {
                    Preference(name = p.name, description = p.instruction, onClick = {})
                } }.toTypedArray())

                GroupTitle(stringResource(R.string.fork_local_ai_custom))
                SettingsGroup(*(custom.mapIndexed { i, p -> @Composable {
                    Preference(name = p.name, description = p.instruction, onClick = { editing = i }) {
                        IconButton(onClick = { custom = custom.move(i, -1).also { RewritePrompts.saveCustom(prefs, it) } }, enabled = i > 0) {
                            Icon(painterResource(R.drawable.ic_dpad_up_rounded), stringResource(R.string.fork_local_ai_move_up))
                        }
                    }
                } } + @Composable {
                    Preference(name = stringResource(R.string.fork_local_ai_add), onClick = { editing = -1 }, icon = R.drawable.ic_plus)
                }).toTypedArray())
                Spacer(Modifier.heightIn(min = 24.dp))
            }
        }
    }

    editing?.let { index ->
        val existing = custom.getOrNull(index)
        PromptDialog(
            prompt = existing,
            onDismiss = { editing = null },
            onSave = { p ->
                custom = (if (existing == null) custom + p else custom.toMutableList().also { it[index] = p })
                RewritePrompts.saveCustom(prefs, custom)
                editing = null
            },
            onDelete = existing?.let { {
                custom = custom.filterIndexed { i, _ -> i != index }
                RewritePrompts.saveCustom(prefs, custom)
                editing = null
            } },
        )
    }
}

private fun <T> List<T>.move(index: Int, by: Int): List<T> {
    val to = (index + by).coerceIn(indices)
    return toMutableList().also { it.add(to, it.removeAt(index)) }
}

@Composable
private fun StatusCard(installed: Boolean, paired: Boolean, onPair: () -> Unit, onUnpair: () -> Unit, onGet: () -> Unit) {
    val (title, body) = when {
        !installed -> R.string.fork_local_ai_status_missing to R.string.fork_local_ai_status_missing_body
        paired -> R.string.fork_local_ai_status_paired to R.string.fork_local_ai_status_paired_body
        else -> R.string.fork_local_ai_status_unpaired to R.string.fork_local_ai_status_unpaired_body
    }
    Column(Modifier.fillMaxWidth().padding(ForkUi.RowPadding)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically) {
            when {
                !installed -> Button(onClick = onGet) { Text(stringResource(R.string.fork_ai_action_get)) }
                paired -> {
                    TextButton(onClick = onUnpair) { Text(stringResource(R.string.fork_local_ai_unpair)) }
                    Button(onClick = onPair) { Text(stringResource(R.string.fork_local_ai_pair_again)) }
                }
                else -> Button(onClick = onPair) { Text(stringResource(R.string.fork_local_ai_pair)) }
            }
        }
    }
}

@Composable
private fun PromptDialog(
    prompt: RewritePrompts.Prompt?,
    onDismiss: () -> Unit,
    onSave: (RewritePrompts.Prompt) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by rememberSaveable { mutableStateOf(prompt?.name ?: "") }
    var instruction by rememberSaveable { mutableStateOf(prompt?.instruction ?: "") }
    ThreeButtonAlertDialog(
        onDismissRequest = onDismiss,
        onConfirmed = { onSave(RewritePrompts.Prompt(name.trim(), instruction.trim())) },
        checkOk = { name.isNotBlank() && instruction.isNotBlank() },
        title = { Text(stringResource(if (prompt == null) R.string.fork_local_ai_add else R.string.fork_local_ai_edit)) },
        neutralButtonText = if (onDelete != null) stringResource(R.string.fork_local_ai_delete) else null,
        onNeutral = { onDelete?.invoke() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, singleLine = true,
                    label = { Text(stringResource(R.string.fork_local_ai_name)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = instruction, onValueChange = { instruction = it.take(500) }, minLines = 3,
                    label = { Text(stringResource(R.string.fork_local_ai_instruction)) },
                    placeholder = { Text(stringResource(R.string.fork_local_ai_instruction_hint)) }, modifier = Modifier.fillMaxWidth())
            }
        },
    )
}
