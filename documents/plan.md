# 类今日头条安卓 Demo 开发计划

## 1. 项目目标

本项目目标是开发一个类似“今日头条首页”的 Android Demo，通过一个新闻信息流列表页面，练习 Android 开发的基础能力、工程组织能力、Git 使用能力和技术文档沉淀能力。

项目不追求完整复刻今日头条，而是聚焦首页核心体验：

- 新闻数据加载
- 新闻卡片展示
- 列表滚动浏览
- 加载中、失败、空数据等状态展示
- 可选实现下拉刷新、加载更多、本地数据库缓存

完成后，项目应具备以下交付物：

- 可运行的 Android Demo
- 清晰的 Git 提交记录
- README 使用说明
- 开发日志
- 简单架构说明文档
- 关键功能截图或录屏

## 2. 学习与技术目标

### 2.1 工具使用

- 熟悉 Android Studio 的基本使用
- 掌握 Git 的基础命令和提交习惯
- 了解 GitHub 仓库管理、远程推送和项目展示方式

### 2.2 Android 开发技能

- 使用 Kotlin 编写业务代码
- 使用 Jetpack Compose 构建声明式 UI
- 理解 ViewModel 的作用
- 使用 Kotlin Coroutine 处理异步数据加载
- 理解 Repository 的职责
- 可选学习 Room 数据库存储

### 2.3 工程与架构素养

- 了解基础 MVVM 架构
- 理解 UI State 的概念
- 将 UI、状态、数据源进行简单分层
- 形成边开发边记录的习惯
- 通过文档说明设计思路、问题和解决过程

### 2.4 业务理解

通过新闻首页列表，理解内容类 App 的基础业务流程：

1. 用户进入首页
2. 页面请求新闻数据
3. 展示新闻卡片列表
4. 用户滚动浏览内容
5. 用户触发刷新或加载更多
6. 页面根据加载结果更新 UI 状态

## 3. 推荐技术方案

### 3.1 技术栈

- 开发语言：Kotlin
- UI 框架：Jetpack Compose
- 架构模式：MVVM
- 异步处理：Kotlin Coroutine
- 状态管理：StateFlow 或 Compose State
- 图片加载：Coil
- 本地数据库：Room，可选
- 版本管理：Git + GitHub

### 3.2 初期实现原则

作为初学者，开发顺序应遵循“先跑通，再优化”的原则：

1. 先用本地假数据完成 UI
2. 再抽离数据模型和数据仓库
3. 再加入 ViewModel 和加载状态
4. 最后考虑刷新、加载更多和数据库缓存

不要一开始就追求复杂架构、真实接口、完整商业功能，否则容易被工程细节卡住。

## 4. 功能拆解

### 4.1 基础功能，必须完成

- 创建 Android 项目并成功运行
- 首页新闻列表页面
- 新闻卡片组件
- 新闻数据模型
- 模拟数据加载
- 加载中状态
- 加载失败状态
- 空数据状态
- README 文档
- Git 提交记录

### 4.2 进阶功能，优先尝试

- 顶部频道 Tab，例如推荐、热点、科技、娱乐
- 下拉刷新
- 上拉加载更多
- 新闻封面图展示
- 点击新闻卡片进入简单详情页
- ViewModel + Repository 分层

### 4.3 加分功能，有时间再做

- Room 本地缓存
- 首次加载网络或模拟数据，之后读取缓存
- 更完整的错误重试逻辑
- 更细致的架构说明
- 单元测试或简单 UI 测试
- GitHub 项目展示截图

## 5. 建议工程结构

初期可以采用简单清晰的结构：

```text
app/
  src/main/java/.../topnews/
    MainActivity.kt
    data/
      NewsItem.kt
      NewsRepository.kt
      FakeNewsDataSource.kt
    ui/
      NewsHomeScreen.kt
      NewsCard.kt
      NewsDetailScreen.kt
      theme/
    viewmodel/
      NewsViewModel.kt
      NewsUiState.kt
docs/
  development-log.md
  architecture.md
  git-guide.md
README.md
plan.md
```

如果后期功能变多，再考虑继续细分模块。初学阶段不要过度拆分。

## 6. 三周开发计划

### 第 1 周：环境、基础知识和首页静态列表

目标：完成项目初始化，掌握最基本的 Android Studio、Kotlin 和 Compose 开发流程，做出可以运行的新闻首页静态列表。

#### Day 1：环境准备与项目初始化

- 安装并配置 Android Studio
- 创建 Kotlin + Jetpack Compose Android 项目
- 成功运行默认 Demo
- 初始化 Git 仓库
- 创建 GitHub 远程仓库，可选
- 新增 `README.md`、`docs/development-log.md`

建议提交：

```bash
git commit -m "init android compose project"
git commit -m "docs: add initial project documents"
```

#### Day 2：Kotlin 和 Compose 入门

