# IC-705 Controller / IC-705 卫星跟踪控制器

<div align="center">

[![Android](https://img.shields.io/badge/Android-8.0+-green.svg)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9+-blue.svg)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**English** | [中文](#中文)

An Android application for ICOM IC-705 radio satellite tracking with automatic Doppler correction, VFO avoidance, and custom API support.

一款专为 ICOM IC-705 电台设计的卫星跟踪控制 Android 应用程序。

</div>

---

## Features / 功能特性

### Core Features / 核心功能

| Feature | Description | 功能 | 说明 |
|---------|-------------|------|------|
| 🛰️ Satellite Tracking | Real-time satellite position tracking and pass prediction | 卫星跟踪 | 实时跟踪业余卫星位置，预测过境时间 |
| 📡 Auto Doppler | Automatic frequency adjustment for Doppler shift compensation | 自动多普勒补偿 | 自动计算并调整上下行频率，补偿多普勒频移 |
| 🎛️ VFO Avoidance | Smart detection of manual VFO knob operation | VFO 避让 | 智能检测用户手动操作波轮，自动暂停/恢复频率控制 |
| 📻 PTT Detection | Detect radio transmit state and pause adjustments | PTT 检测 | 检测电台发射状态，发射期间暂停频率调整 |

### Data Management / 数据管理

| Feature | Description | 功能 | 说明 |
|---------|-------------|------|------|
| 🌐 Dual Data Sources | Support Celestrak and SatNOGS TLE data | 双TLE数据源 | 支持 Celestrak 和 SatNOGS 双数据源 |
| 🔧 Custom API | User-configurable satellite data API | 自定义API | 支持用户配置自定义卫星数据 API |
| 📴 Offline Data | Built-in satellite and transmitter data | 离线数据 | 内置卫星和转发器数据，首次启动即可使用 |
| ⏱️ Data Freshness | Auto-check data validity (TLE: 12h, Transmitter: 30d) | 数据时效 | 自动检查数据新鲜度，TLE 12小时、转发器30天 |

### Radio Control / 电台控制

| Feature | Description | 功能 | 说明 |
|---------|-------------|------|------|
| 🔗 Bluetooth CI-V | Connect to IC-705 via Bluetooth | 蓝牙CI-V协议 | 通过蓝牙连接 IC-705 电台 |
| 📶 Frequency Control | Auto-set VFO A/B frequencies and modes | 频率设置 | 自动设置 VFO A/B 频率和模式 |
| 🎙️ PTT Control | PTT state detection and frequency jump recognition | PTT控制 | 支持 PTT 状态检测和频率跳变识别 |

---

## Requirements / 系统要求

| Requirement | Details | 要求 | 详情 |
|-------------|---------|------|------|
| Android Version | 8.0 (API 26) or higher | Android版本 | 8.0 (API 26) 或更高版本 |
| Bluetooth | 4.0 or higher | 蓝牙 | 4.0 或更高版本 |
| Radio | ICOM IC-705 | 电台 | ICOM IC-705 |
| Permissions | Location (GPS), Bluetooth, Storage | 权限 | 位置(GPS)、蓝牙、存储 |

---

## Usage Guide / 使用指南

### First Launch / 首次启动

1. **Grant Permissions / 权限授权**: Location, Bluetooth, Storage / 位置、蓝牙和存储权限
2. **GPS Positioning / GPS定位**: Auto-get current location for satellite tracking / 自动获取当前位置用于卫星跟踪
3. **Data Loading / 数据加载**: Load built-in satellite and transmitter data / 自动加载内置卫星和转发器数据
4. **TLE Update / TLE更新**: Fetch latest TLE data from Celestrak / 自动从 Celestrak 获取最新 TLE 数据

### Connect Radio / 连接电台

1. Open app and go to main screen / 打开应用，进入主界面
2. Tap Bluetooth icon to search and connect IC-705 / 点击蓝牙图标，搜索并连接 IC-705 电台
3. Radio info will display when connected / 连接成功后，电台信息会显示在界面上

### Satellite Tracking / 卫星跟踪

1. Select satellite from list / 从列表选择要跟踪的卫星
2. Tap "Start Tracking" button / 点击"开始跟踪"按钮
3. The app will automatically / 应用会自动：
   - Set VFO A as downlink (RX) / 设置 VFO A 为下行频率（接收）
   - Set VFO B as uplink (TX) / 设置 VFO B 为上行频率（发射）
   - Real-time Doppler compensation / 实时调整频率补偿多普勒频移

### VFO Avoidance / VFO 避让

When user manually turns VFO knob / 当用户手动转动波轮调整频率时：

1. App detects frequency change (>4Hz) / 应用检测到频率变化（>4Hz）
2. Auto-pause tracking / 自动暂停自动跟踪
3. Resume 100ms after user stops / 用户停止操作 100ms 后恢复跟踪
4. Recalculate base from user-adjusted frequency / 根据用户调整后的频率重新计算基准

### Custom API Settings / 自定义 API 设置

1. Go to "Settings" -> "API Settings" / 进入"设置" -> "API 设置"
2. Enter custom API URLs / 输入自定义 API 地址：
   - Satellite Data API / 卫星数据 API
   - Transmitter Data API / 转发器数据 API
   - TLE Data API / TLE 数据 API
3. Tap "Test Connection" to verify / 点击"测试连接"验证 API 可用性
4. Auto-fetch data from custom API after save / 保存后自动从自定义 API 获取数据

---

## Project Structure / 项目结构

```
ic705controler/
├── app/src/main/java/com/bh6aap/ic705Cter/
│   ├── data/
│   │   ├── api/                    # API Data Management / API数据管理
│   │   ├── database/               # Database Management / 数据库管理
│   │   └── location/               # GPS Positioning / GPS定位
│   ├── tracking/                   # Satellite Tracking Core / 卫星跟踪核心
│   ├── rig/                        # Radio Control / 电台控制
│   └── ui/                         # User Interface / 用户界面
├── app/src/main/assets/            # Built-in Data / 内置数据
└── app/src/main/res/               # Resources / 资源文件
```

---

## Tech Stack / 技术栈

| Technology | Purpose | 技术 | 用途 |
|------------|---------|------|------|
| Kotlin | Programming Language | 编程语言 |
| Jetpack Compose | UI Framework | UI框架 |
| SQLite (Room) | Database | 数据库 |
| OkHttp | Network | 网络请求 |
| Orekit | Orbital Mechanics | 轨道力学计算库 |
| Android Bluetooth API | Radio Connection | 蓝牙连接 |

---

## Data Sources / 数据源

### Default Data Sources / 默认数据源

| Data Type | Source | 数据类型 | 来源 |
|-----------|--------|----------|------|
| TLE Data | [Celestrak](https://celestrak.org/NORAD/elements/gp.php?GROUP=amateur&FORMAT=tle) | TLE数据 |
| Satellite Info | [SatNOGS Database](https://db.satnogs.org/) | 卫星信息 |
| Transmitter Data | [SatNOGS Database](https://db.satnogs.org/) | 转发器数据 |

### Supported Data Formats / 支持的数据格式

**TLE Data (Celestrak Format) / TLE数据 (Celestrak格式)**:
```
OSCAR 7 (AO-7)
1 07530U 74089B   25071.26979351 -.00000028  00000+0  11357-3 0  9991
2 07530 101.9968  83.4874 0011956 317.1237 110.4299 12.53696362348375
```

**Satellite Data (JSON) / 卫星数据 (JSON)**:
```json
{
  "norad_cat_id": "7530",
  "name": "OSCAR 7 (AO-7)",
  "tle1": "1 07530U 74089B...",
  "tle2": "2 07530 101.9968..."
}
```

**Transmitter Data (JSON) / 转发器数据 (JSON)**:
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

---

## Configuration / 配置说明

### Frequency Avoidance Parameters / 频率避让参数

Edit in `FrequencyControlAvoidance.kt` / 在 `FrequencyControlAvoidance.kt` 中调整：

```kotlin
// Frequency change threshold (Hz) / 频率变化阈值 (Hz)
private const val FREQUENCY_CHANGE_THRESHOLD_HZ = 4.0

// User activity timeout (ms) / 用户活动超时 (ms)
private const val USER_ACTIVITY_TIMEOUT_MS = 100L

// Max avoidance time (ms) / 最大避让时间 (ms)
private const val MAX_AVOIDANCE_TIME_MS = 1000L

// Command ignore window (ms) / 命令忽略窗口 (ms)
private const val COMMAND_IGNORE_WINDOW_MS = 200L
```

### Data Validity / 数据有效期

Edit in `SplashActivity.kt` / 在 `SplashActivity.kt` 中调整：

```kotlin
// TLE data validity: 12 hours / TLE数据有效期: 12小时
private const val TLE_DATA_VALIDITY_HOURS = 12L

// Transmitter data validity: 30 days / 转发器数据有效期: 30天
private const val TRANSMITTER_VALIDITY_DAYS = 30L
```

---

## Roadmap / 开发计划

- [x] Basic satellite tracking / 基础卫星跟踪功能
- [x] Auto Doppler correction / 自动多普勒补偿
- [x] VFO avoidance mechanism / VFO 避让机制
- [x] Custom API support / 自定义 API 支持
- [x] Offline data support / 离线数据支持
- [ ] More radio models support / 支持更多电台型号
- [ ] Cloud sync / 云同步功能
- [ ] QSO record export / QSO 记录导出

---

## Contributing / 贡献指南

Contributions are welcome! / 欢迎提交 Issue 和 Pull Request！

1. Fork the repository / Fork 本仓库
2. Create feature branch / 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. Commit changes / 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. Push to branch / 推送到分支 (`git push origin feature/AmazingFeature`)
5. Create Pull Request / 创建 Pull Request

---

## License / 许可证

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

本项目采用 MIT 许可证 - 详见 [LICENSE](LICENSE) 文件。

---

## Acknowledgments / 致谢

- [Celestrak](https://celestrak.org/) - TLE Data / TLE 数据
- [SatNOGS](https://satnogs.org/) - Satellite Database / 卫星数据库
- [Orekit](https://www.orekit.org/) - Orbital Mechanics Library / 轨道力学计算库
- ICOM - Excellent IC-705 Radio / 制造优秀的 IC-705 电台

---

## Contact / 联系方式

- GitHub Issues: [Report Bug / 报告问题](https://github.com/bh6aap/ic705controler/issues)
- Email: [1065147896@qq.com](mailto:1065147896@qq.com)

---

## Donate / 捐助

If this project helps you, consider buying me a coffee! ☕

如果这个项目对你有帮助，欢迎打赏支持开发！

- Alipay / 支付宝: `18132886815`

---

<div align="center">

**Note / 注意**: This project is developed for amateur radio enthusiasts. Please comply with local radio regulations when using.

本项目为业余无线电爱好者开发，使用时请遵守当地无线电法规。

</div>
