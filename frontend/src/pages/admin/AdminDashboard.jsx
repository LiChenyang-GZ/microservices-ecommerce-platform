import React, { useState, useEffect } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import adminService from '../../services/adminService';
import './AdminDashboard.css';

const AdminDashboard = () => {
  const navigate = useNavigate();
  const { module } = useParams();

  const modules = [
    {
      id: 'order',
      title: 'Order Module',
      subtitle: 'Order Overview',
      icon: '📦',
      description: 'View all orders and their status'
    },
    {
      id: 'product',
      title: 'Product Module',
      subtitle: 'Product Inventory',
      icon: '🛍️',
      description: 'View product inventory levels'
    },
    {
      id: 'warehouse',
      title: 'Warehouse Module',
      subtitle: 'Warehouse Stock',
      icon: '🏢',
      description: 'View warehouse inventory details'
    },
    {
      id: 'audit',
      title: 'Inventory Audit Report',
      subtitle: 'Transaction History',
      icon: '📊',
      description: 'View all inventory transactions'
    }
  ];

  const selectedModule = modules.find(m => m.id === module);

  const handleModuleClick = (moduleId) => {
    navigate(`/admin/${moduleId}`);
  };

  const handleBack = () => {
    navigate('/admin');
  };

  return (
    <div className="admin-dashboard">
      {!selectedModule ? (
        <>
          <div className="admin-header">
            <h1>Inventory Management System</h1>
            <p className="subtitle">库存管理系统</p>
          </div>

          <div className="admin-container">
            <div className="module-grid">
              {modules.map(m => (
                <div
                  key={m.id}
                  className="module-card"
                  onClick={() => handleModuleClick(m.id)}
                >
                  <div className="card-icon">{m.icon}</div>
                  <h3 className="card-title">{m.title}</h3>
                  <p className="card-subtitle">{m.subtitle}</p>
                  <p className="card-description">{m.description}</p>
                  <button className="card-btn">Enter Module →</button>
                </div>
              ))}
            </div>
          </div>
        </>
      ) : (
        <ModuleView 
          moduleConfig={selectedModule}
          onBack={handleBack}
        />
      )}
    </div>
  );
};

