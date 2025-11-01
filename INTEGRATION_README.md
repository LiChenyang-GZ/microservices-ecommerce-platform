# 前后端连接说明

## 项目结构
- **前端**: React应用运行在 `http://localhost:3000`
- **后端**: Spring Boot应用运行在 `http://localhost:8082`

## 启动步骤

### 1. 启动后端服务
```bash
cd backend/storeService
./gradlew bootRun
```
或者使用IDE运行 `StoreServiceApplication.java`

### 2. 启动前端服务
```bash
cd frontend
npm start
```

## API接口

### 创建账户
- **URL**: `POST http://localhost:8082/api/user`
- **请求体**:
```json
{
  "username": "username",
  "email": "user@example.com",
  "password": "password123"
}
```

### 用户登录
- **URL**: `POST http://localhost:8082/api/user/login`
- **请求体**:
```json
{
  "email": "user@example.com",
  "password": "password123"
}
```
- **成功响应**: `"Login successful!"`
- **失败响应**: `"Invalid email or password."` (HTTP 401)

## 测试连接

### 方法1: 通过前端界面测试
1. 启动前后端服务
2. **测试注册**:
   - 访问 `http://localhost:3000/register`
   - 填写注册表单并提交
   - 查看浏览器控制台的日志输出
3. **测试登录**:
   - 访问 `http://localhost:3000/login`
   - 使用注册的邮箱和密码登录
   - 成功登录后会跳转到首页
   - 在首页可以看到用户信息和登出按钮

### 方法2: 使用测试脚本
```javascript
import { testFullFlow, testCreateAccount, testLogin } from './src/utils/testAPI';

// 测试完整流程（注册+登录）
testFullFlow();

// 或者分别测试
testCreateAccount();
testLogin();
```

## 常见问题

### CORS错误
如果遇到CORS错误，确保：
- 后端CORS配置允许 `http://localhost:3000`
- 前端请求地址正确 (`http://localhost:8082/api`)

### 连接失败
如果无法连接到后端：
1. 检查后端服务是否正在运行
2. 检查端口8082是否被占用
3. 检查数据库连接是否正常

### 401 认证错误
如果遇到401错误：
- 检查Spring Security配置是否正确
- 确保SecurityConfig.java允许API访问
- 检查是否有其他安全拦截器

## 功能特性

### 🔐 认证系统
- **用户注册**: 创建新账户并保存到数据库
- **用户登录**: 验证邮箱和密码
- **密码加密**: 使用BCrypt加密存储密码
- **登录状态管理**: 使用React Context管理全局登录状态
- **路由保护**: 未登录用户自动重定向到登录页
- **自动登出**: 清除本地存储的登录信息

### 🎨 用户界面
- **响应式设计**: 支持桌面和移动设备
- **现代化UI**: 使用渐变背景和毛玻璃效果
- **用户友好**: 详细的错误提示和加载状态
- **导航保护**: 已登录用户访问登录/注册页会自动跳转

## 文件说明

### 前端文件
- `frontend/src/services/api.js`: API服务文件，处理HTTP请求
- `frontend/src/contexts/AuthContext.js`: 认证上下文，管理登录状态
- `frontend/src/pages/RegisterPage.jsx`: 注册页面，集成API调用
- `frontend/src/pages/LoginPage.jsx`: 登录页面，集成API调用
- `frontend/src/pages/HomePage.jsx`: 首页，显示用户信息和登出功能
- `frontend/src/utils/testAPI.js`: API连接测试工具

### 后端文件
- `backend/storeService/src/main/java/comp5348/storeservice/config/WebConfig.java`: CORS配置
- `backend/storeService/src/main/java/comp5348/storeservice/config/SecurityConfig.java`: Spring Security配置
- `backend/storeService/src/main/java/comp5348/storeservice/controller/AccountController.java`: 账户控制器
- `backend/storeService/src/main/java/comp5348/storeservice/service/AccountService.java`: 账户服务
