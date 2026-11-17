# 德谬歌矩阵 · Demiurge Matrix

一个自己用的安卓 AI 聊天客户端 —— 沿用 DeepSeek 的界面与手感，但把限制拿掉。

> 名字来自「德谬歌」（Demiurge）。它记得你所有的事。

## 它和官方客户端有什么不同

| | 官方 App | 德谬歌矩阵 |
|---|---|---|
| 重新生成 | 次数受限 | 无限，旧回答自动进分支 |
| 全局记忆 | 无 | 四层记忆，跨所有对话 |
| 知识库 | 无 | AI 自动归类 + 记忆权重 |
| 对话树 | 无 | 完整分支 + 树状可视化 |
| 数据 | 在云端 | 全本地，可导出、可备份 |
| API | 官方账号 | 自己的 Key |

## 功能

**聊天**
- 流式输出、深度思考（思维链可折叠）
- LaTeX 公式渲染（行内 + 块级）
- 图片输入（相册选图，自动压缩）
- 文档解析（PDF / Word / PPT / Excel，走 Files API）
- 联网搜索，回答附带引用来源

**分支**
- 重新生成、编辑后重发，都会长出新分支
- `‹ 1/2 ›` 切换，Canvas 画出的树状图

**知识库**
- 对话按主题自动归库，一键整理全部
- 每个库有记忆权重（0–100），可手动调
- 手动指定的归类会被锁定，不再被自动整理改动

**总 AI（Demiurge）**
四层记忆，越往下越按需：

| 层 | 内容 | 是否常驻 |
|---|---|---|
| L0 | 用户档案：你是什么人 | 常驻，最优先 |
| L1 | 它自己那些长谈的压缩记忆 | 常驻，权重 75 |
| L2 | 各知识库的会话摘要 | 常驻，按库权重给篇幅 |
| L3 | 原文 | 按问题临时检索 |

**上下文管理**
- 查看任意对话的 token 占用
- 手动压缩前半段，压成摘要保留

**数据**
- 导出单条 / 全部对话为 Markdown
- 完整备份（数据库 + 图片打包成 zip）

## 技术栈

```
Kotlin 2.2.21 · Jetpack Compose (BOM 2025.06.01)
AGP 8.13.2 · Gradle 8.14.3 · minSdk 26 / targetSdk 36
SQLite（手写 SQLiteOpenHelper，schema v1 → v9）
OkHttp 4.12 · jlatexmath-android
```

没有 Room，没有 KSP。数据库迁移全是 `ALTER TABLE`，从第一版到第九版没丢过数据。

## 构建

需要 JDK 17+ 和 Android SDK 36。

```bash
# local.properties 里写自己的 SDK 路径
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew assembleDebug
```

release 需要自己的签名。把 keystore 放好，改 `app/build.gradle.kts` 里的 `signingConfigs.release`：

```kotlin
signingConfigs {
    create("release") {
        storeFile = File(rootProject.rootDir, "your-release.jks")
        storePassword = "..."
        keyAlias = "..."
        keyPassword = "..."
    }
}
```

## 使用前

1. 在 [platform.deepseek.com](https://platform.deepseek.com) 拿一个 API Key
2. App 里 设置 → 接口 → 粘贴

Key 用 Android Keystore 加密存储，不会以明文落盘。

## 成本

日常使用大概 **每月 5–10 元**（按每天 30 轮估算）。文档解析 1.5 元 / 1000 页，联网搜索 5 元 / 1000 次查询。

## 验证签名

安装包用 `Cyrecho` 证书签名。如果你拿到一个「德谬歌矩阵」，想确认它没被改过，可以比对证书指纹：

```
SHA-256:
6342cff8bdd885cdf64f5239162601a7ab10a5026f48fe8c370052f41bb01a5f

SHA-1:
5bf494449494b1772ef08bef6fdda4e999e5a783

DN:
CN=Cyrecho, O=Cyrecho, C=CN
```

两种比对方式：

**在手机上**：设置 → 页面最底部，会显示当前包的签名前缀，
以及「✓ 与官方签名一致」或「⚠ 签名和官方不一致」。

**在电脑上**：

```bash
apksigner verify --print-certs 德谬歌矩阵.apk
```

这个仓库里的这一串是**独立于 APK 本身的基准**——改包的人可以改掉 App 里显示的文字，但改不了这里的记录。两者对不上，就说明那个包不是这里构建出来的。

## 说明

- 个人自用项目，非官方客户端
- 界面配色与图标参考了 DeepSeek 官方 App，仅作学习用途
- 数据全部存在本地，没有后端服务器
- 签名私钥和密码都不在仓库里（`.jks` 已 gitignore，密码在 `local.properties`）

## License

MIT
