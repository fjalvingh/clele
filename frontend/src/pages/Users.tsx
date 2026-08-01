import { useEffect, useState } from 'react';
import {
  addOrganisationMember,
  createUser,
  deletePartsByUser,
  deleteUser,
  getUsers,
  removeOrganisationMember,
  updateUser,
  updateUserPermissions,
} from '../api';
import {
  GLOBAL_PERMISSIONS,
  ORGANISATION_PERMISSIONS,
  PERMISSIONS,
  type User,
  type UserRequest,
} from '../api/types';
import { useAuth } from '../auth/AuthContext';
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
  globalPermissions: [],
});

const permLabel = (key: string) => PERMISSIONS.find((p) => p.key === key)?.label ?? key;

/**
 * Members of the organisation currently in force. Permissions edited here are per-organisation, so
 * an Organisation Admin can never see or change what someone may do elsewhere; only a Global
 * Administrator manages the accounts themselves (an email is unique across the installation).
 */
export default function UsersPage() {
  const { user: me } = useAuth();
  const isGlobalAdmin = !!me?.globalPermissions?.includes('GLOBAL_ADMIN');
  const organisationName = me?.currentOrganisationName ?? 'this organisation';

  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<User | null>(null);
  const [form, setForm] = useState<UserRequest>(emptyForm());
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const [addEmail, setAddEmail] = useState('');
  const [addError, setAddError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);

  const load = () => {
    setLoading(true);
    getUsers()
      .then(setUsers)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

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
      globalPermissions: [...(u.globalPermissions ?? [])],
    });
    setFormError(null);
    setModalOpen(true);
  };

  const toggle = (field: 'permissions' | 'globalPermissions', key: string) => {
    setForm((prev) => {
      const current = prev[field] ?? [];
      return {
        ...prev,
        [field]: current.includes(key) ? current.filter((p) => p !== key) : [...current, key],
      };
    });
  };

  const handleSave = async () => {
    setSaving(true);
    setFormError(null);
    try {
      if (!editing) {
        await createUser(form);
      } else if (isGlobalAdmin) {
        // A Global Administrator edits the account itself; the same request also carries this
        // organisation's permissions.
        await updateUser(editing.id, form);
      } else {
        // An Organisation Admin may only change what this user can do here.
        await updateUserPermissions(editing.id, form.permissions);
      }
      setModalOpen(false);
      load();
    } catch (e: unknown) {
      setFormError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const handleAddMember = async () => {
    setAdding(true);
    setAddError(null);
    try {
      await addOrganisationMember(addEmail.trim());
      setAddOpen(false);
      setAddEmail('');
      load();
    } catch (e: unknown) {
      setAddError((e as Error).message);
    } finally {
      setAdding(false);
    }
  };

  const handleRemoveMember = async (u: User) => {
    if (!confirm(`Remove "${u.email}" from ${organisationName}?\n\nTheir account is not deleted.`))
      return;
    try {
      await removeOrganisationMember(u.id);
      load();
    } catch (e: unknown) {
      alert((e as Error).message);
    }
  };

  const handleDelete = async (u: User) => {
    if (!confirm(`Delete the account "${u.email}" entirely, in every organisation?`)) return;
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
        `Delete every part created by "${u.email}" in ${organisationName}?\n\n` +
          'This also removes their stock entries, photos and movement history, and cannot be undone. ' +
          'Parts in other organisations, and parts created by other users, are not affected.',
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
      key: 'permissions',
      header: 'Permissions here',
      render: (u) => (u.permissions.length ? u.permissions.map(permLabel).join(', ') : '—'),
    },
    {
      key: 'globalPermissions',
      header: 'Global',
      render: (u) =>
        u.globalPermissions?.length ? u.globalPermissions.map(permLabel).join(', ') : '—',
    },
  ];

  const saveDisabled = saving || (!editing && (!form.email.trim() || !(form.password ?? '').trim()));

  return (
    <div className="p-8">
      <div className="mb-2 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-gray-900">Users</h1>
        <div className="flex gap-2">
          <button
            onClick={() => {
              setAddEmail('');
              setAddError(null);
              setAddOpen(true);
            }}
            className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium hover:bg-gray-50"
          >
            Add existing user
          </button>
          {isGlobalAdmin && (
            <button
              onClick={openCreate}
              className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
            >
              + New User
            </button>
          )}
        </div>
      </div>
      <p className="mb-6 text-sm text-gray-600">
        Members of <strong>{organisationName}</strong>. Permissions set here apply in this
        organisation only — the same person can have different rights elsewhere.
        {!isGlobalAdmin && ' Creating and editing accounts requires Global Administrator.'}
      </p>

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
                {isGlobalAdmin ? 'Edit' : 'Permissions'}
              </button>
              <button
                onClick={() => handleDeleteParts(u)}
                className="rounded px-2 py-1 text-xs text-red-600 hover:bg-red-50"
                title="Delete every part this user created in this organisation"
              >
                Delete parts
              </button>
              <button
                onClick={() => handleRemoveMember(u)}
                className="rounded px-2 py-1 text-xs text-amber-700 hover:bg-amber-50"
                title="Remove from this organisation; the account remains"
              >
                Remove
              </button>
              {isGlobalAdmin && (
                <button
                  onClick={() => handleDelete(u)}
                  className="rounded px-2 py-1 text-xs text-red-600 hover:bg-red-50"
                  title="Delete the account entirely"
                >
                  Delete
                </button>
              )}
            </div>
          )}
        />
      )}

      <Modal
        open={addOpen}
        onClose={() => setAddOpen(false)}
        title={`Add a user to ${organisationName}`}
      >
        <p className="mb-4 text-sm text-gray-600">
          The account must already exist. They join with no permissions — set those afterwards.
        </p>
        <FormField
          label="Email *"
          type="email"
          value={addEmail}
          onChange={(e) => setAddEmail(e.target.value)}
          placeholder="e.g. jane@example.com"
        />
        {addError && <p className="mb-3 text-sm text-red-600">{addError}</p>}
        <div className="flex justify-end gap-3">
          <button
            onClick={() => setAddOpen(false)}
            className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-50"
          >
            Cancel
          </button>
          <button
            onClick={handleAddMember}
            disabled={adding || !addEmail.trim()}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {adding ? 'Adding…' : 'Add'}
          </button>
        </div>
      </Modal>

      <Modal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        title={editing ? (isGlobalAdmin ? 'Edit User' : 'Permissions') : 'New User'}
      >
        {(isGlobalAdmin || !editing) && (
          <>
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
          </>
        )}

        {editing && !isGlobalAdmin && (
          <p className="mb-4 text-sm text-gray-600">{form.fullName || form.email}</p>
        )}

        <div className="mb-4">
          <p className="mb-1 text-sm font-medium text-gray-700">
            Permissions in {organisationName}
          </p>
          <p className="mb-2 text-xs text-gray-500">These apply in this organisation only.</p>
          {ORGANISATION_PERMISSIONS.map((perm) => (
            <label key={perm.key} className="mb-1 flex cursor-pointer items-center gap-2">
              <input
                type="checkbox"
                checked={form.permissions.includes(perm.key)}
                onChange={() => toggle('permissions', perm.key)}
                className="rounded border-gray-300 text-blue-600"
              />
              <span className="text-sm text-gray-700">{perm.label}</span>
            </label>
          ))}
        </div>

        {isGlobalAdmin && (
          <div className="mb-4">
            <p className="mb-1 text-sm font-medium text-gray-700">Global permissions</p>
            <p className="mb-2 text-xs text-gray-500">
              In force in every organisation. A Global Administrator implicitly holds every
              organisation permission everywhere.
            </p>
            {GLOBAL_PERMISSIONS.map((perm) => (
              <label key={perm.key} className="mb-1 flex cursor-pointer items-center gap-2">
                <input
                  type="checkbox"
                  checked={(form.globalPermissions ?? []).includes(perm.key)}
                  onChange={() => toggle('globalPermissions', perm.key)}
                  className="rounded border-gray-300 text-blue-600"
                />
                <span className="text-sm text-gray-700">{perm.label}</span>
              </label>
            ))}
          </div>
        )}

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
