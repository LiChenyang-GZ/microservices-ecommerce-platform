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
    fireEvent.change(screen.getByPlaceholderText('Enter 10-digit verification code'), { target: { value: code } });
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

    await submitCode('1234567890');

    expect(userAPI.activateAccount).toHaveBeenCalledWith(EMAIL, '1234567890');
    expect(emailAPI.verifyCode).not.toHaveBeenCalled();
    expect(screen.getByText(/Account activated, please login/)).toBeInTheDocument();
});

test('a wrong code shows the message store sends back', async () => {
    userAPI.activateAccount.mockRejectedValue({
        response: { status: 400, data: { success: false, message: 'Verification code is invalid or expired' } },
    });
    renderPage();

    await submitCode('0000000000');

    expect(screen.getByText('Verification code is invalid or expired')).toBeInTheDocument();
    expect(emailAPI.verifyCode).not.toHaveBeenCalled();
});

// 别人的错猜（Obsidian 70 号 G9）：码改成 10 位数字。粘贴进来的空格、横杠去掉，最多 10 位；不到 10 位不能提交。
// 红灯：页面只收 6 位（maxLength 6、slice(0, 6)），提示也写 6 位。
test('the code box takes 10 digits, drops spaces, and Verify stays off for a length that is neither 6 nor 10', async () => {
    userAPI.activateAccount.mockResolvedValue({ success: true, message: 'Account activated successfully' });
    renderPage();
    const box = screen.getByPlaceholderText('Enter 10-digit verification code');
    const verify = screen.getByRole('button', { name: 'Verify Email' });

    fireEvent.change(box, { target: { value: '12345678' } }); // 6 位也能交（过渡期，71 号 🟡1），8 位不行
    expect(verify).toBeDisabled();

    fireEvent.change(box, { target: { value: '12345 67890 99' } });
    expect(box).toHaveValue('1234567890');
    expect(verify).toBeEnabled();
    expect(screen.getByText(/enter the 10-digit verification code/)).toBeInTheDocument();
});

// 审查 🟡1（Obsidian 71 号）：上线那几分钟里还有 6 位码（email 照样认，G7）。页面过渡期收 6 或 10 位，
// 这样前端可以先上、email 后上，没人卡住。红灯：6 位时按钮是灰的。
test('during the switch a 6-digit code can still be submitted', async () => {
    userAPI.activateAccount.mockResolvedValue({ success: true, message: 'Account activated successfully' });
    renderPage();

    await submitCode('123456');

    expect(userAPI.activateAccount).toHaveBeenCalledWith(EMAIL, '123456');
});

// 审查 🟡3（71 号）：B' 选数字，一半理由是手机弹数字键盘；系统还能提示填入邮件里的码。
// 红灯：Input 组件不转发 inputMode / autoComplete，码框是全键盘。
test('the code box asks phones for a number pad and offers the emailed code', () => {
    renderPage();
    const box = screen.getByPlaceholderText('Enter 10-digit verification code');

    expect(box).toHaveAttribute('inputmode', 'numeric');
    expect(box).toHaveAttribute('autocomplete', 'one-time-code');
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

// 审查 🟠1（Obsidian 56 号）：注册页发码失败（429 / 503）也会跳过来，把后端的提示放在 state.error 里。
// 这里要一进来就显示它，用户才知道码没发出去、要等多久再点"重发"。红灯：页面不看 state.error。
test('arriving with an error from the register page shows it', () => {
    render(
        <MemoryRouter initialEntries={[{
            pathname: '/verify-email',
            state: { email: EMAIL, error: 'Too many attempts, please try again in 600 seconds' },
        }]}>
            <Routes>
                <Route path="/verify-email" element={<EmailVerificationPage />} />
            </Routes>
        </MemoryRouter>
    );

    expect(screen.getByText('Too many attempts, please try again in 600 seconds')).toBeInTheDocument();
    expect(screen.getByText(EMAIL)).toBeInTheDocument();
});
