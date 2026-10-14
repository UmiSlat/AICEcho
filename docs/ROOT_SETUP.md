# Root 模块设置

本页适合已经有 Root 和 LSPosed 的用户。只想管理、读取或备份卡片，不需要安装这些模块；3.0 推荐的 Shizuku 使用流程见 [README](../README.md)。

## 先准备好

- 手机开启 NFC，并在“状态 → 设备兼容性”确认支持卡片模拟。
- 安装 AICEcho，读取或导入原卡资料，保存并选中一张卡片。
- LSPosed 必须支持本项目使用的 libxposed API 101。如果管理器没有识别出模块，先检查这一点。

## 启用模块

1. 在 LSPosed 管理器里启用 AICEcho。
2. 作用域仅勾选 NFC 应用 `com.android.nfc`，不要勾选系统框架。
3. 重启手机，让 NFC 进程重新加载模块。只打开应用或重新授予权限，不能代替这一步。
4. 打开 AICEcho，在“状态 → 模拟方式”选择“Root 模块”。应用请求 Root 权限时按提示授权。
5. 回到“卡片”页，选中原卡并保持应用在前台。Root 模块方式会自动启用当前卡片，不需要进入 Shizuku 配置向导；应用离开前台时停止模拟。

如果仍提示系统拒绝卡片标识，先确认模块已启用、作用域正确，而且启用后已经重启。不要把原卡 IDm 改成生成值或兼容值。

## 什么时候需要兼容补丁？

LSPosed 负责让系统接受原卡身份。兼容补丁则用于修正部分手机向读卡器报告的卡片参数，两者不是同一件事。

先尝试不启用补丁。参数正常、读卡器能够正确识别时，不必额外安装。需要补丁时按 Android 版本选择：

| Android 版本 | 设置方式 |
| --- | --- |
| 10–14 | 在应用的模块方式下开启“读卡兼容修复”，按页面提示完成 NFC 重启 |
| 15–16 | 在 KernelSU 管理器安装 `aicecho-pmm-ksu-v3.zip`，重启手机，再在应用中开启“读卡兼容修复” |

ZIP 是 KernelSU 模块，不是 APK。Android 15–16 的这份补丁只针对已测试的 ST NFC 实现，不要把它当作所有 NFC 芯片都能用的通用模块。显示模块已加载，也不等于读卡器已经识别成功。

补丁可从 [AICEcho 3.0.3 下载页](https://github.com/UmiSlat/AICEcho/releases/tag/v3.0.3)取得。需要测试版时，也可下载 [Android CI](https://github.com/UmiSlat/AICEcho/actions/workflows/android-ci.yml) 主线成功构建的 `AICEcho-release-…` 产物；模块 ID 为 `aicecho_pmm`。

## 刷卡与排查

- 解锁手机并保持刷卡页在前台，移动手机，找到 NFC 天线对应的位置。
- 没反应时先尝试横放、贴紧读卡器和去掉厚手机壳。
- 系统拒绝身份：检查 LSPosed 和重启步骤。
- 应用显示已启用、读卡器仍不识别：记录手机型号、Android 版本、提示文字和外部读卡结果，再开启高级模式排查。
- 如果切换了默认 NFC 应用，使用结束后通过“状态 → 恢复手机钱包”重新选择原钱包。

实现和已验证范围见 [功能约定](FUNCTIONAL_SPEC.md)与[设备适配](AOSP_CAPABILITIES.md)。
