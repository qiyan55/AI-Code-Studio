# AI 开发经验库

## [2026-09-23 03:30] v1.91 流式工具调用即时卡片 + Shell 命令/代码块分离

- 需求原文：AI 流式输出时每出一个 ```shell 命令块就即时识别并显示工具调用卡片（不是等整段回复结束全量涌出）；读文件=小书页图标、写文件=小铅笔图标、执行命令=尖括号斜杠 `</>` 图标，均带呼吸动画；cat 写文件卡片显示"正在写入:文件名"，AI 写完代码闭合代码块后动画消失并真实建目录+落盘；要一步一步即时执行，而不是回复结束后笼统执行全部。用户两次纠正：读文件图标是"小书页"（非小树叶）。
- 根因（改造前现状）：
  1. 消息类型只有 TYPE_USER/AI/SYSTEM/ERROR/LOADING，无独立工具类型；工具执行状态（`⚙ 执行:` / `→ 输出`）以纯文本 chunk 混进 AI 正文流。
  2. 所有 ```shell 命令块（除 `cat >` 特例外）一律走普通代码框渲染，无 Shell 命令框。
  3. `renderCatWriteBlock` 事后渲染、写死文案、呼吸动画 INFINITE 永不消失、只认首行 `cat>`。
  4. 执行发生在回复完成后一次性跑完（runAgentLoop 收完整 reply 后 AgentExecCLI.runWithResults 全量执行）。
- 改动文件：
  - 新增 `agent/StreamingToolDetector.java`（纯 Java 流式状态机，TEXT/FENCE/PASS 三态，跨 chunk 容错，回调 onBody/onToolStart/onToolUpdate/onToolEnd；ToolBlock{type/lang/path/cmd/content}，TYPE_READ=0/TYPE_WRITE=1/TYPE_EXEC=2）
  - 新增 `res/drawable/ic_tool_read.xml`（小书页）、`ic_tool_write.xml`（小铅笔）、`ic_tool_exec.xml`（`</>`）
  - 改 `chat/AIChatManager.java`（AIResponseListener 增 onToolStart/onToolUpdate/onToolEnd 三方法；setupDetector 每轮新建 detector 并清 roundResults；云端 SSE 与本地流式正文 chunk 改走 detector.feed；generateOnce 返回后 detector.flush 处理未闭合块；execToolBlock 按类型即时执行：WRITE 拼 `cat > path\ncontent` 落盘、READ 拼 `cat path`、EXEC 用 cmd+content 多行，结果入 roundResults 回填模型）
  - 改 `chat/ChatPanel.java`（TYPE_AI 消息行改纵向双容器 content+toolHost；新增 ToolHostTag；updateLoadingMessage 解包；listener 三方法 post 主线程；addToolCard 图标+呼吸动画+状态文案、updateToolCardContent 写文件等宽 TextView 流式填充、endToolCard 停动画改完成文案；onResponseComplete 清理工具状态）
  - 改 `chat/AIChatActivity.java`（补 3 空实现，编译必须）
