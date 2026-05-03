package com.bh6aap.ic705Cter.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bh6aap.ic705Cter.data.database.DatabaseHelper
import com.bh6aap.ic705Cter.data.database.entity.StationEntity
import com.bh6aap.ic705Cter.util.LogManager
import com.bh6aap.ic705Cter.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource

/**
 * Ground station list dialog
 * Displays saved ground station list, supports quick switching and deletion
 */
@Composable
fun StationListDialog(
    onDismiss: () -> Unit,
    onStationSelected: (StationEntity) -> Unit,
    currentStationId: Long? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dbHelper = remember { DatabaseHelper.getInstance(context) }

    // Ground station list
    var stations by remember { mutableStateOf<List<StationEntity>>(emptyList()) }
    // Loading state
    var isLoading by remember { mutableStateOf(true) }
    // Delete confirmation dialog
    var stationToDelete by remember { mutableStateOf<StationEntity?>(null) }

    // Load ground station list
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                dbHelper.getAllStations().collect { stationList ->
                    withContext(Dispatchers.Main) {
                        stations = stationList
                        isLoading = false
                    }
                }
            } catch (e: Exception) {
                LogManager.e("StationListDialog", "加载地面站列表失败", e)
                withContext(Dispatchers.Main) {
                    isLoading = false
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .wrapContentHeight(),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Title bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.station_list_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )

                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_close))
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Ground station list
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (stations.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.station_list_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(stations, key = { it.id }) { station ->
                            StationListItem(
                                station = station,
                                isSelected = station.id == currentStationId,
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        // Set as default ground station
                                        dbHelper.setStationAsDefault(station.id)
                                        LogManager.i("StationListDialog", "切换到地面站: ${station.name} (ID: ${station.id})")
                                        withContext(Dispatchers.Main) {
                                            onStationSelected(station)
                                            onDismiss()
                                        }
                                    }
                                },
                                onDeleteClick = {
                                    stationToDelete = station
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    if (stationToDelete != null) {
        AlertDialog(
            onDismissRequest = { stationToDelete = null },
            title = { Text(stringResource(R.string.station_list_delete_confirm_title)) },
            text = { Text(stringResource(R.string.station_list_delete_confirm_message, stationToDelete!!.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val station = stationToDelete!!
                        stationToDelete = null
                        scope.launch(Dispatchers.IO) {
                            try {
                                dbHelper.deleteStation(station)
                                LogManager.i("StationListDialog", "删除地面站: ${station.name} (ID: ${station.id})")
                                // If deleted station was default, list will auto-update via Flow
                                withContext(Dispatchers.Main) {
                                    // List auto-updates because Flow is used
                                }
                            } catch (e: Exception) {
                                LogManager.e("StationListDialog", "删除地面站失败", e)
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { stationToDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/**
 * Ground station list item
 */
@Composable
private fun StationListItem(
    station: StationEntity,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: icon and name info
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Location icon or selected marker
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.station_list_selected),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = stringResource(R.string.station_list_station),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Ground station info
                Column {
                    Text(
                        text = station.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    Text(
                        text = String.format("%.6f°, %.6f°", station.latitude, station.longitude),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (station.notes?.isNotBlank() == true) {
                        Text(
                            text = station.notes!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            },
                            maxLines = 1
                        )
                    }
                }
            }

            // Right: delete button
            IconButton(onClick = onDeleteClick) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
