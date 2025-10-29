# Frontend Login Interface

This is a modern React login interface with the following features:

## Features

- ✅ Responsive design, supports mobile and desktop
- ✅ Form validation (email format, password length, etc.)
- ✅ Loading states and error handling
- ✅ Dark mode support
- ✅ Reusable components (Input, Button)
- ✅ Modern UI design
- ✅ Accessibility support

## Component Structure

```
src/
├── pages/
│   ├── LoginPage.jsx      # Main login page component
│   └── LoginPage.css      # Login page styles
├── components/
│   ├── Input.jsx          # Reusable input component
│   ├── Input.css          # Input component styles
│   ├── Button.jsx         # Reusable button component
│   └── Button.css         # Button component styles
└── App.js                 # Main application component
```

## Usage

1. Start the development server:
   ```bash
   npm start
   ```

2. Visit `http://localhost:3000` to view the login interface

## Customization

### Modify Theme Colors
Modify the following CSS variables in `LoginPage.css`:
```css
background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
```

### Add API Integration
Add actual API calls in the `handleSubmit` function in `LoginPage.jsx`:
```javascript
const response = await fetch('/api/login', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
  },
  body: JSON.stringify(formData),
});
```

### Add Routing
If you need routing functionality, install `react-router-dom`:
```bash
npm install react-router-dom
```

## Style Features

- Gradient background
- Card-based layout
- Smooth animations
- Responsive breakpoints
- Automatic dark mode adaptation
- Modern form controls

## Browser Support

- Chrome (recommended)
- Firefox
- Safari
- Edge

## Tech Stack

- React 19.2.0
- CSS3
- ES6+
