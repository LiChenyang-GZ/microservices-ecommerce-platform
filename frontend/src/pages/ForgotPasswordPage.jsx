import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import Input from '../components/Input';
import Button from '../components/Button';
import { userAPI } from '../services/api';
import './ForgotPasswordPage.css';

const ForgotPasswordPage = () => {
  const navigate = useNavigate();
  const [formData, setFormData] = useState({
    email: ''
  });
  const [errors, setErrors] = useState({});
  const [isLoading, setIsLoading] = useState(false);
  const [isEmailSent, setIsEmailSent] = useState(false);

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
      newErrors.email = 'Please enter your email address';
    } else if (!/\S+@\S+\.\S+/.test(formData.email)) {
      newErrors.email = 'Please enter a valid email address';
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
      const response = await userAPI.forgotPassword(formData.email);
      
      if (response.success) {
        // Navigate to password reset page, passing email parameter
        navigate('/reset-password', { 
          state: { email: formData.email } 
        });
      } else {
        setErrors({ submit: response.message || 'Failed to send, please try again' });
      }
      
    } catch (error) {
      console.error('Forgot password failed:', error);
      const errorMessage = error.response?.data?.message || 'Failed to send, please try again';
      setErrors({ submit: errorMessage });
    } finally {
      setIsLoading(false);
    }
  };

  const handleBackToLogin = () => {
    navigate('/login');
  };

  if (isEmailSent) {
    return (
      <div className="forgot-password-container">
        <div className="forgot-password-card">
          <div className="success-header">
            <div className="success-icon">✓</div>
            <h1 className="success-title">Email Sent!</h1>
            <p className="success-subtitle">
              We've sent a password reset link to <strong>{formData.email}</strong>
            </p>
          </div>
          
          <div className="success-content">
            <p className="success-message">
              Please check your email and follow the instructions to reset your password. 
              If you don't see the email, check your spam folder.
            </p>
            
            <div className="success-actions">
              <Button 
                onClick={handleBackToLogin}
                variant="primary"
                size="large"
                className="back-button"
              >
                Back to Login
              </Button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="forgot-password-container">
      <div className="forgot-password-card">
        <div className="forgot-password-header">
          <h1 className="forgot-password-title">Forgot Password?</h1>
          <p className="forgot-password-subtitle">
            No worries! Enter your email address and we'll send you a reset link.
          </p>
        </div>
        
        <form className="forgot-password-form" onSubmit={handleSubmit}>
          <Input
            type="email"
            name="email"
            label="Email Address"
            value={formData.email}
            onChange={handleChange}
            placeholder="Enter your email address"
            error={errors.email}
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
              Send Reset Link
            </Button>
            
            <Button 
              type="button"
              onClick={handleBackToLogin}
              variant="secondary"
              size="medium"
              className="back-button"
            >
              Back to Login
            </Button>
          </div>
        </form>
      </div>
    </div>
  );
};

export default ForgotPasswordPage;
