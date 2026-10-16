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

产出的 APK：

- 调试包 `app/build/outputs/apk/debug/app-debug.apk`（包名带 `.debug`，可以和正式包并存）
- 正式包 `app/build/outputs/apk/release/app-release.apk`（`bash scripts/build.sh assembleRelease`）

正式包的签名口令放在工作区的 `keystore.properties`（和 `keystore/` 一起 gitignore）。
文件不在时会退回 debug 签名，这样别人 clone 下来也能直接 `assembleRelease` 出包装机。
**故意没开 R8**：kotlinx-serialization 和 Room 的 keep 规则没有真机验证过，
压缩出问题只有运行时才发现，等真机跑通一轮再开。

用 Android Studio 打开也可以，但需要本机有 JDK 17+ 与 Android SDK 35，
并让 Gradle 能写 `$HOME`（或设 `GRADLE_USER_HOME`）。

## 装到手机上

```bash
adb install -r app/build/outputs/apk/release/app-release.apk     # 正式包，包名 org.miu.cookbook
adb install -r app/build/outputs/apk/debug/app-debug.apk         # 调试包，包名带 .debug，可与正式包并存
```

也可以把 APK 拷进手机点开安装（需要在系统里允许"安装未知来源应用"）。
不需要任何权限：没有 INTERNET，也没有存储/相机权限（拍照走系统相机、选图走 Photo Picker）。
数据全在应用私有目录里，卸载即丢——所以换机前记得先用顶栏「⋮」导出备份。

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

搜索（`FuzzySearch`）全在内存里做，不走 SQL：菜名先比前缀、再比子串，然后是**逐字子序列**
（输"番炒"能命中"番茄炒蛋"），最后是**拼音首字母子序列**（输"fqcd"也能命中）；
分类、原料也参与匹配但排在菜名后面。拼音表由 `scripts/gen_pinyin_table.py` 从 Unihan 生成，
多音字（如"茄" jiā/qié）两个声母都认，所以 fqcd 和 fjcd 都搜得到。

## 编辑行为的一处约定：正文只在保存时落地

编辑原料、做法、备注时，界面自己攒一份草稿（`IngredientEditScreen` / `ContentEditScreen`），
点「完成」才整份写回（`replaceIngredients` / `replaceContent`：先删后插，走一个事务）。
所以：

- 拖动排序、行首空格缩进、句号转三级标题，全是对草稿里的小数据做确定性变换，不碰数据库；
- 中途按返回 = 放弃改动，不会留下半截数据；
- 空行在存库前会被丢掉（结构化模型里它没有意义，Markdown 解析时也一样）。

Markdown 的读写都在 `RecipeMarkdown` 里（`serialize` / `parse` 互为逆运算，有 round-trip 单测）。
详情页右上角的「Markdown」按钮可以把当前这道菜导出成 Markdown 并复制走。

## 图片：一律复制进私有目录

`ImageStore` 把选中的图（拍照得到的临时文件、相册给的 content Uri 都走同一条流）解码后
**压到长边 2048 再写进 `filesDir/images/`**，另存一份长边 320 的缩略图到 `filesDir/thumbs/`，
数据库里只记相对路径。不保留原图：SAF 授权会过期、相册里的原图也可能被删，留个路径迟早变死链。
解码前先用 `inJustDecodeBounds` 读尺寸算采样率，不然一张 12MP 的照片按原尺寸就是 48MB 内存；
EXIF 方向会按 Matrix 转正，横着拍的照片进详情页不会歪。

拍照/选图都不需要权限：拍照交给系统相机 App（结果写进自己 `FileProvider` 暴露的
`cacheDir/camera/` 临时文件，`file_paths.xml` 只暴露这一个目录），选图用 Photo Picker
（Android 13+ 原生，以下自动退回文件选择器）。

图片的删、换序、设封面都是**立即写库**的，没有草稿——和正文编辑不同，图片本身已经在磁盘上，
攒草稿反而要处理"拍完照又取消"这种边界。封面永远有且只有一张：删掉当前封面时自动落到第一张。

## 饮食历史：一天一行，倒着排的列表

