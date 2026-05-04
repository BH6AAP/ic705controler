package com.bh6aap.ic705Cter.ui.components

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bh6aap.ic705Cter.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import android.content.pm.PackageManager
import android.os.Build
import android.Manifest
import android.provider.MediaStore
import android.content.ContentValues
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.bh6aap.ic705Cter.R
import androidx.compose.ui.res.stringResource

/**
 * 呼号库编辑对话框
 * 用于添加、删除和查看呼号库中的呼号
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallsignLibraryEditDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var callsigns by remember { mutableStateOf<List<String>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var newCallsign by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }
    var showClearAllConfirm by remember { mutableStateOf(false) }

    // 文件选择器启动器
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val fileName = getFileNameFromUri(context, it) ?: "unknown"
            LogManager.i("CallsignLibraryEditDialog", "选择文件: $fileName, URI: $it")

            scope.launch(Dispatchers.IO) {
                val fileInfo = TxtFileInfo(fileName, it)
                val importCallback: (Boolean, String?, Int) -> Unit = { success, message, importedCount ->
                    scope.launch(Dispatchers.Main) {
                        LogManager.i("CallsignLibraryEditDialog", "导入结果: success=$success, message=$message, count=$importedCount")
                        if (success && importedCount > 0) {
                            val loadedCallsigns = loadCallsignsFromFile(context)
                            callsigns = loadedCallsigns.sorted()
                            errorMessage = null
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.callsign_library_import_success, importedCount),
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        } else if (success) {
                            errorMessage = context.getString(R.string.callsign_library_import_empty)
                        } else {
                            errorMessage = message
                        }
                    }
                }

                if (fileName.endsWith(".csv", ignoreCase = true)) {
                    importCallsignsFromCsv(context, fileInfo, importCallback)
                } else {
                    importCallsignsFromTxt(context, fileInfo, importCallback)
                }
            }
        }
    }

    // 加载呼号列表
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val loadedCallsigns = loadCallsignsFromFile(context)
                withContext(Dispatchers.Main) {
                    callsigns = loadedCallsigns.sorted()
                    isLoading = false
                }
            } catch (e: Exception) {
                LogManager.e("CallsignLibraryEditDialog", "加载呼号库失败", e)
                withContext(Dispatchers.Main) {
                    errorMessage = context.getString(R.string.callsign_library_load_failed, e.message ?: "")
                    isLoading = false
                }
            }
        }
    }

    // 过滤后的呼号列表
    val filteredCallsigns = remember(callsigns, searchQuery) {
        if (searchQuery.isBlank()) {
            callsigns
        } else {
            callsigns.filter { it.contains(searchQuery.trim().uppercase()) }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // 标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.callsign_library_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.callsign_library_close),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 添加新呼号区域
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.callsign_library_add),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newCallsign,
                                onValueChange = { value ->
                                    // 只允许输入字母、数字，并自动转为大写
                                    newCallsign = value.filter { it.isLetterOrDigit() }.uppercase()
                                },
                                label = { Text(stringResource(R.string.callsign_library_input_hint)) },
                                placeholder = { Text(stringResource(R.string.callsign_library_input_placeholder)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Characters,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        if (newCallsign.isNotBlank()) {
                                            scope.launch(Dispatchers.IO) {
                                                addCallsign(context, newCallsign) { success, message ->
                                                    scope.launch(Dispatchers.Main) {
                                                        if (success) {
                                                            callsigns = (callsigns + newCallsign).sorted()
                                                            newCallsign = ""
                                                            errorMessage = null
                                                        } else {
                                                            errorMessage = message
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                ),
                                modifier = Modifier.weight(1f)
                            )

                            Button(
                                onClick = {
                                    if (newCallsign.isNotBlank()) {
                                        scope.launch(Dispatchers.IO) {
                                            addCallsign(context, newCallsign) { success, message ->
                                                scope.launch(Dispatchers.Main) {
                                                    if (success) {
                                                        callsigns = (callsigns + newCallsign).sorted()
                                                        newCallsign = ""
                                                        errorMessage = null
                                                    } else {
                                                        errorMessage = message
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                                enabled = newCallsign.isNotBlank(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = stringResource(R.string.common_add)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.common_save))
                            }
                        }

                        // 取消按钮
                        if (newCallsign.isNotBlank()) {
                            TextButton(
                                onClick = { newCallsign = "" },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text(stringResource(R.string.common_cancel))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 错误提示
                errorMessage?.let { error ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // 搜索框
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it.uppercase() },
                    label = { Text(stringResource(R.string.callsign_library_search)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = stringResource(R.string.common_search)
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 呼号数量和统计 + 导出按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.callsign_library_count, callsigns.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (searchQuery.isNotBlank()) {
                            Text(
                                text = stringResource(R.string.callsign_library_match_count, filteredCallsigns.size),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        // 导出按钮
                        TextButton(
                            onClick = {
                                scope.launch {
                                    LogManager.i("CallsignLibraryEditDialog", "点击导出按钮，呼号数量: ${callsigns.size}")
                                    // 检查并请求存储权限
                                    val hasPermission = checkAndRequestStoragePermission(context)
                                    LogManager.i("CallsignLibraryEditDialog", "权限检查结果: $hasPermission")
                                    if (hasPermission) {
                                        scope.launch(Dispatchers.IO) {
                                            exportCallsignsToTxt(context, callsigns) { success, message ->
                                                scope.launch(Dispatchers.Main) {
                                                    LogManager.i("CallsignLibraryEditDialog", "导出结果: success=$success, message=$message")
                                                    if (success) {
                                                        errorMessage = null
                                                        // 显示成功提示
                                                        android.widget.Toast.makeText(
                                                            context,
                                                            message,
                                                            android.widget.Toast.LENGTH_LONG
                                                        ).show()
                                                    } else {
                                                        errorMessage = message
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        errorMessage = context.getString(R.string.callsign_library_storage_permission)
                                    }
                                }
                            },
                            enabled = callsigns.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = stringResource(R.string.callsign_library_export),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.callsign_library_export))
                        }

                        // 导入按钮
                        TextButton(
                            onClick = {
                                LogManager.i("CallsignLibraryEditDialog", "点击导入按钮，打开系统文件选择器")
                                // 打开系统文件选择器，允许选择txt和csv文件
                                filePickerLauncher.launch("text/*")
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.List,
                                contentDescription = stringResource(R.string.callsign_library_import),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.callsign_library_import))
                        }

                        // 一键清空按钮
                        TextButton(
                            onClick = {
                                if (callsigns.isNotEmpty()) {
                                    showClearAllConfirm = true
                                }
                            },
                            enabled = callsigns.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.callsign_library_clear),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.callsign_library_clear),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 呼号列表
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (filteredCallsigns.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (searchQuery.isBlank()) stringResource(R.string.callsign_library_empty) else stringResource(R.string.callsign_library_no_match),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    // 使用Slider样式的滑动列表
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(
                                items = filteredCallsigns,
                                key = { it }
                            ) { callsign ->
                                CallsignItem(
                                    callsign = callsign,
                                    onDelete = {
                                        showDeleteConfirm = callsign
                                    }
                                )
                            }
                        }
                    }

                    // 滚动指示器（Slider效果）
                    if (filteredCallsigns.size > 10) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val scrollProgress by remember {
                            derivedStateOf {
                                val layoutInfo = listState.layoutInfo
                                val totalItems = layoutInfo.totalItemsCount
                                val visibleItems = layoutInfo.visibleItemsInfo.size
                                if (totalItems > visibleItems) {
                                    val firstVisible = listState.firstVisibleItemIndex
                                    firstVisible.toFloat() / (totalItems - visibleItems).coerceAtLeast(1)
                                } else {
                                    0f
                                }
                            }
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Slider(
                                value = scrollProgress,
                                onValueChange = { },
                                modifier = Modifier.fillMaxWidth(0.8f),
                                enabled = false
                            )
                            Text(
                                text = stringResource(R.string.callsign_library_scroll_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }

    // 删除确认对话框
    showDeleteConfirm?.let { callsignToDelete ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text(stringResource(R.string.callsign_library_delete_confirm_title)) },
            text = { Text(stringResource(R.string.callsign_library_delete_confirm_message, callsignToDelete)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            removeCallsign(context, callsignToDelete) { success, message ->
                                scope.launch(Dispatchers.Main) {
                                    if (success) {
                                        callsigns = callsigns.filter { it != callsignToDelete }
                                        errorMessage = null
                                    } else {
                                        errorMessage = message
                                    }
                                    showDeleteConfirm = null
                                }
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // 一键清空确认对话框
    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text(stringResource(R.string.callsign_library_clear_confirm_title)) },
            text = { Text(stringResource(R.string.callsign_library_clear_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            try {
                                // 清空呼号列表
                                saveCallsignsToFile(context, emptyList())
                                withContext(Dispatchers.Main) {
                                    callsigns = emptyList()
                                    errorMessage = null
                                    showClearAllConfirm = false
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(R.string.callsign_library_clear_success),
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } catch (e: Exception) {
                                LogManager.e("CallsignLibraryEditDialog", "清空呼号库失败", e)
                                withContext(Dispatchers.Main) {
                                    errorMessage = context.getString(R.string.common_failed) + ": ${e.message}"
                                    showClearAllConfirm = false
                                }
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.common_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun CallsignItem(
    callsign: String,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = callsign,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * 从文件加载呼号列表
 */
