import React, { createContext, useContext, useState, useEffect } from 'react';

// Create authentication context
const AuthContext = createContext();

// Authentication provider component
export const AuthProvider = ({ children }) => {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [userEmail, setUserEmail] = useState('');
  const [userId, setUserId] = useState(null);
  const [token, setToken] = useState(null);
  const [isLoading, setIsLoading] = useState(true);

  // Check login status in localStorage on initialization
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

  // Login function
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

  // Logout function
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

// Custom hook to use authentication context
export const useAuth = () => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within AuthProvider');
  }
  return context;
};

export default AuthContext;
