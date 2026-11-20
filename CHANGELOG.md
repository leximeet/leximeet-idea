<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# leximeet-idea Changelog

## [Unreleased]

## [0.0.1]

### 新增

- 从 IDEA 底部词遇工具窗发现并只读连接 Desktop，支持会话恢复和双方断开。
- 打开工具窗后自动异步匹配词汇，支持主动刷新和收起时暂停。覆盖项目树、编辑器、JAR 内文本及 IDEA 反编译文档。
- 词表展示学习状态、标签、位置和次数，支持组合搜索、筛选、表头排序和重置。
- 点选词条后在右侧查看详细词卡，定位到源码或项目树。重复点击同一个词可切换到下一处，也可点击卡内出处直接跳转。
- 增加 IDEA 原生设置。持续高亮默认关闭，主动定位短暂高亮后自动清除；开启持续高亮后可悬浮查看读词提示。
- 增加 Spotless、Kotlin 单元测试、Socket 集成测试、原生 UI 自动化及真实桌面端联调。本地优先复用已安装的 IDEA。
- 版本标签触发完整验收，验收通过后签名、验证 ZIP 内容并生成 GitHub Release 草稿；Marketplace 首发由维护者在网页提交。
- 提供浅色与深色 Marketplace 矢量标志；发布校验会拦截嵌入位图的图标。
- CI 通过 Xvfb 运行 IDEA Community 的真实 Swing 界面及 Desktop/Core 联调，保留隔离日志和超时线程诊断。
- CI 的新建测试资料使用 JetBrains 官方 UI 示例的协议测试配置，支持无人值守启动。
- Linux 联调通过 Electron 捕获隐藏的桌面窗口，保留连接、窄窗、断线恢复和撤销授权的界面断言。

### 许可

- 自有代码与文档采用 AGPL-3.0-only，保留 JetBrains 模板及 Gradle 的原 Apache-2.0 许可。
- 项目使用 [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template) 初始化。