private suspend fun loadCallsignsFromFile(context: Context): List<String> {
    return withContext(Dispatchers.IO) {
        val callsigns = mutableListOf<String>()
        try {
            // 优先从内部存储加载
            val file = context.getFileStreamPath("callsigns_custom.txt")
            val inputStream = if (file.exists()) {
                context.openFileInput("callsigns_custom.txt")
            } else {
                // 从assets复制到内部存储
                copyAssetsToInternal(context)
                context.assets.open("callsigns.txt")
            }

            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                reader.lineSequence().forEach { line ->
                    val trimmed = line.trim().uppercase()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        callsigns.add(trimmed)
                    }
                }
            }
            LogManager.i("CallsignLibraryEditDialog", "加载了 ${callsigns.size} 个呼号")
        } catch (e: Exception) {
            LogManager.e("CallsignLibraryEditDialog", "加载呼号失败", e)
            throw e
        }
        callsigns
    }
}

/**
 * 将assets中的呼号文件复制到内部存储
 */
private suspend fun copyAssetsToInternal(context: Context) {
    withContext(Dispatchers.IO) {
        try {
            context.assets.open("callsigns.txt").use { input ->
                context.openFileOutput("callsigns_custom.txt", Context.MODE_PRIVATE).use { output ->
                    input.copyTo(output)
                }
            }
            LogManager.i("CallsignLibraryEditDialog", "已复制呼号文件到内部存储")
        } catch (e: Exception) {
            LogManager.e("CallsignLibraryEditDialog", "复制呼号文件失败", e)
        }
    }
}

