// 前端 Pay Now："支付处理中 + 定时刷新"。
//
// 【层】组件测试（React Testing Library）：真的渲染 OrderDetailPage，像用户一样看文字、点按钮。
// 【真的】OrderDetailPage 组件本身：按钮什么时候出现、点了以后显示什么、什么时候自动刷新。
// 【假的】后端接口（jest.mock 把 ../services/api 换成假的）、登录状态（useAuth 换成"已登录"）。
// 【时间】jest 的假时钟：jest.advanceTimersByTime(3000) 让"过 3 秒"瞬间发生，不真等。
// 【为什么需要】Pay Now 现在只是"排队扣款"，结果要过一会儿才知道。页面必须自己刷新，
//   而且在扣款进行中 / 结果未知时不能再给用户一个 Pay Now 按钮（规则 8：结果未知时换新键可能扣两次）。

import { render, screen, act, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import OrderDetailPage, { PAYMENT_POLL_INTERVAL_MS } from './OrderDetailPage';
import { orderAPI } from '../services/api';

// react-router-dom v7 的 package.json 写着 "main": "./dist/main.js"，但这个文件不存在（真正的入口只写在 "exports" 里），
// 而 CRA 自带的 jest 27 不认 "exports"，所以 jest 里 import 'react-router-dom' 会报"找不到模块"。
// v7 里 MemoryRouter、Routes、useParams、useNavigate、Link 都来自核心包 react-router（它的 main 是对的），
// 这里让 'react-router-dom' 直接用它。virtual：jest 自己解析不了这个包名也没关系。
// react-router v7 还要用 TextEncoder，jest 27 的 jsdom 里没有，从 Node 的 util 拿一个补上。
jest.mock('react-router-dom', () => {
    const { TextEncoder, TextDecoder } = require('util');
    global.TextEncoder = global.TextEncoder || TextEncoder;
    global.TextDecoder = global.TextDecoder || TextDecoder;
    return jest.requireActual('react-router');
}, { virtual: true });

// jest.mock：把整个模块换成假的。这里只需要两个接口：读订单（含支付）、Pay Now
jest.mock('../services/api', () => ({
    orderAPI: { getOrderWithPayment: jest.fn(), retryPayment: jest.fn(), cancelOrder: jest.fn() },
    paymentAPI: { refundPayment: jest.fn() },
}));

// 登录状态：假装已经登录
jest.mock('../contexts/AuthContext', () => ({
    useAuth: () => ({ isLoggedIn: true, userId: 7, isLoading: false }),
}));

/** 造一份后端 /with-payment 接口的返回：订单状态 + 支付状态 */
function orderWith(orderStatus, paymentStatus, errorMessage = null) {
    return {
        order: { id: 42, status: orderStatus, totalAmount: 100, productId: 1, productName: 'Phone', quantity: 1, unitPrice: 100 },
        payment: { paymentId: 9, status: paymentStatus, amount: 100, errorMessage },
    };
}

/** 渲染订单 42 的详情页（MemoryRouter：测试用的路由，不需要真的浏览器地址栏） */
function renderPage() {
    return render(
        <MemoryRouter initialEntries={['/orders/42']}>
            <Routes>
                <Route path="/orders/:id" element={<OrderDetailPage />} />
            </Routes>
        </MemoryRouter>
    );
}

/** 让等待中的 Promise（假接口的返回）先跑完 */
async function flush() {
    await act(async () => {});
}

beforeEach(() => {
    jest.useFakeTimers(); // 换成假时钟：setInterval 不会自己走，要我们拨
    jest.clearAllMocks();
    window.confirm = jest.fn(() => true); // "确定要付款吗？" → 点确定
    window.alert = jest.fn();
});

afterEach(() => {
    jest.useRealTimers();
});

test('Pay Now after a failed payment shows "processing", then refreshes until the payment succeeds', async () => {
    // 第 1 次读：上次扣款被 bank 拒绝（FAILED）→ 应该有 Pay Now 按钮
    // 第 2 次读（点完 Pay Now）：已经排队，PENDING
    // 第 3 次读（3 秒后自动刷新）：扣款成功
    orderAPI.getOrderWithPayment
        .mockResolvedValueOnce(orderWith('PENDING_PAYMENT', 'FAILED', 'Insufficient balance'))
        .mockResolvedValueOnce(orderWith('PENDING_PAYMENT', 'PENDING'))
        .mockResolvedValueOnce(orderWith('PAID', 'SUCCESS'));
    orderAPI.retryPayment.mockResolvedValue({ success: true, message: 'Payment processing' });

    renderPage();
    await flush();

    // 失败原因显示出来，有 Pay Now 按钮
    expect(screen.getByText(/Insufficient balance/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Pay Now/ }));
    await flush();

    // 点了以后：显示"支付处理中"，Pay Now 按钮消失（防止连点）
    expect(orderAPI.retryPayment).toHaveBeenCalledTimes(1);
    expect(screen.getByText(/Payment processing/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Pay Now/ })).not.toBeInTheDocument();

    // 过 3 秒：页面自己去后端再读一次，读到 SUCCESS
    await act(async () => {
        jest.advanceTimersByTime(PAYMENT_POLL_INTERVAL_MS);
    });
    await flush();

    expect(orderAPI.getOrderWithPayment).toHaveBeenCalledTimes(3);
    expect(screen.queryByText(/Payment processing/)).not.toBeInTheDocument();
    expect(screen.getByText('SUCCESS')).toBeInTheDocument();

    // 已经有结果了：再过很久也不会继续刷新
    await act(async () => {
        jest.advanceTimersByTime(PAYMENT_POLL_INTERVAL_MS * 5);
    });
    expect(orderAPI.getOrderWithPayment).toHaveBeenCalledTimes(3);
});

test('result unknown (PAY_UNKNOWN): says the bank is still processing, hides Pay Now and stops polling', async () => {
    orderAPI.getOrderWithPayment.mockResolvedValue(orderWith('PENDING_PAYMENT', 'PAY_UNKNOWN'));

    renderPage();
    await flush();

    // 告诉用户"银行处理中，请稍后查看"，并且没有 Pay Now（结果未知时换新键可能扣两次）
    expect(screen.getByText(/bank is still processing this payment/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Pay Now/ })).not.toBeInTheDocument();

    // 结果未知不是"处理中"：页面不一直刷（后端每 10 分钟重试，用户稍后再来看）
    await act(async () => {
        jest.advanceTimersByTime(PAYMENT_POLL_INTERVAL_MS * 5);
    });
    expect(orderAPI.getOrderWithPayment).toHaveBeenCalledTimes(1);
});
