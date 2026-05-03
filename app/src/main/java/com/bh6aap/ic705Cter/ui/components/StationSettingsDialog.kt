package com.bh6aap.ic705Cter.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import android.widget.Toast
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bh6aap.ic705Cter.data.database.DatabaseHelper
import com.bh6aap.ic705Cter.data.database.entity.StationEntity
import com.bh6aap.ic705Cter.util.LogManager
import com.bh6aap.ic705Cter.util.MaidenheadConverter
import com.bh6aap.ic705Cter.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource

/**
 * 地面站设置对话框
 * 支持配置六位QTH定位符、经纬度、海拔等参数
 * 提供两种保存方案：覆盖默认 / 添加到库
 */
@Composable
fun StationSettingsDialog(
    onDismiss: () -> Unit,
    onStationSaved: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dbHelper = remember { DatabaseHelper.getInstance(context) }

    // 当前地面站数据
    var currentStation by remember { mutableStateOf<StationEntity?>(null) }

    // 输入字段
    var stationName by remember { mutableStateOf("") }
    var callsign by remember { mutableStateOf("") }
    var qthLocator by remember { mutableStateOf("") }
    var latitude by remember { mutableStateOf("") }
    var longitude by remember { mutableStateOf("") }
    var altitude by remember { mutableStateOf("") }

    // 错误提示
    var qthError by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 保存模式：true=覆盖默认, false=添加到库
    var saveAsDefault by remember { mutableStateOf(true) }

    // 加载当前地面站数据和呼号设置
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            // 加载地面站数据
            val station = dbHelper.getDefaultStation()
            // 加载呼号设置
            val savedCallsign = dbHelper.getCallsign()

            withContext(Dispatchers.Main) {
                currentStation = station
                station?.let {
                    stationName = it.name
                    latitude = String.format("%.6f", it.latitude)
                    longitude = String.format("%.6f", it.longitude)
                    altitude = String.format("%.1f", it.altitude)
                    // 计算QTH定位符
                    qthLocator = MaidenheadConverter.latLonToMaidenhead(it.latitude, it.longitude, 6)
                }
                // 加载呼号
                callsign = savedCallsign ?: ""
            }
        }
    }

    // QTH输入变化时自动计算经纬度
    LaunchedEffect(qthLocator) {
        if (qthLocator.length == 6 && MaidenheadConverter.isValidMaidenhead(qthLocator)) {
            val coords = MaidenheadConverter.maidenheadToLatLon(qthLocator)
            if (coords != null) {
                latitude = String.format("%.6f", coords[0])
                longitude = String.format("%.6f", coords[1])
                qthError = null
            }
        } else if (qthLocator.length >= 6) {
            qthError = context.getString(R.string.station_qth_error_invalid)
        } else {
            qthError = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.8f),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // 可滚动内容区
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                // 标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.station_settings_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // 输入表单
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 地面站名称
                    OutlinedTextField(
                        value = stationName,
                        onValueChange = { stationName = it },
                        label = { Text(stringResource(R.string.station_name)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    // 呼号
                    OutlinedTextField(
                        value = callsign,
                        onValueChange = {
                            callsign = it.uppercase()
                        },
                        label = { Text(stringResource(R.string.station_callsign)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text(stringResource(R.string.station_callsign_hint)) }
                    )

                    // QTH定位符（6位）
                    OutlinedTextField(
                        value = qthLocator,
                        onValueChange = {
                            qthLocator = it.uppercase().take(6)
                        },
                        label = { Text(stringResource(R.string.station_qth_locator)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = {
                            if (qthError != null) {
                                Text(
                                    text = qthError!!,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else if (qthLocator.length == 6) {
                                Text(stringResource(R.string.station_qth_valid))
                            } else {
                                Text(stringResource(R.string.station_qth_hint))
                            }
                        }
                    )

                    // 纬度
                    OutlinedTextField(
                        value = latitude,
                        onValueChange = { latitude = it },
                        label = { Text(stringResource(R.string.station_latitude)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        supportingText = { Text(stringResource(R.string.station_latitude_range)) }
                    )

                    // 经度
                    OutlinedTextField(
                        value = longitude,
                        onValueChange = { longitude = it },
                        label = { Text(stringResource(R.string.station_longitude)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        supportingText = { Text(stringResource(R.string.station_longitude_range)) }
                    )

                    // 海拔
                    OutlinedTextField(
                        value = altitude,
                        onValueChange = { altitude = it },
                        label = { Text(stringResource(R.string.station_altitude)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )

                    // 保存模式选择
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.station_save_mode),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                        )

                        // 覆盖默认选项
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { saveAsDefault = true },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = saveAsDefault,
                                onClick = { saveAsDefault = true }
                            )
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text(
                                    text = stringResource(R.string.station_save_as_default),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = stringResource(R.string.station_save_as_default_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // 添加到库选项
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { saveAsDefault = false },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = !saveAsDefault,
                                onClick = { saveAsDefault = false }
                            )
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text(
                                    text = stringResource(R.string.station_add_to_library),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = stringResource(R.string.station_add_to_library_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // 错误信息
                    if (errorMessage != null) {
                        Text(
                            text = errorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                }

                HorizontalDivider()

                // 按钮行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_cancel))
                    }

                    Button(
                        onClick = {
                            // 验证输入
                            when {
                                stationName.isBlank() -> errorMessage = context.getString(R.string.station_error_name_required)
                                qthLocator.length != 6 -> errorMessage = context.getString(R.string.station_error_qth_length)
                                !MaidenheadConverter.isValidMaidenhead(qthLocator) -> errorMessage = context.getString(R.string.station_error_qth_invalid)
                                latitude.isBlank() -> errorMessage = context.getString(R.string.station_error_latitude_required)
                                longitude.isBlank() -> errorMessage = context.getString(R.string.station_error_longitude_required)
                                else -> {
                                    try {
                                        val lat = latitude.toDouble()
                                        val lon = longitude.toDouble()
                                        val alt = altitude.toDoubleOrNull() ?: 0.0

                                        if (lat < -90.0 || lat > 90.0) {
                                            errorMessage = context.getString(R.string.station_error_latitude_range)
                                            return@Button
                                        }
                                        if (lon < -180.0 || lon > 180.0) {
                                            errorMessage = context.getString(R.string.station_error_longitude_range)
                                            return@Button
                                        }

                                        scope.launch(Dispatchers.IO) {
                                            // 检查名称是否被其他地面站使用
                                            val currentId = currentStation?.id
                                            if (dbHelper.isStationNameExists(stationName, currentId)) {
                                                withContext(Dispatchers.Main) {
                                                    errorMessage = context.getString(R.string.station_error_name_exists, stationName)
                                                }
                                                return@launch
                                            }

                                            val station = StationEntity(
                                                id = currentStation?.id ?: 0,
                                                name = stationName,
                                                latitude = lat,
                                                longitude = lon,
                                                altitude = alt,
                                                isDefault = saveAsDefault,
                                                notes = "QTH: $qthLocator",
                                                updatedAt = System.currentTimeMillis()
                                            )

                                            if (saveAsDefault) {
                                                // 设为默认：清除其他默认地面站，然后插入/更新
                                                if (currentStation != null && currentStation!!.id > 0) {
                                                    // 更新现有记录
                                                    dbHelper.updateStation(station)
                                                    LogManager.i("StationSettings", "更新默认地面站: ${station.name}")
                                                } else {
                                                    // 新建默认地面站
                                                    dbHelper.clearDefaultStation()
                                                    dbHelper.insertStation(station)
                                                    LogManager.i("StationSettings", "新建默认地面站: ${station.name}")
                                                }
                                            } else {
                                                // 添加到库：新建记录（非默认）
                                                val newStation = station.copy(id = 0, isDefault = false)
                                                dbHelper.insertStation(newStation)
                                                LogManager.i("StationSettings", "添加地面站到库: ${station.name}")
                                            }

                                            // 保存呼号设置
                                            if (callsign.isNotBlank()) {
                                                dbHelper.saveCallsign(callsign)
                                                LogManager.i("StationSettings", "保存呼号: $callsign")
                                            }

                                            withContext(Dispatchers.Main) {
                                                val message = if (saveAsDefault) {
                                                    context.getString(R.string.station_saved_default)
                                                } else {
                                                    context.getString(R.string.station_saved_library)
                                                }
                                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                                onStationSaved()
                                                onDismiss()
                                            }
                                        }
                                    } catch (e: NumberFormatException) {
                                        errorMessage = context.getString(R.string.station_error_invalid_number)
                                    }
                                }
                            }
                        }
                    ) {
                        Text(stringResource(R.string.common_save))
                    }
                }
            }
        }
    }
}
