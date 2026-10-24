# 轻记账

一款离线使用的安卓记账本，支持本地保存和文件夹同步。

## 一、使用指南

### 基本功能

- **日常记账**：记录支出、收入和账户间转账，支持两级分类、备注和内置计算器。
- **账户与账本**：管理多个账户和账本，查看余额；支持活期、定期理财计息及定时转账。
- **明细与统计**：按日期、账户、分类查询，查看收支饼图和余额走势。
- **预算管理**：设置每月总预算和分类预算，通过颜色进度条了解使用情况。
- **个性设置**：提供六种淡色主题。

### 下载与开始使用

适用于 **Android 8.0 及以上版本**。

1. 打开 [下载页面](https://github.com/Shisi141414/LightAccountBook/releases/latest)，下载附件中的 `.apk` 文件并安装。若系统询问，允许本次安装来源。
2. 首次打开时选择一个本地文件夹。应用会复用其中已有的账本目录；没有时创建 `LightAccountBook`。
3. 在“账户”页设置账户与初始余额，即可在首页记账。分类、主题和账本可在“设置”中管理。

### 保存、恢复与同步

- **保存**：账目、账户、分类和预算会写入所选文件夹，请保留整个账本文件夹。
- **恢复**：卸载重装或更换手机后，重新选择原账本文件夹，即可读取已有数据。
- **跨设备同步**：可用 FolderSync 将账本文件夹与坚果云双向同步，具体见 [配置教程](https://help.jianguoyun.com/?p=2887)。其他设备先完成文件同步，再打开应用或在设置中点“立即刷新”。

请勿手动删除账本目录中的 JSON 文件。文件为明文，共享文件夹意味着共享其中的账目。定时转账和利息入账可能受后台限制而延迟，打开应用时会补执行。

## 二、二次开发

### 技术与环境

- Kotlin、Jetpack Compose、Room、WorkManager。
- JDK 17、Android SDK Platform 36；项目自带 Gradle Wrapper。
- 最低支持 Android 8.0（API 26），目标 Android 16（API 36）。

### 构建与检查

克隆项目并配置 Android SDK 后，在项目根目录运行：

```powershell
# 构建调试 APK
.\gradlew.bat :app:assembleDebug

# 单元测试与静态检查
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。正式分发需自行配置签名；更新已有安装时须保持签名一致。Windows 下若中文路径导致测试失败，可将项目映射到英文盘符后构建。

### 代码入口

源码位于 `app/src/main/java/com/codex/pocketledger/`：

| 文件 | 主要职责 |
| --- | --- |
| `MainActivity.kt`、`Ledger*Screen.kt` | 页面与交互 |
| `LedgerDomain.kt` | 账户、分类、预算及金额计算 |
| `LedgerData.kt`、`LedgerRepository.kt` | 本地存储、文件同步与冲突处理 |
| `LedgerAutomation.kt`、`LedgerWorker.kt` | 定时转账与利息入账 |
| `CalculatorMath.kt`、`SummaryAmountFormat.kt` | 算式计算与摘要金额显示 |

### 修改数据逻辑前需了解

- 金额以整数“分”存储；摘要显示的位数调整不改变原始精度。转账与余额校准不计入收支。
- 共享数据位于 `ledger-v1/`，新增账本位于其下的 `book-<账本 ID>/`。每次新增、编辑或删除都写入新的不可变操作文件，删除使用标记，不能直接移除旧文件。
- 多设备通过合并操作记录同步；并发编辑可能产生冲突，由用户在设置中选择版本。修改文件格式时需兼容旧记录。
- 理财利息按每日正余额和年化利率计算；已入账利息不会因历史账目变更而自动重算。自动任务需保持跨设备去重。

现有验证结果见 [v0.7.1 验证记录](验证记录-v0.7.1.md)。
