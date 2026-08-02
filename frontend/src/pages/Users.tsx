import { useEffect, useRef, useState } from 'react';
import {
  createInvitation,
  deletePartsByUser,
  getInvitations,
  getUsers,
  lookupEmail,
  removeOrganisationMember,
  revokeInvitation,
  updateUserPermissions,
} from '../api';
import {
  ORGANISATION_PERMISSIONS,
  PERMISSIONS,
  type EmailLookup,
  type Invitation,
  type User,
} from '../api/types';
import { useAuth } from '../auth/AuthContext';
import DataTable from '../components/DataTable';
import type { Column } from '../components/DataTable';
import FormField from '../components/FormField';
import Modal from '../components/Modal';

const permLabel = (key: string) => PERMISSIONS.find((p) => p.key === key)?.label ?? key;

const statusLabel: Record<Invitation['status'], string> = {
  PENDING: 'Awaiting reply',
  ACCEPTED: 'Accepted',
  DECLINED: 'Refused',
  REVOKED: 'Withdrawn',
};

/**
 * Members of the organisation currently in force, plus the invitations sent for it.
 *
 * <p>An Organisation Admin adds nobody directly: they invite an address and the invitee decides.
 * Accounts themselves — creating, editing, deleting — are a Global Administrator's job on the All
 * Users screen, because an email is unique across the whole installation.
 */
