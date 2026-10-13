# AimeSimulator 发布流程

本文供仓库维护者使用，记录 GitHub Actions、APK 固定签名与版本发布步骤。普通安装和使用说明见项目根目录的 [`README.md`](../README.md)。

## 工作流

- `Android CI`：在 `main` push、面向主线的 PR 或手动触发时运行 Android 与诊断工具测试、Lint、Debug 构建、KernelSU 模块打包与产物检查。在 `main` 上推送或手动运行时，额外使用下列正式签名 Secrets 构建 Release APK；PR 和其他分支只生成临时 debug 签名 APK。
- `Android Release`：在推送与 `versionName` 一致的 `v版本号` 标签时构建 release APK，验证 APK 签名与 SHA-256，并创建或更新对应的 GitHub Release。发布资源包含 APK、可选 KernelSU 补丁及各自的校验文件。

主线 CI 的签名 APK 带 `-ci.<运行编号>.<提交短哈希>` 后缀，仅作为 Actions Artifact 供测试，不创建或更新 GitHub Release。产物同时包含 SHA-256、签名证书信息和构建元数据。正式分发仍由 `Android Release` 标签流程完成；临时签名文件在 CI 签名构建后清理。

## Repository Secrets

在仓库 `Settings > Secrets and variables > Actions` 中配置：

| Secret | 内容 |
| --- | --- |
| `ANDROID_SIGNING_KEY` | JKS/PKCS12 签名库文件的 Base64 内容 |
| `ANDROID_KEY_STORE_PASSWORD` | 签名库密码 |
| `ANDROID_KEY_ALIAS` | 签名密钥别名 |
| `ANDROID_KEY_PASSWORD` | 签名密钥密码 |

签名库必须在 GitHub 之外安全备份。丢失签名密钥或密码后，将无法为现有安装生成可直接覆盖升级的 APK。不要将签名库、密码或 Secrets 写入仓库、构建日志和问题报告。

PowerShell 可使用以下命令编码签名库：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("aimesimulator-release.p12")) | Set-Clipboard
```

## 创建版本

1. 更新 `app/build.gradle` 中的 `versionCode` 与 `versionName`。
2. 完成本地单元测试与 Lint；APK 构建、模块打包和产物检查可交由主线 CI 完成。
3. 提交并推送 `main`，确认 `Android CI` 成功。
4. 在 `docs/releases/版本号.md` 写用户发布说明。工作流优先使用该文件，没有时才生成提交说明。
5. 创建与 `versionName` 完全一致的新标签，例如：

```bash
git tag -a v3.0.1 -m "AimeSimulator 3.0.1"
git push origin v3.0.1
```

工作流会拒绝版本标签不匹配、签名 Secrets 缺失或 APK 签名校验失败的发布。发布完成后，应从 GitHub Release 重新下载 APK，并核对工作流生成的 SHA-256 与签名证书。

3.0.1 使用版本码 51，作为首个正式版本；3.0.0 / 50 的预发布保留原状。不要移动或重新使用已推送的版本标签。
