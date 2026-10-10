# AimeSimulator

Android NFC-F / FeliCa Lite 卡片管理与 HCE-F 模拟工具。保存实体卡的原始配置，在手机前台向读卡器提供所选卡片的原 IDm 和块数据。

当前源码为 **3.0.0 预览阶段**，尚未正式发布。使用原 IDm，不再提供生成或前缀替换的兼容身份；FeliCa Access Code 手动填写，不自动解密推导。

## 下载与要求

公开安装包见 [GitHub Releases](https://github.com/UmiSlat/AimeSimulator/releases)。本地测试包不是稳定发布，debug 与正式签名不能互相覆盖；更新前请导出卡片备份，不要直接卸载有数据的旧应用。

- 配置管理与实体卡读取：Android 10+；读取能力取决于 NFC 芯片和驱动。
- 手机模拟：设备必须声明支持 HCE-F，NFC 开启，刷卡页保持前台。当前 APK 仅构建 `arm64-v8a`。
- Shizuku 配置：Shizuku API 13+，通过 ADB 启动的 shell 模式（UID 2000）。当前运行时重建支持 Android 14+、唯一且已解锁的主用户 0，不支持应用分身、工作资料或次用户。
- Root 模块：保留为按需使用的替代路线，不是管理或读取卡片的前置条件。

普通 NFC、能读取 FeliCa 或拥有 Shizuku 权限，不代表已经具备原卡模拟能力。系统必须允许身份配置和加载，并正确处理 PMm；请以设备检查和实际读卡结果为准。

## 开始使用

1. 在“卡片”页添加配置：通过“读取卡片”获取原始信息，或手动填写 IDm。检查后保存、选中。
2. 在“状态 → 模拟方式”选择 Shizuku 或 Root 模块。新安装默认 Shizuku，升级保留原来的选择。
3. Shizuku 首次配置或换卡时，从“卡片”页点“配置卡片”，连接 Shizuku，并完成页面当前显示的步骤：准备、确认系统恢复、单独重载 NFC。
4. 已配置的卡片直接点“开始模拟”。保持刷卡页在前台，将手机 NFC 天线靠近读卡器；退出即停止。
5. “设置 → 卡片数据”可导出或导入完整配置；“状态 → 设备能力检查”可在无卡时检查设备。

日常启用只核对系统中保存的原身份，不重复注册或恢复，也不要求 Shizuku 持续运行。换卡、修改 IDm 或系统登记不匹配时，需要重新配置；应用不会自动替换身份。

如果出现 Android 备份或恢复确认页，请按提示确认，**不要设置备份密码**。完整备份探测属于高级诊断，不是运行时配置的必经步骤。页面进入、刷新或重新连接不会自动恢复或重载 NFC。

> [!IMPORTANT]
> 只有当前 ROM 确有需要时，才将本应用设为默认 NFC 应用。它会替换原默认支付应用，可能影响手机钱包；“状态 → 恢复手机钱包”打开系统选择页，由你重新选择原钱包。应用不提供支付功能，也不静默切换钱包。

## 界面分工

| 页面 | 普通模式 | 高级模式追加内容 |
| --- | --- | --- |
| 卡片 | 选卡、开始模拟、配置、详情、编辑和删除 | 列表/详情中的原 IDm、S_PAD0、ID 块和声明参数 |
| 状态 | 模拟方式、当前状态、设备检查、默认 NFC 应用；模块路线的兼容补丁开关 | 原始错误、PMm 状态细节及登记诊断 |
| 配置卡片 | 当前卡片、连接和结果，按进度显示下一步；需要时继续上次配置或恢复之前身份 | 基线来源、CE/DE、目标 NFCID2、快照和独立 Lab；直接展开，不再另套折叠入口 |
| 设备能力检查 | 型号、NFC、模拟支持、当前卡片登记和配置条件 | 系统备份服务、运行时字段、完整备份探测、恢复能力等技术检查 |
| 设置 | 外观、卡片导入/导出、卡号显示、关于、高级模式开关 | IDm 显示设置 |

“开始模拟 / 配置卡片”集中在卡片页，不再在状态页重复。页面分组使用平铺布局，卡片边框只保留给单张配置。完整卡片详情窗口仍保留，高级模式只影响显示，不修改原始数据。

本节描述最新源码的界面调整；它尚未打包安装，现有 code 49 不包含这轮界面改动。

## 设备适配与当前证据

Shizuku 路线优先从结构化 NFC 接口重建动态登记，再调用 AOSP `bu restore` 写入本应用的单条身份。支持完整文件导出的设备可走文件后备；没有小米专用导出或仅含自身记录的最小表兜底。普通 `dumpsys` 仅作诊断，不能替代完整动态字段。

恢复后必须单独重载并重新核对。计划区分 CE/DE，只写对应存储域；文件来源按字节比较，运行时来源按登记属性比较。命令成功、NFC 进程重启或系统显示登记匹配，都不等于游戏账号已验证。

| 环境 / 路径 | 已验证 | 未验证 |
| --- | --- | --- |
| Xiaomi 15 Pro / Android 16 / HyperOS / 主用户 / CE，code 49 | 原身份到独立合成身份的 AOSP 写入、重载、实际回滚；HINATA Lite 的 IDm、`88B4`、PMm 和块 `00/82/85`；最终原配置及其他登记不变 | 当前版本游戏账号、冷重启、长期稳定性、干净无 Root 部署 |
| 同一设备的标准完整文件导出 | 命令执行结束 | 未取得完整身份 XML，文件读取路线不可用；不影响已通过的运行时路线 |
| DE、其他 ROM / 芯片、分身 / 多用户 | CE/DE 逻辑有本地测试 | DE 与其他设备未实测；多用户运行时预置未支持 |

该测试手机已装 Root/LSPosed。实际预置后端使用 shell、没有小米导出或 Root NFC 写入，不能据此宣布所有无 Root 设备可用。旧模块/旧导出路线的证据与当前路线分开记录。

在 Xiaomi 15 Pro 的 HINATA 实测中，手机需要横置并让 NFC 天线区域贴紧读卡器。遇到无响应时先尝试移动、旋转和去掉厚手机壳，不能仅凭无响应判断协议不兼容。本机游戏经 AimeIO/DLL 的结果也不等于原厂读卡器验收。

包信息、每轮结果及验收边界见 [3.0 验收](docs/3.0_ACCEPTANCE.md)；读取条件和适配限制见 [AOSP 能力说明](docs/AOSP_CAPABILITIES.md)。

### 按需使用 Root 模块

公开 HCE-F 注册通常不接受原卡 IDm / `88B4`，可使用已验证可用的 Shizuku 预置，或在模块路线放宽校验。LSPosed 作用域**仅选择 `com.android.nfc`，不要选择系统框架**；启用或修改作用域后需重启设备或可靠重启 NFC 进程。

PMm 优先使用标准 `t3tPmm-filter`，只有厂商忽略或覆盖它时才考虑补丁：

| Android | PMm 兜底实现 | 使用条件 |
| --- | --- | --- |
| 10–14 | libxposed API 101 与 NFC 进程内原生 Hook | LSPosed 和 Root；切换补丁会短暂重启 NFC |
| 15–16 | 独立 KernelSU 模块注入 NFC HAL | 安装 `aimesim-pmm-ksu-v3.zip` 后重启，在模块路线开启兼容补丁 |

Android 15+ 注入只覆盖已测试的 ST HAL 符号与数据包，不能保证 NXP 或其他厂商可用。模块 `active` 是加载状态，不是射频验证。完整接入与历史流程见 [Shizuku 说明](docs/SHIZUKU_PROVISIONING.md) 和 [功能约定](docs/FUNCTIONAL_SPEC.md)。

## 卡片数据

| 数据 | 保存与读取行为 |
| --- | --- |
| IDm | 8 字节 / 16 位十六进制，模拟必需；始终保留原值 |
| Access Code | 可选的 20 位卡面号码；FeliCa 手动填写，仅供显示，不生成 S_PAD0 或推算 IDm |
| S_PAD0、ID 块 | 可选的 16 字节原始块；读取或导入后原样保存，普通编辑不会丢弃隐藏字段 |

NFC-F 读取尝试服务 `000B` 的 Block `00/82`，组合失败后分别重试；块缺失仍可保存 IDm。旧式 MIFARE Aime 读取实现可提取 UID 和 Access Code，但尚无旧式 Aime 实卡验收，且依赖驱动的 `MifareClassic` 接口。模拟端仍是 HCE-F，不将 MIFARE UID 伪装成 FeliCa IDm；保存前需提供对应 FeliCa 配置。

实体卡读取不写卡。当前卡片映像提供只读服务 `000B`，固定 System Code `88B4`、PMm `00F1000000014300`，响应 Read Without Encryption；写请求返回只读错误。不支持的块读取失败，不伪造完整发行或认证数据。模板与协议细节见 [卡片映像](docs/CARD_IMAGE.md)。

### 备份与隐私

- 导出为带版本号、SHA-256 完整性校验的**明文 JSON**，包含全部配置；校验不是加密或防篡改保护。
- 导入前检查格式、大小、数量、字段及重复 ID，并预览“合并”或“全部替换”；替换再次确认。
- 导入改变当前卡片时，取消选中并停止模拟，避免静默切卡。
- 数据在应用私有目录保存，Android 自动备份/数据提取关闭；应用没有网络权限、遥测或在线账号功能。
- 备份和诊断日志可能含真实卡号，请私密保存，不公开上传。仅测试自己拥有或获准使用的设备与卡片。

## 从源码构建

使用仓库 Gradle Wrapper。环境：JDK 21+（本机已验证 JDK 22）、Android SDK 36、Build Tools 36.1.0、NDK 25.1.8937393、CMake 3.22.1 和 Python 3。Gradle 8.14.5、AGP 8.13.2、Kotlin 2.3.21；APK 字节码目标仍为 17。

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

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，KernelSU 模块位于 `dist/aimesim-pmm-ksu-v3.zip`。首次构建需要下载依赖。

GitHub Actions 的 `Android CI` 对主线执行测试、Lint、构建和产物检查；`Android Release` 由与源码版本一致的标签触发固定签名发布。Secrets、标签与密钥备份见 [发布维护](docs/RELEASE.md)。

## 开发文档

- [功能与协议](docs/FUNCTIONAL_SPEC.md)
- [AOSP 能力与适配](docs/AOSP_CAPABILITIES.md)
- [Shizuku 接入与流程](docs/SHIZUKU_PROVISIONING.md)
- [3.0 包版本与验收](docs/3.0_ACCEPTANCE.md)
- [卡片映像](docs/CARD_IMAGE.md)
- [实现来源与许可边界](docs/PROVENANCE.md)
- [第三方组件](THIRD_PARTY_NOTICES.md)
- [HINATA 本地诊断工具](tools/hinata-diagnostics/README.md)

## 实现来源与权利

早期 SPAD0 解码参考 [Project-HINATA/hinata_go](https://github.com/Project-HINATA/hinata_go/tree/c56d8badc3a720e0ba9e2f721f3f73111f2f6d97)，旧模板与兼容标识源于原版 [UmiSlat/AICEmu](https://github.com/UmiSlat/AICEmu/tree/5a1ecc28e98541e8226e1e92b07bd2747e607bb3)。当前已移除解密器、替换表、卡型指纹、旧模板及兼容 IDm 算法；历史提交与已发布 APK 不因此改变。具体核对见实现来源文档。

第三方组件遵循各自许可证。当前仓库没有授予项目自有代码复制、修改或再发布权利的项目级开源许可证，默认保留相应权利。

Aime 等名称与标识属于各自权利人。本项目是非官方兼容性与研究工具，与相关厂商或运营方没有隶属或背书关系。

## English Summary

AimeSimulator manages original card profiles and provides Android HCE-F emulation. Version 3.0 is a preview, not a stable release. One Xiaomi/CE device has passed the AOSP runtime provisioning, rollback and HINATA RF cycle; other devices, clean unrooted deployment and current game-account acceptance remain unverified. Technical diagnostics are available in Advanced mode. See the Chinese guide and linked documents for requirements and limitations.
