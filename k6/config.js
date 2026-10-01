export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const CHANNELS = ['PUSH', 'EMAIL', 'SMS'];
export const PRIORITIES = ['CRITICAL', 'HIGH', 'NORMAL', 'LOW'];

export const EMAIL_RECIPIENTS = [
  'test_customer_01@example.com',
  'test_customer_02@example.com',
  'finance_alerts@enterprise.com',
  'user_alice@notification-demo.vn'
];

export const SMS_RECIPIENTS = [
  '+84901234567',
  '+84987654321',
  '+84912345678',
  '+84933456789'
];

export const PUSH_RECIPIENTS = [
  'user_alice',
  'user_bob',
  'user_demo',
  'user_charlie',
  'user_david',
  'ALL'
];

export function getRandomRecipient(channel = 'PUSH') {
  if (channel === 'EMAIL') {
    return EMAIL_RECIPIENTS[Math.floor(Math.random() * EMAIL_RECIPIENTS.length)];
  }
  if (channel === 'SMS') {
    return SMS_RECIPIENTS[Math.floor(Math.random() * SMS_RECIPIENTS.length)];
  }
  return PUSH_RECIPIENTS[Math.floor(Math.random() * PUSH_RECIPIENTS.length)];
}

export function getRandomChannel() {
  return CHANNELS[Math.floor(Math.random() * CHANNELS.length)];
}

export function getRandomPriority() {
  return PRIORITIES[Math.floor(Math.random() * PRIORITIES.length)];
}

export function generateNotificationPayload(overrides = {}) {
  const channel = overrides.channel || getRandomChannel();
  const priority = overrides.priority || getRandomPriority();
  const recipient = overrides.recipient || getRandomRecipient(channel);
  const timestamp = Date.now();
  const randomSuffix = Math.floor(Math.random() * 1000000);

  return {
    recipient: recipient,
    channel: channel,
    priority: priority,
    subject: overrides.subject || `Thông báo giao dịch #${timestamp}-${randomSuffix}`,
    content: overrides.content || `Nội dung cập nhật trạng thái đơn hàng #${timestamp}-${randomSuffix}. Vui lòng kiểm tra tài khoản.`,
    ...overrides
  };
}
