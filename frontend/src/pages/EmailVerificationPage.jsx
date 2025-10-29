import React, { useState, useEffect } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import Input from '../components/Input';
import Button from '../components/Button';
import { emailAPI, userAPI } from '../services/api';
import './EmailVerificationPage.css';

const EmailVerificationPage = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const [verificationCode, setVerificationCode] = useState('');
  const [email, setEmail] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [isResending, setIsResending] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [countdown, setCountdown] = useState(0);

  useEffect(() => {
    // 从路由状态或localStorage获取邮箱
    const emailFromState = location.state?.email;
    const emailFromStorage = localStorage.getItem('pendingEmail');
    
    if (emailFromState) {
      setEmail(emailFromState);
      localStorage.setItem('pendingEmail', emailFromState);
    } else if (emailFromStorage) {
      setEmail(emailFromStorage);
    } else {
      // 如果没有邮箱信息，重定向到注册页面
      navigate('/register');
    }
  }, [location.state, navigate]);

  useEffect(() => {
    let timer;
    if (countdown > 0) {
      timer = setTimeout(() => setCountdown(countdown - 1), 1000);
    }
    return () => clearTimeout(timer);
  }, [countdown]);

  const handleVerify = async (e) => {
    e.preventDefault();
    setIsLoading(true);
    setError('');
    setSuccess('');

    try {
      // 验证验证码
      const verifyResponse = await emailAPI.verifyCode(email, verificationCode);
      
      if (verifyResponse.success) {
        // 验证成功，激活账户
        const activateResponse = await userAPI.activateAccount(email);
        
        if (activateResponse.success) {
          setSuccess('邮箱验证成功！账户已激活，请登录。');
          // 清除pending email
          localStorage.removeItem('pendingEmail');
          
          // 2秒后跳转到登录页面
          setTimeout(() => {
            navigate('/login', { 
              state: { 
                message: '邮箱验证成功，请登录',
                email: email 
              }
            });
          }, 2000);
        } else {
          setError('账户激活失败，请重试');
        }
      } else {
        setError(verifyResponse.message || '验证码无效或已过期');
      }
    } catch (error) {
      console.error('验证失败:', error);
      if (error.response?.data?.message) {
        setError(error.response.data.message);
      } else {
        setError('验证失败，请重试');
      }
    } finally {
      setIsLoading(false);
    }
  };

  const handleResendCode = async () => {
    setIsResending(true);
    setError('');
    setSuccess('');

    try {
      const response = await emailAPI.sendVerificationCode(email);
      if (response.success) {
        setSuccess('验证码已重新发送到您的邮箱');
        setCountdown(60); // 60秒倒计时
      } else {
        setError(response.message || '发送失败');
      }
    } catch (error) {
      console.error('重发验证码失败:', error);
      if (error.response?.data?.message) {
        setError(error.response.data.message);
      } else {
        setError('重发验证码失败，请重试');
      }
    } finally {
      setIsResending(false);
    }
  };

  const handleChange = (e) => {
    const { value } = e.target;
    // 只允许输入数字，最多6位
    const numericValue = value.replace(/\D/g, '').slice(0, 6);
    setVerificationCode(numericValue);
    
    if (error) {
      setError('');
    }
  };

  return (
    <div className="email-verification-page">
      <div className="verification-container">
        <div className="verification-header">
          <h1>邮箱验证</h1>
          <p>我们已向 <strong>{email}</strong> 发送了验证码</p>
          <p>请输入6位数字验证码完成注册</p>
        </div>

        <form onSubmit={handleVerify} className="verification-form">
          <div className="verification-input-group">
            <Input
              type="text"
              name="verificationCode"
              label="验证码"
              value={verificationCode}
              onChange={handleChange}
              placeholder="请输入6位验证码"
              error={error}
              disabled={isLoading}
              required
              maxLength={6}
            />
          </div>

          {success && (
            <div className="success-message">
              {success}
            </div>
          )}

          <div className="verification-actions">
            <Button
              type="submit"
              variant="primary"
              disabled={isLoading || verificationCode.length !== 6}
              loading={isLoading}
            >
              {isLoading ? '验证中...' : '验证邮箱'}
            </Button>

            <Button
              type="button"
              variant="secondary"
              onClick={handleResendCode}
              disabled={isResending || countdown > 0}
              loading={isResending}
            >
              {countdown > 0 ? `重新发送 (${countdown}s)` : '重新发送验证码'}
            </Button>
          </div>
        </form>

        <div className="verification-footer">
          <p>没有收到验证码？请检查垃圾邮件文件夹</p>
          <button 
            type="button" 
            className="back-to-register"
            onClick={() => navigate('/register')}
          >
            返回注册页面
          </button>
        </div>
      </div>
    </div>
  );
};

export default EmailVerificationPage;
