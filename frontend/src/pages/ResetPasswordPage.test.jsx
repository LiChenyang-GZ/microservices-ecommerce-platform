// 重置密码页：别人的错猜（Obsidian 70 号 G9）。
//
// 【层】组件测试（React Testing Library）：真的渲染重置页，像用户一样填邮箱、码、新密码，点"Reset Password"。
// 【假的】后端接口（jest.mock 把 ../services/api 换成假的）。
// 【为什么】码从 6 位改成 10 位数字：6 位只有 100 万种，只能靠"猜错 5 次作废"防猜，可这样谁都能把你的码猜死（58 号 Q16）。
//   码够长以后 email 不再作废码，猜的次数由 store 按"IP + 邮箱"限。页面要收 10 位；从邮件里复制带进来的空格去掉。
// 【红灯为什么红】校验写死 /^\d{6}$/，提示和占位文字也写 6 位；输入原样保存，不去空格。

import { render, screen, act, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
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

function renderPage() {
    return render(
        <MemoryRouter initialEntries={[{ pathname: '/reset-password', state: { email: EMAIL } }]}>
            <Routes>
                <Route path="/reset-password" element={<ResetPasswordPage />} />
            </Routes>
        </MemoryRouter>
    );
}

async function fillAndSubmit(code) {
    fireEvent.change(screen.getByPlaceholderText('Enter 10-digit verification code'), { target: { name: 'code', value: code } });
    fireEvent.change(screen.getByPlaceholderText('Enter new password'), { target: { name: 'newPassword', value: 'new-password' } });
    fireEvent.change(screen.getByPlaceholderText('Enter new password again'), { target: { name: 'confirmPassword', value: 'new-password' } });
    await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: 'Reset Password' }));
    });
}

beforeEach(() => {
    jest.clearAllMocks();
});

test('a 10-digit code pasted with spaces is sent as 10 digits', async () => {
    userAPI.resetPassword.mockResolvedValue({ success: true, message: 'Password reset successful' });
    renderPage();

    await fillAndSubmit(' 12345 67890 ');

    expect(userAPI.resetPassword).toHaveBeenCalledWith(EMAIL, '1234567890', 'new-password');
    expect(screen.getByText('Password Reset Successful!')).toBeInTheDocument();
});

// 审查 🟡1（Obsidian 71 号）：过渡期 6 位码（上线前发出的，email 照样认）也能交。红灯：正则只认 10 位。
test('during the switch a 6-digit code is still sent', async () => {
    userAPI.resetPassword.mockResolvedValue({ success: true, message: 'Password reset successful' });
    renderPage();

    await fillAndSubmit('123456');

    expect(userAPI.resetPassword).toHaveBeenCalledWith(EMAIL, '123456', 'new-password');
});

test('a code of another length is not sent and the page says how long it is', async () => {
    renderPage();

    await fillAndSubmit('12345678');

    expect(userAPI.resetPassword).not.toHaveBeenCalled();
    expect(screen.getByText('Verification code must be 10 digits')).toBeInTheDocument();
});

// 审查 🟡3（71 号）：手机弹数字键盘、系统提示填入邮件里的码。红灯：Input 不转发这两个属性。
test('the code box asks phones for a number pad and offers the emailed code', () => {
    renderPage();
    const box = screen.getByPlaceholderText('Enter 10-digit verification code');

    expect(box).toHaveAttribute('inputmode', 'numeric');
    expect(box).toHaveAttribute('autocomplete', 'one-time-code');
});
