# 开发状态备忘(顶部为最近更新)

---

## 【2026-09-07 17:00】2024 年份疑点排查:一次性捕捉瞬态,清库重抓未复现

- **现象**:16:41 a681b64 抓取后,全库 41 条中有 **8 条时间为 2024 年**(转账0.01/韦小堡7.96/超市7.33→2024-09-0x,超市48.20/益禾堂×3/花呗1391.58→2024-08-31),且同商家另有 2026"正确"版本 → 疑似重复入库。
- **排查过程**:
  1. 代码核对:支付宝全部入账路径(模式1/模式2/视觉)都经 `parseBillTimeText`,其年份取设备日历(2026),**代码上不可能把"昨天/09-05"解析成 2024**,故非稳定解析 bug;
  2. 同一批屏行 16:49(P2 版)与 16:57(清库重抓)两次均正确入 2026;
  3. 清空账单库 → 完整重抓支付宝(全程日志落盘):34 笔,日志与 FinanceCsv **0 条 2024**,样本行(含之前出错的行)全部 2026。
- **结论**:8 笔 2024 为 **16:41 一次性捕捉异常**(当时恰在无障碍崩溃清理仪式后首次运行,疑似解析线程读到旧锚/页面滚动错位的瞬态),非版本代码问题、不可复现;已随清库清除。当前库 34 笔全部 2026。
- **若再复发**:用同样流程(cap 全程 logcat + FinanceCsv 导出)取证,重点看该批行的 recordAt 上下文属于哪个解析分支。

---

## 【2026-09-07 16:45】真机故障:无障碍"崩溃名单"致窗口读取全断(与版本无关)+ 修复仪式

- **症状**:回退到旧版(或任意版本)后抓取仍失败——支付宝/美团打开后引擎无任何导航日志,「点我的」不执行;`TEST_DUMP_SCREEN` 持续报 `rootInActiveWindow 为空`(连自己 App 前台也读不到)。
- **根因**:之前某轮服务异常后,系统把无障碍服务记入崩溃名单。`dumpsys accessibility` 会同时看到:
  - `Bound services:{...理伴...}` / `Enabled services:{...}`(看似正常),以及
  - `Crashed services:{{com.example.finance/...}}` ← 关键判据。
  - 崩溃名单存在期间服务虽 Bound,但 `rootInActiveWindow` 恒为空 → 引擎看不到任何节点,点「我的」无从发生。
- **修复仪式(崩溃清理版,比原"修复仪式"多 force-stop 步骤,顺序重要)**:
  1. `settings put secure enabled_accessibility_services ''`(先停用)
  2. `am force-stop com.example.finance`(清掉已崩溃的绑定)
  3. `settings put secure enabled_accessibility_services 'com.example.finance/com.example.finance.service.FinanceAccessibilityService'`
  4. `settings put secure accessibility_enabled 1`
  5. `am start -n com.example.finance/.ui.MainActivity` 前台拉起
  6. 验证:logcat 出现 `♿ 无障碍服务已连接`;**`dumpsys accessibility` 的 Crashed services 为空**;`TEST_DUMP_SCREEN` 能打印窗口节点。
  - **实测细节**:步骤 2→3 之间至少等 ~2s(让系统完成崩溃绑定拆除),否则会出现"Bound 里有服务但 Crashed 名单不消、服务不回调 onServiceConnected"的半死态(16:47-16:48 复现),补等 2s 后一次通过。
- **验证结果(两版本均通过,证明卡死与 P0/P2 代码无关)**:
  - a681b64(上一版):支付宝 6 屏 `🎉 35 笔`;随后装回 4971a5e(P0+P2 版),同一健康环境下单跑支付宝:`🎉 38 笔(去重后 38 条)` + AI 汇总点评触发,全程 pid 9737 无崩溃、Crashed services 为空、无卡死。
- **遗留观察**:本次 3 笔入账日志时间显示 `2024-09-06`(设备 `date` 为 2026-09-07),年份回退疑点待查(可能设备时钟曾在 2024 或去重/锚点链路问题),后续单独核对,勿与本次修复混淆。

---

## 【2026-09-07】P0 工程护栏 + P2 解析器测试化 — 全部编译/单测通过

> 背景:仓库此前只有 1 个 init 提交,且 09-06 发生过"GBK 误回写损坏 850+ 中文字符、靠 .class 词典恢复"的事故。本轮先补安全网再动代码;按主题分提交。

