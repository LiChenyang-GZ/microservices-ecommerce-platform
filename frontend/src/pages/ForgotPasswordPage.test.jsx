// 找回密码页：抢注审查 🟠1（Obsidian 59 号，58 号 Q15）。
//
// 【层】组件测试（React Testing Library）：真的渲染找回密码页和重置页，像用户一样填邮箱、点"Send Reset Link"。
// 【假的】后端接口（jest.mock 把 ../services/api 换成假的）。
// 【为什么】找回密码现在也是"认领被抢注的邮箱"的路（58 号方案 A）。抢注的人每 15 分钟调 3 次 forgot-password，
//   把这个邮箱的发信额度用完；主人提交时拿到 429，以前页面停在原地，到不了输码页——可信箱里其实已经有码了。
//   现在 429 也去重置页，带上邮箱和后端的提示，主人用信箱里最新的那封码。
// 【红灯为什么红】catch 分支只 setErrors，不 navigate；重置页也不看 state.error。

import { render, screen, act, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import ForgotPasswordPage from './ForgotPasswordPage';
import ResetPasswordPage from './ResetPasswordPage';
import { userAPI } from '../services/api';

// 同 OrderDetailPage.test.jsx：jest 27 解析不了 react-router-dom v7，直接用核心包 react-router
jest.mock('react-router-dom', () => {
    const { TextEncoder, TextDecoder } = require('util');
    global.TextEncoder = global.TextEncoder || TextEncoder;
    global.TextDecoder = global.TextDecoder || TextDecoder;
    return jest.requireActual('react-router');
}, { virtual: true });

jest.mock('../services/api', () => ({
    userAPI: { forgotPassword: jest.fn(), resetPassword: jest.fn() },
}));

const EMAIL = 'owner@example.com';
const TOO_MANY = 'Too many attempts, please try again in 600 seconds';

function renderPage() {
    return render(
        <MemoryRouter initialEntries={['/forgot-password']}>
            <Routes>
                <Route path="/forgot-password" element={<ForgotPasswordPage />} />
                <Route path="/reset-password" element={<ResetPasswordPage />} />
            </Routes>
        </MemoryRouter>
    );
}

async function submitEmail() {
    fireEvent.change(screen.getByPlaceholderText('Enter your email address'), { target: { name: 'email', value: EMAIL } });
    await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: /Send Reset Link/ }));
    });
}

beforeEach(() => {
    jest.clearAllMocks();
});

test('a 429 still goes to the reset page, with the email filled in and the "try again" message', async () => {
    userAPI.forgotPassword.mockRejectedValue({
        response: { status: 429, data: { success: false, message: TOO_MANY } },
    });
    renderPage();

    await submitEmail();

    expect(screen.getByRole('heading', { name: 'Reset Password' })).toBeInTheDocument();
    expect(screen.getByDisplayValue(EMAIL)).toBeInTheDocument();
    expect(screen.getByText(new RegExp(TOO_MANY))).toBeInTheDocument();
});

// 邮箱不存在 / 发信失败（400）不跳：码肯定没发，去重置页也没用（防改坏）
test('a 400 stays on the forgot-password page and shows the message', async () => {
    userAPI.forgotPassword.mockRejectedValue({
        response: { status: 400, data: { success: false, message: 'Email does not exist or sending failed' } },
    });
    renderPage();

    await submitEmail();

    expect(screen.getByText('Email does not exist or sending failed')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Reset Password' })).not.toBeInTheDocument();
});
