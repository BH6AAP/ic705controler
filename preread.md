# IC-705 卫星跟踪控制器 - 项目文档

## 项目概述

IC-705 Controller 是一款专为 ICOM IC-705 电台设计的 Android 卫星跟踪控制应用程序。该应用通过蓝牙 CI-V 协议连接电台,实现自动卫星跟踪、多普勒频移补偿、VFO 避让等功能,为业余无线电爱好者提供专业的卫星通信工具。

### 核心特性

- **实时卫星跟踪**: 基于 Orekit 轨道力学库计算卫星实时位置
- **自动多普勒补偿**: 实时计算并调整上下行频率,补偿多普勒频移
- **VFO 智能避让**: 检测用户手动操作波轮,自动暂停/恢复频率控制
- **PTT 状态检测**: 发射期间暂停频率调整,避免干扰通信
- **双数据源支持**: 支持 Celestrak 和 SatNOGS 双 TLE 数据源
- **自定义 API**: 用户可配置自定义卫星数据 API
- **离线数据**: 内置卫星和转发器数据,首次启动即可使用
- **摩尔斯电码**: 支持 CW 模式发送和解码

### 技术信息

- **应用 ID**: com.bh6aap.ic705Cter
- **版本**: 3.5.5
- **最低 Android 版本**: 8.0 (API 27)
- **目标 Android 版本**: 36
- **编程语言**: Kotlin
- **UI 框架**: Jetpack Compose

## 技术架构

### 架构模式

项目采用分层架构设计:

```javascript
┌─────────────────────────────────────┐
│         UI Layer (Compose)          │
│  Activities, ViewModels, Components │
└─────────────────────────────────────┘
                 ↓
┌─────────────────────────────────────┐
│        Business Logic Layer         │
│  Tracking, Doppler, Sensor Fusion   │
└─────────────────────────────────────┘
                 ↓
┌─────────────────────────────────────┐
│          Data Layer                 │
│  Database, API, Location, Radio     │
└─────────────────────────────────────┘
```

### 核心技术栈

| 技术              | 用途      | 版本     |
| --------------- | ------- | ------ |
| Kotlin          | 编程语言    | 1.9+   |
| Jetpack Compose | UI 框架   | BOM    |
| Orekit          | 轨道力学计算  | 13.1.3 |
| OkHttp          | 网络请求    | 4.12.0 |
| Gson            | JSON 解析 | 2.10.1 |
| DataStore       | 数据持久化   | 1.0.0  |
| SQLite          | 本地数据库   | -      |

## 项目结构

### 目录结构