const ModuleView = ({ moduleConfig, onBack }) => {
  const [data, setData] = useState([]);
  const [filteredData, setFilteredData] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [filterValue, setFilterValue] = useState('');

  useEffect(() => {
    loadData();
  }, [moduleConfig.id]);

  const loadData = async () => {
    setLoading(true);
    setError('');
    try {
      let response;
      
      switch(moduleConfig.id) {
        case 'order':
          response = await adminService.getAllOrders();
          break;
        case 'product':
          response = await adminService.getAllProducts();
          break;
        case 'warehouse':
          response = await adminService.getAllWarehouses();
          break;
        case 'audit':
          response = await adminService.getAllOutTransactions();
          break;
        default:
          throw new Error('Unknown module');
      }

      if (!response) {
        setError('No response from server');
        return;
      }

      // Extract data based on response structure
      let extractedData = null;
      
      // Try different data field names depending on response type
      if (response.data) {
        extractedData = response.data;
      } else if (response.orders) {
        extractedData = response.orders;
      } else if (response.products) {
        extractedData = response.products;
      } else if (response.warehouses) {
        extractedData = response.warehouses;
      } else {
        setError(response?.message || response?.result?.message || 'No data available');
        return;
      }

      if (Array.isArray(extractedData) && extractedData.length > 0) {
        setData(extractedData);
        setFilteredData(extractedData);
      } else if (Array.isArray(extractedData) && extractedData.length === 0) {
        setData([]);
        setFilteredData([]);
        setError('No records found');
      } else {
        setError(response?.message || 'Failed to load data');
      }
    } catch (err) {
      console.error('Error loading data:', err);
      setError(`Failed to load data: ${err.message}`);
    } finally {
      setLoading(false);
    }
  };

  const handleFilter = (e) => {
    e.preventDefault();
    const value = filterValue.trim().toLowerCase();
    
    if (!value) {
      setFilteredData(data);
      return;
    }

    const filtered = data.filter(item => {
      // Generic search across common fields
      return JSON.stringify(item).toLowerCase().includes(value);
    });
    
    setFilteredData(filtered);
  };

  const clearFilter = () => {
    setFilterValue('');
    setFilteredData(data);
  };

  const formatDate = (dateString) => {
    if (!dateString) return 'N/A';
    return new Date(dateString).toLocaleString('zh-CN');
  };

  const renderContent = () => {
    switch(moduleConfig.id) {
      case 'order':
        return <OrderModuleContent data={filteredData} />;
      case 'product':
        return <ProductModuleContent data={filteredData} />;
      case 'warehouse':
        return <WarehouseModuleContent data={filteredData} />;
      case 'audit':
        return <AuditModuleContent data={filteredData} formatDate={formatDate} />;
      default:
        return null;
    }
  };

  return (
    <div className="module-view-container">
      <div className="module-header-bar">
        <button className="back-btn" onClick={onBack}>← Back</button>
        <h2>{moduleConfig.title}</h2>
      </div>

      <div className="module-view">
        <form onSubmit={handleFilter} className="search-form">
          <div className="form-group">
            <label>Search:</label>
            <input
              type="text"
              value={filterValue}
              onChange={(e) => setFilterValue(e.target.value)}
              placeholder="Enter search keyword"
              className="input-field"
            />
          </div>
          <button type="submit" className="search-btn" disabled={loading}>
            {loading ? 'Loading...' : 'Search'}
          </button>
          <button type="button" className="clear-btn" onClick={clearFilter} disabled={loading}>
            Clear
          </button>
        </form>

        {error && <div className="error-message">{error}</div>}

        {loading && (
          <div className="loading-state">
            <p>Loading data...</p>
          </div>
        )}

        {!loading && filteredData.length > 0 && (
          <div className="records-info">
            <p>Total Records: <strong>{filteredData.length}</strong></p>
          </div>
        )}

        {!loading && filteredData.length > 0 && renderContent()}

        {!loading && filteredData.length === 0 && data.length > 0 && (
          <div className="empty-state">
            <p>No records match your search criteria</p>
          </div>
        )}

        {!loading && data.length === 0 && (
          <div className="empty-state">
            <p>No data available</p>
          </div>
        )}
      </div>
    </div>
  );
};

