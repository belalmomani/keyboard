package com.omsingh.beckon.ui.screens.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.omsingh.beckon.R
import com.omsingh.beckon.connection.TextSendResult
import com.omsingh.beckon.core.library.LibraryError
import com.omsingh.beckon.core.library.Phrase
import com.omsingh.beckon.core.library.PhraseScope
import com.omsingh.beckon.core.library.PhraseSearch
import com.omsingh.beckon.core.text.TextBlockResult
import com.omsingh.beckon.core.text.TextBlockStage
import com.omsingh.beckon.ui.theme.Spacing

/** Saved phrases and the clipboard history, with sending text to the PC. */
@Composable
fun LibraryTab(
    controller: LibraryController?,
    onCopyToPhone: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (controller == null) return
    var section by rememberSaveable { mutableStateOf(0) }
    DisposableListening(controller)
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(R.string.lib_phrases, R.string.lib_clipboard).forEachIndexed { index, label ->
                SegmentedButton(
                    selected = section == index,
                    onClick = { section = index },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                ) { Text(stringResource(label)) }
            }
        }
        if (section == 0) PhrasesSection(controller) else ClipboardSection(controller, onCopyToPhone)
    }
    SendStatus(controller)
}

@Composable
private fun DisposableListening(controller: LibraryController) {
    androidx.compose.runtime.DisposableEffect(controller) {
        controller.startListening()
        controller.captureNow()
        onDispose { controller.stopListening(); controller.saveNow() }
    }
}

// ── Phrases ──────────────────────────────────────────────────────────

@Composable
private fun PhrasesSection(controller: LibraryController) {
    val data by controller.phrases.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var scopeKey by rememberSaveable { mutableStateOf("all") }
    var editing by remember { mutableStateOf<Phrase?>(null) }
    var adding by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }

    val scope = when {
        scopeKey == "all" -> PhraseScope.All
        scopeKey == "fav" -> PhraseScope.Favorites
        scopeKey == "none" -> PhraseScope.Unfiled
        else -> PhraseScope.Folder(scopeKey.removePrefix("f:"))
    }
    val shown = PhraseSearch.find(data, query, scope)

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SearchField(query) { query = it }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FilterChip(scopeKey == "all", { scopeKey = "all" }, { Text(stringResource(R.string.lib_all)) })
            FilterChip(scopeKey == "fav", { scopeKey = "fav" }, { Text(stringResource(R.string.lib_favorites)) })
            FilterChip(scopeKey == "none", { scopeKey = "none" }, { Text(stringResource(R.string.lib_unfiled)) })
            for (folder in data.folders) {
                FilterChip(scopeKey == "f:${folder.id}", { scopeKey = "f:${folder.id}" }, { Text(folder.name) })
            }
            TextButton(onClick = { newFolder = true }) { Text(stringResource(R.string.lib_new_folder)) }
        }
        androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxWidth()) {
            if (shown.isEmpty()) {
                Text(
                    stringResource(if (data.phrases.isEmpty()) R.string.lib_empty_phrases else R.string.lib_no_match),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.lg),
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    items(shown, key = { it.id }) { phrase ->
                        PhraseCard(
                            phrase,
                            onSend = { controller.requestSend(phrase.text, phrase.id) },
                            onFavorite = { controller.library.toggleFavorite(phrase.id) },
                            onEdit = { editing = phrase },
                            onDelete = { controller.library.delete(phrase.id) },
                        )
                    }
                }
            }
            FloatingActionButton(
                onClick = { adding = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.sm),
            ) { Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.lib_add_phrase)) }
        }
    }

    if (adding) {
        PhraseDialog(null, data.folders, onDismiss = { adding = false }) { title, text, folder ->
            controller.addPhrase(title, text, folder).also { if (it == null) adding = false }
        }
    }
    editing?.let { phrase ->
        PhraseDialog(phrase, data.folders, onDismiss = { editing = null }) { title, text, folder ->
            controller.updatePhrase(phrase.id, title, text, folder).also { if (it == null) editing = null }
        }
    }
    if (newFolder) {
        var name by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<LibraryError?>(null) }
        AlertDialog(
            onDismissRequest = { newFolder = false },
            title = { Text(stringResource(R.string.lib_new_folder)) },
            text = {
                OutlinedTextField(
                    name, { name = it; error = null }, singleLine = true, isError = error != null,
                    label = { Text(stringResource(R.string.lib_folder_name)) },
                    supportingText = error?.let { { Text(stringResource(R.string.lib_err_other)) } },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    error = controller.library.addFolder(name)
                    if (error == null) newFolder = false
                }) { Text(stringResource(R.string.lib_save)) }
            },
            dismissButton = { TextButton(onClick = { newFolder = false }) { Text(stringResource(R.string.lib_cancel)) } },
        )
    }
}

@Composable
private fun PhraseCard(
    phrase: Phrase,
    onSend: () -> Unit,
    onFavorite: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(phrase.title, style = MaterialTheme.typography.titleSmall)
            Text(
                phrase.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onFavorite) {
                    Icon(
                        if (phrase.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = stringResource(R.string.lib_favorite),
                    )
                }
                IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.lib_edit)) }
                IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.lib_delete)) }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                TextButton(onClick = onSend) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.padding(end = Spacing.xs))
                    Text(stringResource(R.string.lib_send))
                }
            }
        }
    }
}