### P0 工程护栏(防编码事故再犯,三重防线)
1. `tools/Check-Encoding.ps1` — 独立扫描脚本:严格 UTF-8 解码(非法字节直接报)+ U+FFFD 检测。`-ScanPath` 扫目录,`-ListFile` 供 git 钩子;文本扩展名白名单,不误伤 .webp 等二进制。
2. `.githooks/pre-commit` + `git config core.hooksPath .githooks` — 提交时检查**本次暂存**的 .kt/.java/.xml/.md/.ps1 等,损坏即拒绝提交。
3. `app/build.gradle.kts` — 新增 `:app:encodingCheck` 任务(扫描 app/src 文本文件),挂到 `preBuild`:每次构建前自动把关,坏文件直接 BUILD FAILED。
- 事故残留归档:`app/.../service/` 下的 `FinanceAccessibilityService.kt.corrupt.bak` + `.repair-report.txt` 移入 `tools/accident-2026-09-06/`(gitignore 已覆盖,不进版本库;src 目录恢复纯净,只剩真实源码)。

### P2 解析器纯函数化 + 单测(行为零改动,逐字搬运)
- 新包 `app/src/main/java/com/example/finance/parsing/`(无 Android 依赖,纯 JVM 可测):
  - `BillTimeParser.kt` — 支付时间解析(今天/昨天 [HH:mm]、yyyy 系、M月d日、MM-dd;无年份回看 2 年、10 分钟未来宽容)+ `extractRowTime`(行尾时间)+ `parseFlexible`(下单：前缀等混排);
  - `MerchantText.kt` — 淘宝状态词表 / 店名噪声 / `normMerchant` 归一化 / 账单行噪声 / 就近商家查找;
  - `BillAmountText.kt` — 金额正则(独立行/价格 token/支付宝"模式1"整行)+ 美团 ¥ 拆分节点合并 `mergeYuanTokens`(¥ 9 .9 → 9.9);
  - `BillKeys.kt` — 引擎内去重键(`商家|金额|日`,金额**固定 Locale.US**:防小数逗号区域把同一笔格式成两种 key 破坏去重)+ 日志时间格式。
- `FinanceAccessibilityService.kt`(2830 → 2692 行):原函数体全部改为同名一行委托,调用点零改动;`parseOrderListStyle` 改接 `mergeYuanTokens`,保留原语义(**拒单不跳游标、成单跳游标**);商家回查/状态词表等行为未动。
- 单测 `app/src/test/java/com/example/finance/parsing/` 4 文件共 49 例(时间 19 / 商家 12 / 金额 11 / 去重键 5 + 存量 1),样本取自真机校准记录,固定注入 `nowMs`(与时钟、时区无关)。命令:`gradlew :app:testDebugUnitTest`。已全绿,`assembleDebug` 通过。
- **行为对齐说明(bug-for-bug)**:MM-dd 当日"还没到点"的时刻(如 18:00 读到"09-06 18:30")按原引擎语义回退到去年同一时刻,测试已固化该行为;若要改进(判无效/宽容窗内保留)需真机立项验证,勿在抽取轮顺手改。
- 说明:抽取只搬纯函数,`recordAt`/UI/抓取编排仍留服务内;剩余可测化对象(下单前判断词表、抓取页判定)留待后续轮次。

---

## 【2026-09-06 夜 3】AI 链路端到端复验(全通过)+ 补齐支付宝"抓取后 AI 汇总点评"

- **复验结果(真机,DeepSeek key 生效)**:
  - 实时消费点评:`TEST_ADVICE/TEST_SIMULATE` → ModelAPI POST deepseek-chat → `AI建议[云端]`,实测回复**真实引用预算**(「餐饮尚余439.65元,占比健康」)——预算上下文链路通;
  - 每周 AI 小结:`TEST_WEEKLY` 28 笔 → 云端输出环比暴涨57%+外食建议;
  - 视觉读屏:支付宝「我的」页(无障碍隐藏文字)经 vision 模型定位「账单」入口并点击成功(点击 205,808),抓取完成 32 笔带真实时间;
  - 抓取后批量点评:美团/淘宝外部引擎原本就有;**支付宝引擎缺失**(历史不对称)→ 本轮补齐并真机验证。
- **修复内容**(`service/FinanceAccessibilityService.kt`,支付宝引擎):完成分支新增与外部引擎一致的点评块;为提供金额/高频去向数据,`ExternalAgg` 接入支付宝解析——`parseOneScreen(seen, agg, isFirst)` 视觉路径累计 + `parseBillScreenText(seen, agg)` 文字模式1/2 入账处累计 `agg.sum`/`agg.merchants`。
- **真机验证**:清库→抓取支付宝 12 笔 → `🧠 已请 AI 汇总点评「支付宝账单」` → `AI建议[云端]: 总体消费以小额高频的日常支出为主,其中 API 充值与深夜外卖占了近六成…`(云端基于真实 top 商家生成)。
- 运维小记:偶发"支付宝未到前台/已回到理伴"= 小米拦截后台拉起,重发一次广播即可(理伴前台时触发)。

---

## 【2026-09-06 夜 2】注释/日志乱码清理完成:全文件 0 处 U+FFFD 残留

