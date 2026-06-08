# AI 与用户交互 — 后台程序运行过程详解

> 本文档详细解释项目中 AI 对话功能的后台完整运行流程，以及修改各环节会如何影响最终输出结果。

---

## 一、整体架构：两条处理路径

系统提供**两套** AI 处理机制，由 `ChatController` 根据请求路径分发：

```
用户在前端发消息
       |
       v
  ChatController
       |
       ├── POST /chat/agent  ──→  LLMAgentService    (新架构，Function Calling)
       |
       └── POST /chat/send   ──→  AgentService        (传统架构，意图识别)
```

| 对比维度 | LLMAgentService (新) | AgentService (传统) |
|----------|---------------------|---------------------|
| 入口 API | `POST /chat/agent` | `POST /chat/send` |
| 核心思想 | AI 直接决定调用哪个工具 | 先识别意图，再匹配工具 |
| LLM 调用次数 | 2 次（决策 + 润色） | 1 次（仅意图识别）+ 可选润色 |
| 降级方式 | 无 LLM 时降级到传统 Agent | 无 LLM 时用正则匹配 |
| 文件 | `agent/LLMAgentService.java` | `agent/AgentService.java` |

---

## 二、LLMAgentService 完整运行流程（主路径）

这是系统推荐的主路径，用户在前端选择 Provider 后走此路径。

### 2.1 调用全景图

```
前端 Chat.vue
  │  POST /api/chat/agent  { message: "查询学号为S2022001的学生", provider: "deepseek" }
  │
  ▼
ChatController.sendToAgent()                          ← controller/ChatController.java:136
  │  ① 从数据库读取 DeepSeek 的 api_key / model / base_url
  │  ② 调用 llmAgentService.processMessage(userMessage, provider)
  ▼
LLMAgentService.processMessage()                      ← agent/LLMAgentService.java:74
  │
  ├── 第1步：recognizeAndDecide()
  │   │  构建 System Prompt（包含全部工具定义）
  │   │  HTTP POST → https://api.deepseek.com/v1/chat/completions
  │   │  LLM 返回 JSON：{"tool":"queryStudents","params":{"stuNo":"S2022001"}}
  │   │  解析为 AgentDecision 对象
  │   │
  │   └── 如果 LLM 返回空 / 异常 → 返回用户友好错误提示
  │
  ├── 第2步：判断 tool 字段
  │   │  如果 tool == null → 直接返回 LLM 的聊天回复（闲聊场景）
  │   │  如果有 tool → 继续第3步
  │
  ├── 第3步：executeTool(toolName, params)
  │   │  switch(toolName):
  │   │    "queryStudents"  → DatabaseQueryTool.queryStudents()
  │   │    "addStudent"     → CrudOperationTool.addStudent()
  │   │    "updateStudent"  → CrudOperationTool.updateStudent()
  │   │    "deleteStudent"  → CrudOperationTool.deleteStudent()
  │   │    "analyzeStudentGender" → DataAnalysisTool.analyzeStudentGender()
  │   │    ... 共 18 个工具
  │   │
  │   │  每个 Tool 调用对应的 Mapper → 执行 SQL → 返回格式化字符串
  │
  └── 第4步：generateFinalResponse()
      │  把工具执行结果 + 原始问题发给 LLM
      │  LLM 生成自然语言回复（如："找到学生赵六，学号S2022001，性别女，年龄19岁..."）
      │  如果 LLM 调用失败 → 返回简化版回复："操作已完成：{原始结果}"
      │
      ▼
  返回最终回复给前端
```

### 2.2 第1步详解：System Prompt 是如何指导 LLM 的

`buildAgentSystemPrompt()` 方法（`LLMAgentService.java:158`）构建了一个约 3000 字的系统提示词，包含：

```
┌────────────────────────────────────────┐
│  🚨 输出格式要求（最先说，确保 LLM 注意）   │
│  必须返回 JSON：{"tool":"...","params":{}}│
├────────────────────────────────────────┤
│  可用工具列表（18 个）                    │
│  ├── 查询：queryStudents/Employees/...  │
│  ├── 添加：addStudent/Employee/...      │
│  ├── 修改：updateStudent/Employee/...   │
│  ├── 删除：deleteStudent/Employee/...   │
│  └── 分析：analyzeStudentGender/...     │
├────────────────────────────────────────┤
│  工具选择规则                            │
│  关键词 "添加" → addXXX                 │
│  关键词 "改为" → updateXXX              │
│  关键词 "删除" → deleteXXX              │
├────────────────────────────────────────┤
│  禁止行为                                │
│  不要把修改操作误解为查询操作              │
│  电话号码/学号必须作为字符串              │
└────────────────────────────────────────┘
```

