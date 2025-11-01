import React from 'react';
import { useNavigate } from 'react-router-dom';

export default function BackButton({ label = '← Back', fallback }) {
  const navigate = useNavigate();
  const onClick = () => {
    if (fallback) {
      navigate(fallback);
    } else {
      navigate(-1);
    }
  };
  return (
    <button className="btn-back" onClick={onClick}>{label}</button>
  );
}
