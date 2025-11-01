// Simple script to test frontend-backend connection
// This file can be used to test if API connections are working properly

import { userAPI } from '../services/api';

// Test data
const testAccountData = {
  firstName: 'Test',
  lastName: 'User',
  email: 'test@example.com',
  password: 'TestPassword123'
};

const testLoginData = {
  email: 'test@example.com',
  password: 'TestPassword123'
};

// Test account creation
export const testCreateAccount = async () => {
  console.log('Starting test for create account API...');
  
  try {
    const response = await userAPI.createAccount(testAccountData);
    console.log('✅ Create account API test successful!');
    console.log('Response data:', response);
    return true;
  } catch (error) {
    console.error('❌ Create account API test failed:', error);
    
    if (error.response) {
      console.error('Server response:', error.response.status, error.response.data);
    } else if (error.request) {
      console.error('Network error: Unable to connect to backend server');
      console.error('Please ensure backend service is running on http://localhost:8082');
    }
    
    return false;
  }
};

// Test login
export const testLogin = async () => {
  console.log('Starting test for login API...');
  
  try {
    const response = await userAPI.login(testLoginData);
    console.log('✅ Login API test successful!');
    console.log('Response data:', response);
    return true;
  } catch (error) {
    console.error('❌ Login API test failed:', error);
    
    if (error.response) {
      console.error('Server response:', error.response.status, error.response.data);
    } else if (error.request) {
      console.error('Network error: Unable to connect to backend server');
      console.error('Please ensure backend service is running on http://localhost:8082');
    }
    
    return false;
  }
};

// Complete test flow
export const testFullFlow = async () => {
  console.log('Starting complete test flow...');
  
  // 1. First create account
  const createSuccess = await testCreateAccount();
  if (!createSuccess) {
    console.log('Account creation failed, skipping login test');
    return false;
  }
  
  // Wait a bit
  await new Promise(resolve => setTimeout(resolve, 1000));
  
  // 2. Test login
  const loginSuccess = await testLogin();
  
  if (createSuccess && loginSuccess) {
    console.log('🎉 Complete test flow successful!');
    return true;
  } else {
    console.log('❌ Complete test flow failed');
    return false;
  }
};

// Auto-run tests in development environment (optional)
if (process.env.NODE_ENV === 'development') {
  // You can uncomment the line below to auto-test
  // testFullFlow();
}