`meal_log` 里 `slot` 存的是枚举名（TEXT），所以**不能交给 SQL 排序**——字母序会得到早/晚/午。
分组和排序都在 Kotlin 里做（`MealHistoryUiModels.toHistoryDays`：先按 `epochDay` 分组，
每天内按 `MealSlot.ordinal` 再按记录时间排）。列表用 `LazyColumn(reverseLayout = true)`：
index 0 是最新的一天、落在屏幕底部，正好是"默认停在最下方、向上拖动回溯"。

历史页的查询是一条手工 JOIN（食谱标题 + 封面子查询），不是 Room 的 `@Relation`——后者
每个关联都要多查一次，一天三顿饭就是几十次查询。

记录一顿有两种入口：食谱列表页非编辑态的加号（进"记录模式"：50% 黑蒙版 + 搜索栏自动聚焦，
挑一道菜再弹确认窗），以及详情页右下角的加号（直接记当前这道菜）。确认窗里的日期/餐段
默认按当前时间推（`MealTime.resolve`），也可以自己翻；**翻过日期之后再改餐段不会把日期拽回去**。
在记录模式里再按一次加号 = 先新建食谱，再拿它弹确认窗（也可以"只保存，不记录"）。

同一天同一餐可以记多道菜；把某一餐整体改到另一餐（`moveSlot`）时，如果目标那一餐已经有记录，
两者交换——这样不会把已有记录挤掉。

## 备份与恢复：一个 zip，导入即整库替换

顶栏右上角的「⋮」是导出 / 导入备份，走 SAF（`CreateDocument` / `OpenDocument`），
不需要任何存储权限。备份文件就是：

```
cookbook.json          数据（可读的 JSON，图片按文件名引用）
images/ab12.jpg        原图（长边 2048 那份）
thumbs/ab12.jpg        缩略图
```

用 zip 而不是把图片 base64 塞进 JSON：图片占备份体积九成以上，base64 还要再涨三分之一，
而且解压时得把整个 JSON 读进内存。`cookbook.json` 本身是可读的，出问题能直接打开看。

导入是**整库替换**（不做合并），所以先弹窗说清会覆盖掉什么。安全性上做了三件事：

- 图片先解到 `restore-tmp/`，等数据库那边成功写完才换目录，中途失败不会留「库里有图、磁盘上没有」；
- 数据库那边包在一个事务里（`clearAllTables` + 按原 id 写回），失败整个回滚；
- 被引用的东西缺了就丢掉那条引用（图片文件丢了、分类 id 不存在），而不是让整个导入失败——
  备份文件可能是手改过的。zip 条目名会做目录穿越检查。

主键 id 原样保留，所以食谱-分类-图片-饮食记录之间的引用关系不会错位。

## 已知边界

- **没有在真机/模拟器上跑过**：这台开发机没有 `/dev/kvm`，起不了模拟器。88 个 JVM 单测覆盖了
  领域逻辑、Room 读写、备份 zip 和主要界面（Robolectric 里跑 Compose），但真机上的触摸、
  系统相机、Photo Picker、SAF 这些都得装上去才知道。**侧边栏条目的点击在 Robolectric 里
  不正常**（触摸会被抽屉蒙版吃掉，怀疑是测试环境的问题），真机上请顺手确认一下。
- **R8 没开**（见上），正式包和调试包体积差不多。
- 正文里的 Markdown 特殊字符（`#`、`-`、`|`）不做转义，导出成 Markdown 后原样呈现。
- 备份导入是整库替换，不合并。
- 需求里提过的两个未来扩展**都没做**：按上次吃的时间加权随机抽菜、食材清单与保质期提醒。
  数据层已经留好了口子（`MealLogDao.lastCookedDays()` 返回每道菜最后一次被吃的天数）。

## 进度

- [x] **M0** 工程骨架：Gradle/Compose/Room 空库、侧边栏、两个主界面空状态、餐段规则与单测
- [x] **M1** 食谱列表、模糊搜索、长按菜单、批量多选、加号新增（详情页暂时只读，编辑留到 M2）
- [x] **M2** 详情页与文本编辑（原料拖动、做法缩进/句号三级标题、分类 tag、Markdown 序列化器）
- [x] **M3** 图片：拍照/相册、排序、封面、黑底预览（最多六张）
- [x] **M4** 饮食历史：记录流程、按天列表、编辑、整餐交换、批量删除
- [x] **M5** 导出导入备份（SAF zip）、正式包签名、README 收尾
