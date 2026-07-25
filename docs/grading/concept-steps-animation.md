# 概念类错误动画：CONCEPT_STEPS（路 B）

> 本文档说明「概念/原理类错误」的教学动画如何生成与渲染。核心思路：**大模型只决定"讲什么"（产出结构化步骤数据），前端固定引擎决定"好不好看"（渲染）**。这取代了早期让大模型直出整段 HTML 的 `HTML_ANIMATION` 方案。

## 1. 背景与动机

早期概念类动画走 `HTML_ANIMATION`：大模型一次性生成一整份可独立运行的 HTML 文档，前端用 `<iframe srcdoc>` 播放。问题是把「内容 + 样式 + 布局 + 交互」全压给一次大模型调用，导致每次风格/布局/箭头都在漂、质量不稳、偶发跑不起来。

对标国内「图码」（totuma.cn）的算法动画后确认：其质量来自**把"要展示什么"（一份步骤/命令数据模型）和"怎么渲染"（一个固定手工调校的引擎）彻底分开**，并让三条轨道同步：**代码行高亮 ↔ 画面 ↔ 旁白字幕**。

`CONCEPT_STEPS`（路 B）就是把这套思路落到本项目：复用已有的执行可视化播放器（`PythonTutorRenderer` + 步骤播放器），让大模型只产出步骤数据。

## 2. 数据流

```
错误候选(AnimationCandidate)
  → AnimationWorkflowRouter.route()  // CONCEPT 及 text/vlm 证据 → CONCEPT_STEPS
  → ConceptStepsWorkflow.generate()  // 调大模型，产出严格 JSON 步骤数据
  → AnimationResult(frames + metadata)
  → GradingErrorDemonstrationService.toErrorDemonstration()  // metadata 带示意代码时优先采用
  → ErrorDemonstration(前端消费)
  → 前端 ErrorDemonstrationPlayer.vue 的 CONCEPT_STEPS 分支渲染
```

## 3. 后端

### 3.1 关键类

| 文件 | 职责 |
|---|---|
| `service/grading/animation/AnimationWorkflow.java` | 枚举新增 `CONCEPT_STEPS`；`HTML_ANIMATION` 标 `@Deprecated` 保留兼容 |
| `service/grading/animation/ConceptStepsWorkflow.java` | 路 B 核心：系统提示词 + 严格 JSON 解析 + 越界防护 + 静态兜底 |
| `service/grading/animation/AnimationWorkflowRouter.java` | `case CONCEPT -> CONCEPT_STEPS`；`defaultRoute` 的 text/vlm 证据 → `CONCEPT_STEPS` |
| `service/GradingErrorDemonstrationService.java` | 注入并在 `executeWorkflow` 增 `case CONCEPT_STEPS`；`toErrorDemonstration` 在 `metadata.sourceCode` 非空时优先采用示意代码 |

### 3.2 大模型输出的步骤 schema（`ConceptStepsWorkflow` 强约束）

大模型被要求**只输出**下面这个 JSON（不得输出 HTML）：

```jsonc
{
  "title": "简短标题",
  "concept": "核心概念，如 链表结构 / 指针与内存 / 递归执行过程",
  "dataStructure": "见 §3.5 全量结构词表（如 linked-list / stack / binary-tree / hash-table …）",
  "sourceCode": "<=25 行示意代码，仅用于配合讲解（不必是学生原代码）",
  "errorLine": 0,
  "correctedCode": "关键正确写法（可空）",
  "explanation": "一句话讲清概念/为什么错",
  "rows": 0, "cols": 0, "capacity": 0, "buckets": 0,   // 结构级标量，按需给（见 §3.5）
  "steps": [
    {
      "line": 3,                                  // 高亮 sourceCode 的行(1起)，无则 0
      "caption": "旁白字幕(<=32字，语气自然)",
      "variables": { "pHead": "NULL", "x": "30" },
      "nodes": [ { "id": "n1", "label": "20", "value": "20", "active": true, "index": 0 } ],
      "edges": [ { "from": "n1", "to": "n2", "label": "next", "kind": "next" } ],
      "pointers": [ { "name": "pHead", "target": "n1" } ],
      "error": false
    }
  ]
}
```

### 3.3 健壮性约束

