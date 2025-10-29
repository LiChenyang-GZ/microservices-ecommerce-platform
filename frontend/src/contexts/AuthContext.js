import React, { createContext, useContext, useState, useEffect } from 'react';

// 创建认证上下文
const AuthContext = createContext();

// 认证提供者组件
export const AuthProvider = ({ children }) => {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [userEmail, setUserEmail] = useState('');
  const [userId, setUserId] = useState(null);
  const [token, setToken] = useState(null);
  const [isLoading, setIsLoading] = useState(true);

  // 初始化时检查localStorage中的登录状态
  useEffect(() => {
    const checkAuthStatus = () => {
      const savedToken = localStorage.getItem('token');
      const savedUserEmail = localStorage.getItem('userEmail');
      const savedUserId = localStorage.getItem('userId');
      
      if (savedToken && savedUserEmail) {
        setIsLoggedIn(true);
        setUserEmail(savedUserEmail);
        setToken(savedToken);
        setUserId(savedUserId ? parseInt(savedUserId) : null);
      }
      setIsLoading(false);
    };

    checkAuthStatus();
  }, []);

  // 登录函数
  const login = (email, token, userId) => {
    setIsLoggedIn(true);
    setUserEmail(email);
    setToken(token);
    setUserId(userId);
    localStorage.setItem('token', token);
    localStorage.setItem('userEmail', email);
    localStorage.setItem('userId', userId);
    localStorage.setItem('isLoggedIn', 'true');
  };

  // 登出函数
  const logout = () => {
    setIsLoggedIn(false);
    setUserEmail('');
    setToken(null);
    setUserId(null);
    localStorage.removeItem('token');
    localStorage.removeItem('userEmail');
    localStorage.removeItem('userId');
    localStorage.removeItem('isLoggedIn');
  };

  const value = {
    isLoggedIn,
    userEmail,
    userId,
    token,
    isLoading,
    login,
    logout
  };

  return (
    <AuthContext.Provider value={value}>
      {children}
    </AuthContext.Provider>
  );
};

// 自定义Hook来使用认证上下文
export const useAuth = () => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth必须在AuthProvider内部使用');
  }
  return context;
};

export default AuthContext;
