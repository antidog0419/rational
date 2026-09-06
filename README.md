# 理伴(Finance)· 智能财务伙伴

> 个人财务 AI 伙伴:Kotlin + Jetpack Compose 的 Android 应用,通过**无障碍服务**自动感知真实消费并记账,配合 **DeepSeek 云端 AI**(可运行时配置,失败自动端侧兜底)给出点评、预算提醒、周报与外卖推荐。
> 详细总结见 [`APP_SUMMARY.md`](./APP_SUMMARY.md);逐轮开发记录见 [`DEV_STATUS.md`](./DEV_STATUS.md)(顶部最新)。

## 核心信念:不手动记账

- 无障碍 + 通知自动感知每笔真实消费(支付宝 / 美团 / 淘宝闪购付款页、账单页);
- 账单带**真实时间**入库,月度/分类/分钟级去重后统计真实;
- 数据全部保存在本机 Room 数据库,不上传。

## 功能特性

### 自动记账(三大通道)
- **支付宝**:全自动进入账单页逐屏解析(行尾时间提取:`今天 14:42 / 09-04 22:14 / 昨天…`);「我的」页内容隐藏时由视觉模型定位入口。
- **美团**:订单列表文字优先解析(行内 `下单：2026-09-02 10:54` 提取真实时间;`¥/9/.9` 拆分金额合并)。
- **淘宝闪购**:冷启动回主界面 → 我的淘宝 → 我的订单 → 闪购订单卡解析;时间采取保守策略(与支付宝金额+店名匹配,匹配不到一律不入账,绝不伪造日期)。
- **实时通道**:付款成功页事件 + 通知监听,跨通道 6 秒去重,入账即弹悬浮窗 + AI 点评。
- 抓取条数上限可调(10~500),抓取控制悬浮窗可随时停止。

### 数据与记账本
- Room 本地库(v1→v3 自动迁移),唯一索引 + 分钟级去重(来源+商家+金额+分钟);
- 来源归一化(支付宝/美团/淘宝闪购/手动),按来源批量清理;
- 账单页:按日分组、月份切换、来源筛选、手动补记/编辑/删除;CSV 导出(含备注)。

### AI 能力(DeepSeek,可运行时配置;云端失败端侧兜底)
- 实时消费点评(带当月/分类剩余预算);
- **下单前 AI 判断**:美团/淘宝结算页识别商家+金额,悬浮窗提示「合适/谨慎/不建议」(「我的」页可开关);
- 抓取后批量点评(三平台);
- 每周 AI 小结(近 7 天 vs 前 7 天 + 预算);
- 首页 AI Top3 外卖推荐(本地商家库积累);
- 视觉读屏(定位隐藏入口 / 读账单行,需云端视觉模型)。

### 三 Tab 界面
- **首页**:今日支出、月度浏览、预算进度条(已花/剩余/日均可用/分类小计)、AI 建议卡、周报与 Top3 入口;
- **账单**:记账本(见上);
- **我的**:无障碍门控、AI/守卫开关、DeepSeek 配置、预算设置、抓取设置、数据清理、CSV 导出。

## 技术栈

| 项 | 值 |
|---|---|
| 语言 / UI | Kotlin · Jetpack Compose(Material3) |
| 无障碍/悬浮窗 | AccessibilityService · WindowManager 悬浮窗 |
| 数据库 | Room(分钟级去重唯一索引) |
| 网络 | Ktor + OkHttp(DeepSeek chat/completions,文本+视觉消息) |
| 异步 | Kotlin Coroutines / SharedFlow 事件总线 |
| SDK | compileSdk 36 · targetSdk 34 · minSdk 24 |
| 应用 ID | com.example.finance(debug 含测试广播接收器) |

## 工程结构(节选)

```
app/src/main/java/com/example/finance/
├── service/FinanceAccessibilityService.kt  无障碍服务:实时感知 + 三平台历史抓取 + 下单前判断(核心)
├── ai/AIService.kt                          AI 编排:点评/周报/Top3/批量点评/视觉读屏 + 端侧兜底
├── network/ModelAPIClient.kt                DeepSeek HTTP 客户端(Key/模型/地址运行时配置)
├── parsing/                                 解析纯函数:支付时间/金额(¥拆分)/店名噪声/去重键(2026-09-07 抽出,49 例单测)
├── data/                                    Room/预算/周报聚合/分类与来源/商家库/设置/事件仓库
├── ui/                                      首页 · 账单(记账本) · 我的 三 Tab(Compose)
├── utils/                                   悬浮窗管理、Key 加密(SecurePrefs)、启动器等
└── service/PaymentNotificationListener.kt   通知监听(实时入账通道之一)
app/src/debug/…/DebugReceiver.kt             测试广播接收器(仅 debug 构建,release 不含)
```

