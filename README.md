<div align="center">

# 词遇 · IntelliJ IDEA 插件

**在代码阅读中遇见单词。**

[GitHub](https://github.com/leximeet/leximeet-idea) · [Gitee](https://gitee.com/leximeet/leximeet-idea)

[开发与本地验收](docs/开发指南.md) · [发布指南](docs/发布指南.md) · [架构](docs/架构与接入准备.md) · [项目路线](docs/项目与路线.md) · [AGPL-3.0-only](LICENSE)

</div>

词遇从 IDEA 底部工具窗连接桌面端，在项目树和打开的代码中自动匹配词汇。底部左侧可以搜索、筛选和定位，点选单词后在右侧查看词卡与可跳转的项目出处。

1. 启动 [词遇 Desktop 1.1.0](https://github.com/leximeet/leximeet-desktop/releases/tag/1.1.0) 或更高版本。在 IDEA 打开 **视图 → 工具窗口 → 词遇**。
2. 点击 **连接桌面端**，在工具窗确认只读权限。也可以从桌面 **设置 → 插件连接** 发起邀请。
3. 连接后词表自动加载。切换文件、滚动或展开目录会在后台更新，点击 **刷新** 可以主动重查。收起工具窗会暂停匹配，再打开会重新加载。
4. 词表展示单词、学习状态、标签、首处位置和次数。搜索支持单词和出处，状态与标签可以组合筛选。点击列名切换排序，**重置** 会清空筛选并恢复次数降序。
5. 点击一行或选中后按 **Enter** 打开右侧词卡并定位，再点同一个词可跳到下一处。词卡的 **项目出处** 可以直接定位。**持续高亮**默认关闭，定位目标仍高亮约两秒，可从工具窗顶部或 **设置 → 工具 → 词遇** 开启持续高亮。

匹配来源为桌面当前学习目标和已收藏词条。范围覆盖项目树可见节点和打开的编辑器附近，包括项目、目录、文件、库、JAR 内文本与 IDEA 反编译文档。打开新文件、展开或滚动项目树会刷新结果。匹配只通过本机通道提交候选词。主动定位会移动光标或选中项目树节点，保持源文件原样。

右侧词卡保留音标、释义和学习状态，并展示桌面资料已有的词形、例句及个人笔记。详情区可独立滚动，也可拖高底部工具窗查看更多内容。悬浮在标记词上仍可快速看简版词卡。

插件支持只读遇见和查词。本机连接使用 macOS / Linux 的 Unix Domain Socket；Desktop 的 Windows 安装包当前不提供 IDEA 连接。可按[发布指南](docs/发布指南.md)校验下载文件并从磁盘安装 ZIP，通过[开发与本地验收](docs/开发指南.md)在隔离资料中测试连接。

GitHub 与 Gitee 是平级的源码和贡献入口。任一平台都可提交问题或合并请求，已有讨论时附原链接。

## 许可与致谢

词遇自有代码采用 [AGPL-3.0-only](LICENSE)。工具窗图标来自词遇品牌物料，释义与音标由 [LexiMeet Dictionary](https://github.com/leximeet/leximeet-dictionary) 经 Desktop 提供。感谢 [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)、[Gradle](https://gradle.org/) 和 [Spotless](https://github.com/diffplug/spotless)。原 Apache-2.0 声明见 `THIRD_PARTY_LICENSES/`，详见[许可范围](docs/许可证.md)。
