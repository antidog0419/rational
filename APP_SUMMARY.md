# 理伴(Finance)· 应用总结文档

> 本文档基于当前仓库真实代码与真机验证记录整理(2026-09-07)。`README.md` / `ARCHITECTURE.md` 为早期版本,内容已过时,以本文档为准;逐轮开发日志见 `DEV_STATUS.md`(顶部为最新)。

---

## 〇、一句话

个人财务 AI 伙伴:纯本地记账 + 平台账单自动抓取 + AI 点评/预算/周报。Kotlin + Jetpack Compose,三 Tab(首页 / 账单 / 我的)。

**核心信念:不手动记账。** 通过无障碍 + 通知自动感知每笔真实消费,账单带真实时间入库,月度/分类去重后统计真实。

---

## 一、功能全景(真机验证 ✅ 为准)

### 1. 数据底座(正确性优先)
| 能力 | 说明 |
|---|---|
| Room 本地库 | v1→v3 自动迁移,数据保留;唯一索引防重复 |
| 分钟级去重桶 | `来源+商家+金额+yyyy-MM-dd HH:mm`;同店同额跨天不误并、滚动重读去重 |
| 来源归一化 | 支付宝账单→支付宝 / 美团账单→美团 / 本地演示→手动 等;存量启动时一次性迁移 |
| 真实时间入库 | 每笔带真实支付/下单时间(今天/昨天/09-04/2026-08-31…解析),未识别才退回抓取时刻并注明 |
| 数据守护 | 金额≤0/非法/商家为空丢弃;按来源批量清理;全库清空 |
| 抓取设置 | 单次条数上限 10~500 可调(默认 100) |
| 崩溃与诊断 | crash.log、`TEST_DUMP_SCREEN`(替代该机失效的 uiautomator)、20s 采样"未判"日志 |

### 2. 三大自动记账通道
- **支付宝**:全自动进账单页逐屏解析;行尾时间提取(`今天 14:42 / 09-04 22:14 / 昨天…`),未来时间判无效、无年份自动回推;「我的」页内容对无障碍隐藏时由**视觉模型读屏定位「账单」入口**(需云端 Key)。
- **美团**:文字优先解析订单列表(行内 `下单：yyyy-MM-dd HH:mm` 提取真实时间);¥ 拆分行(`¥ / 9 / .9`)金额合并;店名-状态配对。
- **淘宝闪购**:冷启动回主界面→点「我的淘宝」→「我的订单」→「闪购」页签→订单卡解析(店名/状态在 desc、实付金额为真实 text)。**时间策略(保守)**:①与支付宝"金额+归一化店名"匹配取真实时间并防重复;②同店同天多笔之和匹配;③匹配不到一律不入账,留日志待办(绝不伪造"今天")。
- **实时通道**:支付宝/美团/淘宝付款成功页 + 通知监听(`PaymentNotificationListener`),RealtimeGate 跨通道 6 秒去重;入账即触发悬浮窗 + AI 点评(带预算上下文)。
- 抓取全程:抓取控制悬浮窗(可点停)、系统「跳转许可」弹窗自动处理、切走自动停止、90s 停滞看门狗、防误触(顶部/输入框/弹层)。

### 3. AI 能力(DeepSeek 可运行时配置,云端失败端侧兜底)
| 能力 | 说明与验证 |
|---|---|
| 实时消费点评 | 带当月/分类剩余预算上下文;云端实测引用真实剩余(「餐饮尚余439.65元」)✅ |
| 下单前 AI 判断 | 美团/淘宝结算页识别商家+金额→悬浮窗「合适/谨慎/不建议」;「我的」页可开关;强词(极速支付/提交订单…)/弱词(去结算/立即购买…)+合计分层判定;自底向上采集;feed 拦截;命中日志带证据 ✅ |
| 抓取后批量点评 | 美团/淘宝原已接入;支付宝引擎已补齐(2026-09-06),云端按真实高频商家+合计生成 ✅ |
| AI Top3 外卖推荐 | 首页输入需求→本地商家库(MerchantStore,当前 145 家)对比推荐;端侧规则兜底 |
| 每周 AI 小结 | 近 7 天 vs 前 7 天聚合(总支出/日均/分类占比/渠道/高频去向 Top3/最大单笔+预算),云端生成评价/异常/建议 ✅(28 笔实测) |
| 视觉读屏 | vision 模型定位隐藏文字入口/读账单行(入口点击实测成功) |
| 端侧兜底 | 云端不可达/超时(15s)/未配 Key 时自动降级启发式规则,标注「云端/端侧」 |

