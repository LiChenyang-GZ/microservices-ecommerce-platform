// 测试前后端连接的简单脚本
// 这个文件可以用来测试API连接是否正常工作

import { userAPI } from '../services/api';

// 测试数据
const testAccountData = {
  firstName: '测试',
  lastName: '用户',
  email: 'test@example.com',
  password: 'TestPassword123'
};

const testLoginData = {
  email: 'test@example.com',
  password: 'TestPassword123'
};

// 测试创建账户
export const testCreateAccount = async () => {
  console.log('开始测试创建账户API...');
  
  try {
    const response = await userAPI.createAccount(testAccountData);
    console.log('✅ 创建账户API测试成功！');
    console.log('响应数据:', response);
    return true;
  } catch (error) {
    console.error('❌ 创建账户API测试失败:', error);
    
    if (error.response) {
      console.error('服务器响应:', error.response.status, error.response.data);
    } else if (error.request) {
      console.error('网络错误: 无法连接到后端服务器');
      console.error('请确保后端服务正在运行在 http://localhost:8082');
    }
    
    return false;
  }
};

// 测试登录
export const testLogin = async () => {
  console.log('开始测试登录API...');
  
  try {
    const response = await userAPI.login(testLoginData);
    console.log('✅ 登录API测试成功！');
    console.log('响应数据:', response);
    return true;
  } catch (error) {
    console.error('❌ 登录API测试失败:', error);
    
    if (error.response) {
      console.error('服务器响应:', error.response.status, error.response.data);
    } else if (error.request) {
      console.error('网络错误: 无法连接到后端服务器');
      console.error('请确保后端服务正在运行在 http://localhost:8082');
    }
    
    return false;
  }
};

// 完整测试流程
export const testFullFlow = async () => {
  console.log('开始完整测试流程...');
  
  // 1. 先创建账户
  const createSuccess = await testCreateAccount();
  if (!createSuccess) {
    console.log('创建账户失败，跳过登录测试');
    return false;
  }
  
  // 等待一下
  await new Promise(resolve => setTimeout(resolve, 1000));
  
  // 2. 测试登录
  const loginSuccess = await testLogin();
  
  if (createSuccess && loginSuccess) {
    console.log('🎉 完整测试流程成功！');
    return true;
  } else {
    console.log('❌ 完整测试流程失败');
    return false;
  }
};

// 在开发环境中自动运行测试（可选）
if (process.env.NODE_ENV === 'development') {
  // 可以取消注释下面的行来自动测试
  // testFullFlow();
}
