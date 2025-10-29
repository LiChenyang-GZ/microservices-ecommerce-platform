import React from 'react';
import './Button.css';

const Button = ({ 
  type = 'button', 
  children, 
  onClick, 
  disabled = false, 
  loading = false,
  variant = 'primary',
  size = 'medium',
  className = ''
}) => {
  const buttonClasses = `btn btn-${variant} btn-${size} ${loading ? 'loading' : ''} ${className}`;
  
  return (
    <button
      type={type}
      onClick={onClick}
      disabled={disabled || loading}
      className={buttonClasses}
    >
      {loading && <span className="btn-spinner"></span>}
      {children}
    </button>
  );
};

export default Button;