### 4. 记账本 / 预算 / 报表 UI(三 Tab)
- **首页**:今日支出(正确口径)+ 月度浏览(月份切换)+ 本月预算进度条(已花/剩余/日均可用/分类小计)+ AI 建议卡 + 每周 AI 小结入口 + AI Top3 推荐。
- **账单**:Room 驱动、按日分组、月份切换、来源筛选、手动补记/编辑/删除(改时间自动重算去重)、空月提示、系统日志。
- **我的**:无障碍门控、下单前判断开关、AI 自动执行开关、DeepSeek 配置(Key/模型)、预算设置、抓取设置、按来源清理、CSV 导出(含备注)、清空全库。

---

## 二、工程与隐私

- **权限最小化**:无障碍(核心)/悬浮窗/通知/网络;数据全部本地 Room,不上传;隐私文案补齐为发版待办。
- **API Key 安全**:Keystore AES-256-GCM(`enc:` 前缀)+ 旧明文自动迁移;本机 AndroidKeyStore 不可用 → 明文回退不丢数据(功能不受影响,发版文档需标注)。
- **DebugReceiver 收敛**:测试钩子(源码+manifest)已移入 `src/debug/`,`assembleRelease` 不含。
- **无障碍事件节流**:统一 2s;非支付日志抽样,降噪省电。

---

## 三、代码地图(现状,替代过时 README)

```
app/src/main/java/com/example/finance/
├── service/FinanceAccessibilityService.kt   无障碍服务:实时感知 + 三平台历史抓取引擎 + 下单前判断 + 抓取UI(核心;时间/金额/商家文本解析已抽至 parsing/)
├── ai/AIService.kt                          AI 编排:点评/周报/Top3/批量点评/视觉读屏;端侧规则兜底;adviceFlow
├── network/ModelAPIClient.kt                Ktor+OkHttp:DeepSeek chat/completions;文本与视觉消息;运行时配置注入
├── parsing/                                 解析纯函数(2026-09-07 从 service 抽出,无 Android 依赖、可单测)
│                                            BillTimeParser(支付时间) · MerchantText(店名/噪声) ·
│                                            BillAmountText(金额/¥拆分合并) · BillKeys(去重键/日志时间)
├── scene/ ocr/ skill/ agent/ capture/ config/   识屏智能体(2026-09-07 移植自 sult_liban,MIT):MediaProjection 录屏
│                                            + MLKit 中文 OCR + 技能决策(预算/储蓄目标/冲动) + DeepSeek 估价;AgentGraph 单例
│                                            (画像/目标用真实预算账单播种,DeepSeek 自动同步,默认 deepseek-v4-flash)
├── data/
│   ├── FinanceDb.kt                         Room v3:BillEntity(timeBucket 分钟去重)+ DAO + Migration
│   ├── AccessibilityEventRepository.kt      事件总线(records/events/指令前缀)+ 入账合法性守卫 + postConsumption
│   ├── BudgetStore.kt / BudgetPlanner.kt    月预算/分类预算;快照+aiBudgetContext()+分类短提醒
│   ├── WeeklyReportBuilder.kt               近7 vs 前7 聚合 → WeeklyReportInput
│   ├── BillCategories.kt / BillSources.kt   本地分类关键词 / 来源规范集
│   ├── MerchantStore.kt                     本地商家库(Top 候选)
│   ├── UserSettings.kt                      SharedPreferences 配置(含下单判断开关)
│   └── FinanceModels.kt / DemoProfile.kt / NavigationPath.kt / UserPreference.kt
├── ui/HomeScreen.kt(三 Tab 主界面) · BillsScreen.kt(记账本) · MonthNav.kt · MainActivity.kt · theme/
├── utils/FloatingWindowManager.kt           悬浮窗:消费提醒/下单判断/抓取控制
├── utils/SecurePrefs.kt                     AES-GCM 加解密(enc: 前缀)
└── service/RealtimeGate.kt                 实时跨通道 6s 去重
app/src/debug/java/com/example/finance/debug/DebugReceiver.kt   测试广播接收器(仅 debug)
tools/                                      源码修复工具(事故产物,见下)
Finance-phone-backup-2150.apk               手机完好旧版 APK 备份(2026-09-06 20:57 构建)
```

