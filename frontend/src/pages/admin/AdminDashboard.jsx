import React, { useState, useEffect } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import adminService from '../../services/adminService';
import { deliveryAPI } from '../../services/api';
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
    },
    {
      id: 'delivery-dlq',
      title: 'Delivery DLQ Alerts',
      subtitle: 'Dead-letter queue monitoring',
      icon: '🚨',
      description: 'Inspect and resolve delivery tasks that landed in the DLQ'
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
  const [actionStatuses, setActionStatuses] = useState({});

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
        case 'delivery-dlq':
          response = await deliveryAPI.getDeliveryDlqAlerts();
          break;
        default:
          throw new Error('Unknown module');
      }

      const records = normalizeRecords(response);
      if (!records) {
        throw new Error(response?.message || 'No data available');
      }

      if (records.length === 0) {
        setData([]);
        setFilteredData([]);
        setError('No records found');
        return;
      }

      setData(records);
      setFilteredData(records);
    } catch (err) {
      console.error('Error loading data:', err);
      setError(err?.formattedMessage || err?.message || 'Failed to load data');
      setData([]);
      setFilteredData([]);
    } finally {
      setLoading(false);
    }
  };

  const handleDeliveryAction = async (alertId, actionType) => {
    setError('');
    const key = `${actionType}-${alertId}`;
    setActionStatuses(prev => ({ ...prev, [key]: true }));
    try {
      const result = actionType === 'resolve'
        ? await deliveryAPI.resolveDeliveryDlqAlert(alertId)
        : await deliveryAPI.requeueDeliveryDlqAlert(alertId);
      if (result) {
        updateAlertRecord(result);
      }
    } catch (err) {
      console.error('Error performing DLQ action:', err);
      setError(err?.formattedMessage || err?.message || 'Failed to perform DLQ action');
    } finally {
      setActionStatuses(prev => {
        const next = { ...prev };
        delete next[key];
        return next;
      });
    }
  };

  const updateAlertRecord = (updated) => {
    if (!updated || typeof updated.id === 'undefined') return;
    setData(prev => prev.map(alert => alert.id === updated.id ? updated : alert));
    setFilteredData(prev => prev.map(alert => alert.id === updated.id ? updated : alert));
  };

  const isActionLoading = (alertId, actionType) => {
    return !!actionStatuses[`${actionType}-${alertId}`];
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
      case 'delivery-dlq':
        return (
          <DeliveryDLQModuleContent
            alerts={filteredData}
            onResolve={(id) => handleDeliveryAction(id, 'resolve')}
            onRequeue={(id) => handleDeliveryAction(id, 'requeue')}
            isActionLoading={isActionLoading}
            formatDate={formatDate}
          />
        );
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

const normalizeRecords = (payload) => {
  if (!payload) return null;
  if (Array.isArray(payload)) return payload;
  if (Array.isArray(payload.data)) return payload.data;
  const candidates = ['orders', 'order', 'products', 'warehouses', 'transactions', 'alerts', 'records', 'items'];
  for (const field of candidates) {
    if (Array.isArray(payload[field])) {
      return payload[field];
    }
  }
  return null;
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

const DeliveryDLQModuleContent = ({ alerts = [], onResolve, onRequeue, isActionLoading, formatDate }) => {
  const normalizedAlerts = Array.isArray(alerts) ? alerts : [];
  const [showResolved, setShowResolved] = useState(false);
  const [severityFilter, setSeverityFilter] = useState('all');

  const severityLevels = React.useMemo(() => {
    const set = new Set();
    normalizedAlerts.forEach(alert => {
      const label = (alert.severity || 'unknown').toLowerCase();
      set.add(label);
    });
    return ['all', ...Array.from(set).sort()];
  }, [normalizedAlerts]);

  const visibleAlerts = React.useMemo(() => {
    const filterSeverity = severityFilter || 'all';
    return normalizedAlerts
      .filter(alert => showResolved || !alert.resolved)
      .filter(alert => filterSeverity === 'all' || (alert.severity || 'unknown').toLowerCase() === filterSeverity);
  }, [normalizedAlerts, showResolved, severityFilter]);

  const unresolvedCount = normalizedAlerts.filter(alert => !alert.resolved).length;
  const resolvedCount = normalizedAlerts.filter(alert => alert.resolved).length;
  const totalCount = normalizedAlerts.length;

  return (
    <div className="dlq-panel">
      <div className="dlq-toolbar">
        <div className="dlq-summary-grid">
          <div className="dlq-summary-card total">
            <p>Total alerts</p>
            <strong>{totalCount}</strong>
          </div>
          <div className="dlq-summary-card pending">
            <p>Unresolved</p>
            <strong>{unresolvedCount}</strong>
          </div>
          <div className="dlq-summary-card resolved">
            <p>Resolved</p>
            <strong>{resolvedCount}</strong>
          </div>
        </div>
        <div className="dlq-controls">
          <label className="dlq-filter-label">
            Severity
            <select value={severityFilter} onChange={(e) => setSeverityFilter(e.target.value)}>
              {severityLevels.map(level => (
                <option key={level} value={level}>
                  {level === 'all' ? 'All severities' : level.toUpperCase()}
                </option>
              ))}
            </select>
          </label>
          <label className="dlq-toggle-label">
            <input
              type="checkbox"
              checked={showResolved}
              onChange={(e) => setShowResolved(e.target.checked)}
            />
            Show resolved
          </label>
        </div>
      </div>

      {visibleAlerts.length === 0 ? (
        <div className="empty-state">
          <p>{totalCount === 0 ? 'No DLQ alerts have been recorded yet.' : 'No alerts match the current filters.'}</p>
        </div>
      ) : (
        <div className="records-table-wrapper dlq-records-table">
          <table className="records-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>Delivery ID</th>
                <th>Severity</th>
                <th>Reason</th>
                <th>Alerted At</th>
                <th>Resolved At</th>
                <th>Status</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {visibleAlerts.map(alert => {
                const severityKey = (alert.severity || 'unknown').toLowerCase();
                const reasonPreview = alert.reason ? (alert.reason.length > 90 ? `${alert.reason.slice(0, 90)}…` : alert.reason) : 'No reason provided';
                const resolving = isActionLoading(alert.id, 'resolve');
                const requeueing = isActionLoading(alert.id, 'requeue');
                return (
                  <tr key={alert.id} className={alert.resolved ? 'dlq-record-row resolved' : ''}>
                    <td>{alert.id}</td>
                    <td>{alert.deliveryId}</td>
                    <td>
                      <span className={`dlq-severity-badge severity-${severityKey}`}>
                        {(alert.severity || 'UNKNOWN').toUpperCase()}
                      </span>
                    </td>
                    <td>
                      <span className="dlq-reason" title={alert.reason || 'No reason provided'}>
                        {reasonPreview}
                      </span>
                    </td>
                    <td>{formatDate(alert.alertTimestamp)}</td>
                    <td>{alert.resolved ? formatDate(alert.resolvedAt) : 'Pending'}</td>
                    <td>
                      <span className={`status-badge ${alert.resolved ? 'status-success' : 'status-failed'}`}>
                        {alert.resolved ? 'Resolved' : 'Pending'}
                      </span>
                    </td>
                    <td>
                      <div className="dlq-action-group">
                        <button
                          className="dlq-action-btn resolve"
                          onClick={() => onResolve(alert.id)}
                          disabled={alert.resolved || resolving}
                        >
                          {resolving ? 'Resolving...' : 'Resolve'}
                        </button>
                        <button
                          className="dlq-action-btn requeue"
                          onClick={() => onRequeue(alert.id)}
                          disabled={alert.resolved || requeueing}
                        >
                          {requeueing ? 'Requeueing...' : 'Requeue'}
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};

export default AdminDashboard;
