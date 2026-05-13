package com.bh6aap.ic705Cter.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bh6aap.ic705Cter.util.LogManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 呼号记录数据类
 */
data class CallsignRecord(
    val callsign: String,
    val satelliteName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val utcTime: String = "",
    val frequency: String = "",
    val mode: String = "",
    val grid: String = ""
)

/**
 * DataStore 管理类
 * 用于存储呼号记录
 */
class CallsignDataStore(private val context: Context) {

    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "callsign_records")
        private val CALLSIGNS_KEY = stringPreferencesKey("callsigns")
        private val gson = Gson()
    }

    /**
     * 保存呼号记录
     */
    suspend fun saveCallsign(record: CallsignRecord) {
        try {
            context.dataStore.edit { preferences ->
                // 获取现有记录
                val jsonString = preferences[CALLSIGNS_KEY] ?: "[]"
                val type = object : TypeToken<List<CallsignRecord>>() {}.type
                val currentList = try {
                    gson.fromJson<List<CallsignRecord>>(jsonString, type) ?: emptyList()
                } catch (e: Exception) {
                    emptyList()
                }

                // 添加新记录到前面
            val newList = mutableListOf(record)
            newList.addAll(currentList)

            // 保存所有记录（无数量限制）
            preferences[CALLSIGNS_KEY] = gson.toJson(newList)
            }

            LogManager.i("CallsignDataStore", "保存呼号: ${record.callsign}, 卫星: ${record.satelliteName}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.e("CallsignDataStore", "保存呼号失败", e)
        }
    }

    /**
     * 获取所有呼号记录（Flow）
     */
    fun getCallsigns(): Flow<List<CallsignRecord>> = context.dataStore.data
        .map { preferences ->
            try {
                val jsonString = preferences[CALLSIGNS_KEY] ?: "[]"
                val type = object : TypeToken<List<CallsignRecord>>() {}.type
                gson.fromJson<List<CallsignRecord>>(jsonString, type) ?: emptyList()
            } catch (e: Exception) {
                LogManager.e("CallsignDataStore", "解析呼号记录失败", e)
                emptyList()
            }
        }

    /**
     * 删除指定时间戳的呼号记录
     */
    suspend fun deleteCallsign(timestamp: Long) {
        try {
            context.dataStore.edit { preferences ->
                val jsonString = preferences[CALLSIGNS_KEY] ?: "[]"
                val type = object : TypeToken<List<CallsignRecord>>() {}.type
                val currentList = try {
                    gson.fromJson<List<CallsignRecord>>(jsonString, type) ?: emptyList()
                } catch (e: Exception) {
                    emptyList()
                }

                val updatedList = currentList.filter { it.timestamp != timestamp }
                preferences[CALLSIGNS_KEY] = gson.toJson(updatedList)
            }

            LogManager.i("CallsignDataStore", "删除呼号记录: $timestamp")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.e("CallsignDataStore", "删除呼号记录失败", e)
        }
    }

    /**
     * 清空所有呼号记录
     */
    suspend fun clearAll() {
        try {
            context.dataStore.edit { it.clear() }
            LogManager.i("CallsignDataStore", "清空所有呼号记录")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.e("CallsignDataStore", "清空呼号记录失败", e)
        }
    }
}