/**
 * 添加呼号到文件
 */
private suspend fun addCallsign(
    context: Context,
    callsign: String,
    callback: (Boolean, String?) -> Unit
) {
    withContext(Dispatchers.IO) {
        try {
            val existingCallsigns = loadCallsignsFromFile(context).toMutableList()

            // 检查是否已存在
            if (existingCallsigns.contains(callsign)) {
                callback(false, context.getString(R.string.callsign_library_exists, callsign))
                return@withContext
            }

            // 添加到列表
            existingCallsigns.add(callsign)

            // 保存到文件
            saveCallsignsToFile(context, existingCallsigns)

            LogManager.i("CallsignLibraryEditDialog", "添加呼号: $callsign")
            callback(true, null)
        } catch (e: Exception) {
            LogManager.e("CallsignLibraryEditDialog", "添加呼号失败", e)
            callback(false, context.getString(R.string.callsign_library_add_failed, e.message ?: ""))
        }
    }
}

/**
 * 从文件中删除呼号
 */
private suspend fun removeCallsign(
    context: Context,
    callsign: String,
    callback: (Boolean, String?) -> Unit
) {
    withContext(Dispatchers.IO) {
        try {
            val existingCallsigns = loadCallsignsFromFile(context).toMutableList()

            // 检查是否存在
            if (!existingCallsigns.contains(callsign)) {
                callback(false, context.getString(R.string.callsign_library_not_exists, callsign))
                return@withContext
            }

            // 从列表中移除
            existingCallsigns.remove(callsign)

            // 保存到文件
            saveCallsignsToFile(context, existingCallsigns)

            LogManager.i("CallsignLibraryEditDialog", "删除呼号: $callsign")
            callback(true, null)
        } catch (e: Exception) {
            LogManager.e("CallsignLibraryEditDialog", "删除呼号失败", e)
            callback(false, context.getString(R.string.callsign_library_delete_failed, e.message ?: ""))
        }
    }
}

