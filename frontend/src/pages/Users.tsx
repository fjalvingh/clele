import { useEffect, useState } from 'react';
import {
  createUser,
  deletePartsByUser,
  deleteUser,
  getOrganisations,
  getUsers,
  updateUser,
} from '../api';
import { PERMISSIONS, type Organisation, type User, type UserRequest } from '../api/types';
import DataTable from '../components/DataTable';
import type { Column } from '../components/DataTable';
import FormField from '../components/FormField';
import Modal from '../components/Modal';

const emptyForm = (): UserRequest => ({
  email: '',
  password: '',
  fullName: '',
  phone: '',
  permissions: [],
  organisationIds: [],
});

const permLabel = (key: string) =>
  PERMISSIONS.find((p) => p.key === key)?.label ?? key;

export default function UsersPage() {
  const [users, setUsers] = useState<User[]>([]);
  const [organisations, setOrganisations] = useState<Organisation[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<User | null>(null);
  const [form, setForm] = useState<UserRequest>(emptyForm());
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const load = () => {
    setLoading(true);
    getUsers()
      .then(setUsers)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  // Membership is assigned here, so the picker needs the full list (requires GLOBAL_ADMIN; a
  // USERS_EDIT-only admin simply gets no options and cannot change membership).
  useEffect(() => {
    getOrganisations().then(setOrganisations).catch(() => setOrganisations([]));
  }, []);

  const orgName = (id: number) => organisations.find((o) => o.id === id)?.name ?? `#${id}`;

  const openCreate = () => {
    setEditing(null);
    setForm(emptyForm());
    setFormError(null);
    setModalOpen(true);
  };

  const openEdit = (u: User) => {
    setEditing(u);
    setForm({
      email: u.email,
      password: '',
      fullName: u.fullName ?? '',
      phone: u.phone ?? '',
      permissions: [...u.permissions],
      organisationIds: [...(u.organisationIds ?? [])],
    });
    setFormError(null);
    setModalOpen(true);
  };

  const toggleOrganisation = (id: number) => {
    setForm((prev) => ({
      ...prev,
      organisationIds: prev.organisationIds.includes(id)
        ? prev.organisationIds.filter((o) => o !== id)
        : [...prev.organisationIds, id],
    }));
  };

  const togglePermission = (key: string) => {
    setForm((prev) => ({
      ...prev,
      permissions: prev.permissions.includes(key)
        ? prev.permissions.filter((p) => p !== key)
        : [...prev.permissions, key],
    }));
  };

  const handleSave = async () => {
    setSaving(true);
    setFormError(null);
    try {
      if (editing) {
        await updateUser(editing.id, form);
      } else {
        await createUser(form);
      }
      setModalOpen(false);
      load();
    } catch (e: unknown) {
      setFormError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (u: User) => {
    if (!confirm(`Delete user "${u.email}"?`)) return;
    try {
      await deleteUser(u.id);
      load();
    } catch (e: unknown) {
      alert((e as Error).message);
    }
  };

  const handleDeleteParts = async (u: User) => {
    if (
      !confirm(
        `Delete every part created by "${u.email}"?\n\n` +
          'This also removes their stock entries, photos and movement history, and cannot be undone. ' +
          'Parts created by other users are not affected.',
      )
    )
      return;
    try {
      const deleted = await deletePartsByUser(u.id);
      alert(deleted === 0 ? 'No parts were created by this user.' : `Deleted ${deleted} part(s).`);
    } catch (e: unknown) {
      alert((e as Error).message);
    }
  };

  const columns: Column<User>[] = [
    { key: 'fullName', header: 'Name', render: (u) => u.fullName || '—' },
    { key: 'email', header: 'Email' },
    { key: 'phone', header: 'Phone', render: (u) => u.phone || '—' },
    {
      key: 'organisations',
      header: 'Organisations',
      render: (u) =>
        u.organisationIds?.length ? u.organisationIds.map(orgName).join(', ') : '—',
    },
    {
      key: 'permissions',
      header: 'Permissions',
      render: (u) =>
        u.permissions.length ? u.permissions.map(permLabel).join(', ') : '—',
    },
  ];

  // On create the password is required; on edit it is optional (blank keeps current). A user must
  // always belong to at least one organisation — there is nothing for them to see otherwise.
  const saveDisabled =
    saving ||
    !form.email.trim() ||
    form.organisationIds.length === 0 ||
    (!editing && !(form.password ?? '').trim());

  return (
    <div className="p-8">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-gray-900">Users</h1>
        <button
          onClick={openCreate}
          className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
        >
          + New User
        </button>
      </div>

      {loading && <p className="text-gray-500">Loading...</p>}
      {error && <p className="text-red-600">{error}</p>}

      {!loading && (
        <DataTable
          columns={columns}
          data={users}
          keyExtractor={(u) => u.id}
          actions={(u) => (
            <div className="flex justify-end gap-2">
              <button
                onClick={() => openEdit(u)}
                className="rounded px-2 py-1 text-xs text-blue-600 hover:bg-blue-50"
              >
                Edit
              </button>
              <button
                onClick={() => handleDeleteParts(u)}
                className="rounded px-2 py-1 text-xs text-red-600 hover:bg-red-50"
                title="Delete every part this user created, with its stock and images"
              >
                Delete parts
              </button>
              <button
                onClick={() => handleDelete(u)}
                className="rounded px-2 py-1 text-xs text-red-600 hover:bg-red-50"
              >
                Delete
              </button>
            </div>
          )}
        />
      )}

      <Modal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        title={editing ? 'Edit User' : 'New User'}
      >
        <FormField
          label="Email *"
          type="email"
          value={form.email}
          onChange={(e) => setForm({ ...form, email: e.target.value })}
          placeholder="e.g. jane@example.com"
        />
        <FormField
          label="Full Name"
          value={form.fullName ?? ''}
          onChange={(e) => setForm({ ...form, fullName: e.target.value })}
        />
        <FormField
          label="Phone"
          value={form.phone ?? ''}
          onChange={(e) => setForm({ ...form, phone: e.target.value })}
        />
        <FormField
          label={editing ? 'Password' : 'Password *'}
          type="password"
          autoComplete="new-password"
          value={form.password ?? ''}
          onChange={(e) => setForm({ ...form, password: e.target.value })}
          placeholder={editing ? 'Leave blank to keep current password' : ''}
        />

        <div className="mb-4">
          <p className="mb-2 text-sm font-medium text-gray-700">Organisations *</p>
          {organisations.length === 0 ? (
            <p className="text-sm text-gray-500">
              No organisations available. Global Administrator permission is required to change
              membership.
            </p>
          ) : (
            organisations.map((org) => (
              <label key={org.id} className="mb-1 flex cursor-pointer items-center gap-2">
                <input
                  type="checkbox"
                  checked={form.organisationIds.includes(org.id)}
                  onChange={() => toggleOrganisation(org.id)}
                  className="rounded border-gray-300 text-blue-600"
                />
                <span className="text-sm text-gray-700">{org.name}</span>
                {org.template && (
                  <span className="rounded bg-gray-100 px-1.5 py-0.5 text-[10px] uppercase tracking-wide text-gray-500">
                    Template
                  </span>
                )}
              </label>
            ))
          )}
        </div>

        <div className="mb-4">
          <p className="mb-2 text-sm font-medium text-gray-700">Permissions</p>
          {PERMISSIONS.map((perm) => (
            <label key={perm.key} className="mb-1 flex items-center gap-2 cursor-pointer">
              <input
                type="checkbox"
                checked={form.permissions.includes(perm.key)}
                onChange={() => togglePermission(perm.key)}
                className="rounded border-gray-300 text-blue-600"
              />
              <span className="text-sm text-gray-700">{perm.label}</span>
            </label>
          ))}
        </div>

        {formError && <p className="mb-3 text-sm text-red-600">{formError}</p>}
        <div className="flex justify-end gap-3">
          <button
            onClick={() => setModalOpen(false)}
            className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-50"
          >
            Cancel
          </button>
          <button
            onClick={handleSave}
            disabled={saveDisabled}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </Modal>
    </div>
  );
}
