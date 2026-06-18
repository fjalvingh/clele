import axios from 'axios';

const client = axios.create({
  baseURL: '/api',
  headers: { 'Content-Type': 'application/json' },
  withCredentials: true, // send the session cookie with every request
});

client.interceptors.response.use(
  (res) => res,
  (error) => {
    const status = error.response?.status;
    // On an auth failure, bounce to the login screen (unless we're already there or probing /auth/me).
    if (status === 401) {
      const url: string = error.config?.url ?? '';
      const onLogin = window.location.pathname === '/login';
      const probingMe = url.includes('/auth/me') || url.includes('/auth/login');
      if (!onLogin && !probingMe) {
        window.location.assign('/login');
      }
    }
    const message =
      error.response?.data?.error ||
      error.response?.data?.message ||
      error.message ||
      'Unknown error';
    return Promise.reject(new Error(message));
  }
);

export default client;