- 学习 Kotlin 基础语法：变量、函数、data class、List
- 学习 Compose 基础组件：Column、Row、Text、Image、LazyColumn
- 在首页显示简单文本和列表
- 在开发日志中记录学习内容和问题

建议提交：

```bash
git commit -m "add basic compose home screen"
```

#### Day 3：定义新闻数据模型

- 创建 `NewsItem` 数据类
- 准备一组本地假新闻数据
- 展示新闻标题、来源、发布时间
- 初步完成新闻列表

建议提交：

```bash
git commit -m "add news model and fake data"
```

#### Day 4：新闻卡片组件

- 封装 `NewsCard` 组件
- 展示标题、摘要、来源、时间、封面图占位
- 优化卡片间距、字体层级和点击反馈

建议提交：

```bash
git commit -m "add news card component"
```

#### Day 5：首页列表完善

- 使用 `LazyColumn` 展示新闻卡片
- 添加顶部标题或频道栏雏形
- 处理列表边距、滚动体验
- 整理当前代码结构

建议提交：

```bash
git commit -m "build news feed list page"
```

#### Day 6-7：复盘与文档

- 整理第 1 周学习笔记
- 更新 `README.md` 的项目介绍和运行方式
- 在 `docs/development-log.md` 记录遇到的问题
- 截图保存首页效果

建议提交：

```bash
git commit -m "docs: update week 1 development log"
```

第 1 周验收标准：

- App 可以运行
- 首页可以看到新闻列表
- 至少有 5 条新闻数据
- 有清晰的 Git 提交记录
- 有基础 README 和开发日志

### 第 2 周：状态管理、架构分层和交互能力

目标：从“静态页面”升级为“有数据加载流程的页面”，引入 ViewModel、Repository、UiState、加载状态、失败状态等概念。

#### Day 8：理解 MVVM 和 UI State

- 学习 MVVM 中 Model、View、ViewModel 的职责
- 设计 `NewsUiState`
- 区分 Loading、Success、Error、Empty 状态
- 在 `docs/architecture.md` 画出简单数据流

建议提交：

```bash
git commit -m "docs: add initial architecture notes"
```

#### Day 9：加入 ViewModel

- 创建 `NewsViewModel`
- 将新闻列表状态从 UI 移到 ViewModel
- 页面通过状态渲染新闻列表
- 保持 UI 组件尽量只负责展示

建议提交：

```bash
git commit -m "add news viewmodel and ui state"
```

#### Day 10：加入 Repository

- 创建 `NewsRepository`
- 将假数据读取逻辑从 ViewModel 移到 Repository
- ViewModel 调用 Repository 获取数据
- 初步形成 UI -> ViewModel -> Repository -> DataSource 的结构

建议提交：

```bash
git commit -m "add repository for news data"
```

#### Day 11：模拟异步加载

- 使用 Coroutine 模拟延迟加载
- 页面启动时显示 Loading
- 加载完成后显示新闻列表
- 在开发日志中记录协程的理解

建议提交：

```bash
git commit -m "add coroutine based news loading"
```

#### Day 12：错误状态和空状态

- 增加加载失败 UI
- 增加重试按钮
- 增加空数据 UI
- 可以通过临时开关模拟失败和空数据

建议提交：

```bash
git commit -m "add loading error and empty states"
```

#### Day 13：频道 Tab 和简单交互

- 添加顶部频道 Tab
- 切换频道后刷新列表数据
- 不同频道可以使用不同假数据
- 记录频道切换的数据流

建议提交：

```bash
git commit -m "add channel tabs for news feed"
```

#### Day 14：第 2 周整理

- 更新 `docs/architecture.md`
- 更新 `README.md` 功能说明
- 整理 Git 提交历史，确认每次提交含义清楚
- 截图保存 Loading、列表、错误、空状态

建议提交：

```bash
git commit -m "docs: update week 2 architecture and progress"
```

第 2 周验收标准：

- 页面具备 Loading、Success、Error、Empty 状态
- 使用 ViewModel 管理页面状态
- 使用 Repository 管理数据来源
- 有频道切换能力
- 文档能说明基本架构

### 第 3 周：刷新、加载更多、缓存与项目收尾

目标：补齐新闻类 App 常见体验，完善文档与展示材料，使项目成为一个可以提交、可以讲清楚的 Demo。

#### Day 15：下拉刷新

- 学习 Compose 下拉刷新能力
- 为首页加入下拉刷新
- 刷新时更新页面状态
- 避免刷新过程中重复请求

建议提交：

```bash
git commit -m "add pull to refresh for news feed"
```

#### Day 16：加载更多

- 监听列表滚动到底部
- 触发加载更多
- 在列表底部展示加载中提示
- 处理没有更多数据的状态

建议提交：

```bash
git commit -m "add load more for news feed"
```

