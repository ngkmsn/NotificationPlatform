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

messaging.onBackgroundMessage(function(payload) {
  console.log('[firebase-messaging-sw.js] Received background message ', payload);
  const notificationTitle = payload.notification ? payload.notification.title : 'Thông báo mới';
  const notificationOptions = {
    body: payload.notification ? payload.notification.body : 'Nội dung thông báo',
    icon: 'https://firebase.google.com/favicon.ico'
  };

  self.registration.showNotification(notificationTitle, notificationOptions);
});
