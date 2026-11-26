package com.azu.bookkeeper.ui.category

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azu.bookkeeper.domain.model.Category
import com.azu.bookkeeper.domain.model.CategoryKind
import com.azu.bookkeeper.ui.common.AppTopBar
import com.azu.bookkeeper.ui.common.CuteOutlinedButton
import com.azu.bookkeeper.ui.common.CutePrimaryButton
import com.azu.bookkeeper.ui.common.CuteTextField
import com.azu.bookkeeper.ui.common.SoftCard

/**
 * 分类管理。
 *
 * 原来顶部永远挂着一个输入框 + 一个「新增」按钮，列表里每行三个按钮横着排，手机上很挤。
 * 现在：新增收进顶栏「+ 新增」弹窗，列表按「一级分类卡片 + 内嵌二级分类」渲染，
 * 二级分类缩进显示，一眼能看出层级关系。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryManageScreen(viewModel: CategoryManageViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var name by remember { mutableStateOf("") }
    var parentId by remember { mutableStateOf(0L) }
    var parentName by remember { mutableStateOf("") }

    val roots = state.categories.filter { it.parentId == 0L }
    val children = state.categories.filter { it.parentId != 0L }.groupBy { it.parentId }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = "分类",
                subtitle = if (state.kind == CategoryKind.EXPENSE) "支出分类" else "收入分类",
                actions = {
                    TextButton(onClick = {
                        parentId = 0L
                        parentName = ""
                        name = ""
                        editing = EditTarget.Create
                    }) { Text("+ 新增") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CategoryKind.entries.forEach { kind ->
                    FilterChip(
                        selected = state.kind == kind,
                        onClick = { viewModel.setKind(kind) },
                        label = { Text(if (kind == CategoryKind.EXPENSE) "支出" else "收入") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            state.message?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                )
            }

            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(items = roots, key = { it.categoryId }) { root ->
                    val kids = children[root.categoryId].orEmpty()
                    SoftCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(11.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = root.icon.ifBlank { "🏷️" }, fontSize = 15.sp)
                                }
                                Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(
                                        text = root.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (kids.isNotEmpty()) {
                                        Text(
                                            text = "${kids.size} 个子分类",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                TextButton(onClick = {
                                    parentId = root.categoryId
                                    parentName = root.name
                                    name = ""
                                    editing = EditTarget.Create
                                }) { Text("加子级", style = MaterialTheme.typography.labelSmall) }
                                TextButton(onClick = {
                                    name = root.name
                                    editing = EditTarget.Rename(root)
                                }) { Text("改名", style = MaterialTheme.typography.labelSmall) }
                            }

                            kids.forEach { child ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 6.dp)
                                        .height(1.dp)
                                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "└ ${child.name}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = {
                                        name = child.name
                                        editing = EditTarget.Rename(child)
                                    }) { Text("改名", style = MaterialTheme.typography.labelSmall) }
                                    TextButton(onClick = { viewModel.delete(child, 0L) }) {
                                        Text("删除", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }

                            if (kids.isEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = { viewModel.delete(root, 0L) }) {
                                        Text("删除分类", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Text(
                        text = "分类最多两级。删除分类时，已记的账单会迁移到「其他」，不会丢账。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp)
                    )
                }
            }
        }
    }

    when (val target = editing) {
        EditTarget.Create -> AlertDialog(
            onDismissRequest = { editing = null },
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(if (parentId == 0L) "新增一级分类" else "在「$parentName」下加子分类") },
            text = {
                CuteTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "分类名称",
                    placeholder = "例如 咖啡 / 房租 / 工资",
                    leadingEmoji = "🏷️",
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                CutePrimaryButton(
                    onClick = {
                        viewModel.add(name, parentId)
                        editing = null
                    },
                    enabled = name.isNotBlank()
                ) { Text("新增") }
            },
            dismissButton = { CuteOutlinedButton(onClick = { editing = null }) { Text("取消") } }
        )

        is EditTarget.Rename -> AlertDialog(
            onDismissRequest = { editing = null },
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("重命名「${target.category.name}」") },
            text = {
                CuteTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "新名称",
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                CutePrimaryButton(
                    onClick = {
                        viewModel.rename(target.category, name)
                        editing = null
                    },
                    enabled = name.isNotBlank()
                ) { Text("保存") }
            },
            dismissButton = { CuteOutlinedButton(onClick = { editing = null }) { Text("取消") } }
        )

        null -> Unit
    }
}

/** 当前弹窗是在「新增」还是「改名」。 */
private sealed interface EditTarget {
    data object Create : EditTarget
    data class Rename(val category: Category) : EditTarget
}
