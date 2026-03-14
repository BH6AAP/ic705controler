# IC-705 Controller

一款专为 ICOM IC-705 电台设计的卫星跟踪控制 Android 应用程序，支持自动多普勒频移补偿、卫星过境预测和自定义 API 数据源。

## 功能特性

### 核心功能

- **卫星跟踪**: 实时跟踪业余卫星位置，预测过境时间
- **自动多普勒补偿**: 自动计算并调整上下行频率，补偿多普勒频移
- **VFO 避让**: 智能检测用户手动操作波轮，自动暂停/恢复频率控制
- **PTT 检测**: 检测电台发射状态，发射期间暂停频率调整

### 数据管理

- **TLE 数据源**: 支持 Celestrak 和 SatNOGS 双数据源
- **自定义 API**: 支持用户配置自定义卫星数据 API
- **离线数据**: 内置卫星和转发器数据，首次启动即可使用
- **数据时效**: 自动检查数据新鲜度，TLE 数据 12 小时、转发器数据 30 天

### 电台控制

- **CI-V 协议**: 通过蓝牙连接 IC-705 电台
- **频率设置**: 自动设置 VFO A/B 频率和模式
- **PTT 控制**: 支持 PTT 状态检测和频率跳变识别

### 用户界面

- **实时显示**: 卫星方位、仰角、距离、速度实时更新
- **过境预测**: 显示未来 24 小时内的卫星过境时间
- **收藏管理**: 支持收藏常用卫星，快速切换
- **日志记录**: 详细的操作日志，便于调试

## 系统要求

- Android 8.0 (API 26) 或更高版本
- 蓝牙 4.0 或更高版本
- ICOM IC-705 电台
- 位置权限（GPS 定位）

## 安装

### 从源码构建

1. 克隆仓库

```bash
git clone https://github.com/bh6aap/ic705controler.git
cd ic705controler
```

1. 使用 Android Studio 打开项目
2. 构建 APK

```bash
./gradlew assembleDebug
```

### 安装 APK

将生成的 APK 文件安装到 Android 设备：

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 使用指南

### 首次启动

1. **权限授权**: 授予位置、蓝牙和存储权限
2. **GPS 定位**: 自动获取当前位置（用于卫星跟踪计算）
3. **数据加载**: 自动加载内置卫星和转发器数据
4. **TLE 更新**: 自动从 Celestrak 获取最新 TLE 数据

### 连接电台

1. 打开应用，进入主界面
2. 点击蓝牙图标，搜索并连接 IC-705 电台
3. 连接成功后，电台信息会显示在界面上

### 卫星跟踪

1. 从列表选择要跟踪的卫星
2. 点击"开始跟踪"按钮
3. 应用会自动：
   - 设置 VFO A 为下行频率（接收）
   - 设置 VFO B 为上行频率（发射）
   - 实时调整频率补偿多普勒频移

### VFO 避让

当用户手动转动波轮调整频率时：

1. 应用检测到频率变化（>4Hz）
2. 自动暂停自动跟踪
3. 用户停止操作 150ms 后恢复跟踪
4. 根据用户调整后的频率重新计算基准

### 自定义 API 设置

1. 进入"设置" -> "API 设置"
2. 输入自定义 API 地址：
   - 卫星数据 API
   - 转发器数据 API
   - TLE 数据 API
3. 点击"测试连接"验证 API 可用性
4. 保存后自动从自定义 API 获取数据

## 项目结构

```
ic705controler/
├── app/src/main/java/com/bh6aap/ic705Cter/
│   ├── data/
│   │   ├── api/              # API 数据管理
│   │   │   ├── TleDataManager.kt
│   │   │   ├── SatelliteDataManager.kt
│   │   │   └── ApiTypeValidator.kt
│   │   ├── database/         # 数据库管理
│   │   │   ├── DatabaseHelper.kt
│   │   │   ├── DatabaseRouter.kt
│   │   │   └── CustomApiDatabaseManager.kt
│   │   └── location/         # GPS 定位
│   ├── tracking/             # 卫星跟踪核心
│   │   ├── SatelliteTrackingController.kt
│   │   ├── FrequencyControlAvoidance.kt
│   │   └── DopplerCalculator.kt
│   ├── rig/                  # 电台控制
│   │   ├── IcomRigController.kt
│   │   └── BluetoothConnectionManager.kt
│   └── ui/                   # 用户界面
│       ├── MainActivity.kt
│       ├── SatelliteTrackingActivity.kt
│       └── components/
├── app/src/main/assets/      # 内置数据
│   ├── satellites_builtin.json
│   └── transmitters_builtin.json
└── app/src/main/res/         # 资源文件
```