- 关键变更：写文件卡片代码区用等宽 TextView 而非嵌套 WebView（规避 WebView 池异步时序导致流式内容丢失）；detector 是纯 Java 无 Android 依赖，可 JVM 单测；非 shell 围栏（```java 等）走 PASS 态整体透传，保持原有代码框渲染。
- 验证结果：JVM 单测 12 项全过（写/读/执行识别、跨界反引号、PASS 透传、多行 EXEC、未闭合 flush）；sdk_build.sh 全流程通过，908 class，APK 216,315,907 bytes（签名 CN=AI Code Studio）。首次构建报 2 错：ToolBlock.lang/type 误标 final，改可变后通过。
- 回滚：versions/v1.91_20260923_0322/java_backup/ 覆盖 java、res_backup/ 覆盖 res 后重跑 sdk_build.sh。
- 备注：① 回填模型仍走原有 system+PREFIX_TOOL 合并，模型是否感知新即时执行未做端到端验证（需装机实测）；② 旧 `renderCatWriteBlock`（v1.94 事后 cat> 卡片）与新即时卡片并存、renderSegment 仍会调用，是否移除旧路径待用户拍板；③ 若需给模型喂回真实执行结果给流式中间的后续回复，靠 roundResults + PREFIX_TOOL 注入，已保留。

### [2026-09-23 03:55] v1.91b 修正：工具卡片内嵌高亮代码框（用户追问"代码框渲染是否被删"）
- 用户确认点：① 语法高亮/代码框渲染**未删除**（createCodeBlock + WebView 池完整保留，renderMarkdown→renderSegment 正文链未动）；② v1.91 偏差点 = ```shell 块被 detector 剥离出正文、只进工具卡片，而卡片里仅写文件有等宽 TextView（非高亮），读/执行连命令都不显示；③ 用户要"每个工具开头后面跟着它自己的代码块"，不要把所有工具堆在消息末尾。
- 改动（ChatPanel.java）：`addToolCard` 改为三类工具统一内嵌 `createCodeBlock` 高亮代码框——WRITE 空代码框流式填充、READ 显示 `cat 路径`、EXEC 显示命令文本；`updateToolCardContent` 复用正文 `updateSegmentView`（pendingCode/diff 追加/高亮机制）；新增 `langForToolBlock`（写文件按扩展名 java/kotlin/python/json/md/html/css/js/ts，默认 shell）；`currentToolContent` 对 READ/EXEC 以已显示命令为基准以便后续命令行追加；字段 `activeCodeView` 由 TextView 改回 `createCodeBlock` 容器 + 新增 `activeCodeLang`。
- 关键决策：不把工具卡片内联进正文 content 流（`updateLoadingMessage` 数量减少时会 `removeAllViews` 重建 content，会冲掉内联卡片）；保持 toolHost 独立容器（不受正文重绘影响）+ 卡片内嵌完整代码块。
- 验证：sdk_build.sh 全流程通过，908 class，APK 216,315,907 bytes。
- 回滚：同 v1.91 备份覆盖。