```javascript
app/src/main/java/com/bh6aap/ic705Cter/
├── MainActivity.kt                 # 主界面
├── SplashActivity.kt              # 启动页
├── SatelliteTrackingActivity.kt   # 卫星跟踪界面
├── MorseCodeActivity.kt           # 摩尔斯电码界面
├── SettingsActivity.kt            # 设置界面
├── IC705Application.kt            # 应用入口
├── OrekitInitializer.kt           # Orekit 初始化
├── PermissionManager.kt           # 权限管理
│
├── data/                          # 数据层
│   ├── api/                       # API 数据管理
│   │   ├── TleDataManager.kt      # TLE 数据管理
│   │   ├── SatelliteDataManager.kt # 卫星信息管理
│   │   ├── TransmitterDataManager.kt # 转发器数据管理
│   │   └── ApiTypeValidator.kt    # API 类型验证
│   │
│   ├── database/                  # 数据库管理
│   │   ├── DatabaseHelper.kt      # 数据库助手
│   │   ├── DatabaseRouter.kt      # 数据库路由
│   │   └── entity/                # 数据实体
│   │       ├── SatelliteEntity.kt # 卫星实体
│   │       ├── TransmitterEntity.kt # 转发器实体
│   │       ├── StationEntity.kt   # 地面站实体
│   │       └── ...
│   │
│   ├── location/                  # 位置管理
│   │   └── GpsManager.kt          # GPS 管理器
│   │
│   ├── radio/                     # 电台控制
│   │   ├── BluetoothConnectionManager.kt # 蓝牙连接管理
│   │   ├── BluetoothSppConnector.kt # SPP 连接器
│   │   ├── CivController.kt       # CI-V 控制器
│   │   ├── CivCommandManager.kt   # CI-V 命令管理
│   │   ├── PttStatusManager.kt    # PTT 状态管理
│   │   └── VfoQueryManager.kt     # VFO 查询管理
│   │
│   └── time/                      # 时间管理
│       └── NtpTimeManager.kt      # NTP 时间同步
│
├── tracking/                      # 卫星跟踪核心
│   ├── SatelliteTracker.kt        # 卫星跟踪器
│   ├── SatelliteTrackingController.kt # 跟踪控制器
│   ├── DopplerCalculator.kt       # 多普勒计算器
│   ├── DopplerDataCache.kt        # 多普勒数据缓存
│   ├── PredictiveDopplerCalculator.kt # 预测性多普勒计算
│   └── FrequencyControlAvoidance.kt # 频率控制避让
│
├── sensor/                        # 传感器管理
│   ├── PhoneSensorManager.kt      # 手机传感器管理
│   ├── SensorFusionManager.kt     # 传感器融合
│   └── CompassCalibrator.kt       # 罗盘校准
│
├── notification/                  # 通知管理
│   └── PassNotificationManager.kt # 过境通知管理
│
├── service/                       # 后台服务
│   └── BluetoothForegroundService.kt # 蓝牙前台服务
│
├── ui/                            # UI 组件
│   ├── components/                # 可复用组件
│   ├── state/                     # UI 状态
│   ├── viewmodel/                 # ViewModel
│   └── theme/                     # 主题
│
└── util/                          # 工具类
    ├── LogManager.kt              # 日志管理
    ├── SatelliteCalculator.kt     # 卫星计算工具
    ├── SatellitePassCalculator.kt # 过境计算
    ├── OptimizedPassCalculator.kt # 优化过境计算
    ├── MaidenheadConverter.kt     # Maidenhead 网格转换
    ├── FrequencyFormatter.kt      # 频率格式化
    ├── CallsignMatcher.kt         # 呼号匹配
    └── ...
```

### 数据模型

#### 核心实体

1. **SatelliteEntity** - 卫星数据
   
   - noradId: NORAD 卫星编号
   - name: 卫星名称
   - tleLine1/tleLine2: TLE 数据
   - category: 卫星类别
   - isFavorite: 是否收藏

2. **TransmitterEntity** - 转发器数据
   
   - uuid: 转发器唯一标识
   - noradId: 所属卫星 ID
   - uplinkLow/High: 上行频率范围
   - downlinkLow/High: 下行频率范围
   - mode: 工作模式 (FM/SSB/CW)
   - invert: 是否反向

3. **StationEntity** - 地面站数据
   
   - name: 地面站名称
   - latitude/longitude: 经纬度
   - altitude: 海拔高度
   - isDefault: 是否默认地面站
   - minElevation: 最小仰角

## 核心功能模块

### 1. 卫星跟踪系统

#### SatelliteTracker

- 使用 Orekit 库计算卫星实时位置
- 缓存地球模型和地面站框架,避免重复初始化
- 计算方位角、仰角、距离和径向速度
- 支持多卫星并行计算

#### SatelliteTrackingController

- 协调卫星跟踪和电台控制
- 管理跟踪状态和频率更新
- 处理用户交互和模式切换

### 2. 多普勒补偿系统

#### DopplerCalculator

- 基于径向速度计算多普勒频移
- 下行链路: `f_r = f_0 * (c - v_r) / c`
- 上行链路: `f_t = f_0 * c / (c - v_r)`
- 支持线性转发器和非线性转发器

#### PredictiveDopplerCalculator

