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
        <h1 className="home-title">欢迎来到我们的平台</h1>
        <div className="user-info">
          <p className="welcome-text">你好, {userEmail}!</p>
          <Button 
            onClick={handleLogout}
            variant="secondary"
            size="medium"
            className="logout-button"
          >
            登出
          </Button>
        </div>
      </div>
      
      <div className="home-content">
        <div className="feature-cards">
          <div className="feature-card" onClick={() => navigate('/orders')}>
            <h3>💳 我的订单</h3>
            <p>查看订单和付款状态</p>
            <Button onClick={(e) => { e.stopPropagation(); navigate('/orders'); }}>
              查看订单
            </Button>
          </div>
          
          <div className="feature-card" onClick={() => navigate('/deliveries')}>
            <h3>📦 配送追踪</h3>
            <p>查看包裹配送进度</p>
            <Button onClick={(e) => { e.stopPropagation(); navigate('/deliveries'); }}>
              查看配送
            </Button>
          </div>
          
          <div className="feature-card" onClick={() => navigate('/products')}>
            <h3>🛍️ 商品目录</h3>
            <p>浏览商品并下单购买</p>
            <Button onClick={(e) => { e.stopPropagation(); navigate('/products'); }}>
              去选购
            </Button>
          </div>
          
          <div className="feature-card">
            <h3>⚙️ 账户设置</h3>
            <p>管理您的个人信息和偏好</p>
            <Button disabled>即将推出</Button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default HomePage;