/**
 * 保存呼号列表到文件
 */
private suspend fun saveCallsignsToFile(context: Context, callsigns: List<String>) {
    withContext(Dispatchers.IO) {
        try {
            context.openFileOutput("callsigns_custom.txt", Context.MODE_PRIVATE).use { output ->
                OutputStreamWriter(output).use { writer ->
                    writer.write(context.getString(R.string.callsign_library_file_header) + "\n")
                    writer.write(context.getString(R.string.callsign_library_file_comment) + "\n")
                    writer.write(context.getString(R.string.callsign_library_file_updated, java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())) + "\n")
                    writer.write(context.getString(R.string.callsign_library_file_separator) + "\n")
                    callsigns.sorted().forEach { callsign ->
                        writer.write("$callsign\n")
                    }
                }
            }
            LogManager.i("CallsignLibraryEditDialog", "保存了 ${callsigns.size} 个呼号到文件")
        } catch (e: Exception) {
            LogManager.e("CallsignLibraryEditDialog", "保存呼号失败", e)
            throw e
        }
    }
}

/**
 * 导出呼号列表到外部存储的TXT文件
 * 使用MediaStore API兼容Android 10+
 */
private suspend fun exportCallsignsToTxt(
    context: Context,
    callsigns: List<String>,
    callback: (Boolean, String?) -> Unit
) {
    withContext(Dispatchers.IO) {
        try {
            // 生成文件名（带时间戳）
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val fileName = "callsigns_${timestamp}.txt"
            
            val filePath: String
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用MediaStore API
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
                }
                
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    contentValues
                )
                
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        OutputStreamWriter(outputStream).use { writer ->
                            writer.write(context.getString(R.string.callsign_library_file_header) + "\n")
                            writer.write(context.getString(R.string.callsign_library_file_export_time, java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())) + "\n")
                            writer.write(context.getString(R.string.callsign_library_file_count, callsigns.size) + "\n")
                            writer.write(context.getString(R.string.callsign_library_file_separator) + "\n\n")
                            callsigns.sorted().forEach { callsign ->
                                writer.write("$callsign\n")
                            }
                        }
                    }
                    filePath = uri.toString()
                    LogManager.i("CallsignLibraryEditDialog", "导出呼号库到MediaStore: $filePath")
                } else {
                    throw Exception("Cannot create file")
                }
            } else {
                // Android 9及以下使用传统方式
                val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                val file = File(downloadDir, fileName)
                
                file.bufferedWriter().use { writer ->
                    writer.write(context.getString(R.string.callsign_library_file_header) + "\n")
                    writer.write(context.getString(R.string.callsign_library_file_export_time, java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())) + "\n")
                    writer.write(context.getString(R.string.callsign_library_file_count, callsigns.size) + "\n")
                    writer.write(context.getString(R.string.callsign_library_file_separator) + "\n\n")
                    callsigns.sorted().forEach { callsign ->
                        writer.write("$callsign\n")
                    }
                }
                
                // 通知系统扫描新文件
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.absolutePath),
                    arrayOf("text/plain"),
                    null
                )
                
                filePath = file.absolutePath
                LogManager.i("CallsignLibraryEditDialog", "导出呼号库到: $filePath")
            }
            
            callback(true, context.getString(R.string.callsign_library_export_success, fileName))
        } catch (e: Exception) {
            LogManager.e("CallsignLibraryEditDialog", "导出呼号库失败", e)
            callback(false, context.getString(R.string.callsign_library_export_failed, e.message ?: ""))
        }
    }
}

