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
    // Clear error for corresponding field
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
      // Prepare data to send to backend
      const loginData = {
        email: formData.email,
        password: formData.password
      };
      
      console.log('Logging in:', loginData);
      
      // Call backend API for login
      const response = await userAPI.login(loginData);
      
      console.log('Login successful:', response);
      
      // Handle successful response
      if (response.success && response.token) {
        // Use auth context to update login status, save token and user info
        login(response.email, response.token, response.userId);
        
        alert('Login successful! Welcome back!');
        
        // Redirect to home page
        navigate('/');
      } else {
        throw new Error(response.message || 'Login failed');
      }
      
    } catch (error) {
      console.error('Login failed:', error);
      
      // Handle different types of errors
      let errorMessage = 'Login failed, please try again';
      
      if (error.response) {
        // Server returned error
        const { status, data } = error.response;
        if (status === 401) {
          // Try to get error message from response data
          errorMessage = (data && data.message) ? data.message : 'Email or password incorrect, please check and try again';
        } else if (status === 400) {
          errorMessage = 'Please check if the entered information is correct';
        } else if (status === 500) {
          errorMessage = 'Server error, please try again later';
        }
        
        // If there's a specific error message, use it
        if (data && data.message) {
          errorMessage = data.message;
        } else if (data && typeof data === 'string') {
          errorMessage = data;
        }
      } else if (error.request) {
        // Network error
        errorMessage = 'Unable to connect to server, please check network connection';
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