## 安装与使用

1. `assembleDebug` 后安装(或 `adb install -r app/build/outputs/apk/debug/app-debug.apk`);
2. 打开 App →「我的」→ 开启**无障碍服务**(系统设置页开启后返回);
3. (可选)在「我的 → DeepSeek 云设置」填入 API Key 与模型(默认 `deepseek-chat`);不填则 AI 走端侧规则;
4. 「账单」页点对应平台的「抓取」按钮,或直接在平台里正常消费(实时感知自动入账);
5. 首页查看今日支出/预算/AI 建议;美团/淘宝结算页会自动出现「下单前判断」。

> 注意:本机为小米(MIUI)设备时,APK 更新/force-stop 后无障碍常被重置,需重新开启并让 App 处于前台一次(详见 DEV_STATUS「修复仪式」);建议给理伴设置「省电策略=无限制 + 自启动」。

## 构建与调试

```bash
# Windows(需先设置 JAVA_HOME,示例)
$env:JAVA_HOME='D:\JAVA\IntelliJ IDEA 2025.2.4\jbr'
.\gradlew.bat :app:assembleDebug          # 调试包(含测试广播)
.\gradlew.bat :app:assembleRelease        # 发布包(不含测试钩子)

# 测试广播(debug 包,驱动全自动验证)
adb shell am broadcast -a com.example.finance.TEST_FETCH_BILLS -n com.example.finance/.debug.DebugReceiver   # 抓支付宝
adb shell am broadcast -a com.example.finance.TEST_FETCH_MEITUAN -n com.example.finance/.debug.DebugReceiver # 抓美团
adb shell am broadcast -a com.example.finance.TEST_WEEKLY -n com.example.finance/.debug.DebugReceiver       # 周报
adb shell am broadcast -a com.example.finance.TEST_SIMULATE -n com.example.finance/.debug.DebugReceiver --es merchant '沙县小吃' --es amount 28.5
adb shell am broadcast -a com.example.finance.TEST_DUMP_SCREEN -n com.example.finance/.debug.DebugReceiver   # 页面结构 dump
# 日志
adb logcat -d 'FinanceAccessibility:V' 'FinanceUI:V' 'ModelAPI:V' '*:S'

# 单元测试(解析纯函数,无需真机)
.\gradlew.bat :app:testDebugUnitTest

# 编码护栏(2026-09-07 起,防 GBK 误回写损坏源码)
.\gradlew.bat :app:encodingCheck          # 每次构建已自动执行
powershell -File tools/Check-Encoding.ps1 -ScanPath app\src   # 手动扫描
git config core.hooksPath .githooks       # 已配置;提交时自动检查暂存文件
```

## 权限说明

| 权限 | 用途 |
|---|---|
| 无障碍服务 | 读取平台付款/账单页内容(核心,功能门控) |
| 悬浮窗(SYSTEM_ALERT_WINDOW) | 消费提醒 / 下单前判断 / 抓取控制悬浮窗 |
| 通知监听 | 付款类通知的实时入账通道 |
| 网络 INTERNET | DeepSeek 云端调用(可不用,端侧兜底) |
| 通知 POST_NOTIFICATIONS / 前台服务 / 定位 | 预留与历史权限 |

## 隐私与安全

- 账单与设置全部存本机 Room/SharedPreferences,不收集、不上传;
- DeepSeek API Key 落盘时经 AES-256-GCM 加密(`enc:` 前缀;设备 Keystore 不可用时自动回退明文,不丢数据);
- 无障碍事件 2s 统一节流;隐私合规文案与正式签名发布列为发版待办。

## 文档索引

- `APP_SUMMARY.md` — 应用总结(功能全景 / 代码地图 / 真机运维 / 路线图,最新)
- `DEV_STATUS.md` — 逐轮开发日志与命令(顶部为最近更新)
- `ARCHITECTURE.md` / 旧版 README — 早期架构笔记,已过时,请勿引用其中"本地 AI 服务 / Room 未落地"等结论

## Roadmap(近期)

- CSV 按月/来源过滤导出 + 系统分享(FileProvider)
- AI 月报 + 环比趋势;智能消费异常预警;对话式账单问答
- 微信支付通知通道;淘宝订单详情取时间补全
- 正式签名发布与隐私合规文案

## License

仅供学习与个人使用。
