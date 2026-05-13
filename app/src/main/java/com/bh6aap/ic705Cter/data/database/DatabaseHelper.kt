package com.bh6aap.ic705Cter.data.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.bh6aap.ic705Cter.data.database.entity.CwPresetEntity
import com.bh6aap.ic705Cter.data.database.entity.CustomTransmitterEntity
import com.bh6aap.ic705Cter.data.database.entity.FrequencyPresetEntity
import com.bh6aap.ic705Cter.data.database.entity.MemoEntity
import com.bh6aap.ic705Cter.data.database.entity.PassCacheEntity
import com.bh6aap.ic705Cter.data.database.entity.PassPredictionEntity
import com.bh6aap.ic705Cter.data.database.entity.SatelliteEntity
import com.bh6aap.ic705Cter.data.database.entity.StationEntity
import com.bh6aap.ic705Cter.data.database.entity.SyncRecordEntity
import com.bh6aap.ic705Cter.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * SQLite数据库帮助类
 * 替代Room实现本地数据存储
 * 使用单例模式避免连接泄漏
 */
class DatabaseHelper private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    companion object {
        const val DATABASE_NAME = "ic705_database.db"
        const val DATABASE_VERSION = 11

        @Volatile
        private var instance: DatabaseHelper? = null

        /**
         * 获取DatabaseHelper单例实例
         */
        fun getInstance(context: Context): DatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: DatabaseHelper(context.applicationContext).also {
                    instance = it
                    LogManager.i(LogManager.TAG_DATABASE, "【数据库】创建DatabaseHelper单例实例")
                }
            }
        }

        /**
         * 关闭数据库连接（应用退出时调用）
         */
        fun closeInstance() {
            synchronized(this) {
                instance?.close()
                instance = null
                LogManager.i(LogManager.TAG_DATABASE, "【数据库】关闭DatabaseHelper实例")
            }
        }

        // 表名
        const val TABLE_SATELLITES = "satellites"
        const val TABLE_STATIONS = "stations"
        const val TABLE_PASS_PREDICTIONS = "pass_predictions"
        const val TABLE_SYNC_RECORDS = "sync_records"
        const val TABLE_TRANSMITTERS = "transmitters"
        const val TABLE_SATELLITE_INFO = "satellite_info"
        const val TABLE_FREQUENCY_PRESETS = "frequency_presets"
        const val TABLE_CW_PRESETS = "cw_presets"
        const val TABLE_CUSTOM_TRANSMITTERS = "custom_transmitters"
        const val TABLE_SETTINGS = "settings"
        const val TABLE_MEMOS = "memos"
        const val TABLE_PASS_CACHE = "pass_cache"
    }

    override fun onCreate(db: SQLiteDatabase) {
        LogManager.i(LogManager.TAG_DATABASE, "【数据库】创建表结构")

        // 创建卫星表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_SATELLITES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                noradId TEXT NOT NULL UNIQUE,
                internationalDesignator TEXT,
                tleLine1 TEXT NOT NULL,
                tleLine2 TEXT NOT NULL,
                category TEXT,
                isFavorite INTEGER DEFAULT 0,
                notes TEXT,
                createdAt INTEGER DEFAULT ${System.currentTimeMillis()},
                updatedAt INTEGER DEFAULT ${System.currentTimeMillis()}
            )
        """.trimIndent())

        // 创建地面站表（移除name字段的UNIQUE约束，允许同名地面站）
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_STATIONS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                altitude REAL DEFAULT 0.0,
                isDefault INTEGER DEFAULT 0,
                minElevation REAL DEFAULT 5.0,
                notes TEXT,
                createdAt INTEGER DEFAULT ${System.currentTimeMillis()},
                updatedAt INTEGER DEFAULT ${System.currentTimeMillis()}
            )
        """.trimIndent())

        // 创建过境预测表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_PASS_PREDICTIONS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                satelliteId INTEGER NOT NULL,
                stationId INTEGER NOT NULL,
                aosTime INTEGER NOT NULL,
                aosAzimuth REAL NOT NULL,
                aosElevation REAL NOT NULL,
                losTime INTEGER NOT NULL,
                losAzimuth REAL NOT NULL,
                losElevation REAL NOT NULL,
                maxElevationTime INTEGER NOT NULL,
                maxElevation REAL NOT NULL,
                duration INTEGER NOT NULL,
                isNotified INTEGER DEFAULT 0,
                isTracked INTEGER DEFAULT 0,
                createdAt INTEGER DEFAULT ${System.currentTimeMillis()}
            )
        """.trimIndent())

        // 创建同步记录表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_SYNC_RECORDS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                syncType TEXT NOT NULL UNIQUE,
                syncTime INTEGER NOT NULL,
                recordCount INTEGER DEFAULT 0,
                source TEXT,
                isSuccess INTEGER DEFAULT 1,
                errorMessage TEXT
            )
        """.trimIndent())

        // 创建转发器表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_TRANSMITTERS (
                uuid TEXT PRIMARY KEY,
                description TEXT NOT NULL,
                alive INTEGER DEFAULT 0,
                type TEXT NOT NULL,
                uplink_low INTEGER,
                uplink_high INTEGER,
                uplink_drift REAL,
                downlink_low INTEGER,
                downlink_high INTEGER,
                downlink_drift REAL,
                mode TEXT NOT NULL,
                mode_id INTEGER NOT NULL,
                uplink_mode TEXT,
                invert INTEGER DEFAULT 0,
                baud REAL,
                sat_id TEXT NOT NULL,
                norad_cat_id INTEGER NOT NULL,
                norad_follow_id INTEGER,
                status TEXT NOT NULL,
                updated TEXT NOT NULL,
                citation TEXT,
                service TEXT,
                iaru_coordination TEXT,
                iaru_coordination_url TEXT,
                frequency_violation INTEGER DEFAULT 0,
                unconfirmed INTEGER DEFAULT 0,
                createdAt INTEGER DEFAULT ${System.currentTimeMillis()}
            )
        """.trimIndent())

        // 创建索引
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_satellites_name ON $TABLE_SATELLITES(name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_satellites_favorite ON $TABLE_SATELLITES(isFavorite)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_stations_default ON $TABLE_STATIONS(isDefault)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pass_predictions_satellite ON $TABLE_PASS_PREDICTIONS(satelliteId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pass_predictions_station ON $TABLE_PASS_PREDICTIONS(stationId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pass_predictions_aos ON $TABLE_PASS_PREDICTIONS(aosTime)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_transmitters_norad ON $TABLE_TRANSMITTERS(norad_cat_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_transmitters_satid ON $TABLE_TRANSMITTERS(sat_id)")

        // 创建卫星信息表（包含别名）
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_SATELLITE_INFO (
                sat_id TEXT PRIMARY KEY,
                norad_cat_id INTEGER NOT NULL UNIQUE,
                name TEXT NOT NULL,
                names TEXT,
                image TEXT,
                status TEXT,
                decayed TEXT,
                launched TEXT,
                deployed TEXT,
                website TEXT,
                operator TEXT,
                countries TEXT,
                updated TEXT NOT NULL,
                citation TEXT,
                is_frequency_violator INTEGER DEFAULT 0,
                createdAt INTEGER DEFAULT ${System.currentTimeMillis()}
            )
        """.trimIndent())

        // 创建卫星信息表索引
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_satellite_info_norad ON $TABLE_SATELLITE_INFO(norad_cat_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_satellite_info_name ON $TABLE_SATELLITE_INFO(name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_satellite_info_names ON $TABLE_SATELLITE_INFO(names)")

        // 创建频率预设表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_FREQUENCY_PRESETS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                noradId TEXT NOT NULL,
                name TEXT NOT NULL,
                uplinkFreqHz INTEGER NOT NULL,
                downlinkFreqHz INTEGER NOT NULL,
                sortOrder INTEGER DEFAULT 0,
                createdAt INTEGER DEFAULT ${System.currentTimeMillis()}
            )
        """.trimIndent())

        // 创建频率预设表索引
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_frequency_presets_norad ON $TABLE_FREQUENCY_PRESETS(noradId)")

        // 创建CW预设表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_CW_PRESETS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                presetIndex INTEGER NOT NULL UNIQUE,
                name TEXT NOT NULL,
                message TEXT NOT NULL,
                createdAt INTEGER DEFAULT ${System.currentTimeMillis()}
            )
        """.trimIndent())

        // 创建CW预设表索引
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cw_presets_index ON $TABLE_CW_PRESETS(presetIndex)")

        // 创建用户自定义转发器表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_CUSTOM_TRANSMITTERS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL UNIQUE,
                norad_cat_id INTEGER NOT NULL,
                description TEXT,
                uplink_low INTEGER,
                uplink_high INTEGER,
                downlink_low INTEGER,
                downlink_high INTEGER,
                downlink_mode TEXT,
                uplink_mode TEXT,
                is_enabled INTEGER DEFAULT 1,
                created_at INTEGER DEFAULT ${System.currentTimeMillis()},
                updated_at INTEGER DEFAULT ${System.currentTimeMillis()}
            )
        """.trimIndent())

        // 创建用户自定义转发器表索引
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_custom_transmitters_uuid ON $TABLE_CUSTOM_TRANSMITTERS(uuid)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_custom_transmitters_norad ON $TABLE_CUSTOM_TRANSMITTERS(norad_cat_id)")

        // 创建设置表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_SETTINGS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                key TEXT NOT NULL UNIQUE,
                value TEXT NOT NULL,
                description TEXT,
                updatedAt INTEGER DEFAULT ${System.currentTimeMillis()}
            )
        """.trimIndent())

        // 创建设置表索引
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_settings_key ON $TABLE_SETTINGS(key)")

        // 创建备忘录表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_MEMOS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                content TEXT NOT NULL,
                satelliteName TEXT,
                satelliteId TEXT,
                localTime INTEGER DEFAULT ${System.currentTimeMillis()},
                utcTime INTEGER DEFAULT ${System.currentTimeMillis()},
                createdAt INTEGER DEFAULT ${System.currentTimeMillis()}
            )
        """.trimIndent())

        // 创建备忘录表索引
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memos_created ON $TABLE_MEMOS(createdAt DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memos_satellite ON $TABLE_MEMOS(satelliteId)")

        // 创建过境缓存表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_PASS_CACHE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                noradId TEXT NOT NULL,
                name TEXT NOT NULL,
                aosTime INTEGER NOT NULL,
                losTime INTEGER NOT NULL,
                maxElevation REAL NOT NULL,
                aosAzimuth REAL NOT NULL,
                losAzimuth REAL NOT NULL,
                duration INTEGER NOT NULL,
                maxElevationTime INTEGER NOT NULL,
                stationLat REAL NOT NULL,
                stationLon REAL NOT NULL,
                stationAlt REAL NOT NULL,
                minElevation REAL NOT NULL,
                calculatedAt INTEGER NOT NULL,
                hoursAhead INTEGER NOT NULL
            )
        """.trimIndent())

        // 创建过境缓存表索引
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pass_cache_norad ON $TABLE_PASS_CACHE(noradId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pass_cache_aos ON $TABLE_PASS_CACHE(aosTime)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pass_cache_calculated ON $TABLE_PASS_CACHE(calculatedAt)")

        LogManager.i(LogManager.TAG_DATABASE, "【数据库】表结构创建完成")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        LogManager.w(LogManager.TAG_DATABASE, "【数据库】升级数据库版本 $oldVersion -> $newVersion (增量迁移)")
        // 只 DROP 可通过网络重新拉取的"远端缓存类"表；用户私有数据
        // (settings/stations/cw_presets/frequency_presets/custom_transmitters/memos)
        // 一律保留——onCreate 中所有 CREATE TABLE 均使用 IF NOT EXISTS，
        // 因此无需删除即可被重新声明。
        val rebuildable = listOf(
            TABLE_SATELLITES,
            TABLE_PASS_PREDICTIONS,
            TABLE_SYNC_RECORDS,
            TABLE_TRANSMITTERS,
            TABLE_SATELLITE_INFO,
            TABLE_PASS_CACHE
        )
        for (table in rebuildable) {
            db.execSQL("DROP TABLE IF EXISTS $table")
        }
        onCreate(db)
        LogManager.i(LogManager.TAG_DATABASE, "【数据库】升级完成，用户数据保留")
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        LogManager.w(LogManager.TAG_DATABASE, "【数据库】检测到降级 $oldVersion -> $newVersion，按升级流程容错处理")
        // 默认 SQLiteOpenHelper 在降级时会抛 SQLiteException，导致应用启动失败。
        // 这里采用最保守策略：不破坏用户数据，仅尝试再次保证 schema 完整。
        onCreate(db)
    }

    // ==================== 卫星表操作 ====================

    fun getAllSatellites(): Flow<List<SatelliteEntity>> = flow {
        val satellites = mutableListOf<SatelliteEntity>()
        readableDatabase.query(
            TABLE_SATELLITES,
            null, null, null, null, null,
            "name ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                satellites.add(cursor.toSatelliteEntity())
            }
        }
        emit(satellites)
    }

    fun getFavoriteSatellites(): Flow<List<SatelliteEntity>> = flow {
        val satellites = mutableListOf<SatelliteEntity>()
        readableDatabase.query(
            TABLE_SATELLITES,
            null, "isFavorite = ?", arrayOf("1"), null, null,
            "name ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                satellites.add(cursor.toSatelliteEntity())
            }
        }
        emit(satellites)
    }

    fun getSatellitesByCategory(category: String): Flow<List<SatelliteEntity>> = flow {
        val satellites = mutableListOf<SatelliteEntity>()
        readableDatabase.query(
            TABLE_SATELLITES,
            null, "category = ?", arrayOf(category), null, null,
            "name ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                satellites.add(cursor.toSatelliteEntity())
            }
        }
        emit(satellites)
    }

    suspend fun getSatelliteById(id: Long): SatelliteEntity? = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_SATELLITES,
            null, "id = ?", arrayOf(id.toString()), null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toSatelliteEntity() else null
        }
    }

    suspend fun getSatelliteByNoradId(noradId: String): SatelliteEntity? = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_SATELLITES,
            null, "noradId = ?", arrayOf(noradId), null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toSatelliteEntity() else null
        }
    }

    suspend fun insertSatellite(satellite: SatelliteEntity): Long = withContext(Dispatchers.IO) {
        writableDatabase.insertWithOnConflict(
            TABLE_SATELLITES,
            null,
            satellite.toContentValues(),
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    suspend fun insertSatellites(satellites: List<SatelliteEntity>) = withContext(Dispatchers.IO) {
        writableDatabase.beginTransaction()
        try {
            satellites.forEach { satellite ->
                writableDatabase.insertWithOnConflict(
                    TABLE_SATELLITES,
                    null,
                    satellite.toContentValues(),
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    suspend fun updateSatellite(satellite: SatelliteEntity) = withContext(Dispatchers.IO) {
        writableDatabase.update(
            TABLE_SATELLITES,
            satellite.toContentValues(),
            "id = ?",
            arrayOf(satellite.id.toString())
        )
    }

    suspend fun updateFavoriteStatus(id: Long, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("isFavorite", if (isFavorite) 1 else 0)
            put("updatedAt", System.currentTimeMillis())
        }
        writableDatabase.update(TABLE_SATELLITES, values, "id = ?", arrayOf(id.toString()))
    }

    suspend fun deleteSatellite(satellite: SatelliteEntity) = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_SATELLITES, "id = ?", arrayOf(satellite.id.toString()))
    }

    suspend fun deleteSatelliteById(id: Long) = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_SATELLITES, "id = ?", arrayOf(id.toString()))
    }

    suspend fun deleteAllSatellites() = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_SATELLITES, null, null)
    }

    suspend fun getSatelliteCount(): Int = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_SATELLITES", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun searchSatellites(query: String): Flow<List<SatelliteEntity>> = flow {
        val satellites = mutableListOf<SatelliteEntity>()
        val searchQuery = "%$query%"
        readableDatabase.query(
            TABLE_SATELLITES,
            null,
            "name LIKE ? OR noradId LIKE ?",
            arrayOf(searchQuery, searchQuery),
            null, null, "name ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                satellites.add(cursor.toSatelliteEntity())
            }
        }
        emit(satellites)
    }

    // ==================== 地面站表操作 ====================

    fun getAllStations(): Flow<List<StationEntity>> = flow {
        val stations = mutableListOf<StationEntity>()
        readableDatabase.query(
            TABLE_STATIONS,
            null, null, null, null, null,
            "name ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                stations.add(cursor.toStationEntity())
            }
        }
        emit(stations)
    }

    suspend fun getDefaultStation(): StationEntity? = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_STATIONS,
            null, "isDefault = ?", arrayOf("1"), null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toStationEntity() else null
        }
    }

    suspend fun getStationById(id: Long): StationEntity? = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_STATIONS,
            null, "id = ?", arrayOf(id.toString()), null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toStationEntity() else null
        }
    }

    suspend fun insertStation(station: StationEntity): Long = withContext(Dispatchers.IO) {
        writableDatabase.insert(
            TABLE_STATIONS,
            null,
            station.toContentValues()
        )
    }

    suspend fun insertStations(stations: List<StationEntity>) = withContext(Dispatchers.IO) {
        writableDatabase.beginTransaction()
        try {
            stations.forEach { station ->
                writableDatabase.insert(
                    TABLE_STATIONS,
                    null,
                    station.toContentValues()
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    suspend fun updateStation(station: StationEntity) = withContext(Dispatchers.IO) {
        writableDatabase.update(
            TABLE_STATIONS,
            station.toContentValues(),
            "id = ?",
            arrayOf(station.id.toString())
        )
    }

    suspend fun clearDefaultStation() = withContext(Dispatchers.IO) {
        val values = ContentValues().apply { put("isDefault", 0) }
        writableDatabase.update(TABLE_STATIONS, values, null, null)
    }

    suspend fun setDefaultStation(id: Long) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("isDefault", 1)
            put("updatedAt", System.currentTimeMillis())
        }
        writableDatabase.update(TABLE_STATIONS, values, "id = ?", arrayOf(id.toString()))
    }

    suspend fun setStationAsDefault(id: Long) = withContext(Dispatchers.IO) {
        writableDatabase.beginTransaction()
        try {
            clearDefaultStation()
            setDefaultStation(id)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    suspend fun deleteStation(station: StationEntity) = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_STATIONS, "id = ?", arrayOf(station.id.toString()))
    }

    suspend fun deleteStationById(id: Long) = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_STATIONS, "id = ?", arrayOf(id.toString()))
    }

    suspend fun deleteAllStations() = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_STATIONS, null, null)
    }

    suspend fun getStationCount(): Int = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_STATIONS", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    /**
     * 检查地面站名称是否已被其他记录使用（排除指定ID的记录）
     * @param name 要检查的名称
     * @param excludeId 要排除的记录ID（用于更新时排除自身）
     * @return 如果名称已被使用返回true，否则返回false
     */
    suspend fun isStationNameExists(name: String, excludeId: Long? = null): Boolean = withContext(Dispatchers.IO) {
        val selection = if (excludeId != null) {
            "name = ? AND id != ?"
        } else {
            "name = ?"
        }
        val selectionArgs = if (excludeId != null) {
            arrayOf(name, excludeId.toString())
        } else {
            arrayOf(name)
        }
        readableDatabase.query(
            TABLE_STATIONS,
            arrayOf("id"),
            selection,
            selectionArgs,
            null, null, null
        ).use { cursor ->
            cursor.count > 0
        }
    }
    
    suspend fun hasStationData(): Boolean = withContext(Dispatchers.IO) {
        getStationCount() > 0
    }

    // ==================== 同步记录表操作 ====================

    fun getAllSyncRecords(): Flow<List<SyncRecordEntity>> = flow {
        val records = mutableListOf<SyncRecordEntity>()
        readableDatabase.query(
            TABLE_SYNC_RECORDS,
            null, null, null, null, null,
            "syncTime DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                records.add(cursor.toSyncRecordEntity())
            }
        }
        emit(records)
    }

    suspend fun getSyncRecordByType(syncType: String): SyncRecordEntity? = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_SYNC_RECORDS,
            null, "syncType = ?", arrayOf(syncType), null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toSyncRecordEntity() else null
        }
    }

    suspend fun getLastSyncRecord(syncType: String): SyncRecordEntity? = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_SYNC_RECORDS,
            null, "syncType = ?", arrayOf(syncType), null, null,
            "syncTime DESC", "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toSyncRecordEntity() else null
        }
    }

    suspend fun insertSyncRecord(record: SyncRecordEntity): Long = withContext(Dispatchers.IO) {
        writableDatabase.insertWithOnConflict(
            TABLE_SYNC_RECORDS,
            null,
            record.toContentValues(),
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    suspend fun updateSyncRecord(record: SyncRecordEntity) = withContext(Dispatchers.IO) {
        writableDatabase.update(
            TABLE_SYNC_RECORDS,
            record.toContentValues(),
            "id = ?",
            arrayOf(record.id.toString())
        )
    }

    suspend fun deleteSyncRecord(record: SyncRecordEntity) = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_SYNC_RECORDS, "id = ?", arrayOf(record.id.toString()))
    }

    suspend fun deleteSyncRecordByType(syncType: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_SYNC_RECORDS, "syncType = ?", arrayOf(syncType))
    }

    suspend fun deleteAllSyncRecords() = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_SYNC_RECORDS, null, null)
    }

    suspend fun getSyncRecordCount(): Int = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_SYNC_RECORDS", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    // ==================== 过境预测表操作 ====================

    fun getAllPredictions(): Flow<List<PassPredictionEntity>> = flow {
        val predictions = mutableListOf<PassPredictionEntity>()
        readableDatabase.query(
            TABLE_PASS_PREDICTIONS,
            null, null, null, null, null,
            "aosTime ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                predictions.add(cursor.toPassPredictionEntity())
            }
        }
        emit(predictions)
    }

    fun getPredictionsBySatellite(satelliteId: Long): Flow<List<PassPredictionEntity>> = flow {
        val predictions = mutableListOf<PassPredictionEntity>()
        readableDatabase.query(
            TABLE_PASS_PREDICTIONS,
            null, "satelliteId = ?", arrayOf(satelliteId.toString()),
            null, null, "aosTime ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                predictions.add(cursor.toPassPredictionEntity())
            }
        }
        emit(predictions)
    }

    fun getPredictionsByStation(stationId: Long): Flow<List<PassPredictionEntity>> = flow {
        val predictions = mutableListOf<PassPredictionEntity>()
        readableDatabase.query(
            TABLE_PASS_PREDICTIONS,
            null, "stationId = ?", arrayOf(stationId.toString()),
            null, null, "aosTime ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                predictions.add(cursor.toPassPredictionEntity())
            }
        }
        emit(predictions)
    }

    fun getPredictionsBySatelliteAndStation(satelliteId: Long, stationId: Long): Flow<List<PassPredictionEntity>> = flow {
        val predictions = mutableListOf<PassPredictionEntity>()
        readableDatabase.query(
            TABLE_PASS_PREDICTIONS,
            null,
            "satelliteId = ? AND stationId = ?",
            arrayOf(satelliteId.toString(), stationId.toString()),
            null, null, "aosTime ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                predictions.add(cursor.toPassPredictionEntity())
            }
        }
        emit(predictions)
    }

    fun getPredictionsInTimeRange(startTime: Long, endTime: Long): Flow<List<PassPredictionEntity>> = flow {
        val predictions = mutableListOf<PassPredictionEntity>()
        readableDatabase.query(
            TABLE_PASS_PREDICTIONS,
            null,
            "aosTime > ? AND aosTime < ?",
            arrayOf(startTime.toString(), endTime.toString()),
            null, null, "aosTime ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                predictions.add(cursor.toPassPredictionEntity())
            }
        }
        emit(predictions)
    }

    suspend fun getNextPass(currentTime: Long): PassPredictionEntity? = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_PASS_PREDICTIONS,
            null,
            "aosTime > ?",
            arrayOf(currentTime.toString()),
            null, null,
            "aosTime ASC",
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toPassPredictionEntity() else null
        }
    }

    fun getUpcomingPassesNotNotified(currentTime: Long): Flow<List<PassPredictionEntity>> = flow {
        val predictions = mutableListOf<PassPredictionEntity>()
        readableDatabase.query(
            TABLE_PASS_PREDICTIONS,
            null,
            "aosTime > ? AND isNotified = 0",
            arrayOf(currentTime.toString()),
            null, null, "aosTime ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                predictions.add(cursor.toPassPredictionEntity())
            }
        }
        emit(predictions)
    }

    suspend fun insertPrediction(prediction: PassPredictionEntity): Long = withContext(Dispatchers.IO) {
        writableDatabase.insertWithOnConflict(
            TABLE_PASS_PREDICTIONS,
            null,
            prediction.toContentValues(),
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    suspend fun insertPredictions(predictions: List<PassPredictionEntity>) = withContext(Dispatchers.IO) {
        writableDatabase.beginTransaction()
        try {
            predictions.forEach { prediction ->
                writableDatabase.insertWithOnConflict(
                    TABLE_PASS_PREDICTIONS,
                    null,
                    prediction.toContentValues(),
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    suspend fun updatePrediction(prediction: PassPredictionEntity) = withContext(Dispatchers.IO) {
        writableDatabase.update(
            TABLE_PASS_PREDICTIONS,
            prediction.toContentValues(),
            "id = ?",
            arrayOf(prediction.id.toString())
        )
    }

    suspend fun markAsNotified(id: Long) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply { put("isNotified", 1) }
        writableDatabase.update(TABLE_PASS_PREDICTIONS, values, "id = ?", arrayOf(id.toString()))
    }

    suspend fun markAsTracked(id: Long) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply { put("isTracked", 1) }
        writableDatabase.update(TABLE_PASS_PREDICTIONS, values, "id = ?", arrayOf(id.toString()))
    }

    suspend fun deletePrediction(prediction: PassPredictionEntity) = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_PASS_PREDICTIONS, "id = ?", arrayOf(prediction.id.toString()))
    }

    suspend fun deletePredictionById(id: Long) = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_PASS_PREDICTIONS, "id = ?", arrayOf(id.toString()))
    }

    suspend fun deleteOldPredictions(currentTime: Long) = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_PASS_PREDICTIONS, "losTime < ?", arrayOf(currentTime.toString()))
    }

    suspend fun deletePredictionsBySatellite(satelliteId: Long) = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_PASS_PREDICTIONS, "satelliteId = ?", arrayOf(satelliteId.toString()))
    }

    suspend fun deleteAllPredictions() = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_PASS_PREDICTIONS, null, null)
    }

    suspend fun getPredictionCount(): Int = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_PASS_PREDICTIONS", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    // ==================== 转发器表操作 ====================

    suspend fun insertTransmitter(transmitter: com.bh6aap.ic705Cter.data.api.Transmitter): Long = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("uuid", transmitter.uuid)
            put("description", transmitter.description)
            put("alive", if (transmitter.alive) 1 else 0)
            put("type", transmitter.type)
            put("uplink_low", transmitter.uplinkLow)
            put("uplink_high", transmitter.uplinkHigh)
            put("uplink_drift", transmitter.uplinkDrift)
            put("downlink_low", transmitter.downlinkLow)
            put("downlink_high", transmitter.downlinkHigh)
            put("downlink_drift", transmitter.downlinkDrift)
            put("mode", transmitter.mode)
            put("mode_id", transmitter.modeId)
            put("uplink_mode", transmitter.uplinkMode)
            put("invert", if (transmitter.invert) 1 else 0)
            put("baud", transmitter.baud)
            put("sat_id", transmitter.satId)
            put("norad_cat_id", transmitter.noradCatId)
            put("norad_follow_id", transmitter.noradFollowId)
            put("status", transmitter.status)
            put("updated", transmitter.updated)
            put("citation", transmitter.citation)
            put("service", transmitter.service)
            put("iaru_coordination", transmitter.iaruCoordination)
            put("iaru_coordination_url", transmitter.iaruCoordinationUrl)
            put("frequency_violation", if (transmitter.frequencyViolation) 1 else 0)
            put("unconfirmed", if (transmitter.unconfirmed) 1 else 0)
        }
        writableDatabase.insertWithOnConflict(
            TABLE_TRANSMITTERS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    suspend fun insertTransmitters(transmitters: List<com.bh6aap.ic705Cter.data.api.Transmitter>) = withContext(Dispatchers.IO) {
        writableDatabase.beginTransaction()
        try {
            transmitters.forEach { transmitter ->
                val values = ContentValues().apply {
                    put("uuid", transmitter.uuid)
                    put("description", transmitter.description)
                    put("alive", if (transmitter.alive) 1 else 0)
                    put("type", transmitter.type)
                    put("uplink_low", transmitter.uplinkLow)
                    put("uplink_high", transmitter.uplinkHigh)
                    put("uplink_drift", transmitter.uplinkDrift)
                    put("downlink_low", transmitter.downlinkLow)
                    put("downlink_high", transmitter.downlinkHigh)
                    put("downlink_drift", transmitter.downlinkDrift)
                    put("mode", transmitter.mode)
                    put("mode_id", transmitter.modeId)
                    put("uplink_mode", transmitter.uplinkMode)
                    put("invert", if (transmitter.invert) 1 else 0)
                    put("baud", transmitter.baud)
                    put("sat_id", transmitter.satId)
                    put("norad_cat_id", transmitter.noradCatId)
                    put("norad_follow_id", transmitter.noradFollowId)
                    put("status", transmitter.status)
                    put("updated", transmitter.updated)
                    put("citation", transmitter.citation)
                    put("service", transmitter.service)
                    put("iaru_coordination", transmitter.iaruCoordination)
                    put("iaru_coordination_url", transmitter.iaruCoordinationUrl)
                    put("frequency_violation", if (transmitter.frequencyViolation) 1 else 0)
                    put("unconfirmed", if (transmitter.unconfirmed) 1 else 0)
                }
                writableDatabase.insertWithOnConflict(
                    TABLE_TRANSMITTERS,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    suspend fun getTransmitterByUuid(uuid: String): com.bh6aap.ic705Cter.data.api.Transmitter? = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_TRANSMITTERS,
            null, "uuid = ?", arrayOf(uuid), null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                com.bh6aap.ic705Cter.data.api.Transmitter(
                        uuid = cursor.getString(cursor.getColumnIndexOrThrow("uuid")),
                        description = cursor.getString(cursor.getColumnIndexOrThrow("description")),
                        alive = cursor.getInt(cursor.getColumnIndexOrThrow("alive")) == 1,
                        type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                        uplinkLow = if (cursor.isNull(cursor.getColumnIndex("uplink_low"))) null else cursor.getLong(cursor.getColumnIndex("uplink_low")),
                        uplinkHigh = if (cursor.isNull(cursor.getColumnIndex("uplink_high"))) null else cursor.getLong(cursor.getColumnIndex("uplink_high")),
                        uplinkDrift = if (cursor.isNull(cursor.getColumnIndex("uplink_drift"))) null else cursor.getDouble(cursor.getColumnIndex("uplink_drift")),
                        downlinkLow = if (cursor.isNull(cursor.getColumnIndex("downlink_low"))) null else cursor.getLong(cursor.getColumnIndex("downlink_low")),
                        downlinkHigh = if (cursor.isNull(cursor.getColumnIndex("downlink_high"))) null else cursor.getLong(cursor.getColumnIndex("downlink_high")),
                        downlinkDrift = if (cursor.isNull(cursor.getColumnIndex("downlink_drift"))) null else cursor.getDouble(cursor.getColumnIndex("downlink_drift")),
                        mode = cursor.getString(cursor.getColumnIndexOrThrow("mode")),
                        modeId = cursor.getInt(cursor.getColumnIndexOrThrow("mode_id")),
                        uplinkMode = if (cursor.isNull(cursor.getColumnIndex("uplink_mode"))) null else cursor.getString(cursor.getColumnIndex("uplink_mode")),
                        invert = cursor.getInt(cursor.getColumnIndexOrThrow("invert")) == 1,
                        baud = if (cursor.isNull(cursor.getColumnIndex("baud"))) null else cursor.getDouble(cursor.getColumnIndex("baud")),
                        satId = cursor.getString(cursor.getColumnIndexOrThrow("sat_id")),
                        noradCatId = cursor.getInt(cursor.getColumnIndexOrThrow("norad_cat_id")),
                        noradFollowId = if (cursor.isNull(cursor.getColumnIndex("norad_follow_id"))) null else cursor.getInt(cursor.getColumnIndex("norad_follow_id")),
                        status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
                        updated = cursor.getString(cursor.getColumnIndexOrThrow("updated")),
                        citation = cursor.getString(cursor.getColumnIndexOrThrow("citation")),
                        service = cursor.getString(cursor.getColumnIndexOrThrow("service")),
                        iaruCoordination = cursor.getString(cursor.getColumnIndexOrThrow("iaru_coordination")),
                        iaruCoordinationUrl = cursor.getString(cursor.getColumnIndexOrThrow("iaru_coordination_url")),
                        frequencyViolation = cursor.getInt(cursor.getColumnIndexOrThrow("frequency_violation")) == 1,
                        unconfirmed = cursor.getInt(cursor.getColumnIndexOrThrow("unconfirmed")) == 1
                    )
            } else {
                null
            }
        }
    }

    fun getTransmittersByNoradId(noradId: Int): Flow<List<com.bh6aap.ic705Cter.data.api.Transmitter>> = flow {
        val transmitters = mutableListOf<com.bh6aap.ic705Cter.data.api.Transmitter>()
        readableDatabase.query(
            TABLE_TRANSMITTERS,
            null, "norad_cat_id = ?", arrayOf(noradId.toString()), null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                transmitters.add(
                    com.bh6aap.ic705Cter.data.api.Transmitter(
                        uuid = cursor.getString(cursor.getColumnIndexOrThrow("uuid")),
                        description = cursor.getString(cursor.getColumnIndexOrThrow("description")),
                        alive = cursor.getInt(cursor.getColumnIndexOrThrow("alive")) == 1,
                        type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                        uplinkLow = if (cursor.isNull(cursor.getColumnIndex("uplink_low"))) null else cursor.getLong(cursor.getColumnIndex("uplink_low")),
                        uplinkHigh = if (cursor.isNull(cursor.getColumnIndex("uplink_high"))) null else cursor.getLong(cursor.getColumnIndex("uplink_high")),
                        uplinkDrift = if (cursor.isNull(cursor.getColumnIndex("uplink_drift"))) null else cursor.getDouble(cursor.getColumnIndex("uplink_drift")),
                        downlinkLow = if (cursor.isNull(cursor.getColumnIndex("downlink_low"))) null else cursor.getLong(cursor.getColumnIndex("downlink_low")),
                        downlinkHigh = if (cursor.isNull(cursor.getColumnIndex("downlink_high"))) null else cursor.getLong(cursor.getColumnIndex("downlink_high")),
                        downlinkDrift = if (cursor.isNull(cursor.getColumnIndex("downlink_drift"))) null else cursor.getDouble(cursor.getColumnIndex("downlink_drift")),
                        mode = cursor.getString(cursor.getColumnIndexOrThrow("mode")),
                        modeId = cursor.getInt(cursor.getColumnIndexOrThrow("mode_id")),
                        uplinkMode = if (cursor.isNull(cursor.getColumnIndex("uplink_mode"))) null else cursor.getString(cursor.getColumnIndex("uplink_mode")),
                        invert = cursor.getInt(cursor.getColumnIndexOrThrow("invert")) == 1,
                        baud = if (cursor.isNull(cursor.getColumnIndex("baud"))) null else cursor.getDouble(cursor.getColumnIndex("baud")),
                        satId = cursor.getString(cursor.getColumnIndexOrThrow("sat_id")),
                        noradCatId = cursor.getInt(cursor.getColumnIndexOrThrow("norad_cat_id")),
                        noradFollowId = if (cursor.isNull(cursor.getColumnIndex("norad_follow_id"))) null else cursor.getInt(cursor.getColumnIndex("norad_follow_id")),
                        status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
                        updated = cursor.getString(cursor.getColumnIndexOrThrow("updated")),
                        citation = cursor.getString(cursor.getColumnIndexOrThrow("citation")),
                        service = cursor.getString(cursor.getColumnIndexOrThrow("service")),
                        iaruCoordination = cursor.getString(cursor.getColumnIndexOrThrow("iaru_coordination")),
                        iaruCoordinationUrl = cursor.getString(cursor.getColumnIndexOrThrow("iaru_coordination_url")),
                        frequencyViolation = cursor.getInt(cursor.getColumnIndexOrThrow("frequency_violation")) == 1,
                        unconfirmed = cursor.getInt(cursor.getColumnIndexOrThrow("unconfirmed")) == 1
                    )
                )
            }
        }
        emit(transmitters)
    }

    fun getAllTransmitters(): Flow<List<com.bh6aap.ic705Cter.data.api.Transmitter>> = flow {
        val transmitters = mutableListOf<com.bh6aap.ic705Cter.data.api.Transmitter>()
        readableDatabase.query(
            TABLE_TRANSMITTERS,
            null, null, null, null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                transmitters.add(
                    com.bh6aap.ic705Cter.data.api.Transmitter(
                        uuid = cursor.getString(cursor.getColumnIndexOrThrow("uuid")),
                        description = cursor.getString(cursor.getColumnIndexOrThrow("description")),
                        alive = cursor.getInt(cursor.getColumnIndexOrThrow("alive")) == 1,
                        type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                        uplinkLow = if (cursor.isNull(cursor.getColumnIndex("uplink_low"))) null else cursor.getLong(cursor.getColumnIndex("uplink_low")),
                        uplinkHigh = if (cursor.isNull(cursor.getColumnIndex("uplink_high"))) null else cursor.getLong(cursor.getColumnIndex("uplink_high")),
                        uplinkDrift = if (cursor.isNull(cursor.getColumnIndex("uplink_drift"))) null else cursor.getDouble(cursor.getColumnIndex("uplink_drift")),
                        downlinkLow = if (cursor.isNull(cursor.getColumnIndex("downlink_low"))) null else cursor.getLong(cursor.getColumnIndex("downlink_low")),
                        downlinkHigh = if (cursor.isNull(cursor.getColumnIndex("downlink_high"))) null else cursor.getLong(cursor.getColumnIndex("downlink_high")),
                        downlinkDrift = if (cursor.isNull(cursor.getColumnIndex("downlink_drift"))) null else cursor.getDouble(cursor.getColumnIndex("downlink_drift")),
                        mode = cursor.getString(cursor.getColumnIndexOrThrow("mode")),
                        modeId = cursor.getInt(cursor.getColumnIndexOrThrow("mode_id")),
                        uplinkMode = if (cursor.isNull(cursor.getColumnIndex("uplink_mode"))) null else cursor.getString(cursor.getColumnIndex("uplink_mode")),
                        invert = cursor.getInt(cursor.getColumnIndexOrThrow("invert")) == 1,
                        baud = if (cursor.isNull(cursor.getColumnIndex("baud"))) null else cursor.getDouble(cursor.getColumnIndex("baud")),
                        satId = cursor.getString(cursor.getColumnIndexOrThrow("sat_id")),
                        noradCatId = cursor.getInt(cursor.getColumnIndexOrThrow("norad_cat_id")),
                        noradFollowId = if (cursor.isNull(cursor.getColumnIndex("norad_follow_id"))) null else cursor.getInt(cursor.getColumnIndex("norad_follow_id")),
                        status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
                        updated = cursor.getString(cursor.getColumnIndexOrThrow("updated")),
                        citation = cursor.getString(cursor.getColumnIndexOrThrow("citation")),
                        service = cursor.getString(cursor.getColumnIndexOrThrow("service")),
                        iaruCoordination = cursor.getString(cursor.getColumnIndexOrThrow("iaru_coordination")),
                        iaruCoordinationUrl = cursor.getString(cursor.getColumnIndexOrThrow("iaru_coordination_url")),
                        frequencyViolation = cursor.getInt(cursor.getColumnIndexOrThrow("frequency_violation")) == 1,
                        unconfirmed = cursor.getInt(cursor.getColumnIndexOrThrow("unconfirmed")) == 1
                    )
                )
            }
        }
        emit(transmitters)
    }

    suspend fun deleteTransmitter(uuid: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_TRANSMITTERS, "uuid = ?", arrayOf(uuid))
    }

    suspend fun deleteAllTransmitters() = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_TRANSMITTERS, null, null)
    }

    suspend fun getTransmitterCount(): Int = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_TRANSMITTERS", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    suspend fun hasTransmitterData(): Boolean = withContext(Dispatchers.IO) {
        getTransmitterCount() > 0
    }

    // ==================== 卫星信息表操作（包含别名）====================

    suspend fun insertSatelliteInfo(satelliteInfo: com.bh6aap.ic705Cter.data.api.SatelliteInfo): Long = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("sat_id", satelliteInfo.satId)
            put("norad_cat_id", satelliteInfo.noradCatId)
            put("name", satelliteInfo.name)
            put("names", satelliteInfo.names)
            put("image", satelliteInfo.image)
            put("status", satelliteInfo.status)
            put("decayed", satelliteInfo.decayed)
            put("launched", satelliteInfo.launched)
            put("deployed", satelliteInfo.deployed)
            put("website", satelliteInfo.website)
            put("operator", satelliteInfo.operator)
            put("countries", satelliteInfo.countries)
            put("updated", satelliteInfo.updated)
            put("citation", satelliteInfo.citation)
            put("is_frequency_violator", if (satelliteInfo.isFrequencyViolator) 1 else 0)
            put("createdAt", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            TABLE_SATELLITE_INFO,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    suspend fun insertSatelliteInfos(satelliteInfos: List<com.bh6aap.ic705Cter.data.api.SatelliteInfo>) = withContext(Dispatchers.IO) {
        writableDatabase.beginTransaction()
        try {
            satelliteInfos.forEach { info ->
                insertSatelliteInfo(info)
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    suspend fun getSatelliteInfoByNoradId(noradId: Int): com.bh6aap.ic705Cter.data.api.SatelliteInfo? = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_SATELLITE_INFO,
            null,
            "norad_cat_id = ?",
            arrayOf(noradId.toString()),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                cursorToSatelliteInfo(cursor)
            } else null
        }
    }

    suspend fun getSatelliteInfoBySatId(satId: String): com.bh6aap.ic705Cter.data.api.SatelliteInfo? = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_SATELLITE_INFO,
            null,
            "sat_id = ?",
            arrayOf(satId),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                cursorToSatelliteInfo(cursor)
            } else null
        }
    }

    /**
     * 搜索卫星信息（支持名称和别名模糊搜索）
     */
    fun searchSatelliteInfo(query: String): Flow<List<com.bh6aap.ic705Cter.data.api.SatelliteInfo>> = flow {
        val satelliteInfos = mutableListOf<com.bh6aap.ic705Cter.data.api.SatelliteInfo>()
        val searchQuery = "%$query%"
        readableDatabase.query(
            TABLE_SATELLITE_INFO,
            null,
            "name LIKE ? OR names LIKE ?",
            arrayOf(searchQuery, searchQuery),
            null, null, "name ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                cursorToSatelliteInfo(cursor)?.let { satelliteInfos.add(it) }
            }
        }
        emit(satelliteInfos)
    }

    fun getAllSatelliteInfos(): Flow<List<com.bh6aap.ic705Cter.data.api.SatelliteInfo>> = flow {
        val satelliteInfos = mutableListOf<com.bh6aap.ic705Cter.data.api.SatelliteInfo>()
        readableDatabase.query(
            TABLE_SATELLITE_INFO,
            null, null, null, null, null,
            "name ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                cursorToSatelliteInfo(cursor)?.let { satelliteInfos.add(it) }
            }
        }
        emit(satelliteInfos)
    }

    suspend fun deleteAllSatelliteInfos() = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_SATELLITE_INFO, null, null)
    }

    suspend fun getSatelliteInfoCount(): Int = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_SATELLITE_INFO", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    suspend fun hasSatelliteInfoData(): Boolean = withContext(Dispatchers.IO) {
        getSatelliteInfoCount() > 0
    }

    private fun cursorToSatelliteInfo(cursor: android.database.Cursor): com.bh6aap.ic705Cter.data.api.SatelliteInfo? {
        return try {
            com.bh6aap.ic705Cter.data.api.SatelliteInfo(
                satId = cursor.getString(cursor.getColumnIndexOrThrow("sat_id")),
                noradCatId = cursor.getInt(cursor.getColumnIndexOrThrow("norad_cat_id")),
                noradFollowId = null, // 数据库中未存储
                name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                names = cursor.getString(cursor.getColumnIndexOrThrow("names")),
                image = cursor.getString(cursor.getColumnIndexOrThrow("image")),
                status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
                decayed = cursor.getString(cursor.getColumnIndexOrThrow("decayed")),
                launched = cursor.getString(cursor.getColumnIndexOrThrow("launched")),
                deployed = cursor.getString(cursor.getColumnIndexOrThrow("deployed")),
                website = cursor.getString(cursor.getColumnIndexOrThrow("website")),
                operator = cursor.getString(cursor.getColumnIndexOrThrow("operator")),
                countries = cursor.getString(cursor.getColumnIndexOrThrow("countries")),
                telemetries = null, // 数据库中未存储
                updated = cursor.getString(cursor.getColumnIndexOrThrow("updated")),
                citation = cursor.getString(cursor.getColumnIndexOrThrow("citation")),
                isFrequencyViolator = cursor.getInt(cursor.getColumnIndexOrThrow("is_frequency_violator")) == 1,
                associatedSatellites = null // 数据库中未存储
            )
        } catch (e: Exception) {
            LogManager.e("DatabaseHelper", "解析卫星信息失败", e)
            null
        }
    }

    // ==================== 频率预设表操作 ====================

    fun getFrequencyPresetsByNoradId(noradId: String): Flow<List<FrequencyPresetEntity>> = flow {
        val presets = mutableListOf<FrequencyPresetEntity>()
        readableDatabase.query(
            TABLE_FREQUENCY_PRESETS,
            null, "noradId = ?", arrayOf(noradId), null, null,
            "sortOrder ASC, createdAt ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                presets.add(cursor.toFrequencyPresetEntity())
            }
        }
        emit(presets)
    }

    suspend fun getFrequencyPresetCountByNoradId(noradId: String): Int = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_FREQUENCY_PRESETS WHERE noradId = ?",
            arrayOf(noradId)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    suspend fun insertFrequencyPreset(preset: FrequencyPresetEntity): Long = withContext(Dispatchers.IO) {
        val count = getFrequencyPresetCountByNoradId(preset.noradId)
        val values = ContentValues().apply {
            put("noradId", preset.noradId)
            put("name", preset.name)
            put("uplinkFreqHz", preset.uplinkFreqHz)
            put("downlinkFreqHz", preset.downlinkFreqHz)
            put("sortOrder", count) // 新预设默认排在最后
            put("createdAt", preset.createdAt)
        }
        writableDatabase.insertWithOnConflict(
            TABLE_FREQUENCY_PRESETS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    suspend fun updateFrequencyPreset(preset: FrequencyPresetEntity) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("name", preset.name)
            put("uplinkFreqHz", preset.uplinkFreqHz)
            put("downlinkFreqHz", preset.downlinkFreqHz)
        }
        writableDatabase.update(
            TABLE_FREQUENCY_PRESETS,
            values,
            "id = ?",
            arrayOf(preset.id.toString())
        )
    }

    suspend fun deleteFrequencyPreset(id: Long) = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_FREQUENCY_PRESETS, "id = ?", arrayOf(id.toString()))
    }

    suspend fun deleteAllFrequencyPresetsByNoradId(noradId: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_FREQUENCY_PRESETS, "noradId = ?", arrayOf(noradId))
    }

    private fun android.database.Cursor.toFrequencyPresetEntity(): FrequencyPresetEntity {
        return FrequencyPresetEntity(
            id = getLong(getColumnIndexOrThrow("id")),
            noradId = getString(getColumnIndexOrThrow("noradId")),
            name = getString(getColumnIndexOrThrow("name")),
            uplinkFreqHz = getLong(getColumnIndexOrThrow("uplinkFreqHz")),
            downlinkFreqHz = getLong(getColumnIndexOrThrow("downlinkFreqHz")),
            sortOrder = getInt(getColumnIndexOrThrow("sortOrder")),
            createdAt = getLong(getColumnIndexOrThrow("createdAt"))
        )
    }

    /**
     * 更新频率预设排序顺序
     */
    suspend fun updateFrequencyPresetSortOrder(id: Long, sortOrder: Int) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("sortOrder", sortOrder)
        }
        writableDatabase.update(
            TABLE_FREQUENCY_PRESETS,
            values,
            "id = ?",
            arrayOf(id.toString())
        )
    }

    /**
     * 批量更新频率预设排序
     */
    suspend fun updateFrequencyPresetsSortOrder(presets: List<FrequencyPresetEntity>) = withContext(Dispatchers.IO) {
        writableDatabase.beginTransaction()
        try {
            presets.forEachIndexed { index, preset ->
                val values = ContentValues().apply {
                    put("sortOrder", index)
                }
                writableDatabase.update(
                    TABLE_FREQUENCY_PRESETS,
                    values,
                    "id = ?",
                    arrayOf(preset.id.toString())
                )
            }
            writableDatabase.setTransactionSuccessful()
            LogManager.i("DatabaseHelper", "更新预设排序成功: ${presets.size}条")
        } catch (e: Exception) {
            LogManager.e("DatabaseHelper", "更新预设排序失败", e)
        } finally {
            writableDatabase.endTransaction()
        }
    }

    // ==================== CW预设表操作 ====================

    fun getAllCwPresets(): Flow<List<CwPresetEntity>> = flow {
        val presets = mutableListOf<CwPresetEntity>()
        readableDatabase.query(
            TABLE_CW_PRESETS,
            null, null, null, null, null,
            "presetIndex ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                presets.add(cursor.toCwPresetEntity())
            }
        }
        emit(presets)
    }

    suspend fun getCwPresetByIndex(presetIndex: Int): CwPresetEntity? = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_CW_PRESETS,
            null, "presetIndex = ?", arrayOf(presetIndex.toString()), null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toCwPresetEntity() else null
        }
    }

    suspend fun getCwPresetById(id: Long): CwPresetEntity? = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_CW_PRESETS,
            null, "id = ?", arrayOf(id.toString()), null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toCwPresetEntity() else null
        }
    }

    suspend fun insertCwPreset(preset: CwPresetEntity): Long = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("presetIndex", preset.presetIndex)
            put("name", preset.name)
            put("message", preset.message)
            put("createdAt", preset.createdAt)
        }
        writableDatabase.insertWithOnConflict(
            TABLE_CW_PRESETS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    suspend fun insertCwPresets(presets: List<CwPresetEntity>) = withContext(Dispatchers.IO) {
        writableDatabase.beginTransaction()
        try {
            presets.forEach { preset ->
                val values = ContentValues().apply {
                    put("presetIndex", preset.presetIndex)
                    put("name", preset.name)
                    put("message", preset.message)
                    put("createdAt", preset.createdAt)
                }
                writableDatabase.insertWithOnConflict(
                    TABLE_CW_PRESETS,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    suspend fun updateCwPreset(preset: CwPresetEntity) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("presetIndex", preset.presetIndex)
            put("name", preset.name)
            put("message", preset.message)
        }
        writableDatabase.update(
            TABLE_CW_PRESETS,
            values,
            "id = ?",
            arrayOf(preset.id.toString())
        )
    }

    suspend fun updateCwPresetByIndex(presetIndex: Int, name: String, message: String) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("name", name)
            put("message", message)
        }
        writableDatabase.update(
            TABLE_CW_PRESETS,
            values,
            "presetIndex = ?",
            arrayOf(presetIndex.toString())
        )
    }

    suspend fun deleteCwPreset(id: Long) = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_CW_PRESETS, "id = ?", arrayOf(id.toString()))
    }

    suspend fun deleteCwPresetByIndex(presetIndex: Int) = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_CW_PRESETS, "presetIndex = ?", arrayOf(presetIndex.toString()))
    }

    suspend fun deleteAllCwPresets() = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_CW_PRESETS, null, null)
    }

    suspend fun getCwPresetCount(): Int = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_CW_PRESETS", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    suspend fun hasCwPresetData(): Boolean = withContext(Dispatchers.IO) {
        getCwPresetCount() > 0
    }

    suspend fun initDefaultCwPresets() = withContext(Dispatchers.IO) {
        if (!hasCwPresetData()) {
            val defaults = CwPresetEntity.createDefaults()
            insertCwPresets(defaults)
        }
    }

    private fun android.database.Cursor.toCwPresetEntity(): CwPresetEntity {
        return CwPresetEntity(
            id = getLong(getColumnIndexOrThrow("id")),
            presetIndex = getInt(getColumnIndexOrThrow("presetIndex")),
            name = getString(getColumnIndexOrThrow("name")),
            message = getString(getColumnIndexOrThrow("message")),
            createdAt = getLong(getColumnIndexOrThrow("createdAt"))
        )
    }

    // ==================== 设置表操作 ====================

    // 设置键常量
    object SettingKeys {
        const val CALLSIGN = "callsign" // 呼号
        const val STATION_NAME = "station_name" // 地面站名称
    }

    /**
     * 保存设置
     */
    suspend fun saveSetting(key: String, value: String, description: String? = null) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
            put("description", description)
            put("updatedAt", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            TABLE_SETTINGS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /**
     * 获取设置值
     */
    suspend fun getSetting(key: String): String? = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_SETTINGS,
            arrayOf("value"),
            "key = ?",
            arrayOf(key),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow("value"))
            } else {
                null
            }
        }
    }

    /**
     * 保存呼号
     */
    suspend fun saveCallsign(callsign: String) = saveSetting(
        SettingKeys.CALLSIGN,
        callsign,
        "用户呼号"
    )

    /**
     * 获取呼号
     */
    suspend fun getCallsign(): String? = getSetting(SettingKeys.CALLSIGN)

    /**
     * 检查是否有呼号设置
     */
    suspend fun hasCallsign(): Boolean = getCallsign()?.isNotEmpty() ?: false

    /**
     * 删除设置
     */
    suspend fun deleteSetting(key: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_SETTINGS, "key = ?", arrayOf(key))
    }

    /**
     * 获取所有设置
     */
    fun getAllSettings(): Flow<Map<String, String>> = flow {
        val settings = mutableMapOf<String, String>()
        readableDatabase.query(TABLE_SETTINGS, null, null, null, null, null, null).use {
            while (it.moveToNext()) {
                val key = it.getString(it.getColumnIndexOrThrow("key"))
                val value = it.getString(it.getColumnIndexOrThrow("value"))
                settings[key] = value
            }
        }
        emit(settings)
    }

    // ==================== 备忘录表操作 ====================

    /**
     * 插入备忘录记录
     */
    suspend fun insertMemo(memo: MemoEntity): Long = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("content", memo.content)
            put("satelliteName", memo.satelliteName)
            put("satelliteId", memo.satelliteId)
            put("localTime", memo.localTime)
            put("utcTime", memo.utcTime)
            put("createdAt", memo.createdAt)
        }
        writableDatabase.insert(TABLE_MEMOS, null, values)
    }

    /**
     * 获取所有备忘录记录（按时间倒序）
     */
    fun getAllMemos(): Flow<List<MemoEntity>> = flow {
        val memos = mutableListOf<MemoEntity>()
        readableDatabase.query(
            TABLE_MEMOS,
            null, null, null, null, null,
            "createdAt DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                memos.add(cursor.toMemoEntity())
            }
        }
        emit(memos)
    }

    /**
     * 获取指定卫星的备忘录记录
     */
    fun getMemosBySatellite(satelliteId: String): Flow<List<MemoEntity>> = flow {
        val memos = mutableListOf<MemoEntity>()
        readableDatabase.query(
            TABLE_MEMOS,
            null, "satelliteId = ?", arrayOf(satelliteId), null, null,
            "createdAt DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                memos.add(cursor.toMemoEntity())
            }
        }
        emit(memos)
    }

    /**
     * 删除备忘录记录
     */
    suspend fun deleteMemo(id: Long) = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_MEMOS, "id = ?", arrayOf(id.toString()))
    }

    /**
     * 删除所有备忘录记录
     */
    suspend fun deleteAllMemos() = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_MEMOS, null, null)
    }

    /**
     * 获取备忘录数量
     */
    suspend fun getMemoCount(): Int = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_MEMOS", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun android.database.Cursor.toMemoEntity(): MemoEntity {
        return MemoEntity(
            id = getLong(getColumnIndexOrThrow("id")),
            content = getString(getColumnIndexOrThrow("content")),
            satelliteName = getString(getColumnIndexOrThrow("satelliteName")),
            satelliteId = getString(getColumnIndexOrThrow("satelliteId")),
            localTime = getLong(getColumnIndexOrThrow("localTime")),
            utcTime = getLong(getColumnIndexOrThrow("utcTime")),
            createdAt = getLong(getColumnIndexOrThrow("createdAt"))
        )
    }

    // ==================== 用户自定义转发器表操作 ====================

    /**
     * 插入或更新用户自定义转发器
     */
    suspend fun insertOrUpdateCustomTransmitter(entity: CustomTransmitterEntity): Long = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("uuid", entity.uuid)
            put("norad_cat_id", entity.noradCatId)
            put("description", entity.description)
            put("uplink_low", entity.uplinkLow)
            put("uplink_high", entity.uplinkHigh)
            put("downlink_low", entity.downlinkLow)
            put("downlink_high", entity.downlinkHigh)
            put("downlink_mode", entity.downlinkMode)
            put("uplink_mode", entity.uplinkMode)
            put("is_enabled", if (entity.isEnabled) 1 else 0)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            TABLE_CUSTOM_TRANSMITTERS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /**
     * 根据UUID获取用户自定义转发器
     */
    suspend fun getCustomTransmitterByUuid(uuid: String): CustomTransmitterEntity? = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_CUSTOM_TRANSMITTERS,
            null,
            "uuid = ?",
            arrayOf(uuid),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toCustomTransmitterEntity() else null
        }
    }

    /**
     * 根据NORAD ID获取所有用户自定义转发器
     */
    fun getCustomTransmittersByNoradId(noradCatId: Int): Flow<List<CustomTransmitterEntity>> = flow {
        val entities = mutableListOf<CustomTransmitterEntity>()
        readableDatabase.query(
            TABLE_CUSTOM_TRANSMITTERS,
            null,
            "norad_cat_id = ? AND is_enabled = 1",
            arrayOf(noradCatId.toString()),
            null, null,
            "created_at ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                entities.add(cursor.toCustomTransmitterEntity())
            }
        }
        emit(entities)
    }

    /**
     * 获取所有用户自定义转发器
     */
    fun getAllCustomTransmitters(): Flow<List<CustomTransmitterEntity>> = flow {
        val entities = mutableListOf<CustomTransmitterEntity>()
        readableDatabase.query(
            TABLE_CUSTOM_TRANSMITTERS,
            null, null, null, null, null,
            "created_at ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                entities.add(cursor.toCustomTransmitterEntity())
            }
        }
        emit(entities)
    }

    /**
     * 删除用户自定义转发器
     */
    suspend fun deleteCustomTransmitter(id: Long) = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_CUSTOM_TRANSMITTERS, "id = ?", arrayOf(id.toString()))
    }

    /**
     * 根据UUID删除用户自定义转发器
     */
    suspend fun deleteCustomTransmitterByUuid(uuid: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_CUSTOM_TRANSMITTERS, "uuid = ?", arrayOf(uuid))
    }

    /**
     * 禁用用户自定义转发器（不删除，只标记为禁用）
     */
    suspend fun disableCustomTransmitter(uuid: String) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("is_enabled", 0)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update(TABLE_CUSTOM_TRANSMITTERS, values, "uuid = ?", arrayOf(uuid))
    }

    /**
     * 检查是否存在用户自定义转发器
     */
    suspend fun hasCustomTransmitter(uuid: String): Boolean = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_CUSTOM_TRANSMITTERS WHERE uuid = ? AND is_enabled = 1",
            arrayOf(uuid)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) > 0 else false
        }
    }

    private fun android.database.Cursor.toCustomTransmitterEntity(): CustomTransmitterEntity {
        return CustomTransmitterEntity(
            id = getLong(getColumnIndexOrThrow("id")),
            uuid = getString(getColumnIndexOrThrow("uuid")),
            noradCatId = getInt(getColumnIndexOrThrow("norad_cat_id")),
            description = getString(getColumnIndexOrThrow("description")),
            uplinkLow = if (isNull(getColumnIndex("uplink_low"))) null else getLong(getColumnIndex("uplink_low")),
            uplinkHigh = if (isNull(getColumnIndex("uplink_high"))) null else getLong(getColumnIndex("uplink_high")),
            downlinkLow = if (isNull(getColumnIndex("downlink_low"))) null else getLong(getColumnIndex("downlink_low")),
            downlinkHigh = if (isNull(getColumnIndex("downlink_high"))) null else getLong(getColumnIndex("downlink_high")),
            downlinkMode = getString(getColumnIndex("downlink_mode")),
            uplinkMode = getString(getColumnIndex("uplink_mode")),
            isEnabled = getInt(getColumnIndex("is_enabled")) == 1,
            createdAt = getLong(getColumnIndexOrThrow("created_at")),
            updatedAt = getLong(getColumnIndexOrThrow("updated_at"))
        )
    }

    // ==================== 过境缓存表操作 ====================

    /**
     * 获取过境缓存
     * @param noradId 卫星NORAD ID
     * @param currentTime 当前时间（毫秒）
     * @param hoursAhead 预测小时数
     * @return 缓存的过境列表，如果没有缓存或已过期则返回null
     */
    suspend fun getPassCache(
        noradId: String,
        currentTime: Long,
        hoursAhead: Int
    ): List<PassCacheEntity>? = withContext(Dispatchers.IO) {
        // 3小时过期时间
        val expireTime = 3 * 60 * 60 * 1000L

        readableDatabase.query(
            TABLE_PASS_CACHE,
            null,
            "noradId = ? AND calculatedAt > ? AND hoursAhead = ?",
            arrayOf(noradId, (currentTime - expireTime).toString(), hoursAhead.toString()),
            null, null,
            "aosTime ASC"
        ).use { cursor ->
            val passes = mutableListOf<PassCacheEntity>()
            while (cursor.moveToNext()) {
                passes.add(cursor.toPassCacheEntity())
            }
            if (passes.isNotEmpty()) passes else null
        }
    }

    /**
     * 保存过境缓存
     */
    suspend fun savePassCache(passes: List<PassCacheEntity>) = withContext(Dispatchers.IO) {
        writableDatabase.beginTransaction()
        try {
            // 先删除该卫星的旧缓存
            if (passes.isNotEmpty()) {
                val noradId = passes.first().noradId
                writableDatabase.delete(TABLE_PASS_CACHE, "noradId = ?", arrayOf(noradId))
            }
            // 插入新缓存
            passes.forEach { pass ->
                writableDatabase.insertWithOnConflict(
                    TABLE_PASS_CACHE,
                    null,
                    pass.toContentValues(),
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            writableDatabase.setTransactionSuccessful()
            LogManager.i(LogManager.TAG_DATABASE, "【数据库】保存过境缓存: ${passes.size} 条记录")
        } finally {
            writableDatabase.endTransaction()
        }
    }

    /**
     * 删除过期的过境缓存
     */
    suspend fun deleteExpiredPassCache(currentTime: Long) = withContext(Dispatchers.IO) {
        // 删除已经出境的过境记录
        writableDatabase.delete(TABLE_PASS_CACHE, "losTime < ?", arrayOf(currentTime.toString()))
    }

    /**
     * 删除所有过境缓存
     */
    suspend fun deleteAllPassCache() = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_PASS_CACHE, null, null)
    }

    /**
     * 获取缓存的地面站位置
     */
    suspend fun getCacheStationLocation(noradId: String): Triple<Double, Double, Double>? = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_PASS_CACHE,
            arrayOf("stationLat", "stationLon", "stationAlt"),
            "noradId = ?",
            arrayOf(noradId),
            null, null,
            "calculatedAt DESC",
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                Triple(
                    cursor.getDouble(cursor.getColumnIndexOrThrow("stationLat")),
                    cursor.getDouble(cursor.getColumnIndexOrThrow("stationLon")),
                    cursor.getDouble(cursor.getColumnIndexOrThrow("stationAlt"))
                )
            } else null
        }
    }

    private fun android.database.Cursor.toPassCacheEntity(): PassCacheEntity {
        return PassCacheEntity(
            id = getLong(getColumnIndexOrThrow("id")),
            noradId = getString(getColumnIndexOrThrow("noradId")),
            name = getString(getColumnIndexOrThrow("name")),
            aosTime = getLong(getColumnIndexOrThrow("aosTime")),
            losTime = getLong(getColumnIndexOrThrow("losTime")),
            maxElevation = getDouble(getColumnIndexOrThrow("maxElevation")),
            aosAzimuth = getDouble(getColumnIndexOrThrow("aosAzimuth")),
            losAzimuth = getDouble(getColumnIndexOrThrow("losAzimuth")),
            duration = getLong(getColumnIndexOrThrow("duration")),
            maxElevationTime = getLong(getColumnIndexOrThrow("maxElevationTime")),
            stationLat = getDouble(getColumnIndexOrThrow("stationLat")),
            stationLon = getDouble(getColumnIndexOrThrow("stationLon")),
            stationAlt = getDouble(getColumnIndexOrThrow("stationAlt")),
            minElevation = getDouble(getColumnIndexOrThrow("minElevation")),
            calculatedAt = getLong(getColumnIndexOrThrow("calculatedAt")),
            hoursAhead = getInt(getColumnIndexOrThrow("hoursAhead"))
        )
    }
}