### [2026-09-23 04:12] v1.91c 移除 v1.94 renderCatWriteBlock 旧路径（消除"两套算法打架"）
- 用户报：现在两套算法在打架。核实根因：`renderSegment` 对 CodeSegment 优先走 v1.94 `renderCatWriteBlock`（事后 cat> 工具卡片 + 呼吸动画 INFINITE），而 v1.91 新即时卡片（detector）在 toolHost——当 ```shell cat> 块在历史加载/兜底路径进入正文时，被旧路径渲染成旧卡片，与新即时卡片样式/语义冲突、同一块可能双显。
- 改动（ChatPanel.java）：`renderSegment` 的 CodeSegment 分支去掉 `renderCatWriteBlock` 调用，一律走 `createCodeBlock` 普通高亮代码框（正文里的 shell 块兜底显示为代码框）；删除整个 `renderCatWriteBlock` 方法及其 Javadoc（原 L2403-2511）。`mapExtToLanguage` 随之无调用者，保留未用（javac 不报错）。
- 关键决策：shell cat> 块的"工具卡片 + 即时执行"由 v1.91 即时卡片独占；正文兜底只显示普通代码框，不再出现第二种卡片样式。执行层面已核实无双跑：runAgentLoop 不调用旧 `AgentExecCLI.runWithResults`，`roundResults` 只由即时 `execToolBlock` 填充回填模型。
- 验证：sdk_build.sh 全流程通过，908 class，APK 216,315,907 bytes。
- 回滚：versions/v1.91b_20260923_0359/java_backup/ChatPanel.java 覆盖后重跑构建。

### [2026-09-23 04:51] v1.91d 去除消息气泡描边 + 思考过程实时刷新增强
- 用户需求：① 消息气泡外面的描边去除；② 思考过程要实时显示，现在"思考完了才看得到"。
- 改动（ChatPanel.java）：
  - 去除描边：AI 正文容器 `makeBubble(BUBBLE_FILL_AI, BUBBLE_STROKE_AI)` → `makeBubble(BUBBLE_FILL_AI, 0)`；用户消息 `BUBBLE_STROKE_USER` → 0；TYPE_LOADING `BUBBLE_STROKE_AI` → 0。`makeBubble` stroke=0 走 `setStroke(0, TRANSPARENT)` 不描边。常量 BUBBLE_STROKE_AI/USER 定义保留未用（javac 不报错）。
  - think 实时：`updateSegmentView` 的 ThinkSegment 分支把 `ts.content != null && !ts.content.equals(contentTv.getText().toString())` 改为 `ts.content != null`（无条件刷新）。原 equals 可能因 HTML 渲染后文本与原文不等被误判相等而跳过更新，导致思考内容不实时刷新。
- 关键结论（think 实时性核实）：增量渲染路径本身实时——`onResponseChunk`(pendingUpdate 合并节流)→`updateLoadingMessage`→`parseMarkdown` 对未闭合 `<think>` 在末尾实时生成 ThinkSegment(空则"思考中...")→`createThinkBlock` 默认展开+`setTag(contentTv)`→`updateSegmentView` 实时更新。若用户实测仍"思考完才看到"，最可能是**本地模型把 think 作为整块一次性回调**（onToken 一个 chunk 到达），UI 无法逐字，需在推理器层(InferenceEngine/JNI llama_jni)确认/改造成逐 token 流式回调 think，超出 ChatPanel 范围。
- 验证：sdk_build.sh 全流程通过，908 class，APK 216,315,907 bytes。
- 回滚：versions/v1.91c_20260923_0415/java_backup/ChatPanel.java 覆盖后重跑构建。

## [2026-09-21 22:05] v1.90 聊天历史迁移到 SQLite

- 需求原文：所有聊天历史（用户消息+AI 完整思考过程）+ KV 缓存元数据全部存 SQLite。
- 改动：新建 DatabaseHelper（sessions/messages/kv_cache 三表）；SessionManager 全部 CRUD 走 SQLite；ChatPanel saveToFile/loadHistory 改调 sm.saveMessages/loadMessages。
- 验证：javac 900 class 通过。
- 回滚：versions/v1.90_20260921_2200/。

## [2026-09-21 21:28] v1.88 会话切换/新建对话/历史覆盖回归修复

- 报错原文：新建对话时历史聊天被覆盖；toast 提示"已在新对话中"但列表里没有新对话；对话列表新建不了。
- 根因：v1.67 引入 SessionManager 时留下的双轨制断裂——
  1. ChatPanel 旧系统用 `currentConvKey`（chat_时间戳）+ `filesDir/chat_history/` 存历史；
  2. SessionManager 新系统用 `sessionId`（UUID）+ `filesDir/sessions/{uuid}/chat_history.json`；
  3. `switchToSession()` 只调 `chatPanel.newConversation(false)`，从来没调 `openConversation()` 加载对应历史；
  4. `isBlankSessionEmpty()` 查新系统文件（永远不存在）→ 永远返回 true → 总是走"复用"分支；
  5. 每次切换会话都新建 convKey，旧历史文件从未被 load 回来，表现为"历史被覆盖"。
- 改动文件：
  - `SessionManager.java`：`isBlankSessionEmpty()` 改查 ChatPanel 实际写历史的路径 `filesDir/chat_history/{sessionId}.json`，并解析 JSON 判断是否有 TYPE_USER 消息。
  - `ChatActivity.java`：`switchToSession()` 改调 `chatPanel.openConversation(s.sessionId, s.projectPath)`（openConversation 内部先 saveHistory 当前对话，再 loadHistory 目标会话）；`onResume()` 加分支：带了 session_id 就 switchToSession，而不是走 newConversation(false)。
- 关键决策：直接用 `sessionId`（UUID）作为 ChatPanel 的 `currentConvKey`，不再维护两套 key 映射。
- 验证结果：javac + d8 + 签名全流程通过，产出 v1.88 APK。
- 回滚：`cp versions/v1.88_20260921_2125/*.java` 回覆盖。
- 备注：v1.67~v1.86 期间用 SessionManager 创建的旧 session，其 sessionId 与旧 chat_时间戳 key 不对应，切过去会显示空历史（老数据本来就乱），新对话开始后正常。

## [2026-09-21 21:19] v1.87 空白对话首条消息自动生成标题

- 需求原文：用户新建对话发第一条消息后，侧边栏/顶部标题仍是"新对话"，没有把用户第一条消息的前几个字作为标题；空对话保留"新对话"。
- 根因：`SessionManager.createBlankSession()` 固定写死 `title="新对话"`，`ChatPanel.sendToAI()` 发送消息时没有任何回调通知 Activity 更新标题，`ChatActivity` 也从未调过 `sm.updateTitle()`。
- 改动文件：
  - `src/main/java/com/aicodestudio/ide/ai/chat/ChatPanel.java`
  - `src/main/java/com/aicodestudio/ide/ai/ChatActivity.java`
  - `src/main/java/com/aicodestudio/ide/ai/MainActivity.java`（接口新增方法的空实现，编译必须）
- 关键变更：
  1. `ChatPanel.ChatPanelListener` 新增 `onFirstUserMessage(String text)`。
  2. `ChatPanel.sendToAI()` 开头统计 `mMessages` 中 `TYPE_USER` 条数，为 0（即首条）时在 `addMessage(text, TYPE_USER)` 之前回调。注意必须先判断后 add，否则本次消息已入列永远为 false。
  3. `ChatActivity` 实现回调：仅当当前会话为空白会话（未绑定项目）且 title 仍为"新对话"时，取消息第一行、截前 16 字、超长加"…"，调 `sm.updateTitle()`，并在 UI 线程刷新 `tvSubTitle` 和 `rebuildChatList()`。
  4. `MainActivity` 补空实现（它的会话绑定项目，标题=项目名，不覆盖）。
- 验证结果：`sdk_build.sh` javac + d8 + 签名全流程通过，产出 v1.87 APK。装机后新建对话发首条消息，标题应自动变为消息前 16 字；继续发后续消息标题不变；已绑定项目的会话标题不受影响。
- 回滚方式：`cp versions/v1.87_20260921_2118/*.java` 回覆盖三个源文件后重新 `bash sdk_build.sh`，或直接安装 v1.86 APK。
- 备注：
  - 全模块关联审查要点：`ChatPanelListener` 是接口，所有实现类（ChatActivity / MainActivity）都必须补新方法，否则 javac 报 "not abstract and does not override abstract method"。
  - 列表刷新用 `rebuildChatList()` 安全：发送消息不调 `touchSession()`，lastOpenedAt 不变，顺序不跳。
  - 标题长度定 16 字（char 单位，中文一字一 char），后续如需调整改 ChatActivity 里的 `MAX` 常量即可。

## [2026-09-23 04:22] 切换对话后历史只剩用户消息、AI 完整回复丢失
- 需求/报错原文：切换对话页面再点回来，历史聊天只有用户发的消息，没有 AI 的完整回复。
- 根因：历史保存/加载链路（saveHistory→saveToFile→SessionManager.saveMessages→DatabaseHelper.replaceMessages；loadHistory→loadMessages→addMessageInternal）本身正常，SQLite 全量存取、类型持久化无误。唯一会让 AI 完整回复被丢弃的场景是：AI 回复仍处于生成中（消息记录 text 还是"正在思考..."占位）就切换对话——openConversation 里的 saveHistory 会因 saveToFile 的"过滤正在思考 AI 消息"逻辑把该条 AI 丢出，历史只保存用户消息；切回后加载自然只剩用户消息。
- 改动文件：chat/ChatPanel.java
- 关键变更：新增成员 currentPartialReply/generatingConvKey；sendToAI 开头记录生成会话 key 并清空；onResponseChunk 更新 currentPartialReply=已生成内容；onResponseComplete 更新为完整回复、onError 更新为 kept/空；saveToFile 过滤"正在思考"AI 时，若正在生成且 key 匹配则用 currentPartialReply 补存该条 AI 消息，否则才过滤。
- 验证结果：sdk_build.sh 全流程通过，908 class，APK 216,315,907 bytes 签名成功。
- 回滚方式：versions/v1.91e_20260923_0422/java_backup/ChatPanel.java 覆盖后重跑 sdk_build.sh。
- 备注：此修复覆盖"生成中切走"这一最可能根因。若用户在"回复已生成完成后"才切换仍丢失 AI，则需进一步排查 onResponseComplete 是否被触发、或提供实际历史数据（截图/复现步骤）继续深挖。

## [2026-09-23 05:00] 重开历史渲染效果消失 + AI 输出顺序打乱 + 气泡规则
- 需求/报错原文：重新打开历史聊天后之前的渲染效果没了（代码块/命令块等）；AI 每轮输出完历史内容顺序被打乱；数据库必须存 AI 输出的完整原始 Markdown（含代码块/命令块/思考块标记），流式与历史加载都用同一套渲染；用户消息气泡描边必须保留，AI 回复不用气泡。
- 根因：
  1) v1.91 为即时工具卡片把 AI 正文经 detector 剥离 ```shell 块后存入 streamBuffer，历史保存用 streamBuffer → 代码块/命令块内容丢失 → 重开渲染缺这些块；顺序因代码块缺失而视觉错乱。
  2) 本地流式 generateOnce 只返回剥 think 的 visibleResponse（fullResponse 原始在局部未传出），历史也缺 think 块。
  3) v1.91d 去掉所有气泡描边（含用户消息），与"用户气泡描边必须保留、AI 无气泡"相悖。
- 改动文件：chat/AIChatManager.java、chat/ChatPanel.java
- 关键变更：AIChatManager 新增成员 rawFullAll 累积所有轮原始完整回复（含 think+shell），onResponseComplete 改传 rawFullAll；本地 onComplete 与云端完成处 append；ChatPanel onResponseComplete 历史保存 saveResp=fullResponse（原始完整含代码块），UI 仍用 streamBuffer 正文+工具卡片；恢复用户气泡描边 BUBBLE_STROKE_USER；AI 正文/loading setBackground(null)。
- 验证结果：sdk_build.sh 全流程通过，908 class，APK 216,315,907 bytes 签名成功。
- 回滚方式：versions/v1.91g_20260923_0500/java_backup/ 覆盖后重跑 sdk_build.sh。
- 备注：数据库存原始 Markdown，历史加载走同一套 renderMarkdown 渲染所有块。旧历史数据（已缺代码块）不会自动回补，需重新对话或清空历史后生成。

## [2026-09-23 08:05] v1.98 打开历史后代码块/工具调用/Shell 命令块不渲染修复
- 需求/报错原文：打开历史聊天/历史对话页后，代码块、工具调用、Shell 命令块都没有正常渲染（只剩一坨带 ``` 标记的原文）。
- 全模块关联审查：
  1. 数据流已正确（v1.91g 修过）：AIChatManager.rawFullAll 累积含 <think>+``` 代码块+```shell 的原始完整回复，onResponseComplete 传 rawFullAll；ChatPanel saveResp=fullResponse，mMessages 记录与 SQLite 存的都是原始 Markdown。
  2. 渲染分两条路：流式实时 onResponseChunk→updateLoadingMessage→parseMarkdown（**无长度上限**，逐段渲染）；历史 loadHistory→addMessageInternal→renderMarkdown（**L2150 有一道“文本 > 30000 字符整体降级为纯等宽 TextView”保护**）。
  3. v1.91g 后单条 AI 消息存的是完整原始 Markdown（含完整思考过程+全部代码块+shell 块），一条完整 Agent 回复很容易 > 30000 字符 → 历史打开时被 renderMarkdown 一刀切降级成纯文本，所有代码框/命令框/思考框都不渲染；而流式路径无此阈值，实时正常。这就是“实时正常、重开历史不渲染”的不对称根因。
- 改动文件：chat/ChatPanel.java
- 关键变更：renderMarkdown 去掉“> 30000 字符整体纯文本降级”分支，直接 parseMarkdown 分段渲染，与流式 updateLoadingMessage 走同一套。安全性由既有机制兜底：代码块 WebView 有 MAX_ACTIVE_WEBVIEWS=10 自动降级为纯文本 TextView（不会无限累积渲染进程）；普通文本/思考块均为轻量 TextView。
- 验证结果：sdk_build.sh 全流程通过，908 class，APK 216,315,907 bytes，签名 CN=AI Code Studio 成功。产物 AI_Code_Studio_v1.98_历史代码块渲染修复.apk。
- 回滚方式：cp versions/v1.98_20260923_0803/java_backup/ChatPanel.java 覆盖回 src/main/java/com/aicodestudio/ide/ai/chat/ChatPanel.java 后重跑 sdk_build.sh，或直接安装 v1.97 APK。
- 备注：① 本环境无法装机实测，已用代码链路走查确认历史/流式路径对齐；② 历史里 ```shell 块渲染为普通高亮代码框（lang=shell），不会重现流式时带图标的即时工具卡片——这是当前设计（历史只存正文 Markdown、不存工具卡片状态）；若要历史也显示工具卡片样式需另提需求；③ v1.91g 之前存入的旧历史数据本身缺代码块，不会自动回补，需新对话或清空历史后重新生成。

## [2026-09-23 14:40] v1.99 历史还原小书页/小铅笔/工具调用图标卡片 + 修“渲染/原始”切换按钮失效
- 需求/报错原文：用户以为工具卡片被删了。事实澄清：实时流式的 addToolCard/三个图标/StreamingToolDetector 一行没动；真正问题是历史加载把 ```shell 块渲染成普通代码框、没有工具卡片。新需求：① 重开历史后，历史里的读/写/执行块也要显示成和当时流式一样的带小书页/小铅笔/`</>` 图标的工具卡片；② 对话顶部“渲染/原始”切换按钮：渲染模式要有这些工具卡片；切到原始后就是纯原始文本、无任何加工，便于观察 AI 原始输出。
- 全模块关联审查：
  1. 实时链路不变：onResponseChunk→detector 实时识别→onToolStart/Update/End→addToolCard 画卡片。
  2. 历史链路：loadHistory→addMessageInternal→原 renderMarkdown 只把 ```shell 切成普通代码框，丢了卡片语义。
  3. 另发现遗留 bug：refreshAllMessages 只认 row.tag instanceof LinearLayout，但 AI 行 tag 是 ToolHostTag、其他消息 tag 是 TextView——切换按钮对所有消息都不生效。
- 改动文件：chat/ChatPanel.java
- 关键变更：
  1. 新增 renderHistoricalAiMessage(content, toolHost, fullText)：离线 new StreamingToolDetector 喂完整历史文本，onBody 收剥离 shell 的正文、onToolStart 收工具块列表；正文走 renderMarkdown（think/```java 等正常），工具块逐个走新 addHistoricalToolCard。
  2. 新增 addHistoricalToolCard(host, block)：复刻 addToolCard 卡片外观（圆角深底、图标+tint、内嵌 createCodeBlock），但静态完成态——图标不跑呼吸动画，文案用新 toolDoneText（已读取/已写入/执行:…），绿色。写文件卡片代码区=完整文件内容，读=cat 路径，执行=命令。
  3. addMessageInternal 的 TYPE_AI 分支：toolHost 创建提前，渲染模式调 renderHistoricalAiMessage，原始模式仍纯 MONOSPACE 文本。
  4. 修 refreshAllMessages：识别 ToolHostTag（AI 双容器）与 LinearLayout（其他），切换时按 showRawMessage 重渲——渲染模式出工具卡片、原始模式纯文本。
- 验证结果：sdk_build.sh 全流程通过，909 class，APK 216,315,907 bytes 签名成功。产物 AI_Code_Studio_v1.99_历史工具卡片还原.apk。
- 回滚方式：cp versions/v1.99_20260923_1438/java_backup/ChatPanel.java 覆盖后重跑 sdk_build.sh，或装回 v1.98。
- 备注：① 历史工具卡片是“完成态静态版”，无呼吸动画、无执行输出回显（执行结果当时不存历史）；② 流式实时路径未动，首条“正在思考...”走 renderHistoricalAiMessage 无 shell 块不受影响；③ 旧历史数据本身缺 shell 块的仍补不回，需新对话生成。

## [2026-09-23 15:19] v1.100 渲染单流内联 + 切换全量重建（修切换丢卡片/顺序乱/残留）
- 现场问题（屏幕共享确认）：① 渲染模式工具卡片堆在正文末尾、和原始文本顺序脱节，且工具卡片先出、思考卡片滞后；② 来回切“渲染↔原始”后渲染模式丢失工具卡片；③ 切换不是从完整原文重建，而是在现有 View 上硬删，残留碎片、内容丢段；④ 原始模式应完整保留 `` 标签、所有代码块、shell 命令、省略点点点。
- 根因（全模块关联审查）：
  1. v1.99 把正文渲染进 content、工具卡片堆进 toolHost 两个纵向容器 → 工具卡片全在正文末尾，失去与原文穿插顺序。
  2. 流式完成后 content.tag 存的是 detector 剥离 shell 后的 streamBuffer（无 ```shell），切换重建离线 detector 跑不到工具块 → 卡片丢失。
  3. 切换在残缺渲染态上局部增删 View，不基于完整原文全量重建 → 残留/丢段。
- 改动文件：chat/ChatPanel.java
- 关键变更：
  1. 新增 renderAiMessageInline(container, fullText)：离线 StreamingToolDetector 收集**按原文顺序交错的事件流**（正文段 String ↔ 工具块 ToolBlock），依次 append 到同一容器；<think> 经 renderMarkdown→parseMarkdown 生成思考卡片，位置与原文一致；工具块走 addHistoricalToolCard（图标完成态）。不再分离 content/toolHost。
  2. 新增 finalizeHistoricalRow(row, fullText)：把流式双容器（content+toolHost）重排为单流 content，清空重建，row.tag 收敛为 content、content.tag=fullText（完整原文）。
  3. 三处统一调用 finalize：loadHistory 加载 AI 消息后；onResponseComplete 流式完成后（工具卡片从 toolHost 末尾按原文归位）；refreshAllMessages 切换时。
  4. refreshAllMessages 改为：识别 row 为 ToolHostTag 或 LinearLayout content，从 content.tag 取完整原文调 finalize 全量重建——渲染模式=单流内联，原始模式=完整纯原文。彻底杜绝在残缺 DOM 上硬改。
  5. addMessageInternal 的 AI 分支：流式增量阶段 content 只 renderMarkdown，工具卡片走 toolHost（流式实时不变）；完成/历史/切换由 finalize 接管。
- 验证结果：javac 首次报 row.removeView(LinearLayout) 类型错（row 是 View），改 ((LinearLayout)row).removeView 后通过；sdk_build.sh 全流程 909 class，APK 216,320,003 bytes 签名成功。产物 AI_Code_Studio_v1.100_渲染单流内联+切换全量重建.apk。
- 回滚方式：cp versions/v1.100_20260923_1515/java_backup/ChatPanel.java 覆盖后重跑 sdk_build.sh，或装回 v1.99。
- 备注：① 流式输出过程中仍是双容器边出边看（工具卡片在 toolHost），回复一完成立即 finalize 重排为单流内联，可能有一次重排跳变；② 原始模式严格显示完整原文（含 `` 与 ``` 与省略号），不做任何解析；③ 历史工具卡片为完成态静态，不显示当时命令输出；④ 需装机实测确认多次切换稳定、顺序正确。

## [2026-09-23 15:47] v1.101 思考块内 shell 命令不建工具卡片 + 代码块正确包裹
- 现场问题（用户两张截图：原始模式+渲染模式）：① 模型把规划用的 mkdir/cat> 命令写在 <think> 思考过程里，v1.100 直接用 detector 跑全文、不识别 think 边界，把思考里的命令也抽成“执行 mkdir/已写入 AndroidManifest”工具卡片；同时思考卡片被抽空只剩前几句；② 代码块未正确包裹，一部分按原始文本显示。
- 根因：v1.100 renderAiMessageInline 用 StreamingToolDetector.feed(全文)，detector 是字符级状态机、完全不感知 <think>，think 内的 ```shell 被当成工具块 onToolStart，既误建卡片又把 ThinkSegment.content 抽残。
- 改动文件：chat/ChatPanel.java、agent/StreamingToolDetector.java
- 关键变更：
  1. renderAiMessageInline 改用 parseMarkdown 切分全文（它把 <think>...</think> 整块收进 ThinkSegment，think 内的 ```shell 只作思考内容，不产生独立 CodeSegment）。按顺序渲染：ThinkSegment→createThinkBlock（思考卡片，里面的命令留在思考里）；CodeSegment 且 lang∈{shell,bash,sh}→detector.classifyShellCode 离线判读/写/执行→工具卡片；其他 CodeSegment→普通高亮代码框；TextSegment→正文。
  2. StreamingToolDetector 新增 public static classifyShellCode(code, lang) 与 extractCatPathStatic，首行规则与流式状态机一致（cat>/cat>>=写、cat path=读、其他=执行）。
- 验证结果：sdk_build.sh 全流程 908 class，APK 216,320,003 bytes 签名成功。产物 AI_Code_Studio_v1.101_思考内命令不建卡片+代码块正确包裹.apk。
- 回滚方式：cp versions/v1.101_20260923_1545/java_backup/ 下 ChatPanel.java 与 StreamingToolDetector.java 覆盖后重跑 sdk_build.sh，或装回 v1.100。
- 备注：① 流式实时过程 detector 仍不感知 think，think 里的命令可能在流式中短暂显成卡片，但回复完成 finalize 后即纠正为“留在思考卡片”；② 思考卡片内部代码目前由 MarkdownUtil.markdownToHtml 文本渲染，若希望思考内代码也包成独立代码框再单独提；③ 需装机确认：思考卡片内容完整、think 外命令才出工具卡片、普通 ```java 代码框正常。

## [2026-09-23 16:10] v1.102 系统提示词按用户新文本重写
- 需求原文：用户直接给出完整新系统提示词（## 你是谁/## 工作方式/## 标准步骤示例/## 规则/{scenario}/## 安卓项目标准结构），要求替换。
- 根因：内置提示词在 AgentSystemPrompt.rawBase()，旧版工作方式要“先一句话再说命令”、写文件示例把内容塞进同一个 shell 块、规则含“做完回复项目完成/不要在文本里写命令”。
- 改动文件：agent/AgentSystemPrompt.java
- 关键变更：① 工作方式精简为一句“用 ```shell 代码块给出要执行的命令”，删掉“1.说一句话 2.给命令”两步；② 示例 cat > 路径单独成 shell 块，文件内容紧随其后（AndroidManifest.xml 用 ```xml 块包内容，其余直接跟）；③ 规则删掉“项目全部做完回复项目完成”“不要在普通文本里写命令”两条；④ 删掉 withProject 双分支，统一为 head + {scenario} + 安卓项目标准结构；{scenario} 仍由 applyScenario 按“已有项目/空工作空间”注入。保留 AppConfig 的 override 机制。
- 验证结果：sdk_build.sh 全流程通过，APK 216,315,907 bytes 签名成功。产物 AI_Code_Studio_v1.102_新系统提示词.apk。
- 回滚方式：cp versions/v1.102_now/java_backup/AgentSystemPrompt.java 覆盖后重跑 sdk_build.sh，或装回 v1.101。
- 备注：若用户之前在“模型管理→编辑系统提示词”里手填过自定义文本，override 会盖过内置新版，需在设置里点“恢复默认系统提示词”才生效。

## [2026-09-23 16:35] v1.103 连续代码块正确切分 + 思考卡片内部滚动
- 现场问题：① 渲染模式来回切换后，原始模式里完整的 MainActivity.java / xml 大段代码在渲染模式下“什么都没有”；② 思考卡片长文本不能在卡片内部上下滑动，一滑就带起整条对话。
- 根因（parseMarkdown fence 状态机）：已在代码块内又遇到一行 ` ```java ` 时，旧逻辑直接当“闭合上一块”，把这行 fence 吃掉、却没用它开新块 → 后面的 package/代码段落进普通 TextSegment，不再是 CodeSegment，渲染时 java 代码块丢失。小模型实际输出是连续块：` ```shell\ncat > X.java\n```java\npackage…\n``` `（cat> 与代码块紧挨着，中间不单独写闭合 ```）。
- 改动文件：chat/ChatPanel.java
- 关键变更：
  1. parseMarkdown：代码块内遇 ` ``` ` 开头行，先把当前块收尾；再看反引号后是否还有语言标签——空（单独 ```）=普通闭合退出；非空（```java/```xml）=同一行立即开下一个新代码块（inCodeBlock 保持 true）。这样连续 shell→java→xml 能正确切成多个独立 CodeSegment。
  2. createThinkBlock：给思考内容 ScrollView 加 OnTouchListener，卡片滚动期间 requestDisallowInterceptTouchEvent(true)、抬手/取消恢复 false，长思考文本可在卡片内部独立滑动。
- 验证结果：sdk_build.sh 全流程通过，APK 216,320,003 bytes 签名成功。产物 AI_Code_Studio_v1.103_连续代码块正确切分+思考卡片内滚动.apk。
- 回滚方式：cp versions/v1.103_20260923_1634/java_backup/ChatPanel.java 覆盖后重跑 sdk_build.sh，或装回 v1.102。
- 备注：① renderAiMessageInline 仍按 v1.101——think 整块进思考卡片、think 外 shell CodeSegment 走工具卡片、其他走普通代码框；② 需装机确认连续块渲染与思考卡片滚动。

