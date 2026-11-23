// 邮箱验证页：A11（Obsidian 50 号文件 V17）。
//
// 【层】组件测试（React Testing Library）：真的渲染 EmailVerificationPage，像用户一样输入验证码、点按钮。
// 【假的】后端接口（jest.mock 把 ../services/api 换成假的）。
// 【为什么】以前页面先调 email 的 verify-code，成功了再调 store 的 activate（只带邮箱）。store 看不到第一步，
//   所以谁都能跳过它直接激活。现在验证码交给 store，store 自己去问 email：页面只调 activate(email, code)。
// 【红灯为什么红】handleVerify 还在先调 emailAPI.verifyCode，activate 只传了邮箱。

import { render, screen, act, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import EmailVerificationPage from './EmailVerificationPage';
import { emailAPI, userAPI } from '../services/api';

// 同 OrderDetailPage.test.jsx：jest 27 解析不了 react-router-dom v7，直接用核心包 react-router
jest.mock('react-router-dom', () => {
    const { TextEncoder, TextDecoder } = require('util');
    global.TextEncoder = global.TextEncoder || TextEncoder;
    global.TextDecoder = global.TextDecoder || TextDecoder;
    return jest.requireActual('react-router');
}, { virtual: true });

jest.mock('../services/api', () => ({
    emailAPI: { verifyCode: jest.fn(), sendVerificationCode: jest.fn() },
    userAPI: { activateAccount: jest.fn(), sendVerificationCode: jest.fn() },
}));

const EMAIL = 'new-user@example.com';

function renderPage() {
    return render(
        <MemoryRouter initialEntries={[{ pathname: '/verify-email', state: { email: EMAIL } }]}>
            <Routes>
                <Route path="/verify-email" element={<EmailVerificationPage />} />
                <Route path="/login" element={<div>login page</div>} />
            </Routes>
        </MemoryRouter>
    );
}

async function submitCode(code) {
    fireEvent.change(screen.getByPlaceholderText('Enter 6-digit verification code'), { target: { value: code } });
    await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: 'Verify Email' }));
    });
}

beforeEach(() => {
    jest.clearAllMocks();
    localStorage.clear();
});

test('submitting the code activates through store with the email and the code, and does not call email directly', async () => {
    userAPI.activateAccount.mockResolvedValue({ success: true, message: 'Account activated successfully' });
    emailAPI.verifyCode.mockResolvedValue({ success: true });
    renderPage();

    await submitCode('123456');

    expect(userAPI.activateAccount).toHaveBeenCalledWith(EMAIL, '123456');
    expect(emailAPI.verifyCode).not.toHaveBeenCalled();
    expect(screen.getByText(/Account activated, please login/)).toBeInTheDocument();
});

test('a wrong code shows the message store sends back', async () => {
    userAPI.activateAccount.mockRejectedValue({
        response: { status: 400, data: { success: false, message: 'Verification code is invalid or expired' } },
    });
    renderPage();

    await submitCode('000000');

    expect(screen.getByText('Verification code is invalid or expired')).toBeInTheDocument();
    expect(emailAPI.verifyCode).not.toHaveBeenCalled();
});

// 限流（Obsidian 54 号 R23）：重发也找 store 要码，不直接调 email（email 没有公开接口了）。
// 红灯：handleResendCode 还在调 emailAPI.sendVerificationCode。
test('resending asks store for a new code, not email', async () => {
    userAPI.sendVerificationCode.mockResolvedValue({ success: true, message: 'sent' });
    renderPage();

    await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: 'Resend Verification Code' }));
    });

    expect(userAPI.sendVerificationCode).toHaveBeenCalledWith(EMAIL);
    expect(emailAPI.sendVerificationCode).not.toHaveBeenCalled();
    expect(screen.getByText('Verification code has been resent to your email')).toBeInTheDocument();
});

test('a 429 on resend shows store\'s "try again" message', async () => {
    userAPI.sendVerificationCode.mockRejectedValue({
        response: { status: 429, data: { success: false, message: 'Too many attempts, please try again in 600 seconds' } },
    });
    renderPage();

    await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: 'Resend Verification Code' }));
    });

    expect(screen.getByText('Too many attempts, please try again in 600 seconds')).toBeInTheDocument();
});
