import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Input from '../components/Input';
import Button from '../components/Button';
import { userAPI, emailAPI } from '../services/api';
import './RegisterPage.css';

const RegisterPage = () => {
  const navigate = useNavigate();
  const [formData, setFormData] = useState({
    firstName: '',
    lastName: '',
    email: '',
    password: '',
    confirmPassword: '',
    agreeToTerms: false
  });
  const [errors, setErrors] = useState({});
  const [isLoading, setIsLoading] = useState(false);

  const handleChange = (e) => {
    const { name, value, type, checked } = e.target;
    setFormData(prev => ({
      ...prev,
      [name]: type === 'checkbox' ? checked : value
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
    
    if (!formData.firstName.trim()) {
      newErrors.firstName = 'Please enter your first name';
    }
    
    if (!formData.lastName.trim()) {
      newErrors.lastName = 'Please enter your last name';
    }
    
    if (!formData.email) {
      newErrors.email = 'Please enter your email address';
    } else if (!/\S+@\S+\.\S+/.test(formData.email)) {
      newErrors.email = 'Please enter a valid email address';
    }
    
    if (!formData.password) {
      newErrors.password = 'Please enter a password';
    } else if (formData.password.length < 8) {
      newErrors.password = 'Password must be at least 8 characters';
    } else if (!/(?=.*[a-z])(?=.*[A-Z])(?=.*\d)/.test(formData.password)) {
      newErrors.password = 'Password must contain at least one uppercase letter, one lowercase letter, and one number';
    }
    
    if (!formData.confirmPassword) {
      newErrors.confirmPassword = 'Please confirm your password';
    } else if (formData.password !== formData.confirmPassword) {
      newErrors.confirmPassword = 'Passwords do not match';
    }
    
    if (!formData.agreeToTerms) {
      newErrors.agreeToTerms = 'You must agree to the terms and conditions';
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
      // 1. Create account
      const accountData = {
        firstName: formData.firstName,
        lastName: formData.lastName,
        email: formData.email,
        password: formData.password
      };
      
      console.log('Creating account:', accountData);
      
      const response = await userAPI.createAccount(accountData);
      
      if (response.success) {
        console.log('Account created successfully:', response);
        
        // 2. Send verification code
        try {
          console.log('Sending verification code to:', formData.email);
          const emailResponse = await emailAPI.sendVerificationCode(formData.email);
          
          if (emailResponse.success) {
            console.log('Verification code sent successfully');
            // 3. Navigate to email verification page
            navigate('/email-verification', { 
              state: { 
                email: formData.email,
                message: 'Account created successfully, please verify your email'
              }
            });
          } else {
            setErrors({ submit: emailResponse.message || 'Failed to send verification code' });
          }
        } catch (emailError) {
          console.error('Failed to send verification code:', emailError);
          if (emailError.response?.data?.message) {
            setErrors({ submit: emailError.response.data.message });
          } else {
            setErrors({ submit: 'Failed to send verification code, please try again' });
          }
        }
      } else {
        setErrors({ submit: response.message || 'Registration failed' });
      }
      
    } catch (error) {
      console.error('Account creation failed:', error);
      
      // Handle different types of errors
      let errorMessage = 'Account creation failed, please try again';
      
      if (error.response) {
        const { status, data } = error.response;
        if (status === 400) {
          errorMessage = 'Please check if the entered information is correct';
        } else if (status === 409) {
          errorMessage = 'This email is already registered, please use another email';
        } else if (status === 500) {
          errorMessage = 'Server error, please try again later';
        }
        
        if (data && data.message) {
          errorMessage = data.message;
        }
      } else if (error.request) {
        errorMessage = 'Unable to connect to server, please check network connection';
      }
      
      setErrors({ submit: errorMessage });
    } finally {
      setIsLoading(false);
    }
  };

  const handleBackToLogin = () => {
    navigate('/login');
  };

  return (
    <div className="register-container">
      <div className="register-card">
        <div className="register-header">
          <h1 className="register-title">Create Account</h1>
          <p className="register-subtitle">Join us today! Fill in your details to get started.</p>
        </div>
        
        <form className="register-form" onSubmit={handleSubmit}>
          <div className="name-fields">
            <Input
              type="text"
              name="firstName"
              label="First Name"
              value={formData.firstName}
              onChange={handleChange}
              placeholder="Enter your first name"
              error={errors.firstName}
              disabled={isLoading}
              required
            />
            
            <Input
              type="text"
              name="lastName"
              label="Last Name"
              value={formData.lastName}
              onChange={handleChange}
              placeholder="Enter your last name"
              error={errors.lastName}
              disabled={isLoading}
              required
            />
          </div>
          
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
            type="password"
            name="password"
            label="Password"
            value={formData.password}
            onChange={handleChange}
            placeholder="Create a strong password"
            error={errors.password}
            disabled={isLoading}
            required
          />
          
          <Input
            type="password"
            name="confirmPassword"
            label="Confirm Password"
            value={formData.confirmPassword}
            onChange={handleChange}
            placeholder="Confirm your password"
            error={errors.confirmPassword}
            disabled={isLoading}
            required
          />
          
          <div className="terms-section">
            <label className="checkbox-container">
              <input 
                type="checkbox" 
                name="agreeToTerms"
                checked={formData.agreeToTerms}
                onChange={handleChange}
                disabled={isLoading}
              />
              <span className="checkmark"></span>
              I agree to the <a href="/terms" className="terms-link">Terms and Conditions</a> and <a href="/privacy" className="terms-link">Privacy Policy</a>
            </label>
            {errors.agreeToTerms && <span className="error-message">{errors.agreeToTerms}</span>}
          </div>
          
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
              className="register-button"
            >
              Create Account
            </Button>
            
            <Button 
              type="button"
              onClick={handleBackToLogin}
              variant="secondary"
              size="medium"
              className="back-button"
            >
              Already have an account? Sign In
            </Button>
          </div>
        </form>
      </div>
    </div>
  );
};

export default RegisterPage;