- 预测性多普勒计算,提前补偿延迟
- 使用线性外推预测未来频率
- 减少频率更新延迟

#### DopplerDataCache

- 缓存多普勒计算结果
- 减少重复计算开销
- 支持过期数据自动清理

### 3. VFO 避让机制

#### FrequencyControlAvoidance

- 检测用户手动操作 VFO 波轮
- 频率变化阈值: 4 Hz
- 用户活动超时: 100 ms
- 最大避让时间: 1000 ms
- 自动暂停/恢复频率控制
- 根据用户调整重新计算基准频率

### 4. 电台控制系统

#### BluetoothConnectionManager

- 管理蓝牙连接生命周期
- 单例模式,全局共享连接
- 支持自动重连
- 连接状态监听

#### CivController

- CI-V 协议实现
- 支持频率设置、模式设置、Split 模式
- VFO A/B 独立控制
- 命令队列和响应处理

#### PttStatusManager

- PTT 状态检测
- 发射期间暂停频率调整
- 频率跳变识别
- 避免干扰通信

### 5. 数据管理系统

#### TleDataManager

- 从 Celestrak/SatNOGS 获取 TLE 数据
- 数据有效期: 12 小时
- 支持强制刷新
- 自动解析 TLE 格式

#### TransmitterDataManager

- 从 SatNOGS 获取转发器数据
- 数据有效期: 30 天
- 支持内置数据和 API 数据
- 自动合并和去重

#### DatabaseRouter

- 支持默认数据库和自定义数据库
- 动态切换数据源
- 数据隔离和迁移

### 6. GPS 定位系统

#### GpsManager

- GPS 和网络定位并行获取
- 超时机制: 10 秒
- 自动选择最优位置
- Maidenhead 网格转换

### 7. 传感器融合系统

#### SensorFusionManager

- 融合加速度计、磁力计、陀螺仪数据
- 计算设备方位角和俯仰角
- 用于天线指向辅助

#### CompassCalibrator

- 罗盘校准算法
- 磁场干扰补偿
- 提高方位角精度

### 8. 通知系统

#### PassNotificationManager

- 卫星过境提醒
- 可配置提前时间
- 支持多卫星监控
- 后台运行

## 应用流程

### 启动流程

```javascript
SplashActivity
    ↓
1. 权限检查 (位置、蓝牙、存储)
    ↓
2. NTP 时间同步
    ↓
3. GPS 位置获取 (GPS + 网络并行)
    ↓
4. TLE 数据同步 (Celestrak)
    ↓
5. 转发器数据加载 (内置 + API)
    ↓
6. Orekit 初始化
    ↓
MainActivity
```

### 卫星跟踪流程

```javascript
选择卫星
    ↓
选择转发器
    ↓
连接电台 (蓝牙 CI-V)
    ↓
开始跟踪
    ↓
┌─────────────────────────────────┐
│  实时循环 (每秒更新)              │
│  1. 计算卫星位置                 │
│  2. 计算多普勒频移               │
│  3. 检测 VFO 避让                │
│  4. 检测 PTT 状态                │
│  5. 更新电台频率                 │
└─────────────────────────────────┘
    ↓
停止跟踪
```

### 频率控制流程

```javascript
计算目标频率
    ↓
检查 VFO 避让状态
    ↓
检查 PTT 状态
    ↓
发送 CI-V 命令
    ↓
等待响应
    ↓
更新 UI 显示
```

## 配置参数

### VFO 避让参数

```kotlin
// 频率变化阈值 (Hz)
FREQUENCY_CHANGE_THRESHOLD_HZ = 4.0

// 用户活动超时 (ms)
USER_ACTIVITY_TIMEOUT_MS = 100L

// 最大避让时间 (ms)
MAX_AVOIDANCE_TIME_MS = 1000L

// 命令忽略窗口 (ms)
COMMAND_IGNORE_WINDOW_MS = 200L
```

