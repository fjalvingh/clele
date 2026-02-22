import { NavLink, Outlet } from 'react-router-dom';

const navItems = [
  { to: '/', label: 'Dashboard', icon: '📊' },
  { to: '/parts', label: 'Parts', icon: '🔧' },
  { to: '/quick-add', label: 'Quick Add', icon: '🔍' },
  { to: '/categories', label: 'Categories', icon: '📁' },
  { to: '/locations', label: 'Locations', icon: '📍' },
  { to: '/low-stock', label: 'Low Stock', icon: '⚠️' },
];

export default function Layout() {
  return (
    <div className="flex h-screen bg-gray-100">
      {/* Sidebar */}
      <aside className="flex w-56 flex-col bg-gray-900 text-white shadow-xl">
        <div className="flex h-16 items-center border-b border-gray-700 px-4">
          <span className="text-lg font-bold tracking-tight">🔩 Clele</span>
        </div>
        <nav className="flex-1 space-y-1 p-3">
          {navItems.map((item) => (
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
      </aside>

      {/* Main content */}
      <main className="flex-1 overflow-auto">
        <Outlet />
      </main>
    </div>
  );
}
