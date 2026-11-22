import React, { createContext, useContext, useState, useEffect } from 'react';

// Create authentication context
const AuthContext = createContext();

// Authentication provider component
export const AuthProvider = ({ children }) => {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [userEmail, setUserEmail] = useState('');
  const [userId, setUserId] = useState(null);
  const [token, setToken] = useState(null);
  // USER or ADMIN from the login reply (auth PR 3). Only decides what the UI shows; the backend checks again.
  // Nothing saved (logged in before roles existed) counts as an ordinary user.
  const [role, setRole] = useState(null);
  const [isLoading, setIsLoading] = useState(true);

  // Check login status in localStorage on initialization
  useEffect(() => {
    const checkAuthStatus = () => {
      const savedToken = localStorage.getItem('token');
      const savedUserEmail = localStorage.getItem('userEmail');
      const savedUserId = localStorage.getItem('userId');
      const savedRole = localStorage.getItem('role');
      
      if (savedToken && savedUserEmail) {
        setIsLoggedIn(true);
        setUserEmail(savedUserEmail);
        setToken(savedToken);
        setUserId(savedUserId ? parseInt(savedUserId) : null);
        setRole(savedRole);
      }
      setIsLoading(false);
    };

    checkAuthStatus();
  }, []);

  // Login function
  const login = (email, token, userId, role) => {
    setRole(role || null);
    if (role) {
      localStorage.setItem('role', role);
    } else {
      localStorage.removeItem('role');
    }
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
    setRole(null);
    localStorage.removeItem('role');
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
    role,
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