// Order Module Content
const OrderModuleContent = ({ data }) => {
  return (
    <div className="records-table-wrapper">
      <table className="records-table">
        <thead>
          <tr>
            <th>Order ID</th>
            <th>User ID</th>
            <th>Product</th>
            <th>Qty</th>
            <th>Status</th>
            <th>Total Amount</th>
            <th>Created Date</th>
          </tr>
        </thead>
        <tbody>
          {data.map((order, index) => (
            <tr key={index}>
              <td>{order.id}</td>
              <td>{order.userId}</td>
              <td>{order.productName}</td>
              <td>{order.quantity}</td>
              <td><span className="status-badge">{order.status || 'N/A'}</span></td>
              <td>${order.totalAmount?.toFixed(2) || '0.00'}</td>
              <td>{new Date(order.createdAt).toLocaleString('zh-CN')}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

// Product Module Content
const ProductModuleContent = ({ data }) => {
  return (
    <div className="records-table-wrapper">
      <table className="records-table">
        <thead>
          <tr>
            <th>Product ID</th>
            <th>Product Name</th>
            <th>Price</th>
            <th>Stock Quantity</th>
            <th>Description</th>
          </tr>
        </thead>
        <tbody>
          {data.map((product, index) => (
            <tr key={index}>
              <td>{product.id}</td>
              <td>{product.name}</td>
              <td>${product.price?.toFixed(2) || '0.00'}</td>
              <td>{product.stockQuantity || '0'}</td>
              <td>{product.description?.substring(0, 50) || 'N/A'}...</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

// Warehouse Module Content
const WarehouseModuleContent = ({ data }) => {
  const [expandedWarehouse, setExpandedWarehouse] = React.useState(null);

  return (
    <div className="records-table-wrapper">
      <table className="records-table">
        <thead>
          <tr>
            <th style={{ width: '60px' }}></th>
            <th>Warehouse ID</th>
            <th>Warehouse Name</th>
            <th>Location</th>
            <th>Products</th>
            <th>Total Qty</th>
          </tr>
        </thead>
        <tbody>
          {data.map((warehouse, index) => {
            const totalQuantity = warehouse.products?.reduce((sum, p) => sum + (p.stockQuantity || 0), 0) || 0;
            return (
              <React.Fragment key={index}>
                <tr onClick={() => setExpandedWarehouse(expandedWarehouse === index ? null : index)}
                    style={{ cursor: 'pointer', backgroundColor: expandedWarehouse === index ? '#f0f4ff' : 'transparent' }}>
                  <td style={{ textAlign: 'center' }}>
                    <span style={{ fontSize: '1.2em' }}>
                      {expandedWarehouse === index ? '▼' : '▶'}
                    </span>
                  </td>
                  <td>{warehouse.id}</td>
                  <td>{warehouse.name}</td>
                  <td>{warehouse.location || 'N/A'}</td>
                  <td>{warehouse.products?.length || 0}</td>
                  <td>{totalQuantity}</td>
                </tr>
                {expandedWarehouse === index && warehouse.products && warehouse.products.length > 0 && (
                  <tr>
                    <td colSpan="6" style={{ padding: '0' }}>
                      <table style={{ width: '100%', borderCollapse: 'collapse', backgroundColor: '#f9fbff' }}>
                        <thead>
                          <tr style={{ backgroundColor: '#e0eaff', borderBottom: '2px solid #667eea' }}>
                            <th style={{ padding: '10px', textAlign: 'left', fontWeight: '600', color: '#667eea' }}>Product Name</th>
                            <th style={{ padding: '10px', textAlign: 'left', fontWeight: '600', color: '#667eea' }}>Price</th>
                            <th style={{ padding: '10px', textAlign: 'left', fontWeight: '600', color: '#667eea' }}>Quantity</th>
                          </tr>
                        </thead>
                        <tbody>
                          {warehouse.products.map((product, pIndex) => (
                            <tr key={pIndex} style={{ borderBottom: '1px solid #eee' }}>
                              <td style={{ padding: '10px' }}>{product.name}</td>
                              <td style={{ padding: '10px' }}>${product.price?.toFixed(2) || '0.00'}</td>
                              <td style={{ padding: '10px' }}>
                                <span style={{ 
                                  display: 'inline-block',
                                  padding: '4px 8px',
                                  backgroundColor: '#d4edda',
                                  color: '#155724',
                                  borderRadius: '4px',
                                  fontWeight: '600'
                                }}>
                                  {product.stockQuantity}
                                </span>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </td>
                  </tr>
                )}
              </React.Fragment>
            );
          })}
        </tbody>
      </table>
    </div>
  );
};

// Audit Module Content
const AuditModuleContent = ({ data, formatDate }) => {
  return (
    <div className="records-table-wrapper">
      <table className="records-table">
        <thead>
          <tr>
            <th>Product Name</th>
            <th>Warehouse Name</th>
            <th>Qty</th>
            <th>Stock Before</th>
            <th>Stock After</th>
            <th>Operation Type</th>
            <th>Operation Time</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          {data.map((record, index) => (
            <tr key={index}>
              <td>{record.productName}</td>
              <td>{record.warehouseName}</td>
              <td>{record.quantity}</td>
              <td>{record.stockBefore}</td>
              <td>{record.stockAfter}</td>
              <td><span className="operation-badge">{record.operationType}</span></td>
              <td>{formatDate(record.operationTime)}</td>
              <td>
                <span className={`status-badge status-${record.status?.toLowerCase()}`}>
                  {record.status === 'SUCCESS' ? '✓' : record.status === 'FAILED' ? '✗' : record.status}
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

export default AdminDashboard;