@Composable
private fun PhraseDialog(
    phrase: Phrase?,
    folders: List<com.omsingh.beckon.core.library.PhraseFolder>,
    onDismiss: () -> Unit,
    onSave: (title: String, text: String, folderId: String?) -> LibraryError?,
) {
    var title by remember { mutableStateOf(phrase?.title ?: "") }
    var text by remember { mutableStateOf(phrase?.text ?: "") }
    var folder by remember { mutableStateOf(phrase?.folderId) }
    var error by remember { mutableStateOf<LibraryError?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (phrase == null) R.string.lib_add_phrase else R.string.lib_edit_phrase)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(title, { title = it }, singleLine = true, label = { Text(stringResource(R.string.lib_title)) })
                OutlinedTextField(
                    text, { text = it; error = null }, minLines = 3, maxLines = 8, isError = error != null,
                    label = { Text(stringResource(R.string.lib_text)) },
                    supportingText = error?.let {
                        {
                            Text(
                                stringResource(
                                    when (it) {
                                        LibraryError.EMPTY_TEXT -> R.string.lib_err_empty
                                        LibraryError.TEXT_TOO_LONG -> R.string.lib_err_long
                                        else -> R.string.lib_err_other
                                    },
                                ),
                            )
                        }
                    },
                )
                if (folders.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        FilterChip(folder == null, { folder = null }, { Text(stringResource(R.string.lib_unfiled)) })
                        for (f in folders) FilterChip(folder == f.id, { folder = f.id }, { Text(f.name) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { error = onSave(title, text, folder) }) { Text(stringResource(R.string.lib_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lib_cancel)) } },
    )
}

// ── Clipboard history ────────────────────────────────────────────────

@Composable
private fun ClipboardSection(controller: LibraryController, onCopyToPhone: (String) -> Unit) {
    val on by controller.historyOn.collectAsState()
    val entries by controller.history.entries.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(entries, query) { controller.searchHistory(query) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.clip_enable), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.clip_enable_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = on, onCheckedChange = controller::setHistory)
        }
        if (on) {
            SearchField(query) { query = it }
            if (shown.isEmpty()) {
                Text(
                    stringResource(if (entries.isEmpty()) R.string.clip_empty else R.string.lib_no_match),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items(shown, key = { it.id }) { entry ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.md)) {
                            Text(entry.text, maxLines = 3, style = MaterialTheme.typography.bodyMedium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { controller.history.setPinned(entry.id, !entry.pinned) }) {
                                    Icon(
                                        Icons.Rounded.PushPin,
                                        contentDescription = stringResource(if (entry.pinned) R.string.clip_unpin else R.string.clip_pin),
                                        tint = if (entry.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { onCopyToPhone(entry.text) }) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.clip_copy))
                                }
                                IconButton(onClick = { controller.history.delete(entry.id) }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.lib_delete))
                                }
                                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                                TextButton(onClick = { controller.requestSend(entry.text) }) { Text(stringResource(R.string.lib_send)) }
                            }
                        }
                    }
                }
            }
            if (entries.isNotEmpty()) {
                TextButton(onClick = { controller.history.clear() }) { Text(stringResource(R.string.clip_clear)) }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, singleLine = true, modifier = Modifier.fillMaxWidth(),
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        placeholder = { Text(stringResource(R.string.lib_search)) },
    )
}

// ── Sending status ───────────────────────────────────────────────────

@Composable
private fun SendStatus(controller: LibraryController) {
    val ui by controller.send.collectAsState()
    when (val s = ui) {
        SendUi.Idle -> Unit
        is SendUi.Confirm -> AlertDialog(
            onDismissRequest = controller::cancelSend,
            title = { Text(stringResource(R.string.lib_confirm_title)) },
            text = { Text(stringResource(R.string.lib_confirm_body)) },
            confirmButton = { TextButton(onClick = controller::confirmSend) { Text(stringResource(R.string.lib_confirm_send)) } },
            dismissButton = { TextButton(onClick = controller::cancelSend) { Text(stringResource(R.string.lib_cancel)) } },
        )
        is SendUi.Sending -> AlertDialog(
            onDismissRequest = {},
            title = {
                Text(
                    stringResource(
                        when (s.stage) {
                            TextBlockStage.SENDING -> R.string.lib_sending
                            TextBlockStage.WAITING -> R.string.lib_waiting
                            TextBlockStage.TYPING -> R.string.lib_typing
                        },
                    ),
                )
            },
            confirmButton = { TextButton(onClick = controller::cancelSend) { Text(stringResource(R.string.lib_stop)) } },
        )
        is SendUi.Finished -> AlertDialog(
            onDismissRequest = controller::cancelSend,
            text = { Text(finishedText(s.result)) },
            confirmButton = { TextButton(onClick = controller::cancelSend) { Text(stringResource(R.string.lib_dismiss)) } },
        )
    }
}

@Composable
private fun finishedText(result: TextSendResult): String = when (result) {
    is TextSendResult.Confirmed -> {
        val r = result.result
        when (r.outcome) {
            TextBlockResult.Outcome.DONE -> stringResource(R.string.lib_done, r.typed)
            TextBlockResult.Outcome.FAILED -> stringResource(R.string.lib_failed, r.typed, r.length)
            TextBlockResult.Outcome.CANCELLED -> stringResource(R.string.lib_cancelled, r.typed)
            TextBlockResult.Outcome.REJECTED -> stringResource(R.string.lib_rejected)
            TextBlockResult.Outcome.TIMEOUT -> stringResource(R.string.lib_timeout)
            TextBlockResult.Outcome.TOO_LONG -> stringResource(R.string.lib_too_long)
        }
    }
    TextSendResult.Unconfirmed -> stringResource(R.string.lib_unconfirmed)
    TextSendResult.NotConnected -> stringResource(R.string.lib_not_connected)
    TextSendResult.NeedsWifi -> stringResource(R.string.lib_needs_wifi)
    TextSendResult.PcTooOld -> stringResource(R.string.lib_pc_too_old)
}
