# AimeSimulator

AimeSimulator 是一个面向 Android 的 NFC-F / FeliCa Lite 卡片配置管理与 HCE-F 模拟工具。它可以保存多张本地配置，通过统一读卡入口读取 Amusement IC、普通 FeliCa 和旧式 MIFARE Aime 的可用信息，并由 Android `HostNfcFService` 向读卡端提供当前配置。

当前源码版本为 **3.0.0**，正在进行首版验收，尚未作为正式版本发布。本文描述当前源码；当前源码已移除自动 SPAD0 解密及其替换表，FeliCa 卡面 Access Code 改为手动填写。实现来源与历史边界见“实现参考、版权与第三方组件”。

> [!WARNING]
> 当前 Shizuku 路线使用 shell 权限调用 AOSP 标准备份／恢复入口，不再调用小米专用备份接口。此前单一小米 ROM 的写入及射频证据来自旧导出后端，不能证明新后端或其他设备已可用；模块路线按需使用 LSPosed、Root 和 NFC HAL 注入。请仅在自己拥有或获准测试的设备与卡片上使用，并提前备份。

## 下载

公开版本请从 [GitHub Releases](https://github.com/UmiSlat/AimeSimulator/releases) 下载；本地 `3.0.0` 验收包不是已发布的稳定版。Debug 包与正式签名包不能相互覆盖安装，更新前请核对签名并备份卡片数据。

## 快速开始

1. 安装与现有签名相同的 APK；不要为覆盖安装而直接卸载有卡片数据的旧应用。
2. 打开应用并阅读首次 NFC 提示；仅在当前系统确有需要时，手动选择将 AimeSimulator 设为默认 NFC 应用。
3. 在“卡片”页选择“手动添加”或“读取卡片”，检查字段后保存配置。
4. 在“状态 → 模拟方式”选择路线。新安装默认 Shizuku；升级保留原来的选择。模块模式仍保留原有前台激活行为。
5. Shizuku 首次配置前，打开“状态 → 设备能力检查”，连接 shell 模式的 Shizuku，再从卡片页进入“配置卡片”并准备原身份。优先检查运行时重建；“验证完整备份”是另行确认的文件读取探测，不是运行时路线的必经步骤。出现系统备份或恢复页时按提示确认，不要设置备份密码。
6. 完成配置后，从卡片页点“开始模拟”，保持独立刷卡页在前台。已保存身份的日常启用不要求重复导出或写入，也不要求 Shizuku 一直运行。退出刷卡页即停止模拟。

备份／恢复入口来自 AOSP，但不代表每个系统允许备份 NFC 身份表或恢复该文件。准备卡片优先尝试结构化运行时重建，当前仅支持 Android 14+、单一主用户且动态字段与服务范围完整的情况；支持完整文件导出的设备可使用文件路线。两条读取路线都不可用时不生成可写计划，也不回退到小米接口或仅含自身登记的最小表。运行时计划在恢复后等待单独重载 NFC，再核对实际登记。已有身份的日常模拟和模块路线仍保留。应用只使用原卡 IDm；换卡或修改 IDm 后，需要重新配置对应身份，不自动改用其他标识。

> [!IMPORTANT]
> 将本应用设为默认 NFC 应用会替换现有的默认非接触式支付应用，可能暂时影响手机钱包。测试结束后请在“状态”页点击“恢复手机钱包”，并在 Android 设置中重新选择原应用。

## 功能概览

- 管理多张本地卡片配置，可添加、编辑、删除和切换当前配置。
- 卡片页提供“开始模拟 / 配置卡片”；状态页直接选择 Shizuku 或 Root 模块路线，普通页面不再展示 Lab、快照和底层能力参数。
- Shizuku 配置支持完整表读取、当前卡片预置、读回核对、显式 NFC 重载，以及已结束操作的计划找回。找回仅核对当前表，不自动写入或重载。
- “设备能力检查”不需要选卡或高级模式。进入与刷新只查询；“验证完整备份”单独确认，不恢复、不改默认钱包、不手动重载 NFC。标准命令存在仅表示入口可用，不会显示为已支持卡片模拟。
- “读取运行时登记”提供独立只读检查，结构化读取失败时尝试 `dumpsys`。准备卡片另行核对动态字段和完整用户范围，符合条件才生成运行时计划；文本 dump 仍只用于诊断。code 47 测试包在用户删除应用分身、确认仅剩用户 0 后，已通过运行时基线及准备检查，识别出当前卡片身份已登记；新路线的实际恢复及射频尚未验收，多用户仍不支持。
- 每张配置保留独立详情窗口：普通模式显示名称与 Access Code；在设置中开启“高级模式”后，可完整查看配置 ID、原 IDm、S_PAD0、ID 块、System Code 和 PMm，长字段可选中复制。
- 可将全部卡片配置导出为带版本号和 SHA-256 完整性校验的 JSON，并在导入前预览、合并或全部替换。
- 支持手动填写或从支持的实体卡读取 Access Code、IDm、S_PAD0 和 ID 块。
- 使用单一“读取卡片”入口按 NFC-F 或 MIFARE Classic 技术读取数据，不再根据 IDm/PMm 指纹判定 Amusement IC。
- Amusement IC 与其他 FeliCa 卡可读取 IDm，并尝试读取原始 S_PAD0 和 ID 块；Access Code 可手动填写卡面号码，不再自动解密推导。
- 普通 FeliCa 可尝试读取 IDm、S_PAD0 与 ID 块；旧式 MIFARE Aime 的 UID 与 Access Code 读取已实现，但尚待旧式 Aime 实卡验证。
- NFCID2 始终使用所选卡片的原 IDm，不再提供生成或前缀替换的兼容模式。
- 首次进入时说明默认 NFC 应用对手机钱包的影响，由用户决定是否打开系统选择界面；状态页始终提供恢复钱包入口。
- 响应 FeliCa Lite 的 Read Without Encryption 请求。
- 通过标准 HCE-F `t3tPmm-filter` 声明 PMm `00F1000000014300`，兼容的系统无需 Hook。
- 提供 PMm 兼容补丁：
  - Android 14 及以下：通过 libxposed API 101 和应用内原生 Hook 工作；
  - Android 15 及以上：通过独立 KernelSU 模块注入 NFC HAL。
- PMm 补丁启用时将对外配置替换为 `00F1000000014300`。
- 默认跟随系统浅色/深色主题，并允许在设置中手动选择浅色或深色。
- Android 12+ 默认使用 Material You 动态取色，可在设置中关闭。
- 使用原高度的“卡片 / 状态 / 设置”底部导航。普通模式保留日常操作与必要状态；设置中的“高级模式”展开技术字段及诊断，不再包含身份路线选择器。
- 应用不申请网络权限，不包含遥测或在线账户功能。

## 运行条件

### 基础功能

- Android 10 或更高版本（最低 API 29）。
- 设备必须具有 NFC，并由系统声明支持 NFC-F 主机卡模拟（HCE-F）。
- 使用模拟时需要保持 NFC 开启，并让 AimeSimulator 位于前台。

仅安装 APK 就可以管理配置；标准 HCE-F 声明本身不保证系统接受 Aime 所用参数。Shizuku 预置或下方 Hook/Root 组件用于处理系统限制，厂商 PMm 行为仍需要实际读卡验证。

| 能力 | Root/LSPosed 要求 | 说明 |
| --- | --- | --- |
| 配置管理与实体卡读取 | 不需要 | 仍受手机 NFC 芯片和驱动支持范围限制 |
| 标准 HCE-F 模拟 | 不需要 | 设备需原生支持 HCE-F |
| Shizuku 身份预置 | 后端仅用 shell | AOSP 恢复；需可重建的运行时基线或完整文件；重载后验证，跨设备未验证；可能短暂中断 NFC |
| 放宽 NFCID2 / System Code 校验 | 按需使用 LSPosed | 作用域仅为 `com.android.nfc` |
| PMm 厂商兼容补丁 | 按需使用 Root | Android 15+ 还需要 KernelSU 模块 |

这里的“标准 HCE-F 模拟”是指 Android 公开接口允许的参数范围。AOSP 的动态 System Code 校验要求首位为 `4` 且末两位不能为 `FF`，因此 AIME 使用的 `88B4` 在标准实现上通常仍需要校验 Hook；既有测试已确认 `4000` 不能替代 AIME 路线，因此不再作为状态页选项。

### 兼容性组件

| 系统版本 | LSPosed API 101 | Root | KernelSU 模块 | PMm 实现 |
| --- | --- | --- | --- | --- |
| Android 10–14 | 按需启用 | 使用 PMm 兜底补丁时需要 | 不需要 | 标准 HCE-F PMm；异常设备可在 `com.android.nfc` 中加载 `libpmm.so` |
| Android 15–16 | 按需启用 | 使用 PMm 兜底补丁时需要 | 异常设备需要 | 标准 HCE-F PMm；异常设备可向厂商 NFC HAL 注入 `libaimesim_pmm.so` |

LSPosed 模块的静态作用域只有 `com.android.nfc`。不需要、也不应勾选“系统框架”。它负责放宽 NFCID2 / System Code 的格式校验，并为 Android 14 及以下系统加载旧版 PMm Hook。

Android 15+ 的 PMm 路径具有设备相关性：

- 当前只构建 `arm64-v8a`。
- 注入器会寻找名称以 `android.hardware.nfc-service` 开头的 HAL 进程。
- PMm 数据改写目前针对已测试的 ST NFC HAL 符号和数据包格式。
- NXP、其他厂商 HAL 或经过大幅修改的系统即使进程名称能够被发现，也可能因为没有匹配符号而无法启用补丁。

## 安装

### 1. 安装 APK

安装构建得到的 `app-debug.apk`，或从 [GitHub Releases](https://github.com/UmiSlat/AimeSimulator/releases) 下载版本化 APK。更新安装会保留本应用包名下已经保存的配置；卸载应用通常会清除这些本地数据。

应用包名为：

```text
io.github.umislat.aimesimulator
```

### 2. 首次运行

1. 新安装先使用 Shizuku 路线；已有正常工作的模块用户可保留原环境，无需为了升级先关闭模块。
2. 添加或读取一张卡片配置并选中它。
3. 阅读应用内提示；只有当前 ROM 确有要求时，才在状态页手动发起默认 NFC 应用选择。
4. 在“配置卡片”完成原 IDm 的预置，或按下文配置模块路线。
5. 点“开始模拟”后用外部读卡器核对原 IDm、块数据及目标账号；设备不接受原身份时不会自动换成其他身份。

### 3A. 配置 LSPosed（按需）

1. 确认使用支持现代 libxposed API 101 的 LSPosed 版本。
2. 在 LSPosed 中启用 AimeSimulator 模块。
3. 作用域只选择“应用”中的 `com.android.nfc`。
4. 不要选择系统框架。
5. 重启设备，或按照所用框架的要求重新加载 NFC 进程。

LSPosed 只会在目标进程启动时装载模块。设备开机后才启用 AimeSimulator 模块或修改作用域时，必须再次重启设备（或可靠地重启 `com.android.nfc`）；仅授予 AimeSimulator Root 权限不会让校验 Hook 立即生效。反过来，关闭模块后也必须重启，才能得到不受残留 Hook 影响的无 Root 测试结果。

如果系统原生接受所用 NFCID2，并正确采用 APK 中的 `t3tPmm-filter`，可以不启用 LSPosed 或 PMm 补丁。是否需要兜底组件应以实际读卡结果为准。

### Shizuku 配置与已有证据

3.0 的“配置卡片”入口不再要求开启高级模式。普通页面显示当前卡片、连接状态、基线来源和当前操作，并提供独立的“设备能力检查”；“高级诊断”保留独立 Lab 和快照管理。当前源码使用结构化运行时读取或标准 `bu backup` 获取基线，再通过 `bu restore` 恢复；归档使用本机 NFC 包的版本与公开签名，不固定小米 ROM、APK 路径或签名。支持边界、标准入口依据和验证方法见 [AOSP 能力检查说明](docs/AOSP_CAPABILITIES.md)。

**标准文件导出的实测限制：**2026-10-09，`3.0.0-aosp-probe` 在 Xiaomi 15 Pro / Android 16 / `OS3.0.308.0.WOBCNXM` 上完成一次标准备份，但没有取得完整身份 XML；该设备无法通过这条文件读取路线准备新身份。卡片数据、已有 HCE-F 登记和默认钱包保持一致，没有继续恢复或重载。此前同一 ROM 的完整导出依赖已移除的小米专用后端，不能把旧成功记录直接套用，也不能将本次失败泛化到其他 ROM。

随后 `3.0.0-runtime-probe` / code 47 同签名覆盖安装，通过只读结构化接口读到用户 0 的两个服务及动态覆盖字段，内存 XML 预览校验成功。首次检查时设备仍有用户 999（XSpace）；用户删除应用分身后，确认只剩用户 0，再执行“准备当前卡片”通过完整运行时基线检查，返回 `RUNTIME / ALREADY_PRESENT`，即当前卡片身份已登记、无需重复写入。前后卡片数据、HCE-F 登记与默认钱包一致，未执行备份、恢复、动态注册、重载或模拟启用。这验证了本机的运行时读取与准备，不等于新恢复流程或射频已经通过。

实验源码现已接通“准备当前卡片 → 写入并读回 → 手动重载 NFC → 启用模拟”，并提供初次合法登记和本次会话的身份回滚。它修改系统 NFC 身份登记，不修改实体卡、卡片原始数据或默认钱包；原表先保存到应用私有目录。2026-10-09，测试版 `2.2.23` 在上述小米 ROM 上完成了“原 IDm → 临时 02FE 前缀 → 原 IDm”的手机内恢复、读回和 HINATA 射频/三块读取验证。但 NFC 重载后遇到框架旧连接问题，需要重启应用才能启用；账号、干净无 Root 部署、中断恢复与并发保护仍未完成验收，不能称为一键无 Root 稳定模拟。前置条件、证据边界和操作步骤见 [Shizuku 接入说明](docs/SHIZUKU_PROVISIONING.md)。

3.0 将 Shizuku 路线的实验和日常启用统一到独立前台进程：退出模拟页后回收该进程，下次启用重新获取 NFC-F 连接；主界面与 Shizuku 会话保留。还新增手动“找回上次计划”，重新核对当前表后继续流程，未结束的操作仍会阻止继续。新入口和重连策略尚待 3.0 实机验收，不把 2.2.23 的射频成功直接当成新版已通过。

旧版“预置后无模块模式”设置迁移为当前的 Shizuku/保存身份路线，用于复用 Android NFC 服务已经保存的动态映射。启用时只读取 System Code 与 NFCID2；精确匹配当前卡片与路线后调用 `enableService()`，不调用身份注册接口。状态页选择 Shizuku 不会自动查询 Root/PMm 模块。

历史 Hook 预置路径在 3.0 中对应的验证顺序如下：

1. 在校验 Hook 已随 `com.android.nfc` 启动的状态下，使用当前安装的 AimeSimulator 对目标卡片与路线模式完成一次正常激活。
2. 在状态页选择 Shizuku，点“开始模拟”并确认保存值匹配。模式保留到重启后，不在主页面调用动态注册接口。
3. 关闭 LSPosed 模块与 PMm 补丁并冷重启设备，不要卸载应用或清除 NFC 系统数据。
4. 保持同一张卡片和同一路线模式并打开应用，点“开始模拟”；每次打开刷卡页都会执行只读核对。
5. 只有界面报告 System Code 与 NFCID2 均匹配并成功启用后，才继续使用外部读卡器核对 RF System Code、IDm 与 PMm。

该路径已在一台 Android 16 / HyperOS、ST54L NFC 设备上完成严格实机验证：先在 Hook 生效时预置原 IDm + `88B4`，随后关闭 LSPosed 模块与 PMm 模块并冷重启；应用仅复用保存值后，Android 路由、HINATA 定向轮询和服务 `000B` 的 Block `00/82/85` 均通过，PMm 为 `00F1000000014300`，达到 L3。保持模块关闭后又连续完成 3 次冷重启，每次均恢复保存的 `88B4` 路由，最后一轮再次通过 HINATA L3；切换到未预置的固定 NFCID2 时也会明确停止，切回精确组合后可恢复。

此结果仍只是特定设备上的“预置后免模块运行”，不是从零开始的无 Root 方案，也不代表原厂机台已接受。

上述历史路线的首次写入依赖已生效的校验 Hook；新的 Shizuku 预置流程已获单 ROM 写入及射频证据，仍有应用重连缺陷，详见前述实验说明。卸载应用、清除 NFC 服务数据、改变签名/包名或切换卡片路线都可能使预置失效；其他设备是否保留动态映射、是否采用 APK 中的 PMm，以及能否达到 L3，仍必须逐机实测。

### 3B. Android 14 及以下的 PMm 兜底补丁（按需）

1. 完成 APK 和 LSPosed 配置。
2. 打开 AimeSimulator，进入“设置”。
3. 打开“启用 PMm 补丁”，并向应用授予 Root 权限。
4. 此路径会设置 `tmp.aimesim.pmm.enabled`，然后重启 NFC 服务，使原生 Hook 在 `com.android.nfc` 中加载。

因此 Android 14 及以下切换 PMm 时，NFC 会短暂不可用；应用会等待 NFC Binder 恢复并重试 HCE-F 注册。

### 3C. Android 15 及以上的 PMm 兜底补丁（按需）

1. 在 KernelSU 管理器中安装 `aimesim-pmm-ksu-v3.zip`（v3 系列文件名，当前模块元数据版本为 `3.1.0`）。
2. 安装完成后重启一次设备。模块首次安装时默认保持关闭。
3. 打开 AimeSimulator，进入“设置”，向应用授予 Root 权限。
4. 打开“启用 PMm 补丁”，等待状态变为 `active`。

模块会把 Hook 保持在当前 NFC HAL 进程中，并通过 `vendor.aimesim.pmm.enabled` 在运行时控制是否改写 PMm。正常开关补丁不会重启 NFC HAL，也不会重启 `com.android.nfc`；如果 HAL 因系统原因自行重启，后台服务会检测新 PID 并重新注入。

## 使用方法

### 添加卡片配置

在主界面选择“手动添加”，填写：

| 字段 | 长度 | 说明 |
| --- | --- | --- |
| 配置名称 | 不限 | 留空时会根据 IDm 末四位自动生成 |
| Access Code | 20 位十进制数字 | 可选，记录卡面印刷号码，仅用于本地显示 |
| IDm | 16 个十六进制字符 | 必填，表示 8 字节 IDm |
| S_PAD0 | 32 个十六进制字符 | 可选，表示 16 字节块数据 |
| ID 块 | 32 个十六进制字符 | 可选，表示 16 字节块数据 |

输入会被规范化为大写十六进制；长度不正确或包含非十六进制字符时不会保存。

### 实体卡读取支持

| 卡片类型 | 手机侧技术 | 可读取内容 | 配置处理 |
| --- | --- | --- | --- |
| Amusement IC | NFC-F / FeliCa | IDm、System Code，并尝试读取原始加密 S_PAD0 与 ID 块 | 原始数据带入编辑器；Access Code 手动填写 |
| 普通 FeliCa | NFC-F / FeliCa | IDm、System Code，并尝试读取 S_PAD0 与 ID 块 | 可创建配置，缺失块保持可选 |
| 旧式 Aime | NFC-A / MIFARE Classic | UID、Access Code | 已实现并通过单元测试；尚待实卡验证，保存前需填写对应的 FeliCa IDm |

MIFARE Classic 能否读取还取决于手机 NFC 控制器和 Android 驱动。检测到 NFC-A 不代表设备一定提供 `MifareClassic` 接口。

### 读取实体卡

1. 在主界面选择“读取卡片”。
2. 将 FeliCa、Amusement IC 或旧式 MIFARE Aime 卡保持在手机 NFC 天线附近。
3. 应用自动判断卡片协议并读取可用字段。
4. 读取成功后，点击“用于卡片配置”进入编辑器。
5. 检查自动填入的原始数据；FeliCa 的 Access Code 可手动填写卡面号码，再保存。

- Amusement IC 与其他 FeliCa：尝试从服务 `000B` 读取 Block `00` 与 `82`，组合读取失败时分别重试；保存原始字节，不进行 SPAD0 解密。即使块读取失败，仍可用 IDm 创建配置。
- 旧式 Aime：实现使用 Key B 认证 MIFARE 扇区 0，并从 Block 2 提取 Access Code；该路径尚未由旧式 Aime 实卡验证，保存前仍需填写对应的 FeliCa IDm。

只有点击编辑器的“保存”后才会写入本地配置，读取流程不会修改实体卡。

移除自动解密不会清除已有配置或备份中的 Access Code、S_PAD0 和 ID 块。模拟直接使用已保存的原始块；手动填写 Access Code 不会生成或修改 S_PAD0，也不能仅凭卡面号码恢复完整卡片身份。

### 选择并模拟

1. 点击某张配置卡片，使其成为当前配置。
2. Shizuku 模式先在“配置卡片”完成原身份预置，再点“开始模拟”；模块模式按其系统要求完成配置。
3. 确认模拟页或状态栏显示已启用；高级模式仅用于查看参数和诊断，不改变卡片身份。
4. 保持应用位于前台，将手机 NFC 天线靠近读卡器。

在 Xiaomi 15 Pro 与标准版 HINATA 的历史实测中，需要将手机横置，并让机背 NFC 天线区域完全贴紧读卡器，模拟卡才能被稳定读到。不同设备的天线位置和耦合距离不同；遇到“无响应”时，应先尝试旋转、平移、去除厚手机壳并贴紧，而不能直接判定为协议不兼容。旧版兼容身份的结果仅作历史记录，不验证当前版本；新版原身份流程与只读模板仍需对应版本的实测。

离开模拟前台会停用 HCE-F。Shizuku 模式重新进入“开始模拟”时仅复用已保存的精确身份，不重新注册；模块模式返回应用或重新开启 NFC 时会尝试注册当前配置。
如果在手机仍贴着读卡器时切换配置，Android 可能暂时拒绝更新 NFCID2 或 System Code。模块模式会先按未释放链路处理并自动重试约 10 秒；若厂商 NFC 栈在移开后仍锁定旧值，状态页会显示“重新注册”按钮，通过一次完整的 Activity 暂停/恢复重新建立前台 HCE-F 服务。

### 默认 NFC 应用检查

进入主界面时，应用会检查 AimeSimulator 是否为 Android 当前的默认 NFC 支付应用。首次得到可用结果时，应用会明确说明切换的影响和恢复方法；只有用户点击“现在选择”或状态页中的“设为默认 NFC 应用”后，才会打开 Android 的默认钱包或非接触式付款设置，由用户在列表中选择 AimeSimulator。选择“暂不更改”不会改变默认应用，提示也不会反复出现。

为参与系统默认 NFC 应用选择，APK 声明了一个支付类别的最小 HCE-A 辅助服务和固定 AID `F0010203040506`。该服务不承担 Aime/FeliCa 模拟，收到 APDU 时只返回“不支持的指令”；实际卡片模拟仍完全由 HCE-F 服务完成。

选择本应用会替换设备原有的默认 NFC 支付应用，可能影响手机钱包的碰一碰支付。AimeSimulator 成为默认应用后，状态页按钮会改为“恢复手机钱包”；该按钮只打开 Android 的默认应用、非接触式付款或 NFC 设置，最终恢复哪个钱包仍由用户在系统界面中确认。应用不会静默更改或记忆钱包选择。

### 原卡身份

应用只以当前配置的原 IDm 作为 NFCID2，System Code 为 `88B4`。Block `00/82` 的备份原样保留，Block `83` 使用原 IDm 与 PMm；失败时明确报告，不使用生成 IDm 或 `02FE` 前缀替换来绕过。

旧实验设置不再读取，也不再弹出兼容模式迁移提示；卡片内容、选中项和模拟方式保持。旧系统登记不会被升级自动改写，若它不匹配原 IDm，需重新配置。预置流程内部的 `4000 + 02FE000000000000` 合法初始化仍保留，仅用于缺少登记时建立占位记录，不是最终刷卡身份。具体模板边界见 [`docs/CARD_IMAGE.md`](docs/CARD_IMAGE.md)。

### 备份与恢复

“设置 → 卡片数据”提供导出和导入：

1. 导出会通过 Android 文件选择器创建版本化 JSON，包含全部配置、原 IDm、可选 Access Code、S_PAD0、ID 块、固定 System Code/PMm 元数据和 SHA-256 完整性校验。
2. 导入只接受当前支持的格式，并限制文件大小、配置数量、字段长度、重复配置 ID 和校验值；验证失败时不会修改本机数据。
3. “合并”保留本机配置，并按稳定配置 ID 更新同一条记录；“全部替换”会再次确认后删除本机现有配置。
4. 若导入改动了当前配置，应用会取消选中并停用 HCE-F，要求用户重新选择，避免导入后静默切换正在模拟的卡片。

备份文件是便于人工检查和跨设备迁移的明文 JSON，**没有加密**。请把它保存到私密位置，不要公开分享。应用不会自动上传或同步备份。

### 外观设置

“设置 → 外观”提供以下选项：

- **主题模式**：跟随系统、浅色或深色。默认跟随 Android 系统设置。
- **使用动态取色**：Android 12 及以上默认开启，颜色由系统根据壁纸生成；关闭后使用应用自带的 Material 3 配色。Android 11 及以下不支持动态取色，开关会显示为不可用。

外观选项会保存在本机，修改后立即应用，不影响卡片配置、HCE-F 状态或 PMm 补丁。

## 模拟协议行为

当前实现使用以下固定参数：

| 项目 | 值 |
| --- | --- |
| System Code | `88B4` |
| FeliCa Lite 只读服务 | `000B`（数据包中按小端序编码） |
| PMm 兼容值 | `00F1000000014300` |
| NFCID2 | 当前卡片的原 IDm |

PMm 通过 `host_nfcf_service.xml` 中的标准 `<t3tPmm-filter>` 静态声明。未声明该字段时，Android 默认使用 `FFFFFFFFFFFFFFFF`；本项目的 Root/Hook 补丁只负责兼容会忽略或覆盖标准声明的厂商 NFC 栈。

HCE-F 服务会：

- 校验帧长度后处理 Read Without Encryption（命令 `06`）。
- 返回命令 `07`、请求中的 NFCID2、两个零状态字节、块数量和对应块数据。
- 对 Write Without Encryption（命令 `08`）返回命令 `09` 与只读错误 `01/A8`，不再对未执行的写入返回成功。
- 对不支持的命令静默不响应。
- 忽略无法安全解析的畸形帧。

卡片映像按公开字段定义生成只读视图：保留实体卡备份的 `00/82`，构造 `83` 的射频 IDm + PMm、`85` 的 System Code 与 `88` 的只读配置。不再沿用旧模板的已发行 MC 数据、固定密钥版本或虚构认证块；不支持的块读取失败。未选卡时服务不响应。新映像尚待实机回归，不代表完整 FeliCa Lite-S 卡片或原卡发行数据的恢复。

更精确的行为约定见 [`docs/FUNCTIONAL_SPEC.md`](docs/FUNCTIONAL_SPEC.md)。

## PMm 状态说明

Android 15+ 的模块将状态保存在 `/data/adb/aimesim_pmm/`。设置页可能显示：

| 状态 | 含义 |
| --- | --- |
| `disabled` | 用户已关闭补丁 |
| `waiting` | 已请求启用，正在等待 NFC HAL |
| `injecting` | 正在向当前 HAL 进程注入 |
| `active` | Hook 已加载，运行时 PMm 开关已启用 |
| `error` | Root、文件、属性、符号解析或注入过程失败 |

`active` 表示 Hook 已在目标进程中工作，不等同于某个外部读卡器已经验证通过。最终输出仍建议使用实际读卡器确认。

## 常见问题

### 不使用 Root 能否工作

设备需要支持 HCE-F，并正确处理原 IDm、`88B4` 和 PMm。先检查设备能力；公开 API 拒绝原身份时，可评估 Shizuku 预置或模块路线，不再改换兼容 IDm。Root、LSPosed 和 KernelSU 不是配置管理、实体卡读取的前置条件；原身份模拟能否实现仍取决于具体设备和预置结果。

### 实体卡靠近后没有反应

- 确认 Android NFC 已开启，并将卡片移开后重新贴近手机天线位置。
- 确认应用停留在“读取卡片”页面；同一张卡持续贴住时 Android 通常不会重复触发发现回调。
- Amusement IC 和普通 FeliCa 需要 NFC-F；旧式 Aime 需要手机驱动提供 MIFARE Classic。
- 取下手机壳或避免同时贴放多张 NFC 卡，以排除耦合距离和冲突问题。

### 检测到 FeliCa，但块数据读取失败

应用尝试使用服务 `000B` 读取 Block `00` 与 `82`，组合读取失败时再单独重试；块缺失不会阻止保存 IDm。请保持卡片静止并重新贴卡；如果持续失败，应记录手机型号、Android 版本和 NFC 日志。并非所有 FeliCa 卡都提供相同服务或可公开读取的块。Access Code 不再从 SPAD0 自动解密，请手动填写卡面号码。

### 为什么旧式 Aime 识别后仍需填写 FeliCa IDm

旧式 Aime 是 MIFARE Classic 卡，只能提供其 UID 和 Access Code；AimeSimulator 的模拟端使用 Android HCE-F，需要 8 字节 FeliCa IDm。应用不会把 MIFARE UID 伪装成 IDm，因此保存前必须由用户提供对应的 FeliCa 配置。该读取流程目前只有代码和单元测试验证，尚未完成旧式 Aime 实卡验证。

### 手机检测到 NFC-A，但无法读取 MIFARE Classic

部分手机 NFC 控制器或厂商驱动不公开 Android `MifareClassic` 接口。这种限制无法通过应用层密钥或重试修复，可改用支持 MIFARE Classic 的设备读取。

### 系统提示“不支持 NFC-F 主机卡模拟”

设备必须由系统声明 `android.hardware.nfc.hcef`。拥有普通 NFC 或能够读取 FeliCa 卡，不代表设备一定支持 HCE-F；应用无法通过 Root 或 Hook 补出缺失的硬件/系统实现。

### PMm 开关不可用或状态检查失败

- Android 15+：确认 KernelSU 模块已经安装并在安装后重启过一次。
- 确认 Root 管理器已经向 AimeSimulator 授权。
- 确认模块路径为 `/data/adb/modules/aimesim_pmm/`。
- 检查是否有其他 NFC 模块同时修改相同 HAL 或系统属性。

### LSPosed 中没有“推荐应用”或系统框架选项

这是预期行为。AimeSimulator 使用静态作用域，唯一作用域是 `com.android.nfc`；不包含系统框架。

### 开关 PMm 后 NFC 暂时不可用

- Android 14 及以下路径会主动重启 NFC 服务，这是旧版 Hook 加载方式的一部分。
- Android 15+ 当前实现只切换运行时属性，不应因正常开关而重启 HAL 或 NFC 应用。若仍发生重启，应检查模块状态和系统日志。

### 关闭补丁后仍看到 `00F1000000014300`

关闭补丁后，Hook 会将新的 CORE_SET_CONFIG 数据包原样传递给 HAL。读卡端仍看到相同值时，可能是厂商默认值、尚未发生新的 NFC-F 配置、或读卡端缓存；这时应结合 HAL 日志和重新激活后的实测判断，而不是只看 Android 框架中的缓存字段。

## 调试

下面的命令需要 Android Platform Tools；涉及模块状态的命令还需要 Root：

```bash
adb logcat -s AimeSimulator AimePmmPatch
adb shell su -c '/data/adb/modules/aimesim_pmm/service.sh status'
adb shell getprop vendor.aimesim.pmm.enabled
adb shell getprop tmp.aimesim.pmm.enabled
adb shell dumpsys nfc
```

Android 15+ 中，开启补丁后日志应出现类似 `patched ST HAL CORE_SET_CONFIG PMm`；关闭后应出现 `PMm patch disabled; passing through ST HAL CORE_SET_CONFIG`。日志可能包含卡号、序列号和设备实现细节，提交问题前请先检查并移除敏感信息。

## 从源码构建

### 环境要求

- JDK 21 (Android 16 local unit tests require it; APK bytecode target remains 17)
- Gradle Wrapper 8.14.5（仓库已包含，无需安装系统 Gradle）
- Android SDK Platform 36 与 Build Tools 36.1.0
- Android NDK 25.1.8937393（arm64 工具链）
- CMake 3.22.1
- Python 3

项目使用 Android Gradle Plugin 8.13.2 与 Kotlin 2.3.21。请使用仓库中的 `gradlew` / `gradlew.bat`，避免系统 Gradle 版本不一致。

首次构建需要从 Google Maven 和 Maven Central 下载 AndroidX、Material Components、libxposed API 与 Dobby 等依赖。

### 构建与检查

Linux / macOS：

```bash
./gradlew clean testDebugUnitTest lintDebug assembleDebug
python3 tools/package_module.py
python3 tools/check_artifacts.py
```

Windows PowerShell：

```powershell
.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug
python tools\package_module.py
python tools\check_artifacts.py
```

主要生成文件：

- APK：`app/build/outputs/apk/debug/app-debug.apk`
- KernelSU 模块：`dist/aimesim-pmm-ksu-v3.zip`

`tools/check_artifacts.py` 会检查 APK 中的 libxposed API 101 元数据、静态作用域和 arm64 原生库，同时检查 KernelSU ZIP 的必要文件是否完整。

### GitHub Actions

仓库使用两套工作流：

- `Android CI`：在 `main` push 或手动触发时运行单元测试、lint、debug APK 构建、KernelSU 模块打包与产物检查。
- `Android Release`：在推送与 `versionName` 一致的 `v版本号` 标签时构建固定签名的 release APK，验证签名与 SHA-256，并创建或更新对应的 GitHub Release。

签名 Secrets、版本标签和密钥备份等维护者操作见 [`docs/RELEASE.md`](docs/RELEASE.md)。

## 项目结构

```text
app/
├─ src/main/java/.../data/   卡片配置与本地存储
├─ src/main/java/.../nfc/    HCE-F 会话、协议编解码和实体卡读取
├─ src/main/java/.../hook/   libxposed API 101 入口
├─ src/main/java/.../root/   PMm 状态与 Root 命令封装
├─ src/main/java/.../ui/     原生 Android 界面
└─ src/main/cpp/             PMm Hook 与 Android 15+ HAL 注入器
ksu-module/                  KernelSU 模块脚本和元数据
tools/                       诊断、模块打包与产物校验工具
docs/FUNCTIONAL_SPEC.md      可观察行为与协议约定
docs/PROVENANCE.md           实现来源对照与许可边界
docs/RELEASE.md              GitHub Actions 与签名发布流程
THIRD_PARTY_NOTICES.md       第三方依赖及其许可证
```

## 数据与隐私

- 卡片配置保存在应用私有目录的 `SharedPreferences` 中。
- Android 备份与数据提取已关闭。
- 默认使用普通模式，技术参数与诊断集中在设置中的“高级模式”；切换只改变显示，不修改卡片字段、选中项或模拟路线。手动添加仍需填写必需的 IDm；已有卡片和 NFC-F 读取结果在普通编辑器中保留隐藏字段。
- IDm（高级模式）与卡面 Access Code 有独立的列表/状态显示开关。主动打开“详情”会显示卡号，高级模式下再展开完整参数；显示隐藏不等于加密，导出始终包含完整数据。
- 用户主动导出的 JSON 位于用户选择的位置，不再受应用私有目录保护，且文件本身不加密；SHA-256 只用于发现意外损坏，不用于防止读取或恶意修改。
- 应用没有网络权限，不会主动上传卡片配置。
- Root、LSPosed 与 KernelSU 本身具有较高权限；请从可信来源安装并自行评估系统风险。

## 实现参考、版权与第三方组件

早期 AIC/SPAD0 解码参考了 [Project-HINATA/hinata_go](https://github.com/Project-HINATA/hinata_go/tree/c56d8badc3a720e0ba9e2f721f3f73111f2f6d97)；当前源码已删除相关解密器、替换表、解密测试与卡型指纹分支，保留按 NFC-F 技术读取原始块。旧卡片模板与固定兼容标识源于原版 [UmiSlat/AICEmu](https://github.com/UmiSlat/AICEmu/tree/5a1ecc28e98541e8226e1e92b07bd2747e607bb3)；当前使用公开字段定义下的只读构造，兼容标识及后续派生算法均已移除，仅使用原 IDm，见 [`docs/CARD_IMAGE.md`](docs/CARD_IMAGE.md)。历史提交与已发布 APK 不会因此改变，也未据此加入项目级许可证。逐文件核对结果见 [`docs/PROVENANCE.md`](docs/PROVENANCE.md)，第三方组件说明见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

Dobby、libxposed API、AndroidX、Material Components、Kotlin 和测试依赖分别遵循其自身许可证。

当前仓库未附带授予复制、修改或再发布项目自有代码权利的开源许可证；除第三方组件另有声明外，默认保留相应权利。

Aime 及相关名称和标识属于其各自权利人。本项目是非官方兼容性与研究工具，与相关厂商或运营方不存在隶属或背书关系。

## English summary

AimeSimulator is an Android HCE-F / FeliCa Lite profile manager and simulator. Version 2.2.5 is the current stable release. The legacy MIFARE reader path is implemented and unit-tested but still awaits verification with a physical legacy Aime card. See the Chinese sections above for requirements, limitations, implementation references, and safety notes.
