import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import Layout from './components/Layout';
import CategoriesPage from './pages/Categories';
import DashboardPage from './pages/Dashboard';
import LocationsPage from './pages/Locations';
import LowStockPage from './pages/LowStock';
import PartDetailPage from './pages/PartDetail';
import PartsPage from './pages/Parts';
import QuickAddPage from './pages/QuickAdd';

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<Layout />}>
          <Route index element={<DashboardPage />} />
          <Route path="parts" element={<PartsPage />} />
          <Route path="parts/:id" element={<PartDetailPage />} />
          <Route path="quick-add" element={<QuickAddPage />} />
          <Route path="categories" element={<CategoriesPage />} />
          <Route path="locations" element={<LocationsPage />} />
          <Route path="low-stock" element={<LowStockPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
