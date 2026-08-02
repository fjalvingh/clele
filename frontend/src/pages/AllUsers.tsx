import { useEffect, useState } from 'react';
import {
  addUserToOrganisation,
  createAdminUser,
  deleteAdminUser,
  getAllUsers,
  getOrganisations,
  removeUserFromOrganisation,
  setUserPermissionsInOrganisation,
  updateAdminUser,
} from '../api';
import {
  GLOBAL_PERMISSIONS,
  ORGANISATION_PERMISSIONS,
  PERMISSIONS,
  type AdminUser,
  type Organisation,
  type UserRequest,
} from '../api/types';
import { useAuth } from '../auth/AuthContext';
import DataTable from '../components/DataTable';
import type { Column } from '../components/DataTable';
import FormField from '../components/FormField';
import Modal from '../components/Modal';

const permLabel = (key: string) => PERMISSIONS.find((p) => p.key === key)?.label ?? key;

type AccountForm = Pick<UserRequest, 'email' | 'password' | 'fullName' | 'phone'> & {
  globalPermissions: string[];
};

const formFor = (u: AdminUser): AccountForm => ({
  email: u.email,
  password: '',
  fullName: u.fullName ?? '',
  phone: u.phone ?? '',
  globalPermissions: [...(u.globalPermissions ?? [])],
});

/** A blank create form. At least one organisation must be picked before it can be saved. */
const emptyNewUser = () => ({
  email: '',
  password: '',
  fullName: '',
  phone: '',
  globalPermissions: [] as string[],
  organisationIds: [] as number[],
});

/**
 * Every account in the installation, with every organisation it belongs to — the Global
 * Administrator's counterpart to the organisation-scoped Users screen.
 *
 * <p>Memberships and their permissions save immediately on click, one call each, because they are
 * independent facts about different organisations; batching them behind a single Save would make a
 * partial failure ambiguous. Account details keep an explicit Save, since they are one record.
 */