### 真机环境(Redmi K60 Pro 22127RK46C,Android 14/MIUI,无线 adb)
- **坑1**:APK 更新/force-stop 后无障碍被重置且会"假 Bound"(事件全不来,特征是 logcat 无服务连接日志)。**修复仪式**:`settings put` 写入 → `am start` 前台拉起 → 确认出现 `♿ 无障碍服务已连接`。
- **坑1b(2026-09-07 新增)**:无障碍服务进**系统崩溃名单**后,症状是「任意版本抓取都停在点「我的」/无导航日志」,判据 `dumpsys accessibility` 见 `Crashed services:{...finance...}` 且 `rootInActiveWindow` 恒空(TEST_DUMP_SCREEN 报失败,连自己前台也读不到)。**清理仪式**:先 `settings put enabled_accessibility_services ''` → `am force-stop` → 再写入全名 + `accessibility_enabled 1` → 前台拉起 → 确认 Crashed services 为空且 DUMP 能出节点(顺序不可反,否则崩溃态残留)。
- **坑2**:理伴后台被小米冻结(事件/协程全停,`dumpsys` 不显示)。需要「省电策略=无限制 + 自启动」;冻结后拉起前台恢复。
- **坑3**:无线 adb 端口每次配对变化;uiautomator 不可用 → 用 `TEST_DUMP_SCREEN`。
- **坑4**:后台跨应用拉起平台 App 可能被拦(表现为"打开 X 秒后仍不在前台"),理伴前台时重发广播即可。

### 常用命令与测试广播(debug 包)
```bash
# 编译
$env:JAVA_HOME='D:\JAVA\IntelliJ IDEA 2025.2.4\jbr'; .\gradlew.bat :app:assembleDebug
# 无障碍重开仪式
adb shell settings put secure enabled_accessibility_services 'com.example.finance/com.example.finance.service.FinanceAccessibilityService'
adb shell settings put secure accessibility_enabled 1
adb shell am start -n com.example.finance/.ui.MainActivity
# 广播(目标 .debug.DebugReceiver)
TEST_FETCH_BILLS / TEST_FETCH_MEITUAN / TEST_FETCH_TAOBAO / TEST_STOP_FETCH / TEST_DUMP_SCREEN
TEST_SIMULATE(-e merchant/-e amount) / TEST_ADVICE / TEST_WEEKLY / TEST_NOTIFY / TEST_EXPORT_CSV / TEST_CLEAR_BILLS / TEST_CONFIGURE
# 日志
adb logcat -d 'FinanceAccessibility:V' 'FinanceUI:V' 'ModelAPI:V' '*:S'
# 配置 DeepSeek
adb shell am broadcast -a com.example.finance.TEST_CONFIGURE -n com.example.finance/.debug.DebugReceiver --es apiKey sk-xxx --es model deepseek-chat
```

---

## 四、近期里程碑(2026-09-07 更新,详见 DEV_STATUS)

