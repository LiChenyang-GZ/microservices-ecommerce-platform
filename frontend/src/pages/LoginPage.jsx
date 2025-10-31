import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import Input from '../components/Input';
import Button from '../components/Button';
import { userAPI } from '../services/api';
import { useAuth } from '../contexts/AuthContext';
import './LoginPage.css';

const LoginPage = () => {
  const navigate = useNavigate();
  const { login } = useAuth();
  const [formData, setFormData] = useState({
    email: '',
    password: ''
  });
  const [errors, setErrors] = useState({});
  const [isLoading, setIsLoading] = useState(false);

  const handleChange = (e) => {
    const { name, value } = e.target;
    setFormData(prev => ({
      ...prev,
      [name]: value
    }));
    // 清除对应字段的错误
    if (errors[name]) {
      setErrors(prev => ({
        ...prev,
        [name]: ''
      }));
    }
  };

  const validateForm = () => {
    const newErrors = {};
    
    if (!formData.email) {
      newErrors.email = 'Please enter your email or username';
    }
    
    if (!formData.password) {
      newErrors.password = 'Please enter your password';
    } else if (formData.password.length < 6) {
      newErrors.password = 'Password must be at least 6 characters';
    }
    
    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    
    if (!validateForm()) {
      return;
    }
    
    setIsLoading(true);
    
    try {
      // 准备发送给后端的数据
      const loginData = {
        email: formData.email,
        password: formData.password
      };
      
      console.log('正在登录:', loginData);
      
      // 调用后端API进行登录
      const response = await userAPI.login(loginData);
      
      console.log('登录成功:', response);
      
      // 处理成功响应
      if (response.success && response.token) {
        // 使用认证上下文更新登录状态，保存 token 和用户信息
        login(response.email, response.token, response.userId);
        
        alert('登录成功！欢迎回来！');
        
        // 重定向到首页
        navigate('/');
      } else {
        throw new Error(response.message || '登录失败');
      }
      
    } catch (error) {
      console.error('登录失败:', error);
      
      // 处理不同类型的错误
      let errorMessage = '登录失败，请重试';
      
      if (error.response) {
        // 服务器返回的错误
        const { status, data } = error.response;
        if (status === 401) {
          // 尝试从响应数据中获取错误信息
          errorMessage = (data && data.message) ? data.message : '邮箱或密码错误，请检查后重试';
        } else if (status === 400) {
          errorMessage = '请检查输入的信息是否正确';
        } else if (status === 500) {
          errorMessage = '服务器错误，请稍后重试';
        }
        
        // 如果有具体的错误信息，使用它
        if (data && data.message) {
          errorMessage = data.message;
        } else if (data && typeof data === 'string') {
          errorMessage = data;
        }
      } else if (error.request) {
        // 网络错误
        errorMessage = '无法连接到服务器，请检查网络连接';
      }
      
      alert(errorMessage);
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="login-container">
      <div className="login-card">
        <div className="login-header">
          <h1 className="login-title">Welcome Back</h1>
          <p className="login-subtitle">Please sign in to your account</p>
        </div>
        
        <form className="login-form" onSubmit={handleSubmit}>
          <Input
            type="text"
            name="email"
            label="Email / Username"
            value={formData.email}
            onChange={handleChange}
            placeholder="Enter email or 'customer'"
            error={errors.email}
            disabled={isLoading}
            required
          />
          
          <Input
            type="password"
            name="password"
            label="Password"
            value={formData.password}
            onChange={handleChange}
            placeholder="Enter your password"
            error={errors.password}
            disabled={isLoading}
            required
          />
          
          <div className="form-options">
            <label className="checkbox-container">
              <input type="checkbox" />
              <span className="checkmark"></span>
              Remember me
            </label>
            <Link to="/forgot-password" className="forgot-password">
              Forgot password?
            </Link>
          </div>
          
          <Button 
            type="submit" 
            loading={isLoading}
            variant="primary"
            size="large"
            className="login-button"
          >
            Sign In
          </Button>
        </form>
        
        <div className="login-footer">
          <p className="signup-text">
            Don't have an account? <Link to="/register" className="signup-link">Sign up now</Link>
          </p>
        </div>
      </div>
    </div>
  );
};

export default LoginPage;