export default function AllUsersPage() {
  const { user: me, refresh } = useAuth();

  const [users, setUsers] = useState<AdminUser[]>([]);
  const [organisations, setOrganisations] = useState<Organisation[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [selected, setSelected] = useState<AdminUser | null>(null);
  const [form, setForm] = useState<AccountForm | null>(null);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [addOrgId, setAddOrgId] = useState('');

  // Creating an account. This screen is the only place it happens other than someone accepting an
  // invitation — an Organisation Admin invites instead (see the Users screen).
  const [createOpen, setCreateOpen] = useState(false);
  const [newUser, setNewUser] = useState(emptyNewUser());
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  const load = () => {
    setLoading(true);
    Promise.all([getAllUsers(), getOrganisations()])
      .then(([u, o]) => {
        setUsers(u);
        setOrganisations(o);
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  const open = (u: AdminUser) => {
    setSelected(u);
    setForm(formFor(u));
    setFormError(null);
    setAddOrgId('');
  };

  /** Every mutation returns the updated user, so the panel and the list stay in step. */
  const applyUpdated = (updated: AdminUser) => {
    setSelected(updated);
    setUsers((prev) => prev.map((u) => (u.id === updated.id ? updated : u)));
  };

  const runMembershipAction = async (action: () => Promise<AdminUser>) => {
    setBusy(true);
    setFormError(null);
    try {
      applyUpdated(await action());
      // Editing yourself changes what you may do; the sidebar and route guards read /auth/me.
      if (selected?.id === me?.id) await refresh();
    } catch (e: unknown) {
      setFormError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const handleSaveAccount = async () => {
    if (!selected || !form) return;
    setSaving(true);
    setFormError(null);
    try {
      const updated = await updateAdminUser(selected.id, {
        email: form.email,
        password: form.password,
        fullName: form.fullName,
        phone: form.phone,
        permissions: [],
        globalPermissions: form.globalPermissions,
      });
      applyUpdated(updated);
      setForm(formFor(updated));
      if (selected.id === me?.id) await refresh();
    } catch (e: unknown) {
      setFormError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const openCreate = () => {
    setNewUser(emptyNewUser());
    setCreateError(null);
    setCreateOpen(true);
  };

  const handleCreate = async () => {
    setCreating(true);
    setCreateError(null);
    try {
      await createAdminUser({
        email: newUser.email.trim(),
        password: newUser.password,
        fullName: newUser.fullName.trim(),
        phone: newUser.phone.trim(),
        permissions: [],
        globalPermissions: newUser.globalPermissions,
        organisationIds: newUser.organisationIds,
      });
      setCreateOpen(false);
      load();
    } catch (e: unknown) {
      setCreateError((e as Error).message);
    } finally {
      setCreating(false);
    }
  };

  const handleDeleteAccount = async (u: AdminUser) => {
    if (
      !confirm(
        `Delete the account "${u.email}" entirely, in every organisation?\n\n` +
          'This cannot be undone. If they created parts, delete those first (Users screen → ' +
          'Delete parts) — the database refuses while any of their parts remain.',
      )
    )
      return;
    try {
      await deleteAdminUser(u.id);
      if (selected?.id === u.id) setSelected(null);
      load();
    } catch (e: unknown) {
      alert((e as Error).message);
    }
  };

  const togglePermission = (organisationId: number, key: string, held: string[]) => {
    const next = held.includes(key) ? held.filter((p) => p !== key) : [...held, key];
    void runMembershipAction(() =>
      setUserPermissionsInOrganisation(selected!.id, organisationId, next),
    );
  };

  const handleRemoveFromOrg = (organisationId: number, name: string) => {
    if (
      !confirm(
        `Remove "${selected!.email}" from ${name}?\n\n` +
          'Their permissions in that organisation are dropped with the membership. ' +
          'The account and its other organisations are untouched.',
      )
    )
      return;
    void runMembershipAction(() => removeUserFromOrganisation(selected!.id, organisationId));
  };

  const handleAddToOrg = () => {
    if (!addOrgId) return;
    const organisationId = Number(addOrgId);
    setAddOrgId('');
    void runMembershipAction(() => addUserToOrganisation(selected!.id, organisationId));
  };

  const columns: Column<AdminUser>[] = [
    { key: 'fullName', header: 'Name', render: (u) => u.fullName || '—' },
    { key: 'email', header: 'Email' },
    { key: 'phone', header: 'Phone', render: (u) => u.phone || '—' },
    {
      key: 'memberships',
      header: 'Organisations',
      render: (u) =>
        u.memberships.length ? (
          <div className="flex flex-wrap gap-1">
            {u.memberships.map((m) => (
              <span
                key={m.organisationId}
                className="rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-700"
              >
                {m.organisationName}
                {m.template && ' (template)'}
              </span>
            ))}
          </div>
        ) : (
          <span className="text-amber-600">None</span>
        ),
    },
    {
      key: 'globalPermissions',
      header: 'Global',
      render: (u) =>
        u.globalPermissions?.length ? u.globalPermissions.map(permLabel).join(', ') : '—',
    },
  ];

  const memberOf = new Set(selected?.memberships.map((m) => m.organisationId) ?? []);
  const joinable = organisations.filter((o) => !memberOf.has(o.id));

  return (
    <div className="p-8">
      <div className="mb-2 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-gray-900">All Users</h1>
        <button
          onClick={openCreate}
          className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
        >
          + New User
        </button>
      </div>
      <p className="mb-6 text-sm text-gray-600">
        Every account in this installation. Select one to edit its details, change which
        organisations it belongs to, and set what it may do in each of them. This is the only place
        accounts are created and edited — an Organisation Admin invites people instead.
      </p>

      {loading && <p className="text-gray-500">Loading...</p>}
      {error && <p className="text-red-600">{error}</p>}

      {!loading && (
        <DataTable
          columns={columns}
          data={users}
          keyExtractor={(u) => u.id}
          onRowClick={open}
          actions={(u) => (
            <div className="flex justify-end gap-2">
              <button
                onClick={() => open(u)}
                className="rounded px-2 py-1 text-xs text-blue-600 hover:bg-blue-50"
              >
                Manage
              </button>
              <button
                onClick={(e) => {
                  // The row itself opens the manage panel; deleting must not also do that.
                  e.stopPropagation();
                  void handleDeleteAccount(u);
                }}
                className="rounded px-2 py-1 text-xs text-red-600 hover:bg-red-50"
                title="Delete this account entirely"
              >
                Delete
              </button>
            </div>
          )}
        />
      )}

      <Modal open={createOpen} onClose={() => setCreateOpen(false)} title="New user account">
        <FormField
          label="Email *"
          type="email"
          value={newUser.email}
          onChange={(e) => setNewUser({ ...newUser, email: e.target.value })}
          placeholder="e.g. jane@example.com"
        />
        <FormField
          label="Full name"
          value={newUser.fullName}
          onChange={(e) => setNewUser({ ...newUser, fullName: e.target.value })}
        />
        <FormField
          label="Phone"
          value={newUser.phone}
          onChange={(e) => setNewUser({ ...newUser, phone: e.target.value })}
        />
        <FormField
          label="Password *"
          type="password"
          autoComplete="new-password"
          value={newUser.password}
          onChange={(e) => setNewUser({ ...newUser, password: e.target.value })}
        />

        <div className="mb-4">
          <span className="block text-sm font-medium text-gray-700">Organisations *</span>
          <p className="mb-2 text-xs text-gray-500">
            At least one — an account belonging to none can sign in and see nothing.
          </p>
          <div className="max-h-40 overflow-y-auto">
            {organisations.map((o) => (
              <label key={o.id} className="mb-1 flex cursor-pointer items-center gap-2">
                <input
                  type="checkbox"
                  checked={newUser.organisationIds.includes(o.id)}
                  onChange={() =>
                    setNewUser({
                      ...newUser,
                      organisationIds: newUser.organisationIds.includes(o.id)
                        ? newUser.organisationIds.filter((id) => id !== o.id)
                        : [...newUser.organisationIds, o.id],
                    })
                  }
                  className="rounded border-gray-300 text-blue-600"
                />
                <span className="text-sm text-gray-700">
                  {o.name}
                  {o.template ? ' (template)' : ''}
                </span>
              </label>
            ))}
          </div>
        </div>

        <div className="mb-4">
          <span className="block text-sm font-medium text-gray-700">Global permissions</span>
          {GLOBAL_PERMISSIONS.map((p) => (
            <label key={p.key} className="mt-2 flex cursor-pointer items-center gap-2">
              <input
                type="checkbox"
                checked={newUser.globalPermissions.includes(p.key)}
                onChange={() =>
                  setNewUser({
                    ...newUser,
                    globalPermissions: newUser.globalPermissions.includes(p.key)
                      ? newUser.globalPermissions.filter((k) => k !== p.key)
                      : [...newUser.globalPermissions, p.key],
                  })
                }
                className="rounded border-gray-300 text-blue-600"
              />
              <span className="text-sm text-gray-700">{p.label}</span>
            </label>
          ))}
          <p className="mt-1 text-xs text-gray-500">
            Permissions within each organisation are set after creating, in the manage panel.
          </p>
        </div>

        {createError && <p className="mb-3 text-sm text-red-600">{createError}</p>}
        <div className="flex justify-end gap-3">
          <button
            onClick={() => setCreateOpen(false)}
            className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-50"
          >
            Cancel
          </button>
          <button
            onClick={handleCreate}
            disabled={
              creating ||
              !newUser.email.trim() ||
              !newUser.password.trim() ||
              newUser.organisationIds.length === 0
            }
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {creating ? 'Creating…' : 'Create'}
          </button>
        </div>
      </Modal>

      <Modal
        open={!!selected}
        onClose={() => setSelected(null)}
        title={selected ? selected.email : ''}
        wide
      >
        {selected && form && (
          <div className="max-h-[70vh] overflow-y-auto pr-1">
            {formError && <p className="mb-3 text-sm text-red-600">{formError}</p>}

            <h3 className="mb-3 text-sm font-semibold text-gray-900">Account details</h3>
            <div className="grid grid-cols-2 gap-x-4">
              <FormField
                label="Email"
                type="email"
                value={form.email}
                onChange={(e) => setForm({ ...form, email: e.target.value })}
              />
              <FormField
                label="Full name"
                value={form.fullName ?? ''}
                onChange={(e) => setForm({ ...form, fullName: e.target.value })}
              />
              <FormField
                label="Phone"
                value={form.phone ?? ''}
                onChange={(e) => setForm({ ...form, phone: e.target.value })}
              />
              <FormField
                label="New password"
                type="password"
                placeholder="Leave blank to keep current"
                value={form.password ?? ''}
                onChange={(e) => setForm({ ...form, password: e.target.value })}
              />
            </div>

            <div className="mb-4">
              <span className="block text-sm font-medium text-gray-700">Global permissions</span>
              {GLOBAL_PERMISSIONS.map((p) => (
                <label key={p.key} className="mt-2 flex items-center gap-2 text-sm text-gray-700">
                  <input
                    type="checkbox"
                    checked={form.globalPermissions.includes(p.key)}
                    onChange={() =>
                      setForm({
                        ...form,
                        globalPermissions: form.globalPermissions.includes(p.key)
                          ? form.globalPermissions.filter((k) => k !== p.key)
                          : [...form.globalPermissions, p.key],
                      })
                    }
                  />
                  {p.label}
                </label>
              ))}
              <p className="mt-1 text-xs text-gray-500">
                A Global Administrator also holds every organisation permission, everywhere.
              </p>
            </div>

            <div className="mb-6 flex justify-end">
              <button
                onClick={handleSaveAccount}
                disabled={saving || !form.email.trim()}
                className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
              >
                {saving ? 'Saving…' : 'Save details'}
              </button>
            </div>

            <h3 className="mb-1 text-sm font-semibold text-gray-900">Organisations</h3>
            <p className="mb-3 text-xs text-gray-500">
              Permissions apply only in the organisation they sit under, and save as you click.
            </p>

            {selected.memberships.length === 0 && (
              <p className="mb-3 text-sm text-amber-600">
                This user belongs to no organisation and cannot use the app until added to one.
              </p>
            )}

            <div className="space-y-3">
              {selected.memberships.map((m) => (
                <div key={m.organisationId} className="rounded-lg ring-1 ring-gray-200 px-4 py-3">
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-medium text-gray-900">
                      {m.organisationName}
                      {m.template && (
                        <span className="ml-2 rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-600">
                          Template
                        </span>
                      )}
                    </span>
                    <button
                      onClick={() => handleRemoveFromOrg(m.organisationId, m.organisationName)}
                      disabled={busy}
                      className="rounded px-2 py-1 text-xs text-red-600 hover:bg-red-50 disabled:opacity-50"
                    >
                      Remove
                    </button>
                  </div>
                  <div className="mt-2 flex flex-wrap gap-x-5 gap-y-1">
                    {ORGANISATION_PERMISSIONS.map((p) => (
                      <label
                        key={p.key}
                        className={`flex items-center gap-2 text-sm ${
                          m.implied ? 'text-gray-400' : 'text-gray-700'
                        }`}
                      >
                        <input
                          type="checkbox"
                          checked={m.permissions.includes(p.key)}
                          disabled={busy || m.implied}
                          onChange={() =>
                            togglePermission(m.organisationId, p.key, m.permissions)
                          }
                        />
                        {p.label}
                      </label>
                    ))}
                  </div>
                  {m.implied && (
                    <p className="mt-1 text-xs text-gray-500">
                      Held through Global Administrator — remove that to set these individually.
                    </p>
                  )}
                </div>
              ))}
            </div>

            {joinable.length > 0 && (
              <div className="mt-4 flex items-end gap-2">
                <div className="flex-1">
                  <FormField
                    as="select"
                    label="Add to organisation"
                    value={addOrgId}
                    onChange={(e) => setAddOrgId(e.target.value)}
                  >
                    <option value="">Select an organisation…</option>
                    {joinable.map((o) => (
                      <option key={o.id} value={o.id}>
                        {o.name}
                        {o.template ? ' (template)' : ''}
                      </option>
                    ))}
                  </FormField>
                </div>
                <button
                  onClick={handleAddToOrg}
                  disabled={busy || !addOrgId}
                  className="mb-4 rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium hover:bg-gray-50 disabled:opacity-50"
                >
                  Add
                </button>
              </div>
            )}
          </div>
        )}
      </Modal>
    </div>
  );
}
