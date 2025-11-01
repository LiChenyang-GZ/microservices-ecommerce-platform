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
    // Get email from route state or localStorage
    const emailFromState = location.state?.email;
    const emailFromStorage = localStorage.getItem('pendingEmail');
    
    if (emailFromState) {
      setEmail(emailFromState);
      localStorage.setItem('pendingEmail', emailFromState);
    } else if (emailFromStorage) {
      setEmail(emailFromStorage);
    } else {
      // If no email information, redirect to register page
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
      // Verify verification code
      const verifyResponse = await emailAPI.verifyCode(email, verificationCode);
      
      if (verifyResponse.success) {
        // Verification successful, activate account
        const activateResponse = await userAPI.activateAccount(email);
        
        if (activateResponse.success) {
          setSuccess('Email verification successful! Account activated, please login.');
          // Clear pending email
          localStorage.removeItem('pendingEmail');
          
          // Redirect to login page after 2 seconds
          setTimeout(() => {
            navigate('/login', { 
              state: { 
                message: 'Email verification successful, please login',
                email: email 
              }
            });
          }, 2000);
        } else {
          setError('Account activation failed, please try again');
        }
      } else {
        setError(verifyResponse.message || 'Verification code invalid or expired');
      }
    } catch (error) {
      console.error('Verification failed:', error);
      if (error.response?.data?.message) {
        setError(error.response.data.message);
      } else {
        setError('Verification failed, please try again');
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
        setSuccess('Verification code has been resent to your email');
        setCountdown(60); // 60 second countdown
      } else {
        setError(response.message || 'Failed to send');
      }
    } catch (error) {
      console.error('Failed to resend verification code:', error);
      if (error.response?.data?.message) {
        setError(error.response.data.message);
      } else {
        setError('Failed to resend verification code, please try again');
      }
    } finally {
      setIsResending(false);
    }
  };

  const handleChange = (e) => {
    const { value } = e.target;
    // Only allow numbers, maximum 6 digits
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
          <h1>Email Verification</h1>
          <p>We have sent a verification code to <strong>{email}</strong></p>
          <p>Please enter the 6-digit verification code to complete registration</p>
        </div>

        <form onSubmit={handleVerify} className="verification-form">
          <div className="verification-input-group">
            <Input
              type="text"
              name="verificationCode"
              label="Verification Code"
              value={verificationCode}
              onChange={handleChange}
              placeholder="Enter 6-digit verification code"
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
              {isLoading ? 'Verifying...' : 'Verify Email'}
            </Button>

            <Button
              type="button"
              variant="secondary"
              onClick={handleResendCode}
              disabled={isResending || countdown > 0}
              loading={isResending}
            >
              {countdown > 0 ? `Resend (${countdown}s)` : 'Resend Verification Code'}
            </Button>
          </div>
        </form>

        <div className="verification-footer">
          <p>Didn't receive the code? Please check your spam folder</p>
          <button 
            type="button" 
            className="back-to-register"
            onClick={() => navigate('/register')}
          >
            Back to Register
          </button>
        </div>
      </div>
    </div>
  );
};

export default EmailVerificationPage;
