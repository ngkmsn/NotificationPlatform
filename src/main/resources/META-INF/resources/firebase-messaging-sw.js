self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));
importScripts('https://www.gstatic.com/firebasejs/10.9.0/firebase-app-compat.js');
importScripts('https://www.gstatic.com/firebasejs/10.9.0/firebase-messaging-compat.js');

const firebaseConfig = {
  apiKey: "AIzaSyDGT8G9gzkenTw0siY_n4-in3DBYmqV16w",
  authDomain: "notification-a0c90.firebaseapp.com",
  projectId: "notification-a0c90",
  storageBucket: "notification-a0c90.firebasestorage.app",
  messagingSenderId: "595475668363",
  appId: "1:595475668363:web:f783a787f8069b687ca38e"
};

firebase.initializeApp(firebaseConfig);
const messaging = firebase.messaging();

let appRole = 'CLIENT';

// Nhận role từ client window (ADMIN vs CLIENT)
self.addEventListener('message', (event) => {
  if (event.data && event.data.type === 'SET_ROLE') {
    appRole = event.data.role;
    console.log('[firebase-messaging-sw.js] Cập nhật vai trò thiết bị:', appRole);
  }
});

messaging.onBackgroundMessage(async function(payload) {
  console.log('[firebase-messaging-sw.js] Received background message:', payload);
  const notificationTitle = payload.notification ? payload.notification.title : (payload.data ? payload.data.title : '');
  const notificationBody = payload.notification ? payload.notification.body : (payload.data ? payload.data.body || payload.data.content : '');

  // Dùng backend flag là nguồn chính xác nhất (được set trong FirebasePushNotificationProvider)
  // Fallback: kiểm tra keyword riêng biệt của thông báo hệ thống nội bộ
  const isSystemAlert = (payload.data && payload.data.isSystemAlert === 'true') ||
                        notificationTitle.toUpperCase().includes('CẢNH BÁO QUÁ TẢI') ||
                        notificationTitle.toUpperCase().includes('NGẮT KHẨN CẤP') ||
                        notificationTitle.toUpperCase().includes('HỆ THỐNG PHỤC HỒI') ||
                        notificationTitle.includes('HARDWARE_OVERLOAD') ||
                        notificationTitle.includes('CIRCUIT_BREAKER') ||
                        notificationTitle.includes('CRITICAL_CUTOFF');

  // Xác định vai trò từ client window
  let currentRole = appRole;
  try {
    const clients = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    for (const client of clients) {
      if (client.url && client.url.includes('admin')) {
        currentRole = 'ADMIN';
        break;
      }
    }
  } catch (e) {
    // fallback to appRole
  }

  // BỘ LỌC CẢNH BÁO HỆ THỐNG:
  // CLIENT: TUYỆT ĐỐI KHÔNG hiển thị cảnh báo phần cứng nội bộ
  if (currentRole === 'CLIENT' && isSystemAlert) {
    console.log('[firebase-messaging-sw.js] Chặn cảnh báo hệ thống không gửi đến Client:', notificationTitle);
    return;
  }

  // ADMIN: chỉ hiển thị cảnh báo hệ thống, bỏ qua thông báo nghiệp vụ người dùng
  if (currentRole === 'ADMIN' && !isSystemAlert) {
    console.log('[firebase-messaging-sw.js] Admin bỏ qua thông báo người dùng thông thường:', notificationTitle);
    return;
  }

  const notificationOptions = {
    body: notificationBody,
    icon: 'https://firebase.google.com/favicon.ico',
    data: {
      url: currentRole === 'ADMIN' ? '/admin.html' : '/client.html',
      time: new Date().toISOString()
    }
  };

  self.registration.showNotification(notificationTitle || 'Thông báo mới', notificationOptions);
});

self.addEventListener('notificationclick', function(event) {
  event.notification.close();
  const targetUrl = event.notification.data?.url || '/';
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(function(clientList) {
      for (let i = 0; i < clientList.length; i++) {
        const client = clientList[i];
        if (client.url.includes(targetUrl) && 'focus' in client) {
          return client.focus();
        }
      }
      if (self.clients.openWindow) {
        return self.clients.openWindow(targetUrl);
      }
    })
  );
});
