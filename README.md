# Dragon Oro POS - Android APP

把现有的网页 POS 打包成 APP，实现来单自动打印（原生 TCP 直连打印机，不依赖 RawBT）。

## 工作原理

- APP 内嵌 WebView 加载线上 `admin.html`（网页更新自动生效，不用重新打包）
- 原生层通过 `JavascriptInterface` 暴露 `window.AndroidPrinter.print(ip, port, base64)`
- 网页检测到这个接口时，直接把 ESC/POS 字节通过 TCP 发到打印机 9100 端口
- 不弹任何确认框，不需要用户点击，真正的静默打印

## 编译步骤

### 1. 安装 Android Studio

下载地址：https://developer.android.com/studio

安装时全部保持默认，它会一并装好 JDK 和 Android SDK（约 4 GB）。

### 2. 打开项目

Android Studio 启动页 → **Open** → 选择 `DragonOroPOS` 文件夹。

### 3. 等待 Gradle 同步

首次打开会自动下载 Gradle 和依赖，需要几分钟。需要能访问 `google()` 和 `mavenCentral()`，国内网络可能需要开代理。

### 4. 生成 APK

菜单 **Build → Build Bundle(s) / APK(s) → Build APK(s)**

完成后右下角点 **locate**，APK 位置：

```
app/build/outputs/apk/debug/app-debug.apk
```

### 5. 安装到手机

把 APK 传到手机安装，需要在系统设置里允许"安装未知来源应用"。

## 使用

1. 打开 APP
2. 在 **IP Impresora** 输入框填打印机地址（例如 `10.0.4.250`），点 **Guardar**
3. 顶部状态会显示 **Modo APP Nativo (TCP directo puerto 9100)**
4. 点 **Imprimir Ticket de Prueba** 验证出纸
5. 之后来新单会自动出票

## 文件说明

| 文件 | 作用 |
|---|---|
| `MainActivity.java` | WebView 容器，加载网页并注入打印桥 |
| `PrinterBridge.java` | 原生打印实现，TCP 直连 9100 |
| `AndroidManifest.xml` | 权限（INTERNET）与启动入口 |

## 正式发布给客户

debug 版可以直接安装测试。正式发给客户建议签名 release 版：

**Build → Generate Signed Bundle / APK → APK → Create new keystore → 选择 release**

keystore 文件务必妥善保存，以后更新 APP 必须用同一个签名，否则客户无法覆盖安装。

## 客户设备建议

- 一台安卓手机或平板，常插电
- 系统设置里允许 APP 后台运行、关闭电池优化
- 与打印机在同一个局域网
