import { type ReactNode, useEffect, useState } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { getUnreadChanges, markChangesRead } from '../api';
import { useAuth } from '../auth/AuthContext';
import ChangesPanel from './ChangesPanel';

// Shared stroke style for the nav glyphs — matches the icons on the Dashboard
// so the whole app speaks one visual language instead of mixing emoji in.
const icon = {
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.7,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
  viewBox: '0 0 24 24',
  className: 'h-5 w-5 shrink-0',
};

const icons: Record<string, ReactNode> = {
  dashboard: (
    <svg {...icon}>
      <rect x="3" y="3" width="7" height="9" rx="1" />
      <rect x="14" y="3" width="7" height="5" rx="1" />
      <rect x="14" y="12" width="7" height="9" rx="1" />
      <rect x="3" y="16" width="7" height="5" rx="1" />
    </svg>
  ),
  // Microchip — the parts themselves
  parts: (
    <svg {...icon}>
      <rect x="7" y="7" width="10" height="10" rx="1.5" />
      <path d="M10 3v2M14 3v2M10 19v2M14 19v2M3 10h2M3 14h2M19 10h2M19 14h2" />
    </svg>
  ),
  quickAdd: (
    <svg {...icon}>
      <circle cx="11" cy="11" r="6" />
      <path d="m20 20-3.2-3.2M11 8.5v5M8.5 11h5" />
    </svg>
  ),
  categories: (
    <svg {...icon}>
      <path d="M3 6.5A1.5 1.5 0 0 1 4.5 5h4l2 2.2h7A1.5 1.5 0 0 1 19 8.7v9.8A1.5 1.5 0 0 1 17.5 20h-13A1.5 1.5 0 0 1 3 18.5Z" />
    </svg>
  ),
  specs: (
    <svg {...icon}>
      <path d="M4 7h10M4 12h6M4 17h12" />
      <circle cx="18" cy="7" r="2" />
      <circle cx="14" cy="12" r="2" />
      <circle cx="18" cy="17" r="2" />
    </svg>
  ),
  locations: (
    <svg {...icon}>
      <path d="M12 21s7-6.3 7-11a7 7 0 1 0-14 0c0 4.7 7 11 7 11Z" />
      <circle cx="12" cy="10" r="2.5" />
    </svg>
  ),
  lowStock: (
    <svg {...icon}>
      <path d="M10.3 4.3 2.6 17.5A1.5 1.5 0 0 0 3.9 19.8h16.2a1.5 1.5 0 0 0 1.3-2.3L13.7 4.3a1.6 1.6 0 0 0-2.8 0Z" />
      <path d="M12 9.5v4M12 16.6h.01" />
    </svg>
  ),
  users: (
    <svg {...icon}>
      <circle cx="12" cy="8" r="3.5" />
      <path d="M5.5 20a6.5 6.5 0 0 1 13 0" />
    </svg>
  ),
};

interface NavItem {
  to: string;
  label: string;
  icon: ReactNode;
  permission?: string; // only shown when the user has this permission
}

const navItems: NavItem[] = [
  { to: '/', label: 'Dashboard', icon: icons.dashboard },
  { to: '/parts', label: 'Parts', icon: icons.parts },
  { to: '/quick-add', label: 'Quick Add', icon: icons.quickAdd },
  { to: '/categories', label: 'Categories', icon: icons.categories },
  { to: '/specs', label: 'Spec Fields', icon: icons.specs },
  { to: '/locations', label: 'Locations', icon: icons.locations },
  { to: '/low-stock', label: 'Low Stock', icon: icons.lowStock },
  { to: '/users', label: 'Users', icon: icons.users, permission: 'USERS_EDIT' },
];

// Bolt-in-hex brand mark — a fastener head, echoing the app's name.
const BrandMark = (
  <svg viewBox="0 0 24 24" className="h-7 w-7" aria-hidden="true">
    <path
      d="M12 2.2 20 6.8v9.6L12 21.8 4 16.4V6.8Z"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinejoin="round"
    />
    <path
      d="M9.5 9.5h5M9.5 12h5M11 9.5l-1 5M14 9.5l-1 5"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
    />
  </svg>
);

export default function Layout() {
  const { user, hasPermission, logout } = useAuth();
  const navigate = useNavigate();
  const [changesPanel, setChangesPanel] = useState<{ html: string; latestDate: string } | null>(null);

  useEffect(() => {
    getUnreadChanges()
      .then((data) => {
        if (data.count > 0 && data.latestDate) {
          setChangesPanel({ html: data.html, latestDate: data.latestDate });
        }
      })
      .catch(() => {});
  }, []);

  const handleMarkRead = async () => {
    if (!changesPanel) return;
    await markChangesRead(changesPanel.latestDate).catch(() => {});
    setChangesPanel(null);
  };

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
      <aside className="flex w-60 flex-col bg-gray-900 text-gray-300">
        <div className="flex h-16 items-center gap-2.5 px-5 text-white">
          <span className="text-blue-400">{BrandMark}</span>
          <div className="leading-none">
            <div className="text-base font-semibold tracking-tight">Clele</div>
            <div className="mt-1 text-[10px] font-medium uppercase tracking-[0.18em] text-gray-500">
              Parts Inventory
            </div>
          </div>
        </div>

        <nav className="flex-1 space-y-0.5 px-3 py-3">
          {visibleItems.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.to === '/'}
              className={({ isActive }) =>
                `relative flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors ${
                  isActive
                    ? 'bg-blue-600/15 text-white'
                    : 'text-gray-400 hover:bg-white/5 hover:text-white'
                }`
              }
            >
              {({ isActive }) => (
                <>
                  {/* Accent rail marks the current section */}
                  <span
                    className={`absolute left-0 top-1.5 bottom-1.5 w-0.5 rounded-full bg-blue-400 transition-opacity ${
                      isActive ? 'opacity-100' : 'opacity-0'
                    }`}
                  />
                  <span className={isActive ? 'text-blue-300' : 'text-gray-500'}>
                    {item.icon}
                  </span>
                  {item.label}
                </>
              )}
            </NavLink>
          ))}
        </nav>

        {/* Current user + logout */}
        <div className="border-t border-white/10 p-3">
          <NavLink
            to="/profile"
            className={({ isActive }) =>
              `block rounded-lg px-2 py-2 text-xs transition-colors ${
                isActive ? 'bg-white/5' : 'hover:bg-white/5'
              }`
            }
          >
            <div className="truncate font-medium text-gray-200">
              {user?.fullName || user?.email}
            </div>
            {user?.fullName && <div className="truncate text-gray-500">{user.email}</div>}
            <div className="mt-0.5 text-blue-400">My Account ›</div>
          </NavLink>
          <button
            onClick={handleLogout}
            className="mt-1 flex w-full items-center gap-2 rounded-lg px-2 py-2 text-left text-sm font-medium text-gray-400 transition-colors hover:bg-white/5 hover:text-white"
          >
            <svg {...icon} className="h-4 w-4 shrink-0">
              <path d="M14 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2v-2" />
              <path d="M9 12h11m0 0-3-3m3 3-3 3" />
            </svg>
            Log out
          </button>
        </div>
      </aside>

      {/* Main content */}
      <main className="flex-1 overflow-auto">
        <Outlet />
      </main>

      {changesPanel && (
        <ChangesPanel
          html={changesPanel.html}
          latestDate={changesPanel.latestDate}
          onMarkRead={handleMarkRead}
          onClose={() => setChangesPanel(null)}
        />
      )}
    </div>
  );
}