/**
 * 检查并请求存储权限
 * @return 是否有权限
 */
private fun checkAndRequestStoragePermission(context: Context): Boolean {
    // Android 10+ (API 29+) 使用Scoped Storage，导出到Download目录不需要运行时权限
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        return true
    }
    
    // Android 9及以下需要WRITE_EXTERNAL_STORAGE权限
    val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
    val granted = ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    
    if (!granted) {
        // 注意：这里只是检查权限，实际请求权限需要在Activity中进行
        // 返回false让调用方处理权限请求
        LogManager.w("CallsignLibraryEditDialog", "缺少存储权限")
    }
    
    return granted
}

/**
 * 文件信息数据类
 */
private data class TxtFileInfo(
    val name: String,
    val uri: android.net.Uri
)

/**
 * 从TXT文件导入呼号
 */
private suspend fun importCallsignsFromTxt(
    context: Context,
    fileInfo: TxtFileInfo,
    callback: (Boolean, String?, Int) -> Unit
) {
    withContext(Dispatchers.IO) {
        try {
            LogManager.i("CallsignLibraryEditDialog", "开始导入文件: ${fileInfo.name}")
            
            val importedCallsigns = mutableListOf<String>()
            
            // 通过ContentResolver读取文件内容
            context.contentResolver.openInputStream(fileInfo.uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.lineSequence().forEach { line ->
                        val trimmed = line.trim().uppercase()
                        // 跳过空行和注释行
                        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                            // 验证呼号格式（只包含字母和数字）
                            if (trimmed.all { it.isLetterOrDigit() } && trimmed.length in 3..10) {
                                importedCallsigns.add(trimmed)
                            }
                        }
                    }
                }
            }
            
            LogManager.i("CallsignLibraryEditDialog", "从文件读取到 ${importedCallsigns.size} 个呼号")
            
            if (importedCallsigns.isEmpty()) {
                callback(true, context.getString(R.string.callsign_library_import_empty), 0)
                return@withContext
            }
            
            // 加载现有呼号
            val existingCallsigns = loadCallsignsFromFile(context).toMutableList()
            val originalCount = existingCallsigns.size
            
            // 合并呼号（去重）
            var addedCount = 0
            importedCallsigns.forEach { callsign ->
                if (!existingCallsigns.contains(callsign)) {
                    existingCallsigns.add(callsign)
                    addedCount++
                }
            }
            
            LogManager.i("CallsignLibraryEditDialog", "新增 $addedCount 个呼号，原有 $originalCount 个")
            
            // 保存到文件
            saveCallsignsToFile(context, existingCallsigns)

            callback(true, context.getString(R.string.callsign_library_import_success, addedCount), addedCount)
        } catch (e: Exception) {
            LogManager.e("CallsignLibraryEditDialog", "导入呼号库失败", e)
            callback(false, context.getString(R.string.common_failed) + ": ${e.message}", 0)
        }
    }
}

/**
 * 从CSV文件导入呼号
 * 提取CALL列的呼号，去重并导入
 */
