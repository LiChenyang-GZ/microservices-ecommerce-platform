import React, { useState, useEffect } from 'react';
import adminService from '../../services/adminService';
import '../admin/AdminInventoryRecords.css';

const ProductInventoryRecords = () => {
  const [productId, setProductId] = useState('');
  const [records, setRecords] = useState([]);
  const [filteredRecords, setFilteredRecords] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  // Load all OUT transactions on component mount
  useEffect(() => {
    loadAllRecords();
  }, []);

  const loadAllRecords = async () => {
    setLoading(true);
    setError('');
    try {
      const response = await adminService.getAllOutTransactions();
      if (response.success) {
        const data = response.data || [];
        setRecords(data);
        setFilteredRecords(data);
      } else {
        setError(response.message || 'Failed to load data');
      }
    } catch (err) {
      setError(err.response?.data?.message || 'Failed to load data');
    } finally {
      setLoading(false);
    }
  };

  const handleFilter = (e) => {
    e.preventDefault();
    if (!productId.trim()) {
      setFilteredRecords(records);
    } else {
      const filtered = records.filter(record => 
        record.productId?.toString() === productId.trim()
      );
      setFilteredRecords(filtered);
    }
  };

  const clearFilter = () => {
    setProductId('');
    setFilteredRecords(records);
  };

  const formatDate = (dateString) => {
    if (!dateString) return 'N/A';
    return new Date(dateString).toLocaleString('zh-CN');
  };

  return (
    <div className="inventory-records-container">
      <h2>Product Module</h2>
      
      <form onSubmit={handleFilter} className="search-form">
        <div className="form-group">
          <label>Filter by Product ID:</label>
          <input
            type="text"
            value={productId}
            onChange={(e) => setProductId(e.target.value)}
            placeholder="Enter Product ID (optional)"
            className="input-field"
          />
        </div>
        <button type="submit" className="search-btn" disabled={loading}>
          {loading ? 'Loading...' : 'Filter'}
        </button>
        <button type="button" className="clear-btn" onClick={clearFilter} disabled={loading}>
          Clear Filter
        </button>
      </form>

      {error && <div className="error-message">{error}</div>}

      {loading && (
        <div className="loading-state">
          <p>Loading data...</p>
        </div>
      )}

      {!loading && filteredRecords.length > 0 && (
        <div className="records-info">
          <p>Total Records: <strong>{filteredRecords.length}</strong></p>
        </div>
      )}

      {!loading && filteredRecords.length > 0 && (
        <div className="records-table-wrapper">
          <table className="records-table">
            <thead>
              <tr>
                <th>Warehouse Name</th>
                <th>Order ID</th>
                <th>Qty</th>
                <th>Stock Before</th>
                <th>Stock After</th>
                <th>Operation Time</th>
                <th>Status</th>
                <th>Reason</th>
              </tr>
            </thead>
            <tbody>
              {filteredRecords.map((record, index) => (
                <tr key={index}>
                  <td>{record.warehouseName}</td>
                  <td>{record.orderId || '-'}</td>
                  <td>{record.quantity}</td>
                  <td>{record.stockBefore}</td>
                  <td>{record.stockAfter}</td>
                  <td>{formatDate(record.operationTime)}</td>
                  <td>
                    <span className={`status-badge status-${record.status?.toLowerCase()}`}>
                      {record.status === 'SUCCESS' ? '✓ Success' : record.status === 'FAILED' ? '✗ Failed' : record.status}
                    </span>
                  </td>
                  <td>{record.reason || '-'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {!loading && filteredRecords.length === 0 && records.length > 0 && (
        <div className="empty-state">
          <p>No records match your filter criteria</p>
        </div>
      )}

      {!loading && records.length === 0 && (
        <div className="empty-state">
          <p>No OUT transaction records available</p>
        </div>
      )}
    </div>
  );
};

export default ProductInventoryRecords;
