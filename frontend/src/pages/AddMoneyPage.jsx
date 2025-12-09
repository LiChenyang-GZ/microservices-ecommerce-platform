import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import BackButton from '../components/BackButton';
import { bankAPI } from '../services/api';
import { useAuth } from '../contexts/AuthContext';
import './AddMoneyPage.css';

function AddMoneyPage() {
    const [balance, setBalance] = useState(null);
    const [accountNumber, setAccountNumber] = useState('');
    const [amount, setAmount] = useState('');
    const [isLoading, setIsLoading] = useState(true);
    const [isProcessing, setIsProcessing] = useState(false);
    const [error, setError] = useState('');
    const [successMessage, setSuccessMessage] = useState('');
    const { isLoggedIn, userId, userEmail, isLoading: authLoading } = useAuth();
    const navigate = useNavigate();

    // Fetch user's bank account and balance
    useEffect(() => {
        if (!authLoading && !isLoggedIn) {
            navigate('/login');
            return;
        }

        if (isLoggedIn && userEmail) {
            fetchAccountInfo();
        }
    }, [isLoggedIn, userEmail, authLoading, navigate]);

    const fetchAccountInfo = async () => {
        try {
            setIsLoading(true);
            setError('');

            // Get or create user's bank account (backend uses JWT token to identify user)
            const accountResponse = await bankAPI.getOrCreateAccount();

            if (accountResponse.success) {
                setAccountNumber(accountResponse.accountNumber);

                // Fetch balance
                const balanceResponse = await bankAPI.getBalance(accountResponse.accountNumber);
                if (balanceResponse.success) {
                    setBalance(balanceResponse.balance);
                }
            } else {
                setError(accountResponse.message || 'Failed to retrieve account information');
            }
        } catch (err) {
            console.error('Failed to fetch account info:', err);
            setError(err.formattedMessage || 'Unable to load account information');
        } finally {
            setIsLoading(false);
        }
    };

    const handleAddMoney = async (e) => {
        e.preventDefault();

        if (!amount || parseFloat(amount) <= 0) {
            setError('Please enter a valid amount');
            return;
        }

        setIsProcessing(true);
        setError('');
        setSuccessMessage('');

        try {
            // Simulate adding money by transferring from a "source" account
            const result = await bankAPI.addMoney(accountNumber, parseFloat(amount));

            if (result.success) {
                setSuccessMessage(`Successfully added $${parseFloat(amount).toFixed(2)} to your account!`);
                setAmount('');
                // Refresh balance
                await fetchAccountInfo();
            } else {
                setError(result.message || 'Failed to add money');
            }
        } catch (err) {
            console.error('Failed to add money:', err);
            setError(err.formattedMessage || 'Failed to process transaction');
        } finally {
            setIsProcessing(false);
        }
    };

    const formatBalance = (bal) => {
        if (bal === null || bal === undefined) return '$0.00';
        return `$${parseFloat(bal).toFixed(2)}`;
    };

    if (isLoading) {
        return <div className="loading-container">Loading account information...</div>;
    }

    return (
        <div className="add-money-container">
            <BackButton fallback="/" />
            <h1 className="page-title">Manage Your Balance</h1>

            <div className="account-info-card">
                <h2>Account Information</h2>
                <div className="info-row">
                    <span className="label">Account Number:</span>
                    <span className="value">{accountNumber || 'N/A'}</span>
                </div>
                <div className="info-row">
                    <span className="label">Current Balance:</span>
                    <span className="value balance">{formatBalance(balance)}</span>
                </div>
            </div>

            <div className="add-money-card">
                <h2>Add Money</h2>
                <p className="description">Add funds to your account for shopping</p>

                {error && <div className="error-message">{error}</div>}
                {successMessage && <div className="success-message">{successMessage}</div>}

                <form onSubmit={handleAddMoney} className="add-money-form">
                    <div className="form-group">
                        <label htmlFor="amount">Amount to Add</label>
                        <div className="input-wrapper">
                            <span className="currency-symbol">$</span>
                            <input
                                type="number"
                                id="amount"
                                value={amount}
                                onChange={(e) => setAmount(e.target.value)}
                                placeholder="0.00"
                                step="0.01"
                                min="0.01"
                                disabled={isProcessing}
                                required
                            />
                        </div>
                    </div>

                    <div className="quick-amounts">
                        <p className="quick-amounts-label">Quick amounts:</p>
                        <div className="quick-buttons">
                            <button
                                type="button"
                                onClick={() => setAmount('10')}
                                className="quick-btn"
                                disabled={isProcessing}
                            >
                                $10
                            </button>
                            <button
                                type="button"
                                onClick={() => setAmount('50')}
                                className="quick-btn"
                                disabled={isProcessing}
                            >
                                $50
                            </button>
                            <button
                                type="button"
                                onClick={() => setAmount('100')}
                                className="quick-btn"
                                disabled={isProcessing}
                            >
                                $100
                            </button>
                            <button
                                type="button"
                                onClick={() => setAmount('500')}
                                className="quick-btn"
                                disabled={isProcessing}
                            >
                                $500
                            </button>
                        </div>
                    </div>

                    <button
                        type="submit"
                        className="btn btn-primary"
                        disabled={isProcessing || !amount}
                    >
                        {isProcessing ? 'Processing...' : 'Add Money'}
                    </button>
                </form>
            </div>
        </div>
    );
}

export default AddMoneyPage;