### 2.3 第3步详解：Tool 执行数据库操作

以用户说「把张三的电话改为15862840271」为例：

```
LLM 返回:
  {"tool":"updateStudent","params":{"name":"张三","phone":"15862840271"}}

↓ LLMAgentService.executeTool()

CrudOperationTool.updateStudent(params)
  │  ① params 中有 name="张三" → 调用 studentMapper.findAll("张三")
  │  ② 找到 Student{id=1, stuName="张三", stuNo="S2021001"}
  │  ③ params 中有 phone="15862840271" → student.setPhone("15862840271")
  │  ④ 调用 studentService.updateStudent(1, student)
  │  ⑤ 返回 "✅ 成功更新学生：张三"
```

### 2.4 第4步详解：LLM 润色回复

第3步的输出是机器格式的 `✅ 成功更新学生：张三`。第4步会把这个结果 + 用户原始问题再发给 LLM：

```
第二次 LLM 调用：
  System Prompt: "你是信息技术学院管理系统的智能助手。请用友好、自然的语气回复。"
  User Prompt:   "用户问：把张三的电话改为15862840271
                  工具执行结果：✅ 成功更新学生：张三
                  请生成回复："

LLM 返回（润色后的回复）：
  "已成功将张三的电话号码更新为 15862840271，信息已保存到系统中。"
```

---

## 三、AgentService 传统架构流程（兼容路径）

当用户没有配置 AI Key，或通过 `/chat/send` 接口调用时走此路径。

### 3.1 调用全景图

```
ChatController.sendMessage()
  │
  ▼
AgentService.processMessage(userMessage, apiKey, model, baseUrl, mode)
  │                                    ↑
  │                          识别模式（4 种可选）
  │
  ├── 第1步：意图识别（根据 mode 选择策略）
  │   │
  │   ├── LLM_ONLY：    只调 LLM，失败则报错
  │   ├── RULE_ONLY：   只用正则匹配关键词
  │   ├── LLM_FIRST：  先调 LLM，置信度<0.7 则降级正则（默认）
  │   └── RULE_FIRST： 先正则，匹配不到再调 LLM
  │
  │   输出：Intent 对象 { type, action }
  │        Params Map { "name":"张三", "phone":"15862840271" }
  │
  ├── 第2步：executeIntentWithParams(intent, userMessage, params)
  │   │  和 LLMAgentService 第3步一样，调用对应的 Tool
  │
  └── 第3步（可选）：enhanceResponseWithAI()
      │  有 AI Key 时，把结果发给 LLM 润色
      │  无 AI Key 时，直接返回 Tool 的原始格式化字符串
```

### 3.2 四种识别模式的对比

| 模式 | 行为 | 无 API Key 时 | 适用场景 |
|------|------|--------------|----------|
| `LLM_ONLY` | 只用 LLM | 报错提示 | API 稳定，希望纯 AI 理解 |
| `RULE_ONLY` | 只用正则 | 正常工作 | 没配 Key，或需要精确控制 |
| `LLM_FIRST` | LLM 优先，降级正则 | 降级到正则 | **默认模式**，兼顾智能和容错 |
| `RULE_FIRST` | 正则优先，降级 LLM | 先正则，再尝试 LLM | 简单操作不想浪费 API 额度 |

### 3.3 规则匹配的参数提取（AgentService.extractParameters）

当 LLM 不可用时，用正则提取参数，按优先级：

```
1. 学号/工号/编号   →  正则: (学号|工号|编号)[^A-Za-z0-9_]*([A-Za-z][A-Za-z0-9_]*)
2. 数字 ID          →  正则: (id|ID|编号)[\s:=：]*?(\d+)
3. 纯数字          →  仅当上下文有 "ID" 时才提取
4. 中文姓名        →  正则: (姓名|名字|叫|改为|设置为)[^一-龥]*([一-龥]{2,4})
5. 年龄            →  正则: (年龄|岁数)[\s:=：]*?(\d+)
6. 电话            →  正则: (电话|手机|联系方式)[\s:=：]*?(1\d{10})
7. 邮箱            →  正则: (邮箱|email|邮件)[\s:=：]*?([\w.-]+@[\w.-]+\.[a-zA-Z]{2,})
8. 职位            →  正则: (职位|职务|岗位)[\s:=：]*?([一-龥]{2,10})
```

---

## 四、三个 Tool 类的职责细节

### 4.1 DatabaseQueryTool — 查询类操作

