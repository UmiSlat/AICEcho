# 从源码构建

普通用户直接下载 APK 即可，不需要准备本页环境。下载和使用流程见 [README](../README.md)。

## 构建环境

| 工具 | 当前要求 |
| --- | --- |
| JDK | 21 或更新版本；本机已验证 JDK 22，GitHub Actions 使用 JDK 21 |
| Android SDK | Platform 36、Build Tools 36.1.0 |
| NDK | 25.1.8937393 |
| CMake | 3.22.1 |
| Python | Python 3，用于模块打包和产物检查 |

使用仓库自带的 Gradle Wrapper，不需要单独安装 Gradle。当前版本为 Gradle 8.14.5、AGP 8.13.2、Kotlin 2.3.21；APK 的 Java/Kotlin 字节码目标仍为 17。构建 JDK 与字节码目标不是同一个设置。

Android SDK 通过 `ANDROID_HOME` 或本地 `local.properties` 指定。首次构建需要下载依赖。当前 APK 只构建 `arm64-v8a`，最低 Android 10，目标 Android 16。

## 本地测试与打包

在仓库根目录执行。

Linux / macOS：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
python3 tools/package_module.py
python3 tools/check_artifacts.py
```

Windows PowerShell 7：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
python tools\package_module.py
python tools\check_artifacts.py
```

只验证代码而不生成 APK 时，可省略 `assembleDebug` 和后面两条产物命令。

## 输出文件

| 文件 | 位置 |
| --- | --- |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| KernelSU 模块 | `dist/aimesim-pmm-ksu-v3.zip` |
| 测试报告 | `app/build/reports/tests/testDebugUnitTest/` |
| Lint 报告 | `app/build/reports/lint-results-debug.html` |

运行 `tools/package_module.py` 需要相应的原生构建产物；不要用旧产物冒充当前源码的新构建。

## 签名与版本

Debug APK 与正式签名 APK 通常不能直接互相覆盖。测试更新需核对签名和版本码，保留已有卡片资料。

当前源码版本为 `3.0.0` / code 45，已有本地测试包使用 code 49。下一测试包需设置更高版本码；本地测试包的版本覆盖与构建产物继续放在忽略目录，不将临时配置作为正式版本发布。

正式签名配置、Secrets 和发布标签规则见 [发布维护](RELEASE.md)。密钥、密码和本地签名文件不提交仓库。

## GitHub Actions

- `Android CI` 对主线推送、面向主线的 PR 和手动运行执行测试、Lint、Debug 构建、模块打包及产物检查。
- `Android Release` 由与源码版本一致的 `v*` 标签触发，使用配置的正式证书构建并发布。
- 存档标签 `archive/hcef-rootless-20261010` 不触发正式发布。

托管构建配置已保留；本地检查通过不等于远端任务已运行。当前版本验收见 [3.0 验收](3.0_ACCEPTANCE.md)。