- 步骤:①脚本清除纯注释行与代码尾部注释内的 `U+FFFD`/损坏 `?`(tools/strip_comments.ps1,清 206 行);②剩余 61 处日志字符串按词典前缀证据(`⏭ 实时[`/`✅ 实时[` 等)+上下文逐行定稿(tools/fixspec_logs.tsv,61+1 行);③assembleDebug 通过、全文件 U+FFFD 计数 = 0、注释无孤立 `?`。
- 装机验证:新 APK 已装(pid 21634),无障碍已连接。日志文案恢复如 `支付宝文字[模式1]: 入账 N，日期锚点 M…` 等。
- 注:损坏字符本身不可逆(GBK 有损),恢复值为语义推断;个别注释用词可能非原文但通顺无误。

---

## 【2026-09-06 夜】源码 GBK 误损坏 → 词典校准 + 逐行定稿恢复(已编译装机,双通道真机验证通过)

- **事故**:排查抓取问题时误用 GBK 编码回写 `service/FinanceAccessibilityService.kt`(无 git/无备份),全文件中文字符 850+ 处损坏(U+FFFD/丢引号),不可逆。
- **恢复方法(可复用)**:
  1. 最后一次成功编译(20:57)的 `.class` 完好 → `javap -c -p`(注意 Windows 下 javap 输出 GBK,需 `-J-Dstdout.encoding=UTF-8` 或按 936 解析)按方法顺序提取全部字符串常量作"词典"(`svc_pool.txt`,941 条);
  2. `tools/RepairStrings.java`(单文件 Java):逐行扫描被损坏的字面量,用词典做 LCS 匹配修复(吃掉的引号按"损坏段是否直达内容尾"判定补回);
  3. 自动修复残余 ~70 行结构错误 → **按行号规格表人工定稿**(tools/fixspec*.tsv):四轮把编译错误 230+ → 0;
  4. 关键坑:①修复产物 `.fixed.kt` 若留在 src 目录会被当第二份源文件编译,报错全指它(删掉);②kotlinc 报的"缺 }"其实是**行内被误插的函数签名(多余 `{`)把后续成员全嵌套成局部函数**——用探针 `}` + 报错模式定位到杂行(2299 误插的 `private fun isProfilePage…`)删除即可;③正则恢复必须对照词典——"日"是**可选** `日?`,手写恢复成必选会导致美团 `下单：2026-09-02 10:54` 时间解析失败(支付宝行尾时间走 MM-dd 分支不受影响,易漏检)。
- **真机验证(22:12,装机 pid 18164,无障碍已重开)**:支付宝 32 笔带真实时间;美团点「我的」→ profile veto 生效不再误判我的页 → 视觉点「我的订单」→ 订单列表 25 笔**全部带真实下单时间**(2026-06-19~09-02)。下单前判断/守卫词表均按损坏前状态恢复。
- **遗留(仅观感)**:注释与少量日志文案仍有 U+FFFD(约 110 行,不影响编译/功能);工具与损坏备份保留在 `tools/` 与 `FinanceAccessibilityService.kt.corrupt.bak`;完好旧 APK 备份 `Finance-phone-backup-2150.apk`。

---

## 【2026-09-06 追 3】误弹回归修复:店铺菜单页底栏「去结算」不再误判(真机双向验证通过)