private suspend fun importCallsignsFromCsv(
    context: Context,
    fileInfo: TxtFileInfo,
    callback: (Boolean, String?, Int) -> Unit
) {
    withContext(Dispatchers.IO) {
        try {
            LogManager.i("CallsignLibraryEditDialog", "开始导入CSV文件: ${fileInfo.name}")
            
            val importedCallsigns = mutableSetOf<String>()
            var callColumnIndex = -1
            
            // 通过ContentResolver读取文件内容
            context.contentResolver.openInputStream(fileInfo.uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var lineNumber = 0
                    reader.lineSequence().forEach { line ->
                        lineNumber++
                        
                        // 解析CSV行（处理引号）
                        val columns = parseCsvLine(line)
                        
                        if (lineNumber == 1) {
                            // 第一行是表头，查找CALL列
                            callColumnIndex = columns.indexOfFirst { 
                                it.trim().uppercase() == "CALL" 
                            }
                            LogManager.i("CallsignLibraryEditDialog", "CALL列索引: $callColumnIndex")
                            if (callColumnIndex == -1) {
                                LogManager.e("CallsignLibraryEditDialog", "CSV文件中没有找到CALL列")
                                return@forEach
                            }
                        } else if (callColumnIndex >= 0 && callColumnIndex < columns.size) {
                            // 提取呼号
                            val callsign = columns[callColumnIndex].trim().uppercase()
                            if (callsign.isNotEmpty() && callsign != "CALL") {
                                // 验证呼号格式（只包含字母和数字）
                                if (callsign.all { it.isLetterOrDigit() } && callsign.length in 3..10) {
                                    importedCallsigns.add(callsign)
                                }
                            }
                        }
                    }
                }
            }
            
            LogManager.i("CallsignLibraryEditDialog", "从CSV读取到 ${importedCallsigns.size} 个唯一呼号")
            
            if (importedCallsigns.isEmpty()) {
                callback(true, context.getString(R.string.callsign_library_import_empty), 0)
                return@withContext
            }
            
            // 加载现有呼号
            val existingCallsigns = loadCallsignsFromFile(context).toMutableList()
            val originalCount = existingCallsigns.size
            
            // 合并呼号（去重）
            var addedCount = 0
            importedCallsigns.forEach { callsign ->
                if (!existingCallsigns.contains(callsign)) {
                    existingCallsigns.add(callsign)
                    addedCount++
                }
            }
            
            LogManager.i("CallsignLibraryEditDialog", "新增 $addedCount 个呼号，原有 $originalCount 个")
            
            // 保存到文件
            saveCallsignsToFile(context, existingCallsigns)

            callback(true, context.getString(R.string.callsign_library_import_csv_success, addedCount), addedCount)
        } catch (e: Exception) {
            LogManager.e("CallsignLibraryEditDialog", "导入CSV失败", e)
            callback(false, context.getString(R.string.callsign_library_import_csv_failed, e.message ?: ""), 0)
        }
    }
}

/**
 * 解析CSV行（处理引号包裹的字段）
 */
private fun parseCsvLine(line: String): List<String> {
    val result = mutableListOf<String>()
    val sb = StringBuilder()
    var inQuotes = false
    
    for (char in line) {
        when {
            char == '"' -> {
                inQuotes = !inQuotes
            }
            char == ',' && !inQuotes -> {
                result.add(sb.toString())
                sb.clear()
            }
            else -> {
                sb.append(char)
            }
        }
    }
    result.add(sb.toString())

    return result
}

/**
 * 从URI获取文件名
 */
private fun getFileNameFromUri(context: Context, uri: android.net.Uri): String? {
    var fileName: String? = null

    // 尝试从ContentResolver查询文件名
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val displayNameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (displayNameIndex >= 0) {
                fileName = cursor.getString(displayNameIndex)
            }
        }
    }

    // 如果查询失败，从URI路径中提取
    if (fileName == null) {
        fileName = uri.lastPathSegment
    }

    return fileName
}


