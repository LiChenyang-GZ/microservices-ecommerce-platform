// 鉴权 PR ③：管理页只给管理员看。
//
// 【层】组件测试（React Testing Library）：真的渲染 AdminDashboard。
// 【假的】登录状态（useAuth）、后端接口（adminService、api）。
// 【为什么前端也要挡】真正的检查在后端（普通用户调管理接口回 403）。前端挡是体验：普通用户手输 /admin 时，
//   不该看到一个每个模块都报错的管理页，而是被送回首页。
// 【红灯为什么红】AdminDashboard 不看角色，谁登录都显示五个模块。

import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import AdminDashboard from './AdminDashboard';
import { useAuth } from '../../contexts/AuthContext';

// 原因见 OrderDetailPage.test.jsx：CRA 的 jest 27 解析不了 react-router-dom v7，直接用核心包 react-router
jest.mock('react-router-dom', () => {
    const { TextEncoder, TextDecoder } = require('util');
    global.TextEncoder = global.TextEncoder || TextEncoder;
    global.TextDecoder = global.TextDecoder || TextDecoder;
    return jest.requireActual('react-router');
}, { virtual: true });

jest.mock('../../services/adminService', () => ({
    __esModule: true,
    default: {
        getAllOrders: jest.fn().mockResolvedValue({ orders: [] }),
        getAllProducts: jest.fn().mockResolvedValue({ products: [] }),
        getAllWarehouses: jest.fn().mockResolvedValue({ warehouses: [] }),
        getAllOutTransactions: jest.fn().mockResolvedValue({ data: [] }),
    },
}));

jest.mock('../../services/api', () => ({
    deliveryAPI: { getDeliveryDlqAlerts: jest.fn().mockResolvedValue([]) },
}));

jest.mock('../../contexts/AuthContext', () => ({ useAuth: jest.fn() }));

function renderAdmin() {
    return render(
        <MemoryRouter initialEntries={['/admin']}>
            <Routes>
                <Route path="/" element={<div>Home page</div>} />
                <Route path="/admin" element={<AdminDashboard />} />
            </Routes>
        </MemoryRouter>
    );
}

test('a logged-in ordinary user is sent home instead of seeing the admin modules', () => {
    useAuth.mockReturnValue({ isLoggedIn: true, isLoading: false, role: 'USER' });

    renderAdmin();

    expect(screen.queryByText('Delivery DLQ Alerts')).not.toBeInTheDocument();
    expect(screen.getByText('Home page')).toBeInTheDocument();
});

test('a token from before roles existed (no role saved) counts as an ordinary user', () => {
    useAuth.mockReturnValue({ isLoggedIn: true, isLoading: false, role: undefined });

    renderAdmin();

    expect(screen.queryByText('Delivery DLQ Alerts')).not.toBeInTheDocument();
});

test('an admin sees the modules', () => {
    useAuth.mockReturnValue({ isLoggedIn: true, isLoading: false, role: 'ADMIN' });

    renderAdmin();

    expect(screen.getByText('Delivery DLQ Alerts')).toBeInTheDocument();
});