- **回归来源**:追 2 把「去结算」留在强词表 + feed 拦截后置 → 店铺点菜页(购物车有货时底栏=「共减¥4…去结算」)被强词直判,弹"拼好饭/菜单页"假判断(实测 20:51:36 拼好饭 ¥13.95 误弹)。
- **修法**:
  1. **「去结算」从强词降为弱词**——它同时出现在菜单底栏/购物车栏与真结算页;真结算页独有词(提交订单/确认订单/去支付/立即支付/**极速支付**)保持强词即判;
  2. feedMarkers 增加 **「月售」**(店铺菜单页必有;账单行/订单列表无此词,不影响抓取页判定),菜单页弱命中会被 feed 拦截;
  3. 命中日志打印命中的词:`🛒 下单前判断[强=[…] 弱=[…] 合计=[…] feed=…]`,以后任何误弹/漏弹一眼定位。
- **真机双向验证(logcat)**:店铺菜单页停 5 秒 → 无弹窗;点「去结算」进确认页 → `[强=[极速支付] 弱=[支付] 合计=[共减,券前] feed=false] 大馅手工水饺… ¥18.80 → 合适` 弹出悬浮窗。美团首页信息流仍不弹(未判日志留痕)。

---

## 【2026-09-06 追 2】下单前判断 真机端到端通过;并挖出两个环境级大坑(附修复仪式)

- **验证结果(真机 logcat)**:美团结算页依次命中 `🛒 下单前判断: 拼好饭 ¥13.95 / 大馅手工水饺（商中美食城第6档口）¥18.80 / 三米粥铺（海大店）¥20.80（餐饮）→ 合适`,悬浮窗弹出;商家菜单页与首页信息流不弹(由 20s 采样"结算页未判"日志留痕确认)。
- **真机实测补词(美团结算页根本没有"提交订单/合计"字样)**:
  - 底部大按钮叫 **「极速支付」**(点它即提交+支付)→ 已加入 ORDER_CTA_STRONG;
  - 金额区文案 **「券前 ¥18.8 / 共减¥0.5」** → 已加入 SUM_LABELS(券前/共减);
  - **商家名选择器重写**:`pickMerchantName` 取 y 最接近屏幕 45% 高度、含中文、非运费/红包/券/文案行的候选(允许店名含数字)。旧逻辑会取到"外卖配送";新逻辑实测取到"大馅手工水饺(商中美食城第6档口)" ✓。
- **环境坑 1(重要):APK 更新后无障碍"假 Bound"——dumpsys 显示 Bound 但事件一个都不来**
  - 特征:logcat 无任何 FinanceAccessibility 输出;`dumpsys activity processes` 里服务有 4 条 `ConnectionRecord ... CR FGSA CAPS DEAD`。
  - 根因:新装包处于 stopped 态,`settings put` 只写入了假 Bound,服务进程从未真连(MIUI 拒绝为 stopped/后台应用拉起)。
  - **修复仪式(每次 adb install -r 后必做)**:①`settings put` 写入(先清空再写,`enabled_accessibility_services ''` → 全名,`accessibility_enabled 1`)②`am start` 把理伴拉前台 ③3 秒后看 logcat 出现 `♿ 无障碍服务已连接` 才算真连 ④切走后台验证事件仍在流动。
- **环境坑 2:理伴后台被冻结,事件流停摆**
  - 特征:理伴在后台时美团页面事件完全不来(连 TEST_DUMP_SCREEN 广播都不执行);`isFreezeExempt=false`。
  - 修法:**省电策略=无限制**(设置→应用设置→理伴→省电策略→无限制)+ 自启动;adb 无法直接设 MIUI 专有项。appops RUN_IN_BACKGROUND/RUN_ANY_IN_BACKGROUND 可辅助但非充分。
- **诊断速查**:`adb logcat -d 'FinanceAccessibility:V' '*:S'`(Windows 上 `-s` 多 tag 写法会报 Bad arguments);后台日志命令 `adb shell "dumpsys activity processes | grep -i finance"` 查 isFrozen/ConnectionRecord;美团结算页 dump 参考 `TEST_DUMP_SCREEN`(缓冲易被 ColorManager 刷掉,dump 后立即拉)。

---

## 【2026-09-06 追】下单前判断"结算页不弹"修复(代码完成,待真机复现校准)

- 症状演变:词表太宽→信息流乱弹;收紧+feed 拦截+金额取底部后→**真结算页不再触发**。
- 本轮改动(全部在 `service/FinanceAccessibilityService.kt` + `HomeScreen.kt` + `UserSettings.kt`,assembleDebug 已通过):
  1. **采集 150→300 且自底向上**:新增 `collectTokensForJudge`——同一父节点的子节点按屏幕 y 从大到小(下→上)递归,text/desc 各收 300 条。结算/支付按钮与「合计」行在页面最底部,旧版按树先序从头截 150 条会漏采 CTA → 强词判空。根因①按此修复。
  2. **判定顺序改为"先 CTA/合计 → 再 feed 拦截"**(`looksLikeShoppingFeed` 不再提前 return):强结算词命中即判;弱命中才允许被 feed 特征压制。根因②(结算页含 满减/评分 等 feed 词被误伤)缓解。
  3. **词表重组**:强词只留结算/收银页词(确认订单/提交订单/去支付/立即支付/去结算…);「立即购买/马上购买/一键购买/立即下单/去买单」等详情页 CTA 降为弱词——详情页没有"合计/地址"→ 弱词+合计不成立 → 不会在浏览商品时误弹(防旧"乱弹"回归)。
  4. feedMarkers 删「满减」(结算页满减券信息不再被当 feed 特征)。
  5. 新增 `logJudgeMiss`:"接近但未判"日志 20s 采样一条,打印 strong/weak/合计/feed 各标志 + 页面底部前 8 个 token——真机在结算页可直接据此调词,不再瞎猜。
  6. 「我的」页新增「下单前 AI 判断(结算页守卫)」开关(UserSettings `judgeBeforeOrderEnabled`,默认开,依赖无障碍门控)。
- **待真机验证**:装 Finance-debug.apk → 重开无障碍(小米:不要 force-stop 后直接启,先 `dumpsys accessibility` 确认 Bound)→ 美团真实下单走到结算页 → logcat `FinanceAccessibility`:
  - 期望:看到 `🛒 下单前判断: 店名 ¥xx(分类)→ 合适/谨慎/不建议` + 悬浮窗;
  - 若仍不弹:看 `🛒 结算页未判[...]` 日志判断卡在哪个分支;底部 token 会列出该页真实文案用于补词;
  - 回归:美团/淘宝首页与商品信息流停留数秒 → 不应弹窗(旧"乱弹"用例)。
- 顺带说明:历史遗留"结算页可能命中 满减 等词"的顾虑已由 ①强词即判 ②feedMarkers 删满减 双重覆盖。

---

## 【2026-09-06】账单里程碑:数据正确性 + 账单页记账本(已完成并装机)

目标:先把「账单」做扎实 —— 入账数据正确 + 账单页是完整记账本。全部改动已编译并安装到真机(192.168.43.198:37263,无崩溃,来源归一化/实时入账已在 logcat 验证)。

### 关键行为变化(相对上一版)
1. **去掉"内存双源"**:首页/账单页一律订阅 Room Flow。旧实现是"启动回填内存 300 条 + 实时追加",与库里删改不一致;现在抓取/补记/删除/清库后全 UI 自动一致。
2. **首页「今日支出」修口径**:旧代码把整月加载记录全加进"今日";现改为查询今天 00:00 起的 `sumBetween`/`countBetween`。
3. **来源归一化(去脏数据)**:新增 `BillSources` 规范集(支付宝/美团/淘宝闪购/手动/其他)。仓库入口与存量数据都归一化(`支付宝账单→支付宝`、`美团账单→美团`、`本地演示→手动`),月度统计不再把同一来源拆成多行;抓取/实时日志也显示规范名。
4. **账单页(记账本)**:
   - 全量明细直接读 Room,按日分组(今天/昨天/M月d日 周X),每日小计;
   - 来源筛选 Chip;本月统计卡保留;
   - **手动补记**、**单笔编辑**(含改日期时间,自动重算去重日)、**单笔删除**(带确认);
   - 补记/抓取/删除结果即时出现在首页今日卡与本月统计。
5. **数据守卫**:仓库入口丢弃 金额≤0 / 非法 / 商家为空 的脏数据;重复入库(同天·同商家·同金额)由唯一索引忽略,UI 会提示"已存在相同记录"。
6. DB v1→v2:新增 `note` 备注列(Migration,自动迁移保留数据);CSV 导出增加备注列。

### 改动文件
- `data/FinanceDb.kt` — v2 + Migration(1,2)、note 列、DAO:`observeAll/latestFew/sumBetween/countBetween/update/deleteById/来源迁移`、`todayRange()`
- `data/BillSources.kt`(新)— 来源规范集与归一化
- `data/AccessibilityEventRepository.kt` — postConsumption 归一化 + persist 合法性守卫
- `data/MerchantStore.kt` — 新增 `clear()`
- `FinanceApp.kt` — 启动时对存量来源做一次性归一化
- `ui/BillsScreen.kt`(新)— 记账本账单页(替换 HomeScreen 内旧的账单页)
- `ui/HomeScreen.kt` — 去内存记录/去重键;今日卡/最近一笔改 Flow;清理旧账单页代码

### 已修 Bug(同日):无障碍检测"永远未开启"
- 现象:设置页开关后返回,App 仍显示未开启、按钮全灰。
- 根因:小米把已启用服务写成**全限定类名** `com.example.finance/com.example.finance.service.FinanceAccessibilityService`,旧检测只认短名 `com.example.finance/.service.FinanceAccessibilityService`(settings put 写入的形式),导致误判。
- 修法:`HomeScreen.isFinanceAccessibilityEnabled` 改用 `ComponentName.unflattenFromString` 归一化比较(两种写法都识别);ON_RESUME 时输出 `FinanceUI: onResume 无障碍检测=true/false` 便于验证。已装机确认输出 true。
- 诊断命令:`adb shell settings get secure enabled_accessibility_services` / `dumpsys accessibility`(看 Enabled/Bound services 是否含 Finance)。

### 【同日追加】抓取控制悬浮窗:点击即可停止抓取(已装机并验证)
- 交互:抓取(支付宝/美团/淘宝)进行时,顶部常驻悬浮窗显示「📋 自动抓取中 + 实时进度 + 红底⏹ 点击停止抓取」;点按钮立即取消当前抓取并收起悬浮窗(任务 finally 兜底清理)。
- 实现:
  - `utils/FloatingWindowManager.kt` — 新增 `showFetchControl(status, onStop) / updateFetchStatus / hideFetchControl`(与应用内自动隐藏状态窗相互独立、互斥叠加)。
  - `service/FinanceAccessibilityService.kt` — 新增 `beginFetchUI / updateFetchUI / endFetchUI / requestStopFetch`;两个抓取引擎(支付宝 + 美团/淘宝外部引擎)都挂在同一个 `billFetchJob`,取消即停;两个引擎的 catch 均先接 `CancellationException`(用户停止不再当异常刷日志),finally 收起悬浮窗。
  - `data/AccessibilityEventRepository.kt` + `debug/DebugReceiver.kt` — 新增停止指令通道 `PREFIX_STOP_BILL_FETCH` / 广播 `TEST_STOP_FETCH`(adb 也可远程停止)。
- 验证(logcat 实测):开始抓取 → `FloatingWindow: ✅ 抓取控制悬浮窗已显示`;广播停止 → `🛑 收到停止抓取指令 → ⏹ 正在停止抓取… → ⏹「美团账单」抓取已取消(用户停止) → 🧹 抓取控制悬浮窗已移除`,无崩溃。
- 提示:悬浮窗按钮点击 → `requestStopFetch()`,与广播同一入口;若当时无任务会提示"当前没有进行中的抓取任务"。

### 【同日追加】抓取账单带真实"支付时间"入账(已装机,待真机复抓确认)
- 问题:历史抓取把每笔都记为"抓取当天",全部挤进当日/当月统计。
- 改动:
  - `ai/AIService.kt` — 视觉读屏 `VisionBillEntry` 新增 `time` 字段;prompt 要求模型逐行输出支付时间(行内没有就补上方的日期分组标题,如"6月18日";今天/昨天换算成实际日期;无时间给空串)。
  - `service/FinanceAccessibilityService.kt` — 新增 `parseBillTimeText`(识别 今天/昨天、yyyy-M-d [HH:mm]、M月d日 [HH:mm] 等,未来时间判无效,无年份的 M月d日 自动取最近一次≤今天);`recordAt` 有真实时间用真实时间、没有才退回抓取时刻;`seenKeyFor` 去重键加入"发生日"(同商家同金额跨天不再被合并)。5 个入账点全部接入:支付宝文字(模式1/2 日期锚点游标)、支付宝视觉兜底、美团订单拆分样式、外部独立金额、外部视觉读屏。
- 验证状态:编译通过、APK 已装。真机端到端需再抓一次核对(见下)。
- 注意:此前已抓进库的旧记录仍是"抓取当天"时刻,不会自动改;清空账单库(`TEST_CLEAR_BILLS` 或「我的」页清库)后重新抓一次即全部带真实日期。

### 【同日跟进】支付宝/美团时间解析已实测通过;美团导航防误触
- 实测发现支付宝账单是"合并文本节点",整行=「商家，-10.90元，，餐饮美食，，今天 14:42」,整屏没有独立日期行 → 原"日期锚点"方案失效。修复:`extractRowTime()` 从行尾提取时间短语(`今天 14:42 / 昨天 21:26 / 09-04 22:14 / 2024-07-15 12:30`),`parseBillTimeText` 增加 `MM-dd HH:mm` 分支(无年份取最近一次≤今天,跨年自动前推)。真机日志已确认每笔带真实时间(昨天→前一日、09-04→9月4日…)。
- 美团问题:①误触顶部搜索栏=引擎进入列表后仍继续点泛词「订单」,`findAccessibilityNodeInfosByText("订单")` 命中了顶部"搜索我的订单"输入框 → 列表一旦可见且已点过订单入口(`stepIndex >= primary+bill-1`)即停止导航;`tryClickText`/`clickByVision` 再加防误触(顶部 y<12% 区域与 EditText 祖先不点)。
- 美团时间:行内文本有「下单：2026-09-02 10:54」,原"视觉优先"把无时间行直接入账 → 外部引擎改为**文字优先**(时间可靠),视觉仅兜底;时间解析容忍前缀(`parseTimeFlexible`:整体失败则扫行内短语)。真机日志:19 笔全部带真实时间(09-02/08-31/07-03/06-05…),无一条落今天。
- 新增诊断日志:`recordAt` 每笔打印解析到的时间(`💰 入账[来源] 商家 ¥x 2026-09-02 10:54`,未识别会注明),各解析路径打印 `文字解析/视觉解析 新增 N 笔`、支付宝打印日期锚点数——后续若再异常直接看 `FinanceAccessibility` tag。

### 【同日追加】首页/账单页月份切换(已装机)
- 新增 `ui/MonthNav.kt`:月份编码/加减/区间/日均工具 + 共享 `MonthSwitcherRow`(‹ 2026年9月 › + 回到本月,非本月时显示"回到本月")。
- 账单 Tab:顶部月份切换卡 → 统计卡与按日明细都随所选月变化(明细按 dayBucket 前缀过滤),标题显示"2026年9月明细"等;空月有提示。
- 首页 Tab:今日卡下方新增"月度浏览"卡:切月看支出合计/笔数/日均(本月按已过天数,历史月按整月)。
- 运维注意(小米):**APK 更新/force-stop 后会把本应用无障碍设置重置**;且无线调试会话 `settings put secure` 不可靠(写入后会被清,需避免 force-stop、改由设置页开关或写入后立即前台拉起不 force-stop 验证:`dumpsys accessibility` 看 Bound services=理伴)。

### 【同日修复】从 App 跳到支付宝/美团抓取后再切回,App 卡死无响应
- 根因:①支付宝抓取引擎跑在 `Dispatchers.Main`,逐屏在主线程阻塞读无障碍窗口树(单次可达数秒,历史记录在案),期间切回 App 即长时间无响应;②切回后引擎仍继续对**我们自己的界面**做解析/上滑(还可能把 App 界面上的金额误当账单)。
- 修复(已装机):
  - 支付宝引擎 `Dispatchers.Main → Dispatchers.IO`(UI 操作全部走已封装的 main-executor 悬浮窗/日志通道)。
  - 新增 `ensureStillOnPlatform(targetPkg)`:**每个抓取屏解析前**校验当前前台;已回到理伴/切去其它应用 → 立即停止,绝不解析/点击非目标窗口。
  - 导航阶段(支付宝 `navigateToBillPage` 与外部引擎 nav 循环)检测到前台是本应用也立刻中止。
  - 被"用户切走"中止时日志与悬浮窗提示为"已停止",不再误报"抓取完成",也不触发 AI 汇总点评。
- 验证:正常抓取回归通过(美团 20 笔带时间入账、完成提示正常、无 ANR);因支付宝/美团首屏即达 20 条上限、抓取过快,"切回发生在抓取中"难以用脚本稳定复现——请人工快速验证一次:点抓取后数秒内按 Home/切回理伴,应看到日志 `⏹ 已回到理伴应用，自动停止抓取` 且界面流畅无 ANR。

### 【下一步 P0】去重升级"分钟级" + 按来源清理工具(已装机)
- 问题:Room 去重键原来是 `来源+商家+金额+当天(天)`,同一天不同分钟买两件同价商品会被误合并。
- 改动(v2→v3 Migration 自动迁移,保留数据):
  - `BillEntity` 新增 `timeBucket`(yyyy-MM-dd HH:mm);唯一索引改为 `(source,merchant,amount,timeBucket)`;旧索引删除;存量行按 occurredAtMs 回填分钟。`fromRecord/withDerived` 自动计算,所有入账/补记/编辑路径无需改。
  - 效果:滚动重读同一笔仍同分钟 → 照常去重;同天"不同分钟"的合法重复 → 各自保留。
- 清理工具:「我的→账单数据→🧹 清理某来源的全部账单」,按来源(支付宝/美团/淘宝闪购/手动/其他)单独清空,带确认;DAO `deleteBySource`。误抓整批可单独清掉再重抓,不必清全库。
- 验证:DB v2→v3 自动迁移无异常(设备数据保留),首页无障碍/账单正常;真机功能与去重行为待人工按正常流程使用确认。

### 【P1】预算闭环(已装机,云端 AI 已实测引用真实剩余预算)
- 新增:`data/BillCategories.kt`(本地关键词分类:餐饮/购物/日用/交通/娱乐/医疗/其他,UI/AI/悬浮窗共用同一口径)、`data/BudgetStore.kt`(月预算+分类预算,首次示例值 餐饮800/购物400/日用300/交通200)、`data/BudgetPlanner.kt`(当月快照:本月已花/分类已花+预算,产出 `aiBudgetContext()` 给 AI、`shortReminder(category)` 给悬浮窗)。
- 首页:今日卡下方"本月预算"进度条卡(预算/已花/剩余·日均可用(超支转红)/分类小计)。
- 「我的」:新增「预算设置」区(月预算+各分类额度,留空=不限,保存/恢复演示预算)。
- 实时链路:无障碍页面/通知/测试广播入账后,按分类算当月剩余 → 悬浮窗"💳 支出 ¥x ·「餐饮」预算剩 ¥y";AI 点评提示词带上 `aiBudgetContext()`。实测云端 AI 回复已正确引用真实计算值(如"餐饮剩余452元")。
- DAO 新增 `sumBetweenOnce / merchantAmountsBetween`(一次性聚合)。

### 【P1】每周 AI 小结(周报,已装机实测云端输出)
- 新增 `data/WeeklyReportBuilder.kt`:近 7 天 vs 前 7 天聚合(总支出/笔数/日均/分类占比/渠道/高频去向 Top3/最大单笔)+ 当前预算上下文,产出 `WeeklyReportInput`。
- `AIService.generateWeeklyReport`:云端生成 ≤130 字周报(总体评价 + 变化/异常/超支风险 + 下周 1 条建议);云端不可用时端侧拼统计摘要。
- 首页新增「每周 AI 小结」按钮(结果进上方 AI 建议卡);DEBUG 广播 `TEST_WEEKLY` 可脚本触发。
- 实测:22 笔聚合 → 云端输出"近7天支出542.94元…餐饮占66.9%、单笔最高136元…警惕月底超支…控制餐饮日均45元以内"——分类/环比/预算全链路打通。

### 人工验收清单(设备端,机器无法自动完成的部分)
真机可见操作一遍即可:
1. 打开 App → 首页卡应显示「今日 2 笔 / ¥57.00」左右(本轮广播测试造了 沙县小吃¥28.5、瑞幸咖啡¥28.5 两笔,均为"手动"来源)。
2. 切「账单」Tab:本月统计 + 按日分组两条明细 + 备注/编辑/删除可用;点「编辑」把时间改成昨天 → 首页今日应变 0 笔、分组里出现"昨天"。
3. 「＋ 手动补记」新增一笔 → 首页/统计/明细立即出现;若与已有同天同商家同金额,应提示"已存在"。
4. 删除、清库按钮与 CSV(导出文件在 App 外部目录)行为正常。
5. 无障碍开着时点抓取按钮 → 入账进同一列表(旧抓取链路未改动)。

### 已知(未变)限制
- 抓取的历史账单仍记为"抓取当天"(平台行内时间提取依赖被搁置的淘宝引擎重构),账单页有提示文案,可手动编辑改时间。
- 去重规则(同天·同商家·同金额)对"同一天买两次完全相同的商品"会合并,属可接受取舍,可用删除/编辑纠正。
- 本机 uiautomator dump 本轮返回 `null root node`(该通道本来就易坏);截图用 screencap 可行,但当前 agent 模型不能读图,页面视觉验收需人工进行。

---

## 【旧主题】淘宝冷启动抓取(恢复用,已搁置)


## 背景
理伴 App 三平台自动抓账单:支付宝 ✅ 已可用;美团 ⚠️ 部分可用;淘宝闪购 ❌ 调式中(本备忘录主题)。

## 已确认的手机/App 环境事实(校准数据)
- 真机:Redmi K60 Pro (22127RK46C), Android 14 (MIUI), 无线 adb 端口**每次配对会变**;重启后需重新配对。
- **uiautomator dump 与 logcat 会莫名卡死,重启手机可恢复**(多次复现)。
- 无障碍服务:force-stop / 重启后需重新 enable:
  `settings put secure enabled_accessibility_services 'com.example.finance/.service.FinanceAccessibilityService'`
  `settings put secure accessibility_enabled 1`
- 淘宝首页底部 Tab 用 **content-desc** 暴露且可点击:「我的淘宝」bounds [864,2253][1080,2400](归一化 ≈0.90, 0.969)。
- 「我的淘宝」页:整条 bar desc=`我的订单全部` clickable=true,bounds [23,938][1057,1048],内含文本节点「我的订单」。
- 「我的订单」页:顶部类型 Tab desc=`全部订单未选中/购物未选中/闪购已选中/飞猪未选中`(y≈245-303);订单行几乎全部信息(店名/商品/状态"已完成"等)在 **content-desc**,只有**实付金额是真实 text 节点**(¥7.96 等);行按钮 desc:删除订单/查看订单/评价/再买一单。
- 淘宝冷启动/首页/信息流有大量商品价 text(¥9.01…) + desc(好评榜/已售/加购…),**不能当账单列表解析**。

## 已修(代码已改、APK 已装,但端到端未复测)
- `FinanceAccessibilityService.startExternalBillFetch`:整链移到 Dispatchers.IO;启动后先等前台,期间**自动点掉小米「跳转应用许可」弹窗**;前台确认失败即中止(不再盲点);每轮输出当前 pkg + 前若干文本日志。
- Feed 误判防护:`looksLikeShoppingFeed` / `isBillRowsPage`(入口判定 + 文字兜底均加)。
- 悬浮窗状态改走主线程(`showStatusSafe`)。
- `TEST_CLEAR_BILLS` 清空账单库通道(DebugReceiver + AccessibilityEventRepository.clearAllBills)。
- Taobao 事件配置步骤:primary=[我的订单], bill=[全部订单], post=[闪购], bottomTabTap=(0.86,0.965) ← **坐标待修正为 (0.90,0.969),并应改为 desc 文字点击优先**。

## 尚未做(恢复时的下一步)
1. 列表判定与文字解析**读取 content-desc**(当前只收 text,淘宝行会漏)。
2. 底部 Tab 点击改为 tryClickText("我的淘宝") 优先 + 坐标兜底(0.90,0.969)。
3. 订单行配对:实付金额 text + 行内店名 desc;过滤 删除订单/查看订单/评价/再买一单/筛选/管理 等噪声。
4. 建议新增 `TEST_DUMP_SCREEN`:a11y 输出当前窗口 text+desc+bounds 到 logcat(替代易坏的 uiautomator)。
5. 端到端冷启动复测:强停淘宝 → 广播 TEST_FETCH_TAOBAO → 校验只入真实订单、无假账。

## 复测命令速查
- 清日志+触发:`adb logcat -c`;`adb shell am force-stop com.taobao.taobao`;`adb shell am start -n com.example.finance/.ui.MainActivity`;`adb shell am broadcast -a com.example.finance.TEST_FETCH_TAOBAO -n com.example.finance/.debug.DebugReceiver`
- 看日志:`adb logcat -d -s FinanceAccessibility FinanceTest`
- 若 APK 变更需重开无障碍(见上)。
- 清空账单库(避免假账):广播 `com.example.finance.TEST_CLEAR_BILLS`。
