import React, { createContext, useContext, useState, useEffect } from 'react';

// 创建认证上下文
const AuthContext = createContext();

// 认证提供者组件
export const AuthProvider = ({ children }) => {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [userEmail, setUserEmail] = useState('');
  const [isLoading, setIsLoading] = useState(true);

  // 初始化时检查localStorage中的登录状态
  useEffect(() => {
    const checkAuthStatus = () => {
      const savedLoginStatus = localStorage.getItem('isLoggedIn');
      const savedUserEmail = localStorage.getItem('userEmail');
      
      if (savedLoginStatus === 'true' && savedUserEmail) {
        setIsLoggedIn(true);
        setUserEmail(savedUserEmail);
      }
      setIsLoading(false);
    };

    checkAuthStatus();
  }, []);

  // 登录函数
  const login = (email) => {
    setIsLoggedIn(true);
    setUserEmail(email);
    localStorage.setItem('isLoggedIn', 'true');
    localStorage.setItem('userEmail', email);
  };

  // 登出函数
  const logout = () => {
    setIsLoggedIn(false);
    setUserEmail('');
    localStorage.removeItem('isLoggedIn');
    localStorage.removeItem('userEmail');
  };

  const value = {
    isLoggedIn,
    userEmail,
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
