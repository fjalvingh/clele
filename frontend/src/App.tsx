import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth/AuthContext';
import { SettingsProvider } from './settings/SettingsContext';
import { ThemeProvider } from './theme/ThemeContext';
import Layout from './components/Layout';
import CategoriesPage from './pages/Categories';
import DashboardPage from './pages/Dashboard';
import LocationsPage from './pages/Locations';
import LoginPage from './pages/Login';
import LowStockPage from './pages/LowStock';
import PartDetailPage from './pages/PartDetail';
import PartsPage from './pages/Parts';
import ProfilePage from './pages/Profile';
import BarcodeScannerPage from './pages/BarcodeScanner';
import QuickAddPage from './pages/QuickAdd';
import ProjectDetailPage from './pages/ProjectDetail';
import ProjectsPage from './pages/Projects';
import SpecDefinitionsPage from './pages/SpecDefinitions';
import UsersPage from './pages/Users';

function RequireAuth({ children }: { children: React.ReactElement }) {
  const { user, loading } = useAuth();
  const location = useLocation();

  if (loading) {
    return (
      <div className="flex h-screen items-center justify-center bg-gray-100 text-gray-500">
        Loading…
      </div>
    );
  }
  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  return children;
}

// Router basename derived from Vite's base ('/clele/' → '/clele'; '/' → '/').
const basename = import.meta.env.BASE_URL.replace(/\/$/, '') || '/';

export default function App() {
  return (
    <BrowserRouter basename={basename}>
      <ThemeProvider>
      <AuthProvider>
        <SettingsProvider>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route
            element={
              <RequireAuth>
                <Layout />
              </RequireAuth>
            }
          >
            <Route index element={<DashboardPage />} />
            <Route path="parts" element={<PartsPage />} />
            <Route path="parts/:id" element={<PartDetailPage />} />
            <Route path="quick-add" element={<QuickAddPage />} />
            <Route path="barcode-scan" element={<BarcodeScannerPage />} />
            <Route path="categories" element={<CategoriesPage />} />
            <Route path="specs" element={<SpecDefinitionsPage />} />
            <Route path="locations" element={<LocationsPage />} />
            <Route path="low-stock" element={<LowStockPage />} />
            <Route path="projects" element={<ProjectsPage />} />
            <Route path="projects/:id" element={<ProjectDetailPage />} />
            <Route path="profile" element={<ProfilePage />} />
            <Route path="users" element={<UsersPage />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Route>
        </Routes>
        </SettingsProvider>
      </AuthProvider>
      </ThemeProvider>
    </BrowserRouter>
  );
}