文件: `agent/DatabaseQueryTool.java`

| 方法 | 输入 | SQL 逻辑 | 输出格式 |
|------|------|----------|----------|
| `queryStudents` | params (未使用过滤) | `SELECT * FROM student` | 列表（最多10条） |
| `queryEmployees` | params | `SELECT * FROM employee` | 完整列表 |
| `queryClasses` | params | `SELECT * FROM class_info` | 完整列表 |
| `queryDepartments` | params | `SELECT * FROM department` | 完整列表 |
| `countStudents` | 无 | `SELECT COUNT(*)` | `"当前共有 X 名学生"` |
| `countEmployees` | 无 | `SELECT COUNT(*)` | `"当前共有 X 名员工"` |
| `countClasses` | 无 | `SELECT COUNT(*)` | `"当前共有 X 个班级"` |
| `getDatabaseOverview` | 无 | 四个 COUNT | 综合统计 |

**重要限制**：目前查询方法**没有使用 params 参数做过滤**，即使 LLM 传了 `stuNo`，也会返回所有学生。这是个已知的简化设计。

### 4.2 CrudOperationTool — 增删改操作

文件: `agent/CrudOperationTool.java`（753 行）

**关键设计模式 — 参数双命名兼容**：
```java
// 同时支持 LLM 返回的参数名 和 系统内部标准名
String stuName = (String) params.get("stuName");  // 标准名
if (stuName == null || stuName.isEmpty()) {
    stuName = (String) params.get("name");         // LLM 可能返回的简化名
}
```

**更新操作的定位策略**（以 updateStudent 为例）：
```
1. 有 stuNo？  → 用学号查（最精确）
2. 有 name？   → 用姓名查（可能多条，取第一条）
3. 有 id？     → 用 ID 查（精确）
4. 都没有      → 返回 "❌ 未找到要更新的学生"
```

**姓名修改的特殊逻辑**：
```
LMM 传 name="张三", newName="赵六"
  → name 用于定位原记录，newName 是新的值
  → student.setStuName("赵六")
```

### 4.3 DataAnalysisTool — 统计分析

文件: `agent/DataAnalysisTool.java`（192 行）

| 方法 | 统计逻辑 | 输出示例 |
|------|----------|----------|
| `analyzeStudentGender` | 按 gender 字段分组 | `男生: 30人 (60.0%)` |
| `analyzeStudentAge` | 按 age 聚合统计 | `18岁: 10人` |
| `analyzeClassSize` | 计算总人数、平均、最大/最小班级 | 格式化统计表 |
| `analyzeEmployeeDistribution` | 按 deptId 分组 | 各部门人数及占比 |
| `generateComprehensiveReport` | 调用以上 4 个方法 | 完整分析报告 |

---

## 五、如何修改代码来影响运行结果

以下是按影响程度排序的修改点和具体文件。

### 5.1 影响「LLM 能做什么」— 修改 System Prompt

**文件**: `LLMAgentService.java` 的 `buildAgentSystemPrompt()` 方法（行 158-312）

| 修改内容 | 效果 | 示例 |
|----------|------|------|
| 增加新工具描述 | LLM 能调用新功能 | 加一段 `"addCourse: 添加课程"` |
| 修改工具选择规则 | 改变 LLM 的行为倾向 | 把某个工具放到更靠前的位置 |
| 修改输出格式要求 | 影响 LLM 返回的 JSON 结构 | 要求返回 `{"tool":"xxx","args":{}}` 而非 `{"params":{}}` |
| 修改 temperature | 影响回复的随机性 | `0.1`=稳定, `0.7`=多样 |
| 修改工具参数说明 | 影响 LLM 能提取哪些字段 | 在 `updateStudent` 的描述中加入 `email` 参数 |

**实际修改例子**：让 LLM 学会按班级查询学生：
```java
// 在 buildAgentSystemPrompt() 的查询工具段落中加入：
"- queryStudents: 查询学生信息\n" +
"  参数: stuNo(学号), name(姓名), classId(班级ID), major(专业)\n" +
"  示例: {\"tool\":\"queryStudents\",\"params\":{\"classId\":1}}\n\n" +
```

### 5.2 影响「增删改查怎么执行」— 修改 Tool 类

**文件**: `CrudOperationTool.java`, `DatabaseQueryTool.java`, `DataAnalysisTool.java`

