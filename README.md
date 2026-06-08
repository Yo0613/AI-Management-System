# 信息技术学院智能管理系统

基于 LLM Agent 的 AI 智能教务管理平台，集成 DeepSeek/智谱AI 大语言模型，通过 Function Calling 实现自然语言驱动的数据库操作与数据分析。

## 技术栈

- **后端**：Java · Spring Boot 2.7 · MyBatis · MySQL 8.0
- **前端**：Vue 3 · TypeScript · Tailwind CSS · Vite
- **AI**：DeepSeek / 智谱AI / 通义千问 / OpenAI · Function Calling

## 核心功能

- **AI 智能对话**：通过自然语言完成学生/员工/班级/部门的增删改查与统计分析
- **多轮上下文记忆**：对话历史持久化，LLM 调用携带上下文
- **数据可视化**：性别分布、年龄分布、班级规模等统计分析
- **多 LLM 切换**：支持 4 种大模型提供商，前端一键切换
- **容错降级**：AI 不可用时自动降级到规则匹配

## 快速启动

### 环境要求
- JDK 17+ · MySQL 8.0+ · Node.js 16+

### 1. 数据库
```sql
CREATE DATABASE xinxi_college CHARACTER SET utf8mb4;
USE xinxi_college;
SOURCE database/schema.sql;
```

### 2. 修改配置
编辑 `backend/src/main/resources/application.yml`，填入你的 MySQL 用户名和密码。

### 3. 启动后端
```bash
cd backend
mvn spring-boot:run
```

### 4. 启动前端
```bash
cd frontend
npm install
npm run dev
```

### 5. 配置 AI
访问 `http://localhost:3000` → 系统设置 → 填入 API Key → 启用

## 项目结构

```
├── backend/                 # Spring Boot 后端
│   └── src/main/java/com/xinxi/college/
│       ├── agent/           # AI Agent 核心（LLMAgentService、Tool 类）
│       ├── controller/      # REST API 控制器
│       ├── service/         # 业务逻辑层
│       ├── mapper/          # MyBatis 数据访问层
│       └── entity/          # 实体类
├── frontend/                # Vue 3 前端
│   └── src/
│       ├── views/           # 页面组件
│       ├── api/             # API 封装（axios）
│       └── router/          # 路由配置
└── database/                # SQL 脚本
    └── schema.sql
```

## 许可证

MIT
