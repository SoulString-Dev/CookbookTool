# 菜谱本（CookbookTool）

Android 端的**纯本地**食谱工具：记食谱、记每天吃了什么。不联网，不申请网络权限，数据只在本机。

- 包名 `org.miu.cookbook`，minSdk 26（Android 8.0），targetSdk 35
- Kotlin + Jetpack Compose (Material 3) + Room
- 侧边栏两个主界面：**食谱**（默认）和**饮食历史**

## 一条核心设计原则：Markdown 不是数据库格式

需求里说食谱正文"以 markdown 形式存储展示"，但实现上反过来：

| | 存什么 | 为什么 |
|---|---|---|
| 数据库 | **结构化数据**：原料是独立表的行（名字/数量/量词/顺序），做法和备注是带 `level`（缩进层级）的有序列表项 | 拖动排序、行首空格调整缩进、句号转三级标题，这些操作在结构化模型上都是确定性的；直接改 Markdown 字符串则每次按键都要重新解析，且 CommonMark 的列表缩进规则会频繁和"两个空格一行"的直觉冲突 |
| Markdown | **只是序列化结果**，用于展示和导出 | 生成器有 bug 只会显示难看，不会损坏数据；而且未来做"食材清单/保质期"需要跨食谱查食材，文本里查不了 |

## 构建

本机 `$HOME` 只读、且没有 JDK/SDK，所以工具链装在工作区 `.toolchain/`（已在 .gitignore）。

```bash
bash scripts/setup_toolchain.sh   # 首次：下载 JDK 21 + Android SDK 35 + Gradle 8.9，约 2GB
bash scripts/build.sh             # 默认 assembleDebug
bash scripts/build.sh test        # JVM 单测
```

产出的 APK：`app/build/outputs/apk/debug/app-debug.apk`

用 Android Studio 打开也可以，但需要本机有 JDK 17+ 与 Android SDK 35，
并让 Gradle 能写 `$HOME`（或设 `GRADLE_USER_HOME`）。

## 数据模型要点

- `recipe` — 菜名、创建/更新时间（H1 菜名不存进正文，只在序列化时生成）
- `content_block` — 做法/备注的行，`section`（DIRECTIONS/NOTE）+ `kind`（有序列表项/三级标题）+ `level`（缩进层级）
- `ingredient` — 原料名 + 食材数（文本，允许"适量"）+ 量词（可空）
- `category` / `recipe_category` — 分类是 tag，多对多
- `recipe_image` — 相对 `filesDir` 的路径 + 缩略图 + 顺序 + 是否列表封面
- `meal_log` — `epoch_day` + 餐段（早/午/晚）+ 食谱；查"每道菜最后一次被吃"用于未来的加权随机

餐段判定（`MealTime`）：02:00–10:29 早餐，10:30–15:29 午餐，其余晚餐；
其中 00:00–01:59 算作**前一天**的晚餐。

删除食谱会连带删掉它的原料、做法、图片和饮食记录（`ON DELETE CASCADE`，删除前会提示影响条数）。

## 进度

- [x] **M0** 工程骨架：Gradle/Compose/Room 空库、侧边栏、两个主界面空状态、餐段规则与单测
- [ ] **M1** 食谱列表、模糊搜索、长按菜单、批量多选、新增/编辑
- [ ] **M2** 详情页与文本编辑（原料拖动、做法缩进、分类 tag、Markdown 序列化器）
- [ ] **M3** 图片：拍照/相册、排序、封面、黑底预览
- [ ] **M4** 饮食历史：记录流程、列表、编辑、整餐交换、批量删除
- [ ] **M5** 导出导入备份、打磨、出正式包