| 修改内容 | 效果 | 文件.方法 |
|----------|------|-----------|
| 支持新的参数字段 | 可以修改更多字段 | `CrudOperationTool.updateStudent()` 加 phone/email |
| 修改返回消息文本 | 用户看到的成功/失败消息变化 | 各方法中的 `return String.format(...)` |
| 增加前置校验 | 防止错误操作 | 在 `deleteStudent` 中加 "确认删除" 逻辑 |
| 支持更多查询条件 | LLM 能精确查询 | `DatabaseQueryTool.queryStudents()` 使用 params 过滤 |
| 增加新的统计维度 | 更多分析能力 | 在 `DataAnalysisTool` 中加 `analyzeByMajor()` |

**实际修改例子**：让 queryStudents 支持按学号精确查询：
```java
// DatabaseQueryTool.java 修改 queryStudents 方法
public String queryStudents(Map<String, Object> params) {
    String keyword = "";
    if (params.containsKey("stuNo")) {
        keyword = (String) params.get("stuNo");   // ← 新增
    } else if (params.containsKey("name")) {
        keyword = (String) params.get("name");     // ← 新增
    }
    List<Student> students = studentMapper.findAll(keyword);
    // ... 后续格式化不变
}
```

### 5.3 影响「LLM 返回结果怎么变成操作」— 修改 LLMAgentService

**文件**: `LLMAgentService.java`

| 修改位置 | 效果 |
|----------|------|
| `executeTool()` 的 switch 分支 | 增加新工具的路由 |
| `parseAgentDecision()` | 改变 JSON 解析逻辑 |
| `extractJsonFromResponse()` | 兼容不同的 LLM 输出格式 |
| `recognizeAndDecide()` 中的降级逻辑 | 改变 LLM 失败时的行为 |
| `callLLMWithTools()` 中的 temperature | 0.1=精确，0.7=灵活 |

**实际修改例子**：新增一个「发送通知」工具：
```java
// 1. 在 buildAgentSystemPrompt() 中增加描述
// 2. 在 executeTool() 中增加 case:
case "sendNotification":
    return notificationService.send(params);
```

### 5.4 影响「无 AI 时的行为」— 修改规则匹配

**文件**: `AgentService.java`

| 修改位置 | 效果 |
|----------|------|
| `recognizeIntent()` 关键词匹配 | 增加新的意图识别词 |
| `extractParameters()` 正则表达式 | 改变参数提取规则 |
| `RecognitionMode` 枚举 | 改变模式切换行为 |

**实际修改例子**：让系统识别「帮我找」作为查询意图：
```java
// AgentService.java recognizeIntent() 方法中
// 在查询类意图的条件中加一句：
else if (lowerMsg.contains("帮我找") || lowerMsg.contains("找一下")) {
    // 根据下文判断目标类型
}
```

### 5.5 影响「LLM 如何对话」— 修改 System Prompt 描述

**文件**: `IntentRecognitionService.java` 的 `SYSTEM_PROMPT` 常量（行 24-103）

| 修改内容 | 效果 |
|----------|------|
| 增加 action 映射 | LLM 能识别更多操作 |
| 修改参数提取规则 | 改变 LLM 提取参数的方式 |
| 修改输出 JSON Schema | 影响 LLM 返回的数据结构 |
| 增加 prompt 示例 | 提高 LLM 的识别准确率 |

### 5.6 影响「API 配置和切换」— 数据库/Controller

| 修改位置 | 效果 |
|----------|------|
| `database/schema.sql` 的 `api_config` INSERT | 增加新的 AI 提供商 |
| `ConfigController.saveApiConfig()` | 改变保存逻辑 |
| `ApiConfigServiceImpl.saveConfig()` | 改变 upsert 逻辑 |

---

## 六、两条路径的代码调用链对比

### 路径 A：LLM Agent（新架构）

```
ChatController.sendToAgent()
  → ApiConfigService.getConfigByProvider("deepseek")
  → LLMAgentService.processMessage(userMessage, "deepseek")
    → API 可用？
      ├── YES: ApiConfigService.getConfigByProvider(provider)
      │        → recognizeAndDecide()
      │          → buildAgentSystemPrompt()
      │          → callLLMWithTools()         ← HTTP POST LLM
      │          → parseAgentDecision()
      │        → executeTool()                ← 操作数据库
      │        → generateFinalResponse()      ← HTTP POST LLM (润色)
      └── NO:  agentService.processMessage()  ← 降级到传统 Agent
  → ChatHistoryService.saveMessage()          ← 保存对话记录
```

### 路径 B：传统 Agent（兼容路径）

