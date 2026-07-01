# 色差校正 (scrBalance)

折叠屏手机色差校正工具 - 在展开状态下通过半透明覆盖层平衡左右屏幕色差。

## 功能特性

- **悬浮窗覆盖**：在全局界面叠加半透明颜色层，平衡两屏色差
- **折叠检测**：自动检测折叠屏展开/折叠状态，仅在展开时启用校正
- **颜色自定义**：左屏和右屏可独立设置RGB覆盖色
- **透明度调节**：0-100%透明度可调
- **半屏模式**：默认左右半屏分别覆盖
- **自定义区域**：可精确设置覆盖区域的位置和大小（百分比）
- **悬浮窗权限管理**：引导用户授权

## 适用设备

- 荣耀 V-Purse（主要目标设备）
- 其他折叠屏设备

## 构建说明

通过 Gitee Actions 自动编译，推送代码后自动触发构建。

本地构建需 JDK 17+ 和 Android SDK：
```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

注意：gradle-wrapper.jar 需从 Gradle 官方下载或使用 `gradle wrapper` 命令生成。

## 权限说明

| 权限 | 用途 |
|------|------|
| `SYSTEM_ALERT_WINDOW` | 显示悬浮覆盖层 |
| `FOREGROUND_SERVICE` | 后台常驻校正服务 |
| `POST_NOTIFICATIONS` | 前台服务通知 |

## 项目结构

```
app/src/main/java/com/vlab/scrbalance/
├── AppConfig.java          # 配置管理
├── FoldDetector.java       # 折叠状态检测
├── OverlayService.java     # 悬浮窗覆盖服务
├── MainActivity.java       # 主界面
├── SettingsActivity.java   # 设置界面
└── SimpleSeekBarListener.java
```

## License

MIT