#### Day 17：新闻详情页，可选但推荐

- 点击新闻卡片进入详情页
- 详情页展示标题、来源、时间、正文
- 学习简单页面跳转
- 如果时间紧，可以只做弹窗或简单详情页面

建议提交：

```bash
git commit -m "add simple news detail page"
```

#### Day 18：Room 数据库缓存，可选加分

- 学习 Room 的 Entity、Dao、Database
- 将新闻列表保存到本地数据库
- 页面启动时可先读取缓存
- 如果 Room 难度过高，可以只写学习记录，不强行完成

建议提交：

```bash
git commit -m "add room cache for news list"
```

#### Day 19：代码整理和体验优化

- 删除临时代码
- 统一命名风格
- 优化 UI 细节
- 检查异常情况
- 确认 App 可以从零构建运行

建议提交：

```bash
git commit -m "refactor news feed code and polish ui"
```

#### Day 20：文档完善

- 完善 `README.md`
- 补充运行环境、功能列表、截图
- 完善 `docs/architecture.md`
- 完善 `docs/development-log.md`
- 新增或完善 `docs/git-guide.md`

建议提交：

```bash
git commit -m "docs: complete project documentation"
```

#### Day 21：最终检查和项目总结

- 从 GitHub 拉取或重新打开项目验证运行
- 检查 README 是否能指导别人运行项目
- 检查提交记录是否能体现开发过程
- 写项目总结：完成了什么、遇到什么问题、还能怎么改进
- 准备最终展示截图或录屏

建议提交：

```bash
git commit -m "docs: add final project summary"
```

第 3 周验收标准：

- 支持下拉刷新
- 支持加载更多
- 至少完成一个加分功能
- 文档基本完整
- Git 记录清楚
- 项目可以完整展示和讲解

## 7. Git 使用规范

### 7.1 基础流程

每天开发前：

```bash
git status
git pull
```

开发过程中：

```bash
git status
git add .
git commit -m "清晰描述本次改动"
```

推送到 GitHub：

```bash
git push
```

### 7.2 提交信息建议

提交信息尽量使用英文短句，格式可以参考：

```text
init android compose project
add news card component
add news viewmodel and ui state
add pull to refresh for news feed
docs: update development log
fix news loading error state
refactor news repository
```

### 7.3 提交原则

- 一个提交只做一类事情
- 不要把一天所有内容混成一个巨大提交
- 文档改动可以使用 `docs:` 前缀
- 修复问题可以使用 `fix:` 前缀
- 重构可以使用 `refactor:` 前缀

## 8. 文档记录规范

### 8.1 README.md

建议包含：

- 项目名称
- 项目简介
- 技术栈
- 功能列表
- 运行方式
- 项目截图
- 项目结构
- 开发总结

### 8.2 docs/development-log.md

每天记录：

```text
日期：
今日目标：
完成内容：
遇到问题：
解决方式：
明日计划：
相关提交：
```

### 8.3 docs/architecture.md

建议说明：

- 为什么使用 MVVM
- UI、ViewModel、Repository 分别负责什么
- 页面状态如何流转
- 数据加载流程
- 后续可以如何优化

### 8.4 docs/git-guide.md

建议记录：

- 常用 Git 命令
- 本项目提交规范
- 如何查看提交历史
- 如何推送到 GitHub
- 遇到冲突或错误时如何处理

## 9. 学习优先级

如果时间不够，优先级如下：

1. App 能跑起来
2. 首页列表能展示新闻卡片
3. 有 ViewModel 和 Repository
4. 有 Loading、Error、Empty 状态
5. 有 Git 提交记录
6. 有 README 和开发日志
7. 有下拉刷新和加载更多
8. 有 Room 缓存
9. 有测试和更复杂架构

## 10. 最终交付检查清单

- [ ] Android 项目可以正常运行
- [ ] 首页新闻列表可展示
- [ ] 新闻卡片信息完整
- [ ] 有加载状态
- [ ] 有错误状态
- [ ] 有空状态
- [ ] 有频道切换
- [ ] 有下拉刷新
- [ ] 有加载更多
- [ ] 有清晰项目结构
- [ ] 有 README
- [ ] 有开发日志
- [ ] 有架构说明
- [ ] 有 Git 使用记录
- [ ] 有 GitHub 仓库，可选
- [ ] 有截图或录屏

## 11. 项目完成后的讲解思路

最终展示项目时，可以按下面顺序讲：

1. 这个 Demo 解决什么问题
2. 首页信息流有哪些功能
3. 使用了哪些技术栈
4. 项目代码结构如何组织
5. 数据从哪里来，如何显示到页面上
6. ViewModel 如何管理状态
7. 加载、失败、空数据如何处理
8. Git 和文档如何记录开发过程
9. 开发中遇到的问题和解决方式
10. 如果继续优化，下一步会做什么