0. **(2026-09-07)** P0 工程护栏 + P2 解析器测试化:编码三重防线(Check-Encoding.ps1 / git 预提交钩子 / Gradle encodingCheck 挂 preBuild)、事故残留归档 tools/accident-2026-09-06/、核心服务 2830→2692 行抽离 4 个纯解析对象,49 例 JUnit 全绿。

1. **下单前判断修复 + 真机校准**:150→300 自底向上采集、CTA 先判、词表分层(强词留结算页特有词,「去结算/立即购买」降弱词)、feedMarkers 删「满减」加「月售」、商家名 45% 高度选择器、「我的」开关、命中/未判日志。
2. **抓取链路修复**:「我的页」误判为订单列表(profile veto 修复)、支付宝「我的」页视觉兜底依赖 Key、90s 停滞看门狗、支付宝入口耐心重试、美团时间正则校准(`日?` 可选)。
3. **源码事故与恢复(教训)**:误用 GBK 回写损坏 `FinanceAccessibilityService.kt`(无 git/无备份)→ 用最后一次编译的 `.class` 提取 941 条字符串词典 + `tools/RepairStrings.java` LCS 校准 + 规格表逐行定稿,全部编译错误清零并真机复验;随后清理注释/日志乱码至全文件 0 处 `U+FFFD`。
4. **AI 链路复验 + 补齐**:实时点评/周报/视觉/批量点评全链路云端验证;补齐支付宝引擎"抓取后 AI 汇总点评"(原缺失,接入 ExternalAgg 金额/商家累计)。

---

## 五、已知限制与遗留

- 淘宝未匹配历史单一律不入账(保守策略;可选增强:进订单详情读时间)。
- 微信支付通知通道未接入(需先验证通知监听授权与真实样本)。
- CSV 导出为全量;按月份/来源过滤 + FileProvider 系统分享未做(P1)。
- 抓取依赖屏幕可达性;个别平台改版/按钮为纯图片时需按"未判日志"补词。
- 本机 AndroidKeyStore 不可用,Key 明文回退(已在手记标注,发版文档需提示)。
- 旧文档(README/ARCHITECTURE)未同步,勿引用其中已过时结论(如"本地 127.0.0.1:8000 AI 服务")。

---

## 六、下一步建议(优先级;2026-09-07 更新)

> ✅ 已完成:P0 编码护栏/提交纪律(git 钩子 + Gradle preBuild 双闸);P2 解析器纯函数化 + 49 例单测;
>   蓝版主题 UI(屏1~3)+4-Tab;**sult_liban 识屏智能体整合**(录屏+OCR+技能决策+DeepSeek 估价,「咨询」Tab);
>   记录页「每日花费」日历;**单测 16 套件 119 例全绿**;识屏 LLM 统一 DeepSeek(v4-flash)。

- **P1**:CSV 按月份/来源过滤 + FileProvider 系统分享(数据已真实可用,收益高)。
- **P1(AI)**:AI 月报 + 环比趋势图(近30 vs 前30),首页趋势卡;或智能消费异常预警(超支/深夜/高频小额)。
- **P2**:微信支付通知通道(先抓真实样本)。
- **P3**:对话式账单问答;淘宝订单详情取时间补全;预算/周报迭代。
- **P4**:正式签名发布、隐私合规文案(无障碍/通知/悬浮窗用途)、Key 存储说明。

---

## 七、核心数据流(一张图)

```
平台付款成功/账单页 → 无障碍事件(2s 节流)
  → 实时:商家+金额+时间 → RealtimeGate 6s 去重 → Room(分钟级去重)
  → 历史:导航进列表 → 逐屏文字/视觉解析 → 每笔带真实时间入库
Room → UI Flow(首页今日/月度、账单按日分组、预算进度)
  → AI:点评(预算上下文)/周报/批量点评/Top3 → 悬浮窗 + AI 建议卡
```
