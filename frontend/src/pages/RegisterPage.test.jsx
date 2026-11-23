// 注册页：限流（Obsidian 54 号 R23）。
//
// 【层】组件测试（React Testing Library）：真的渲染 RegisterPage，像用户一样填表、点"Create Account"。
// 【假的】后端接口（jest.mock 把 ../services/api 换成假的）。
// 【为什么】以前注册成功后页面直接调 email 的 send-verification（公开），谁都能让 email 给任意地址发信、不限次数。
//   现在验证码也找 store 要：store 看账号、数次数（限流只写在 store 一处），再带服务凭据让 email 发。
// 【红灯为什么红】handleSubmit 还在调 emailAPI.sendVerificationCode。

import { render, screen, act, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import RegisterPage from './RegisterPage';
import { emailAPI, userAPI } from '../services/api';

// 同 OrderDetailPage.test.jsx：jest 27 解析不了 react-router-dom v7，直接用核心包 react-router
jest.mock('react-router-dom', () => {
    const { TextEncoder, TextDecoder } = require('util');
    global.TextEncoder = global.TextEncoder || TextEncoder;
    global.TextDecoder = global.TextDecoder || TextDecoder;
    return jest.requireActual('react-router');
}, { virtual: true });

jest.mock('../services/api', () => ({
    emailAPI: { sendVerificationCode: jest.fn() },
    userAPI: { createAccount: jest.fn(), sendVerificationCode: jest.fn() },
}));

const EMAIL = 'new-user@example.com';

function renderPage() {
    return render(
        <MemoryRouter initialEntries={['/register']}>
            <Routes>
                <Route path="/register" element={<RegisterPage />} />
                <Route path="/email-verification" element={<div>verification page</div>} />
            </Routes>
        </MemoryRouter>
    );
}

async function fillAndSubmit() {
    fireEvent.change(screen.getByPlaceholderText('Enter your username'), { target: { name: 'username', value: 'newuser' } });
    fireEvent.change(screen.getByPlaceholderText('Enter your email address'), { target: { name: 'email', value: EMAIL } });
    fireEvent.change(screen.getByPlaceholderText('Create a strong password'), { target: { name: 'password', value: 'pw-123456' } });
    fireEvent.change(screen.getByPlaceholderText('Confirm your password'), { target: { name: 'confirmPassword', value: 'pw-123456' } });
    fireEvent.click(screen.getByRole('checkbox'));
    await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: 'Create Account' }));
    });
}

beforeEach(() => {
    jest.clearAllMocks();
});

test('after creating the account the page asks store, not email, to send the code', async () => {
    userAPI.createAccount.mockResolvedValue({ success: true, message: 'created' });
    userAPI.sendVerificationCode.mockResolvedValue({ success: true, message: 'sent' });
    renderPage();

    await fillAndSubmit();

    expect(userAPI.sendVerificationCode).toHaveBeenCalledWith(EMAIL);
    expect(emailAPI.sendVerificationCode).not.toHaveBeenCalled();
    expect(screen.getByText('verification page')).toBeInTheDocument();
});

test('a 429 from store shows its "try again" message', async () => {
    userAPI.createAccount.mockResolvedValue({ success: true, message: 'created' });
    userAPI.sendVerificationCode.mockRejectedValue({
        response: { status: 429, data: { success: false, message: 'Too many attempts, please try again in 600 seconds' } },
    });
    renderPage();

    await fillAndSubmit();

    expect(screen.getByText('Too many attempts, please try again in 600 seconds')).toBeInTheDocument();
});