- **解析**：先剥 ```` ```json ```` 围栏，再取首个 `{` 到末个 `}`，最后 Jackson 解析。
- **越界防护**：步数 ≤ 12、每步节点 ≤ 12、示意代码 ≤ 30 行、`dataStructure` 走白名单，越界回落 `code`。
- **兜底**：大模型不可用或解析失败时，返回单步静态帧（`dataStructure=code`，caption = 教师批注），保证前端仍能渲染。
- **示意代码回流**：`sourceCode / correctedCode / errorLine` 放进 `AnimationResult.metadata`；`toErrorDemonstration` 检测到 `metadata.sourceCode` 非空时，用它覆盖 `ErrorDemonstration.sourceCode`，并由每步 `line` 驱动高亮（不强加固定 highlight 区间）。**其它工作流行为不变**。

### 3.4 帧 → 前端契约

`ConceptStepsWorkflow` 产出的每帧 Map 键与前端播放器一致：
`order` / `line` / `variables`(Map) / `state{dataStructure,nodes,edges,pointers,rows?,cols?,capacity?,buckets?}` / `explanation`(= caption) / `error` / `memory`(空)。

### 3.5 支持的结构词表与数据字段

后端 `ConceptStepsWorkflow.ALLOWED_STRUCTURES` 与前端 `PythonTutorRenderer.STRUCTURE_ALIASES` 保持一致；越界值回落 `code`。常见别名（`bst`→`binary-tree`、`hashmap`→`hash-table` 等）两端都做映射。

| 分类 | dataStructure | 关键数据字段 |
|---|---|---|
| 线性表 | `array` / `string` | nodes（顺序即元素，`index` 下标；string 每格一个字符） |
| | `matrix` | node 给 `row`/`col`；或顶层 `rows`/`cols` |
| | `linked-list` | nodes 按链序 + edges(next)；`pointers` 标 pHead/头尾 |
| | `doubly-linked-list` | 同上，自动补反向 prior 箭头 |
| | `circular-linked-list` | 同上，自动补末→首回边 |
| | `static-linked-list` | node 给 `value` + `cursor`(下一下标) |
| 栈队列 | `stack` | nodes 按 `index` 0(栈底)→顶；`pointers` 放 top |
| | `queue` | nodes 队头→队尾；`pointers` 放 front/rear |
| | `circular-queue` | 顶层 `capacity`；`pointers` 放 front/rear；占用槽 active |
| 树 | `tree` | edges(from=父,to=子) |
| | `binary-tree`(含 BST/AVL/Huffman) | edges `kind`='child-left'/'child-right' |
| | `heap` | node `index` 按完全二叉树层序，自动 2i/2i+1 布局 |
| 图/散列 | `graph` | nodes + edges(label 可作权重) |
| | `adjacency-matrix` | nodes(顶点) + edges；自动生成 0/1 邻接矩阵 |
| | `adjacency-list` | edges(from→to) 表示邻居 |
| | `hash-table` | 顶层 `buckets`；node 给 `slot`(桶下标)，同桶串链 |
| 辅助 | `pointer` / `loop` / `code` | pointer 两 node 示意；loop 用 nodes[0].value 作条件文字 |

## 4. 前端

`components/grading/ErrorDemonstrationPlayer.vue` 新增 `current.workflow === 'CONCEPT_STEPS'` 分支：

- **画布为主角**：`PythonTutorRenderer`（D3，高 300px）渲染 `activeStep.state`，覆盖 §3.5 全量结构（数组/矩阵/串、单双循环静态链表、栈/队列/循环队列、树/二叉树/堆、图/邻接矩阵/邻接表/哈希表、指针/循环）。
- **旁白字幕**：画布下方居中醒目字幕条（图码式），错误步转红。
- **变量芯片** + **示意代码栏**（有 `sourceCode` 时显示，随当前步 `line` 高亮）。
- 复用既有 上一步/播放/下一步/重置 控件与进度条；概念动画播放间隔 900ms → **1600ms** 便于阅读字幕（只影响 CONCEPT_STEPS）。

渲染器 `PythonTutorRenderer.vue` 的 state 契约：
`nodes[].{id,label,value,active,outOfBounds,index, next?,cursor?,row?,col?,slot?}`、`edges[].{from|source,to|target,label,kind?}`、`pointers[].{name,target}`，以及结构级 `rows?/cols?/capacity?/buckets?`。

## 5. 验证状态

- 后端 `mvnw compile`：通过。
- 前端 `vue-cli-service build`：通过。
- 帧数据契约与播放器/渲染器逐字段核对一致。
- **未做**：尚未在运行中的系统里跑真实 CONCEPT 类批改，端到端观感（大模型实际产出的步骤 JSON 质量、动画效果）**待实测**。

## 6. 后续方向

路 B 已消除"大模型直出 HTML"的随机性。若要进一步逼近图码精细度，下一步是打磨 `PythonTutorRenderer` 的布局与补间动画（纯前端），与本工作流解耦、可独立迭代。