### 数据有效期

```kotlin
// TLE 数据有效期: 12 小时
TLE_DATA_VALIDITY_HOURS = 12L

// 卫星信息有效期: 30 天
SATELLITE_INFO_VALIDITY_DAYS = 30L

// 转发器数据有效期: 30 天
TRANSMITTER_VALIDITY_DAYS = 30L
```

### CI-V 协议参数

```kotlin
// IC-705 地址
IC705_ADDRESS = 0xA4

// 控制器地址
CONTROLLER_ADDRESS = 0xE0

// 响应超时 (ms)
RESPONSE_TIMEOUT_MS = 2000L
```

## 数据源

### 默认数据源

| 数据类型   | 来源        | URL                                                                  |
| ------ | --------- | -------------------------------------------------------------------- |
| TLE 数据 | Celestrak | https://celestrak.org/NORAD/elements/gp.php?GROUP=amateur&FORMAT=tle |
| 卫星信息   | SatNOGS   | https://db.satnogs.org/api/satellites/                               |
| 转发器数据  | SatNOGS   | https://db.satnogs.org/api/transmitters/                             |

### 内置数据

- `satellites_builtin.json`: 内置卫星数据
- `transmitters_builtin.json`: 内置转发器数据
- `satellite_whitelist.txt`: 卫星白名单
- `callsigns.txt`: 呼号数据库
- `orekit-data/`: Orekit 轨道数据

## 权限要求

| 权限                           | 用途                 |
| ---------------------------- | ------------------ |
| ACCESS_FINE_LOCATION         | GPS 精确定位           |
| ACCESS_COARSE_LOCATION       | 网络粗略定位             |
| BLUETOOTH                    | 蓝牙基础功能             |
| BLUETOOTH_ADMIN              | 蓝牙管理               |
| BLUETOOTH_SCAN               | 蓝牙扫描 (Android 12+) |
| BLUETOOTH_CONNECT            | 蓝牙连接 (Android 12+) |
| INTERNET                     | 网络请求               |
| ACCESS_NETWORK_STATE         | 网络状态检查             |
| READ_EXTERNAL_STORAGE        | 读取存储               |
| WRITE_EXTERNAL_STORAGE       | 写入存储 (Android 9-)  |
| FOREGROUND_SERVICE           | 前台服务               |
| FOREGROUND_SERVICE_DATA_SYNC | 数据同步服务             |
| WAKE_LOCK                    | 保持唤醒               |
| RECORD_AUDIO                 | CW 解码              |
| POST_NOTIFICATIONS           | 通知权限 (Android 13+) |

## 开发规范

### 代码风格

- 使用 Kotlin 官方代码风格
- 类名使用 PascalCase
- 函数名使用 camelCase
- 常量使用 UPPER_SNAKE_CASE
- 私有成员使用下划线前缀

### 日志规范

使用 LogManager 统一管理日志:

```kotlin
LogManager.i(TAG, "信息日志")
LogManager.d(TAG, "调试日志")
LogManager.w(TAG, "警告日志")
LogManager.e(TAG, "错误日志", exception)
```

### 异步处理

- 使用 Kotlin Coroutines
- IO 操作使用 Dispatchers.IO
- UI 更新使用 Dispatchers.Main
- 计算密集型使用 Dispatchers.Default

### 状态管理

- 使用 StateFlow 管理状态
- 使用 MutableStateFlow 更新状态
- UI 层使用 collectAsState() 订阅

## 已知问题

1. 部分 Android 设备蓝牙连接不稳定
2. GPS 定位在室内可能失败
3. TLE 数据更新可能超时
4. 某些卫星转发器数据不完整

## 联系方式

- GitHub: https://github.com/bh6aap/ic705controler
- Email: 1065147896@qq.com
- 呼号: BH6AAP

## 许可证

MIT License

---

**文档版本**: 1.0  
**最后更新**: 2025-01-XX  
**应用版本**: 3.5.5