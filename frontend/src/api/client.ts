import axios from 'axios';

// BASE_URL is injected by Vite from `base` (always trailing-slashed): '/' unless built with a subpath.
const loginPath = `${import.meta.env.BASE_URL}login`;

const client = axios.create({
  baseURL: `${import.meta.env.BASE_URL}api`,
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
      const onLogin = window.location.pathname === loginPath;
      const probingMe = url.includes('/auth/me') || url.includes('/auth/login');
      if (!onLogin && !probingMe) {
        window.location.assign(loginPath);
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
