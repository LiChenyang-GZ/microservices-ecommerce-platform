import React from 'react';
import './Input.css';

const Input = ({ 
  type = 'text', 
  name, 
  value, 
  onChange, 
  placeholder, 
  label, 
  error, 
  disabled = false,
  required = false
}) => {
  return (
    <div className="input-group">
      {label && (
        <label htmlFor={name} className="input-label">
          {label}
          {required && <span className="required">*</span>}
        </label>
      )}
      <input
        type={type}
        id={name}
        name={name}
        value={value}
        onChange={onChange}
        placeholder={placeholder}
        className={`input-field ${error ? 'error' : ''}`}
        disabled={disabled}
        required={required}
      />
      {error && <span className="input-error">{error}</span>}
    </div>
  );
};

export default Input;
