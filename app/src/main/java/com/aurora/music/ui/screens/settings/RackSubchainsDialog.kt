package com.aurora.music.ui.screens.settings

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
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (saving) "Save subchain" else "Saved subchains") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (saving) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                rack.nodes.forEach { node ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(node.id in selected, { checked -> selected = if (checked) selected + node.id else selected - node.id })
                        Text(node.name)
                    }
                }
                Text("External inputs become the subchain input.", style = MaterialTheme.typography.bodySmall)
            } else {
                chains.forEach { chain ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(chain.name); Text("${chain.rack.nodes.size} stages", style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { onAppend(chain) }) { Text("Append") }
                        TextButton(onClick = { onDelete(chain.id) }) { Text("Delete") }
                    }
                }
                if (chains.isEmpty()) Text("No saved subchains.")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        }
    }, confirmButton = {
        TextButton(enabled = if (saving) name.isNotBlank() && selected.isNotEmpty() else rack.nodes.isNotEmpty() && chains.size < RackSubchainCodec.MAX_CHAINS,
            onClick = {
                if (!saving) saving = true else runCatching { RackSubchainCodec.capture(rack, selected, name) }
                    .onSuccess { onSave(it); saving = false; name = ""; error = null }
                    .onFailure { error = it.message }
            }) { Text(if (saving) "Save" else "Save stages") }
    }, dismissButton = { TextButton(onClick = { if (saving) saving = false else onDismiss() }) { Text(if (saving) "Cancel" else "Close") } })
}
