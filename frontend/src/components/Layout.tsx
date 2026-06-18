import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

interface NavItem {
  to: string;
  label: string;
  icon: string;
  permission?: string; // only shown when the user has this permission
}

const navItems: NavItem[] = [
  { to: '/', label: 'Dashboard', icon: '📊' },
  { to: '/parts', label: 'Parts', icon: '🔧' },
  { to: '/quick-add', label: 'Quick Add', icon: '🔍' },
  { to: '/categories', label: 'Categories', icon: '📁' },
  { to: '/specs', label: 'Spec Fields', icon: '🏷️' },
  { to: '/locations', label: 'Locations', icon: '📍' },
  { to: '/low-stock', label: 'Low Stock', icon: '⚠️' },
  { to: '/users', label: 'Users', icon: '👤', permission: 'USERS_EDIT' },
];

export default function Layout() {
  const { user, hasPermission, logout } = useAuth();
  const navigate = useNavigate();

  const handleLogout = async () => {
    await logout();
    navigate('/login', { replace: true });
  };

  const visibleItems = navItems.filter(
    (item) => !item.permission || hasPermission(item.permission)
  );

  return (
    <div className="flex h-screen bg-gray-100">
      {/* Sidebar */}
      <aside className="flex w-56 flex-col bg-gray-900 text-white shadow-xl">
        <div className="flex h-16 items-center border-b border-gray-700 px-4">
          <span className="text-lg font-bold tracking-tight">🔩 Clele</span>
        </div>
        <nav className="flex-1 space-y-1 p-3">
          {visibleItems.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.to === '/'}
              className={({ isActive }) =>
                `flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors ${
                  isActive
                    ? 'bg-blue-600 text-white'
                    : 'text-gray-300 hover:bg-gray-800 hover:text-white'
                }`
              }
            >
              <span>{item.icon}</span>
              {item.label}
            </NavLink>
          ))}
        </nav>

        {/* Current user + logout */}
        <div className="border-t border-gray-700 p-3">
          <div className="px-1 pb-2 text-xs text-gray-400">
            <div className="truncate font-medium text-gray-200">
              {user?.fullName || user?.email}
            </div>
            {user?.fullName && <div className="truncate">{user.email}</div>}
          </div>
          <button
            onClick={handleLogout}
            className="w-full rounded-lg px-3 py-2 text-left text-sm font-medium text-gray-300 hover:bg-gray-800 hover:text-white"
          >
            ⎋ Log out
          </button>
        </div>
      </aside>

      {/* Main content */}
      <main className="flex-1 overflow-auto">
        <Outlet />
      </main>
    </div>
  );
}