export default function UsersPage() {
  const { user: me } = useAuth();
  const organisationName = me?.currentOrganisationName ?? 'this organisation';

  const [users, setUsers] = useState<User[]>([]);
  const [invitations, setInvitations] = useState<Invitation[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Permissions of one existing member.
  const [editing, setEditing] = useState<User | null>(null);
  const [permissions, setPermissions] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  // Invite dialog.
  const [inviteOpen, setInviteOpen] = useState(false);
  const [inviteEmail, setInviteEmail] = useState('');
  const [invitePermissions, setInvitePermissions] = useState<string[]>([]);
  const [lookup, setLookup] = useState<EmailLookup | null>(null);
  const [inviting, setInviting] = useState(false);
  const [inviteError, setInviteError] = useState<string | null>(null);
  const [sent, setSent] = useState<Invitation | null>(null);

  const load = () => {
    setLoading(true);
    Promise.all([getUsers(), getInvitations()])
      .then(([u, i]) => {
        setUsers(u);
        setInvitations(i);
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  // Who the typed address belongs to, so the admin can see they are inviting the person they meant.
  // Debounced, and guarded by a sequence number: replies can arrive out of order while typing.
  const lookupSeq = useRef(0);
  useEffect(() => {
    const email = inviteEmail.trim();
    if (!inviteOpen || !email.includes('@')) {
      setLookup(null);
      return;
    }
    const seq = ++lookupSeq.current;
    const timer = setTimeout(() => {
      lookupEmail(email)
        .then((result) => {
          if (seq === lookupSeq.current) setLookup(result);
        })
        .catch(() => {
          if (seq === lookupSeq.current) setLookup(null);
        });
    }, 350);
    return () => clearTimeout(timer);
  }, [inviteEmail, inviteOpen]);

  const openInvite = () => {
    setInviteEmail('');
    setInvitePermissions([]);
    setLookup(null);
    setInviteError(null);
    setSent(null);
    setInviteOpen(true);
  };

  const toggleInvitePermission = (key: string) =>
    setInvitePermissions((prev) =>
      prev.includes(key) ? prev.filter((p) => p !== key) : [...prev, key],
    );

  const handleInvite = async () => {
    setInviting(true);
    setInviteError(null);
    try {
      const invitation = await createInvitation({
        email: inviteEmail.trim(),
        permissions: invitePermissions,
      });
      load();
      if (invitation.mailSent) {
        setInviteOpen(false);
      } else {
        // No mail went out. Keep the dialog open and show the link — otherwise the admin waits
        // for a reply to an invitation that was never delivered.
        setSent(invitation);
      }
    } catch (e: unknown) {
      setInviteError((e as Error).message);
    } finally {
      setInviting(false);
    }
  };

  const handleRevoke = async (invitation: Invitation) => {
    if (!confirm(`Withdraw the invitation for "${invitation.email}"?`)) return;
    try {
      await revokeInvitation(invitation.id);
      load();
    } catch (e: unknown) {
      alert((e as Error).message);
    }
  };

  const openPermissions = (u: User) => {
    setEditing(u);
    setPermissions([...u.permissions]);
    setFormError(null);
  };

  const handleSavePermissions = async () => {
    if (!editing) return;
    setSaving(true);
    setFormError(null);
    try {
      await updateUserPermissions(editing.id, permissions);
      setEditing(null);
      load();
    } catch (e: unknown) {
      setFormError((e as Error).message);
    } finally {
      setSaving(false);
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

  const invitationColumns: Column<Invitation>[] = [
    {
      key: 'email',
      header: 'Invited',
      render: (i) => (
        <span>
          {i.email}
          {i.fullName && <span className="ml-2 text-gray-500">({i.fullName})</span>}
        </span>
      ),
    },
    {
      key: 'permissions',
      header: 'Permissions on joining',
      render: (i) => (i.permissions.length ? i.permissions.map(permLabel).join(', ') : '—'),
    },
    { key: 'invitedByName', header: 'Invited by', render: (i) => i.invitedByName || '—' },
    {
      key: 'status',
      header: 'Status',
      render: (i) => (
        <span
          className={
            i.status === 'ACCEPTED'
              ? 'text-green-700'
              : i.status === 'PENDING' && !i.expired
                ? 'text-blue-700'
                : 'text-gray-500'
          }
        >
          {i.status === 'PENDING' && i.expired ? 'Expired' : statusLabel[i.status]}
        </span>
      ),
    },
    {
      key: 'createdAt',
      header: 'Sent',
      render: (i) => new Date(i.createdAt).toLocaleDateString(),
    },
  ];

  const inviteBlocked = lookup?.member || lookup?.invited;

  return (
    <div className="p-8">
      <div className="mb-2 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-gray-900">Users</h1>
        <button
          onClick={openInvite}
          className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
        >
          Invite user
        </button>
      </div>
      <p className="mb-6 text-sm text-gray-600">
        Members of <strong>{organisationName}</strong>. Permissions set here apply in this
        organisation only — the same person can have different rights elsewhere. To bring someone in,
        invite their email address; they receive a mail and decide for themselves. Accounts
        themselves are managed by a Global Administrator on the All Users screen.
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
                onClick={() => openPermissions(u)}
                className="rounded px-2 py-1 text-xs text-blue-600 hover:bg-blue-50"
              >
                Permissions
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
            </div>
          )}
        />
      )}

      {!loading && invitations.length > 0 && (
        <div className="mt-10">
          <h2 className="mb-1 text-lg font-semibold text-gray-900">Invitations</h2>
          <p className="mb-3 text-sm text-gray-600">
            Everyone invited to {organisationName}, and what became of it.
          </p>
          <DataTable
            columns={invitationColumns}
            data={invitations}
            keyExtractor={(i) => i.id}
            actions={(i) =>
              i.status === 'PENDING' && !i.expired ? (
                <div className="flex justify-end">
                  <button
                    onClick={() => handleRevoke(i)}
                    className="rounded px-2 py-1 text-xs text-red-600 hover:bg-red-50"
                    title="Withdraw this invitation"
                  >
                    Withdraw
                  </button>
                </div>
              ) : null
            }
          />
        </div>
      )}

      <Modal
        open={inviteOpen}
        onClose={() => setInviteOpen(false)}
        title={`Invite someone to ${organisationName}`}
      >
        {sent ? (
          <>
            <p className="mb-3 text-sm text-amber-700">
              The invitation was created, but no mail could be sent (this installation has no mail
              server configured). Pass this link to <strong>{sent.email}</strong> yourself:
            </p>
            <p className="mb-4 break-all rounded bg-gray-100 p-3 font-mono text-xs text-gray-800">
              {sent.link}
            </p>
            <div className="flex justify-end">
              <button
                onClick={() => setInviteOpen(false)}
                className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
              >
                Done
              </button>
            </div>
          </>
        ) : (
          <>
            <p className="mb-4 text-sm text-gray-600">
              They get a mail naming you and {organisationName}, and can accept or refuse. If they
              have no account yet, one is created when they accept.
            </p>
            <FormField
              label="Email *"
              type="email"
              value={inviteEmail}
              onChange={(e) => setInviteEmail(e.target.value)}
              placeholder="e.g. jane@example.com"
            />
            {lookup && (
              <p className="-mt-2 mb-4 text-sm">
                {lookup.member ? (
                  <span className="text-amber-700">
                    {lookup.fullName || lookup.email} is already a member of {organisationName}.
                  </span>
                ) : lookup.invited ? (
                  <span className="text-amber-700">
                    An invitation for this address is already outstanding.
                  </span>
                ) : lookup.exists ? (
                  <span className="text-gray-600">
                    Existing user: <strong>{lookup.fullName || lookup.email}</strong>
                  </span>
                ) : (
                  <span className="text-gray-500">
                    No account yet — one is created when they accept.
                  </span>
                )}
              </p>
            )}

            <div className="mb-4">
              <p className="mb-1 text-sm font-medium text-gray-700">
                Permissions in {organisationName}
              </p>
              <p className="mb-2 text-xs text-gray-500">Granted when they accept.</p>
              {ORGANISATION_PERMISSIONS.map((perm) => (
                <label key={perm.key} className="mb-1 flex cursor-pointer items-center gap-2">
                  <input
                    type="checkbox"
                    checked={invitePermissions.includes(perm.key)}
                    onChange={() => toggleInvitePermission(perm.key)}
                    className="rounded border-gray-300 text-blue-600"
                  />
                  <span className="text-sm text-gray-700">{perm.label}</span>
                </label>
              ))}
            </div>

            {inviteError && <p className="mb-3 text-sm text-red-600">{inviteError}</p>}
            <div className="flex justify-end gap-3">
              <button
                onClick={() => setInviteOpen(false)}
                className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-50"
              >
                Cancel
              </button>
              <button
                onClick={handleInvite}
                disabled={inviting || !inviteEmail.trim() || inviteBlocked}
                className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
              >
                {inviting ? 'Sending…' : 'Send invitation'}
              </button>
            </div>
          </>
        )}
      </Modal>

      <Modal open={!!editing} onClose={() => setEditing(null)} title="Permissions">
        <p className="mb-4 text-sm text-gray-600">{editing?.fullName || editing?.email}</p>
        <div className="mb-4">
          <p className="mb-1 text-sm font-medium text-gray-700">
            Permissions in {organisationName}
          </p>
          <p className="mb-2 text-xs text-gray-500">These apply in this organisation only.</p>
          {ORGANISATION_PERMISSIONS.map((perm) => (
            <label key={perm.key} className="mb-1 flex cursor-pointer items-center gap-2">
              <input
                type="checkbox"
                checked={permissions.includes(perm.key)}
                onChange={() =>
                  setPermissions((prev) =>
                    prev.includes(perm.key)
                      ? prev.filter((p) => p !== perm.key)
                      : [...prev, perm.key],
                  )
                }
                className="rounded border-gray-300 text-blue-600"
              />
              <span className="text-sm text-gray-700">{perm.label}</span>
            </label>
          ))}
        </div>

        {formError && <p className="mb-3 text-sm text-red-600">{formError}</p>}
        <div className="flex justify-end gap-3">
          <button
            onClick={() => setEditing(null)}
            className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-50"
          >
            Cancel
          </button>
          <button
            onClick={handleSavePermissions}
            disabled={saving}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </Modal>
    </div>
  );
}
