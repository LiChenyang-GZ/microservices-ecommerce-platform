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
    // Get email from URL parameters or state
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
      newErrors.email = 'Please enter email address';
    } else if (!/\S+@\S+\.\S+/.test(formData.email)) {
      newErrors.email = 'Please enter a valid email address';
    }
    
    if (!formData.code) {
      newErrors.code = 'Please enter verification code';
    } else if (!/^\d{6}$/.test(formData.code)) {
      newErrors.code = 'Verification code must be 6 digits';
    }
    
    if (!formData.newPassword) {
      newErrors.newPassword = 'Please enter new password';
    } else if (formData.newPassword.length < 6) {
      newErrors.newPassword = 'Password must be at least 6 characters';
    }
    
    if (!formData.confirmPassword) {
      newErrors.confirmPassword = 'Please confirm new password';
    } else if (formData.newPassword !== formData.confirmPassword) {
      newErrors.confirmPassword = 'Passwords do not match';
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
        setErrors({ submit: response.message || 'Password reset failed, please try again' });
      }
      
    } catch (error) {
      console.error('Reset password failed:', error);
      const errorMessage = error.response?.data?.message || 'Password reset failed, please try again';
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
      setErrors({ submit: 'Please enter email address first' });
      return;
    }
    
    setIsLoading(true);
    setErrors({});
    
    try {
      const response = await userAPI.forgotPassword(formData.email);
      
      if (response.success) {
        setErrors({ submit: 'Verification code has been resent' });
      } else {
        setErrors({ submit: response.message || 'Failed to resend' });
      }
      
    } catch (error) {
      console.error('Resend code failed:', error);
      const errorMessage = error.response?.data?.message || 'Failed to resend';
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
            <h1 className="success-title">Password Reset Successful!</h1>
            <p className="success-subtitle">
              Your password has been successfully reset, you can now login with your new password
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
                Back to Login
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
          <h1 className="reset-password-title">Reset Password</h1>
          <p className="reset-password-subtitle">
            Please enter the verification code you received and your new password
          </p>
        </div>
        
        <form className="reset-password-form" onSubmit={handleSubmit}>
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
          
          <Input
            type="text"
            name="code"
            label="Verification Code"
            value={formData.code}
            onChange={handleChange}
            placeholder="Enter 6-digit verification code"
            error={errors.code}
            disabled={isLoading}
            required
          />
          
          <Input
            type="password"
            name="newPassword"
            label="New Password"
            value={formData.newPassword}
            onChange={handleChange}
            placeholder="Enter new password"
            error={errors.newPassword}
            disabled={isLoading}
            required
          />
          
          <Input
            type="password"
            name="confirmPassword"
            label="Confirm New Password"
            value={formData.confirmPassword}
            onChange={handleChange}
            placeholder="Enter new password again"
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
              Reset Password
            </Button>
            
            <Button 
              type="button"
              onClick={handleResendCode}
              disabled={isLoading}
              variant="secondary"
              size="medium"
              className="resend-button"
            >
              Resend Verification Code
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

export default ResetPasswordPage;
