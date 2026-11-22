package com.azu.bookkeeper.ui.category

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azu.bookkeeper.domain.model.Tag

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagManageScreen(viewModel: TagManageViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<Tag?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("标签管理") }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("新标签名称（最多 10 个/账单）") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = { viewModel.add(name); name = "" }, modifier = Modifier.padding(top = 8.dp)) {
                Text("新增标签")
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            LazyColumn(modifier = Modifier.padding(top = 12.dp)) {
                items(items = state.tags, key = { it.tagId }) { tag ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = tag.name, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { renaming = tag; name = tag.name }) { Text("改名") }
                        OutlinedButton(
                            onClick = { viewModel.delete(tag.tagId) },
                            modifier = Modifier.padding(start = 6.dp)
                        ) { Text("删除") }
                    }
                    Divider()
                }
            }
        }
    }

    renaming?.let { tag ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名标签") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }) },
            confirmButton = {
                Button(onClick = { viewModel.rename(tag, name); renaming = null }) { Text("保存") }
            },
            dismissButton = { OutlinedButton(onClick = { renaming = null }) { Text("取消") } }
        )
    }
}
