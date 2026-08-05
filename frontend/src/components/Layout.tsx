import { type ReactNode, useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { getUnreadChanges, markChangesRead, switchOrganisation } from '../api';
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
  barcodeScan: (
    <svg {...icon}>
      <path d="M3 7V5a2 2 0 0 1 2-2h2M17 3h2a2 2 0 0 1 2 2v2M21 17v2a2 2 0 0 1-2 2h-2M7 21H5a2 2 0 0 1-2-2v-2" />
      <path d="M7 8v8M10 8v8M13 8v4M16 8v8M13 14v2" />
    </svg>
  ),
  // Building — organisations are the tenant each user works inside
  organisations: (
    <svg {...icon}>
      <path d="M4 21V6a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v15M12 21V11a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v10M3 21h18" />
      <path d="M7 9h2M7 13h2M7 17h2M15 14h2M15 18h2" />
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
  // Blueprint-style icon for project builds
  projects: (
    <svg {...icon}>
      <rect x="3" y="4" width="18" height="16" rx="1.5" />
      <path d="M7 8h10M7 12h6M7 16h4" />
      <path d="M15 14l2 2 3-3" />
    </svg>
  ),
  users: (
    <svg {...icon}>
      <circle cx="12" cy="8" r="3.5" />
      <path d="M5.5 20a6.5 6.5 0 0 1 13 0" />
    </svg>
  ),
  allUsers: (
    <svg {...icon}>
      <circle cx="9" cy="8" r="3" />
      <path d="M3.5 19a5.5 5.5 0 0 1 11 0" />
      <path d="M16 5.5a3 3 0 0 1 0 5.9M17.5 19a5.5 5.5 0 0 0-2-4.2" />
    </svg>
  ),
  adminActions: (
    <svg {...icon}>
      <path d="M12 3 4 6.5V11c0 4.9 3.4 9.4 8 10.5 4.6-1.1 8-5.6 8-10.5V6.5Z" />
      <path d="m9.5 12 1.8 1.8L14.8 10" />
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
  { to: '/barcode-scan', label: 'Barcode Scan', icon: icons.barcodeScan },
  { to: '/categories', label: 'Categories', icon: icons.categories },
  { to: '/specs', label: 'Spec Fields', icon: icons.specs },
  { to: '/locations', label: 'Locations', icon: icons.locations },
  { to: '/low-stock', label: 'Low Stock', icon: icons.lowStock },
  { to: '/projects', label: 'Projects', icon: icons.projects, permission: 'PARTS_EDIT' },
  { to: '/users', label: 'Users', icon: icons.users, permission: 'ORG_ADMIN' },
  { to: '/all-users', label: 'All Users', icon: icons.allUsers, permission: 'GLOBAL_ADMIN' },
  { to: '/organisations', label: 'Organisations', icon: icons.organisations, permission: 'GLOBAL_ADMIN' },
  { to: '/admin-actions', label: 'Admin Actions', icon: icons.adminActions, permission: 'ORG_ADMIN' },
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
  const location = useLocation();
  const [changesPanel, setChangesPanel] = useState<{ html: string; latestDate: string } | null>(null);
  const [orgMenuOpen, setOrgMenuOpen] = useState(false);
  const [switching, setSwitching] = useState(false);
  // Drawer state for the small-screen sidebar. Desktop (md+) ignores it entirely — the sidebar is
  // statically laid out there and never translated away.
  const [navOpen, setNavOpen] = useState(false);

  const organisations = user?.selectableOrganisations ?? [];

  /**
   * Switch tenant, then reload. Every page loads its data on mount, so a full reload is the only
   * way to be certain no screen is left showing the previous organisation's data.
   */
  const handleSwitchOrganisation = async (id: number) => {
    if (id === user?.currentOrganisationId) {
      setOrgMenuOpen(false);
      return;
    }
    setSwitching(true);
    try {
      await switchOrganisation(id);
      window.location.reload();
    } catch {
      setSwitching(false);
      setOrgMenuOpen(false);
    }
  };

  useEffect(() => {
    getUnreadChanges()
      .then((data) => {
        if (data.count > 0 && data.latestDate) {
          setChangesPanel({ html: data.html, latestDate: data.latestDate });
        }
      })
      .catch(() => {});
  }, []);

  // Navigating closes the drawer — on a phone the destination is behind it.
  useEffect(() => {
    setNavOpen(false);
    setOrgMenuOpen(false);
  }, [location.pathname]);

  useEffect(() => {
    if (!navOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setNavOpen(false);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [navOpen]);

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
    <div className="flex h-dvh bg-gray-100">
      {/* Backdrop for the mobile drawer. Never rendered at md+, where the sidebar is part of the
          normal flow and there is nothing to dismiss. */}
      {navOpen && (
        <div
          className="fixed inset-0 z-30 bg-black/50 md:hidden"
          onClick={() => setNavOpen(false)}
          aria-hidden="true"
        />
      )}

      {/* Sidebar — intentionally always-dark chrome, independent of the light/dark app theme, so
          it uses the untouched `neutral-*` palette rather than the theme-remapped `gray-*` ramp.

          Below md it is an overlay drawer translated off-canvas; from md up every mobile-only class
          is overridden (`md:static`, `md:translate-x-0`, `md:transition-none`) so the desktop
          sidebar is laid out exactly as it always was. */}
      <aside
        className={`fixed inset-y-0 left-0 z-40 flex w-60 shrink-0 flex-col bg-neutral-900 text-neutral-300 transition-transform duration-200 ease-out md:static md:z-auto md:translate-x-0 md:transition-none ${
          navOpen ? 'translate-x-0' : '-translate-x-full'
        }`}
      >
        <div className="flex h-16 items-center gap-2.5 px-5 text-white">
          <span className="text-blue-400">{BrandMark}</span>
          <div className="leading-none">
            <div className="text-base font-semibold tracking-tight">Sortiment</div>
            <div className="mt-1 text-[10px] font-medium uppercase tracking-[0.18em] text-neutral-500">
              Parts Inventory
            </div>
          </div>
          {/* Dismiss control for the drawer — the backdrop works too, but a visible affordance
              matters when the drawer covers most of a phone screen. */}
          <button
            type="button"
            onClick={() => setNavOpen(false)}
            aria-label="Close navigation"
            className="ml-auto -mr-2 rounded-lg p-2 text-neutral-400 transition-colors hover:bg-white/5 hover:text-white md:hidden"
          >
            <svg {...icon} className="h-5 w-5">
              <path d="M6 6l12 12M18 6 6 18" />
            </svg>
          </button>
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
                    : 'text-neutral-400 hover:bg-white/5 hover:text-white'
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
                  <span className={isActive ? 'text-blue-300' : 'text-neutral-500'}>
                    {item.icon}
                  </span>
                  {item.label}
                </>
              )}
            </NavLink>
          ))}
        </nav>

        {/* Current organisation — the tenant everything on screen belongs to */}
        {user?.currentOrganisationName && (
          <div className="relative border-t border-white/10 px-3 pt-3">
            <button
              type="button"
              onClick={() => setOrgMenuOpen((open) => !open)}
              disabled={switching || organisations.length <= 1}
              className={`flex w-full items-center gap-2 rounded-lg px-2 py-2 text-left transition-colors ${
                organisations.length > 1 ? 'hover:bg-white/5' : 'cursor-default'
              }`}
            >
              <span className="text-neutral-500">{icons.organisations}</span>
              <span className="min-w-0 flex-1">
                <span className="block text-[10px] font-medium uppercase tracking-[0.14em] text-neutral-500">
                  Organisation
                </span>
                <span className="block truncate text-sm font-medium text-neutral-100">
                  {user.currentOrganisationName}
                </span>
              </span>
              {organisations.length > 1 && (
                <svg {...icon} className="h-4 w-4 shrink-0 text-neutral-500">
                  <path d="m7 15 5 5 5-5M7 9l5-5 5 5" />
                </svg>
              )}
            </button>

            {orgMenuOpen && (
              <div className="absolute bottom-full left-3 right-3 z-20 mb-1 overflow-hidden rounded-lg border border-white/10 bg-neutral-800 shadow-lg">
                {organisations.map((org) => (
                  <button
                    key={org.id}
                    type="button"
                    onClick={() => handleSwitchOrganisation(org.id)}
                    className={`flex w-full items-center gap-2 px-3 py-2 text-left text-sm transition-colors hover:bg-white/5 ${
                      org.id === user.currentOrganisationId ? 'text-white' : 'text-neutral-300'
                    }`}
                  >
                    <span className="w-4 shrink-0 text-blue-400">
                      {org.id === user.currentOrganisationId && (
                        <svg {...icon} className="h-4 w-4">
                          <path d="m5 12 4.5 4.5L19 7" />
                        </svg>
                      )}
                    </span>
                    <span className="min-w-0 flex-1 truncate">{org.name}</span>
                    {org.template && (
                      <span className="shrink-0 rounded bg-white/10 px-1.5 py-0.5 text-[10px] uppercase tracking-wide text-neutral-400">
                        Template
                      </span>
                    )}
                  </button>
                ))}
              </div>
            )}
          </div>
        )}

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
            <div className="truncate font-medium text-neutral-200">
              {user?.fullName || user?.email}
            </div>
            {user?.fullName && <div className="truncate text-neutral-500">{user.email}</div>}
            <div className="mt-0.5 text-blue-400">My Account ›</div>
          </NavLink>
          <button
            onClick={handleLogout}
            className="mt-1 flex w-full items-center gap-2 rounded-lg px-2 py-2 text-left text-sm font-medium text-neutral-400 transition-colors hover:bg-white/5 hover:text-white"
          >
            <svg {...icon} className="h-4 w-4 shrink-0">
              <path d="M14 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2v-2" />
              <path d="M9 12h11m0 0-3-3m3 3-3 3" />
            </svg>
            Log out
          </button>
        </div>
      </aside>

      {/* Main content. `min-w-0` lets wide tables scroll inside their own container instead of
          stretching this column — which on desktop used to squeeze the sidebar narrower than w-60. */}
      <div className="flex min-w-0 flex-1 flex-col">
        {/* Mobile-only top bar: the drawer trigger plus enough branding to know where you are.
            `md:hidden` keeps it off the desktop layout entirely. */}
        <header className="flex h-14 shrink-0 items-center gap-2 bg-neutral-900 px-2 text-white md:hidden">
          <button
            type="button"
            onClick={() => setNavOpen(true)}
            aria-label="Open navigation"
            aria-expanded={navOpen}
            className="rounded-lg p-2.5 text-neutral-300 transition-colors hover:bg-white/5 hover:text-white"
          >
            <svg {...icon} className="h-6 w-6">
              <path d="M4 7h16M4 12h16M4 17h16" />
            </svg>
          </button>
          <span className="text-blue-400">{BrandMark}</span>
          <span className="text-base font-semibold tracking-tight">Sortiment</span>
        </header>

        <main className="min-w-0 flex-1 overflow-auto">
          <Outlet />
        </main>
      </div>

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