```
ChatController.sendMessage()
  → ApiConfigService.getConfigByProvider(provider)
  → AgentService.processMessage(message, apiKey, model, baseUrl, mode)
    → 意图识别:
      ├── LLM_FIRST: IntentRecognitionService.recognizeIntent() ← HTTP POST LLM
      │              置信度 < 0.7 → recognizeIntent() (关键词) + extractParameters() (正则)
      ├── RULE_ONLY: recognizeIntent() + extractParameters() (正则)
      └── etc.
    → executeIntentWithParams()
      → switch(action):
        ├── "queryStudents" → DatabaseQueryTool.queryStudents()
        ├── "addStudent"    → CrudOperationTool.addStudent()
        ├── "deleteStudent" → CrudOperationTool.deleteStudent()
        └── 共计 18 个 case
    → enhanceResponseWithAI()           ← 可选：HTTP POST LLM (润色)
  → ChatHistoryService.saveMessage()    ← 保存对话记录
```

---

## 七、LLM 调用的 HTTP 请求格式

无论是 LLMAgentService 还是 IntentRecognitionService，都使用相同的 HTTP 调用模式：

```http
POST {baseUrl}/chat/completions
Content-Type: application/json
Authorization: Bearer {apiKey}

{
  "model": "deepseek-chat",
  "temperature": 0.1,
  "messages": [
    {"role": "system", "content": "你是信息技术学院管理系统的智能助手..."},
    {"role": "user",   "content": "查询学号为S2022001的学生"}
  ]
}
```

**支持的所有 Provider（在 `schema.sql` 中预设）**：

| Provider | Base URL |
|----------|----------|
| 智谱AI (zhipu) | `https://open.bigmodel.cn/api/paas/v4` |
| DeepSeek | `https://api.deepseek.com/v1` |
| 通义千问 (qwen) | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| OpenAI | `https://api.openai.com/v1` |

---

## 八、常见问题的代码级排查

| 现象 | 排查位置 | 可能原因 |
|------|----------|----------|
| AI 返回「API配置不可用」 | `LLMAgentService.java:55-59` | `enabled != 1` 或 `api_key` 为空 |
| AI 返回 JSON 格式错误 | `LLMAgentService.java:390-423` | LLM 没遵守 System Prompt 的格式要求 |
| 操作总是降级到规则匹配 | `AgentService.java:116-139` | LLM 返回置信度 < 0.7，触发降级 |
| 却说「未找到学生」 | `CrudOperationTool.java:417` | params 中的 stuNo/name 没有匹配到记录 |
| 参数没有传给 SQL | `DatabaseQueryTool.java:83` | queryStudents 没有使用 params 做过滤 |
| 对话记录没保存 | `ChatController.java:178-200` | sessionId 为空字符串 |

---

## 九、总结：一张图看懂整个系统

```
                    ┌─────────────────────────────────────┐
                    │         前端 Chat.vue                │
                    │  用户输入 + 选择 Provider            │
                    └──────────┬──────────────────────────┘
                               │ POST /api/chat/agent
                               ▼
                    ┌─────────────────────────────────────┐
                    │     ChatController                   │
                    │  ① 查 DB 获取 API 配置                │
                    │  ② 路由到 Agent                      │
                    └───┬──────────────┬──────────────────┘
                        │              │
             有 API Key │              │ 无 API Key
                        ▼              ▼
        ┌───────────────────────┐  ┌───────────────────┐
        │  LLMAgentService      │  │  AgentService      │
        │                       │  │  (降级)            │
        │ ① LLM 决策工具 ──→ LLM │  │ ① 正则匹配意图     │
        │ ② 执行 Tool ──→ MySQL │  │ ② 执行 Tool        │
        │ ③ LLM 润色回复 ──→ LLM │  │ ③ 可选 LLM 润色    │
        └───────────┬───────────┘  └─────────┬─────────┘
                    │                        │
                    ▼                        ▼
        ┌───────────────────────────────────────────────┐
        │              Tool 执行层                       │
        │  DatabaseQueryTool   CrudOperationTool         │
        │  DataAnalysisTool                             │
        └──────────────────────┬────────────────────────┘
                               │
                               ▼
        ┌───────────────────────────────────────────────┐
        │         MyBatis Mapper → MySQL                 │
        │  student | employee | class_info | department  │
        └───────────────────────────────────────────────┘
```

**修改影响速查**：
- 想让 LLM **能做更多事** → 改 `LLMAgentService.buildAgentSystemPrompt()`
- 想让操作**执行得更准** → 改 `CrudOperationTool` / `DatabaseQueryTool` / `DataAnalysisTool`
- 想让**无 AI 时更好用** → 改 `AgentService` 的正则和关键词
- 想**换模型/Provider** → 前端设置页面配置，或直接改数据库 `api_config` 表
