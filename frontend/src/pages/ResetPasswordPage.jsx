import React, { useState, useEffect } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import Input from '../components/Input';
import Button from '../components/Button';
import { userAPI } from '../services/api';
import './ResetPasswordPage.css';

const ResetPasswordPage = () => {
  const navigate = useNavigate();
  const location = useLocation();
  
  const [formData, setFormData] = useState({
    email: '',
    code: '',
    newPassword: '',
    confirmPassword: ''
  });
  
  const [errors, setErrors] = useState({});
  const [isLoading, setIsLoading] = useState(false);
  const [isSuccess, setIsSuccess] = useState(false);

  useEffect(() => {
    // 从URL参数或state中获取邮箱
    const urlParams = new URLSearchParams(location.search);
    const emailFromUrl = urlParams.get('email');
    const emailFromState = location.state?.email;
    
    if (emailFromUrl) {
      setFormData(prev => ({ ...prev, email: emailFromUrl }));
    } else if (emailFromState) {
      setFormData(prev => ({ ...prev, email: emailFromState }));
    }
  }, [location]);

  const handleChange = (e) => {
    const { name, value } = e.target;
    setFormData(prev => ({
      ...prev,
      [name]: value
    }));
    // Clear error for this field
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
      newErrors.email = '请输入邮箱地址';
    } else if (!/\S+@\S+\.\S+/.test(formData.email)) {
      newErrors.email = '请输入有效的邮箱地址';
    }
    
    if (!formData.code) {
      newErrors.code = '请输入验证码';
    } else if (!/^\d{6}$/.test(formData.code)) {
      newErrors.code = '验证码必须是6位数字';
    }
    
    if (!formData.newPassword) {
      newErrors.newPassword = '请输入新密码';
    } else if (formData.newPassword.length < 6) {
      newErrors.newPassword = '密码至少需要6位字符';
    }
    
    if (!formData.confirmPassword) {
      newErrors.confirmPassword = '请确认新密码';
    } else if (formData.newPassword !== formData.confirmPassword) {
      newErrors.confirmPassword = '两次输入的密码不一致';
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
    setErrors({});
    
    try {
      const response = await userAPI.resetPassword(
        formData.email, 
        formData.code, 
        formData.newPassword
      );
      
      if (response.success) {
        setIsSuccess(true);
      } else {
        setErrors({ submit: response.message || '密码重置失败，请重试' });
      }
      
    } catch (error) {
      console.error('Reset password failed:', error);
      const errorMessage = error.response?.data?.message || '密码重置失败，请重试';
      setErrors({ submit: errorMessage });
    } finally {
      setIsLoading(false);
    }
  };

  const handleBackToLogin = () => {
    navigate('/login');
  };

  const handleResendCode = async () => {
    if (!formData.email) {
      setErrors({ submit: '请先输入邮箱地址' });
      return;
    }
    
    setIsLoading(true);
    setErrors({});
    
    try {
      const response = await userAPI.forgotPassword(formData.email);
      
      if (response.success) {
        setErrors({ submit: '验证码已重新发送' });
      } else {
        setErrors({ submit: response.message || '重新发送失败' });
      }
      
    } catch (error) {
      console.error('Resend code failed:', error);
      const errorMessage = error.response?.data?.message || '重新发送失败';
      setErrors({ submit: errorMessage });
    } finally {
      setIsLoading(false);
    }
  };

  if (isSuccess) {
    return (
      <div className="reset-password-container">
        <div className="reset-password-card">
          <div className="success-header">
            <div className="success-icon">✓</div>
            <h1 className="success-title">密码重置成功！</h1>
            <p className="success-subtitle">
              您的密码已成功重置，现在可以使用新密码登录了
            </p>
          </div>
          
          <div className="success-content">
            <div className="success-actions">
              <Button 
                onClick={handleBackToLogin}
                variant="primary"
                size="large"
                className="back-button"
              >
                返回登录
              </Button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="reset-password-container">
      <div className="reset-password-card">
        <div className="reset-password-header">
          <h1 className="reset-password-title">重置密码</h1>
          <p className="reset-password-subtitle">
            请输入您收到的验证码和新密码
          </p>
        </div>
        
        <form className="reset-password-form" onSubmit={handleSubmit}>
          <Input
            type="email"
            name="email"
            label="邮箱地址"
            value={formData.email}
            onChange={handleChange}
            placeholder="请输入您的邮箱地址"
            error={errors.email}
            disabled={isLoading}
            required
          />
          
          <Input
            type="text"
            name="code"
            label="验证码"
            value={formData.code}
            onChange={handleChange}
            placeholder="请输入6位验证码"
            error={errors.code}
            disabled={isLoading}
            required
          />
          
          <Input
            type="password"
            name="newPassword"
            label="新密码"
            value={formData.newPassword}
            onChange={handleChange}
            placeholder="请输入新密码"
            error={errors.newPassword}
            disabled={isLoading}
            required
          />
          
          <Input
            type="password"
            name="confirmPassword"
            label="确认新密码"
            value={formData.confirmPassword}
            onChange={handleChange}
            placeholder="请再次输入新密码"
            error={errors.confirmPassword}
            disabled={isLoading}
            required
          />
          
          {errors.submit && (
            <div className="error-message submit-error">
              {errors.submit}
            </div>
          )}
          
          <div className="form-actions">
            <Button 
              type="submit" 
              loading={isLoading}
              variant="primary"
              size="large"
              className="submit-button"
            >
              重置密码
            </Button>
            
            <Button 
              type="button"
              onClick={handleResendCode}
              disabled={isLoading}
              variant="secondary"
              size="medium"
              className="resend-button"
            >
              重新发送验证码
            </Button>
            
            <Button 
              type="button"
              onClick={handleBackToLogin}
              variant="secondary"
              size="medium"
              className="back-button"
            >
              返回登录
            </Button>
          </div>
        </form>
      </div>
    </div>
  );
};

export default ResetPasswordPage;
