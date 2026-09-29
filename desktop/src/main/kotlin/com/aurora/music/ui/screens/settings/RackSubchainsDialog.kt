package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aurora.music.data.*

@Composable
internal fun RackSubchainsDialog(rack: ProcessingRack, chains: List<RackSubchain>, onDismiss: () -> Unit,
    onSave: (RackSubchain) -> Unit, onDelete: (String) -> Unit, onAppend: (RackSubchain) -> Unit) {
    var saving by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(rack.nodes.map { it.id }.toSet()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (saving) appString(R.string.text_save_subchain_d43195) else appString(R.string.text_saved_subchains_46c948)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (saving) {
                OutlinedTextField(name, { name = it }, label = { Text(appString(R.string.text_name_709a23)) }, singleLine = true)
                rack.nodes.forEach { node ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(node.id in selected, { checked -> selected = if (checked) selected + node.id else selected - node.id })
                        Text(node.name)
                    }
                }
                Text(appString(R.string.text_external_inputs_become_the_subchain_input_3057fb), style = MaterialTheme.typography.bodySmall)
            } else {
                chains.forEach { chain ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(chain.name); Text(appString(R.string.text_stages_b02622, (chain.rack.nodes.size)), style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { onAppend(chain) }) { Text(appString(R.string.text_append_6b3a60)) }
                        TextButton(onClick = { onDelete(chain.id) }) { Text(appString(R.string.text_delete_f6fdbe)) }
                    }
                }
                if (chains.isEmpty()) Text(appString(R.string.text_no_saved_subchains_27969b))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        }
    }, confirmButton = {
        TextButton(enabled = if (saving) name.isNotBlank() && selected.isNotEmpty() else rack.nodes.isNotEmpty() && chains.size < RackSubchainCodec.MAX_CHAINS,
            onClick = {
                if (!saving) saving = true else runCatching { RackSubchainCodec.capture(rack, selected, name) }
                    .onSuccess { onSave(it); saving = false; name = ""; error = null }
                    .onFailure { error = it.message }
            }) { Text(if (saving) appString(R.string.text_save_efc007) else appString(R.string.text_save_stages_a0f22f)) }
    }, dismissButton = { TextButton(onClick = { if (saving) saving = false else onDismiss() }) { Text(if (saving) appString(R.string.text_cancel_77dfd2) else appString(R.string.text_close_bbfa77)) } })
}
