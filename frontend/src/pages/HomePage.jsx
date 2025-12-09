import React from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import Button from '../components/Button';
import './HomePage.css';

const HomePage = () => {
  const { userEmail, logout } = useAuth();
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
  };

  return (
    <div className="home-container">
      <div className="home-header">
        <h1 className="home-title">Welcome to Our Platform</h1>
        <div className="user-info">
          <p className="welcome-text">Hello, {userEmail}!</p>
          <Button 
            onClick={handleLogout}
            variant="secondary"
            size="medium"
            className="logout-button"
          >
            Logout
          </Button>
        </div>
      </div>
      
      <div className="home-content">
        <div className="feature-cards">
          <div className="feature-card" onClick={() => navigate('/products')}>
            <h3>🛍️ Product Catalog</h3>
            <p>Browse products and place orders</p>
            <Button onClick={(e) => { e.stopPropagation(); navigate('/products'); }}>
              Shop Now
            </Button>
          </div>

          <div className="feature-card" onClick={() => navigate('/orders')}>
            <h3>📋 My Orders</h3>
            <p>View your order history and details</p>
            <Button onClick={(e) => { e.stopPropagation(); navigate('/orders'); }}>
              View Orders
            </Button>
          </div>

          <div className="feature-card" onClick={() => navigate('/add-money')}>
            <h3>💰 Add Money</h3>
            <p>Add funds to your bank account</p>
            <Button onClick={(e) => { e.stopPropagation(); navigate('/add-money'); }}>
              Manage Balance
            </Button>
          </div>

          <div className="feature-card" onClick={() => navigate('/deliveries')}>
            <h3>📦 Delivery Tracking</h3>
            <p>Track your package deliveries</p>
            <Button onClick={(e) => { e.stopPropagation(); navigate('/deliveries'); }}>
              View Deliveries
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default HomePage;
