import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import Input from '../components/Input';
import Button from '../components/Button';
import { userAPI } from '../services/api';
import './RegisterPage.css';

const RegisterPage = () => {
  const navigate = useNavigate();
  const [formData, setFormData] = useState({
    username: '',
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
    
    if (!formData.username.trim()) {
      newErrors.username = 'Please enter a username';
    }
    
    if (!formData.email) {
      newErrors.email = 'Please enter your email address';
    } else if (!/\S+@\S+\.\S+/.test(formData.email)) {
      newErrors.email = 'Please enter a valid email address';
    }
    
    if (!formData.password) {
      newErrors.password = 'Please enter a password';
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

    // The account exists by now, so the code page is the only way on: going back to sign up says
    // "Email already exists!" and log-in says "verify your email first" (review 1, Obsidian 56). The message
    // (e.g. store's 429 "try again in N seconds") goes along, and the user resends from there.
    const goVerifyWithError = (message) => {
      navigate('/email-verification', {
        state: {
          email: formData.email,
          error: message || 'Failed to send verification code, please try again'
        }
      });
    };

    try {
      // 1. Create account
      const accountData = {
        username: formData.username,
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
          const emailResponse = await userAPI.sendVerificationCode(formData.email);
          
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
            goVerifyWithError(emailResponse.message);
          }
        } catch (emailError) {
          console.error('Failed to send verification code:', emailError);
          goVerifyWithError(emailError.response?.data?.message);
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
        // Registered but never verified, maybe by someone else: a password reset claims it (Obsidian 58)
        if (data && data.emailNotVerified) {
          setErrors({ submit: errorMessage, claimByReset: true });
          return;
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
          <Input
            type="text"
            name="username"
            label="Username"
            value={formData.username}
            onChange={handleChange}
            placeholder="Enter your username"
            error={errors.username}
            disabled={isLoading}
            required
          />
          
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
              {errors.claimByReset && (
                <>
                  {' '}
                  <Link to="/forgot-password">Reset the password</Link>
                </>
              )}
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