## 技术栈

- **语言**: Kotlin
- **UI 框架**: Jetpack Compose
- **数据库**: SQLite (Room)
- **网络**: OkHttp
- **卫星计算**: Orekit (轨道力学库)
- **蓝牙**: Android Bluetooth API

## 数据源

### 默认数据源

- **TLE 数据**: [Celestrak](https://celestrak.org/NORAD/elements/gp.php?GROUP=amateur\&FORMAT=tle)
- **卫星信息**: [SatNOGS Database](https://db.satnogs.org/)
- **转发器数据**: [SatNOGS Database](https://db.satnogs.org/)

### 支持的数据格式

**TLE 数据** (Celestrak 格式):

```
OSCAR 7 (AO-7)
1 07530U 74089B   25071.26979351 -.00000028  00000+0  11357-3 0  9991
2 07530 101.9968  83.4874 0011956 317.1237 110.4299 12.53696362348375
```

**卫星数据** (JSON):

```json
{
  "norad_cat_id": "7530",
  "name": "OSCAR 7 (AO-7)",
  "tle1": "1 07530U 74089B...",
  "tle2": "2 07530 101.9968..."
}
```

**转发器数据** (JSON):

```json
{
  "uuid": "ao-7-linear",
  "norad_cat_id": 7530,
  "description": "AO-7 Linear Transponder B",
  "uplink_low": 432125000,
  "downlink_low": 145925000,
  "mode": "SSB",
  "invert": true
}
```

## 配置说明

### 频率避让参数

在 `FrequencyControlAvoidance.kt` 中可调整：

```kotlin
// 频率变化阈值 (Hz)
private const val FREQUENCY_CHANGE_THRESHOLD_HZ = 4.0

// 用户活动超时 (ms)
private const val USER_ACTIVITY_TIMEOUT_MS = 150L

// 最大避让时间 (ms)
private const val MAX_AVOIDANCE_TIME_MS = 2000L

// 命令忽略窗口 (ms)
private const val COMMAND_IGNORE_WINDOW_MS = 200L
```

### 数据有效期

在 `SplashActivity.kt` 中可调整：

```kotlin
// TLE 数据有效期: 12小时
private const val TLE_DATA_VALIDITY_HOURS = 12L

// 转发器数据有效期: 30天
private const val TRANSMITTER_VALIDITY_DAYS = 30L
```

## 开发计划

- [x] 基础卫星跟踪功能
- [x] 自动多普勒补偿
- [x] VFO 避让机制
- [x] 自定义 API 支持
- [x] 离线数据支持
- [ ] 支持更多电台型号
- [ ] 云同步功能
- [ ] QSO 记录导出

## 贡献指南

欢迎提交 Issue 和 Pull Request！

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 创建 Pull Request

## 许可证

本项目采用 MIT 许可证 - 详见 [LICENSE](LICENSE) 文件

## 致谢

- [Celestrak](https://celestrak.org/) - 提供 TLE 数据
- [SatNOGS](https://satnogs.org/) - 提供卫星数据库
- [Orekit](https://www.orekit.org/) - 轨道力学计算库
- ICOM - 制造优秀的 IC-705 电台

## 联系方式

如有问题或建议，请通过以下方式联系：

- 提交 [GitHub Issue](https://github.com/yourusername/ic705controler/issues)
- 发送邮件至: [1065147896@qq.com](mailto:your.email@example.com)

  <br />

# 捐助

支付宝：18132886815

**注意**: 本项目为业余无线电爱好者开发，使用时请遵守当地无线电法规。
