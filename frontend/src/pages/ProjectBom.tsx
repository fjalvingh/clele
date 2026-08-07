import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  applyImportedBom,
  bomFileUrl,
  deleteImportedBom,
  getBomLineCandidates,
  getImportedBom,
  getParts,
  importBomFile,
  setBomLineMatch,
} from '../api';
import {
  BOM_COLUMN_ROLES,
  type BomApplyResult,
  type BomCandidate,
  type BomColumnMapping,
  type BomColumnRole,
  type BomImportPreview,
  type BomLineStatus,
  type ImportedBom,
  type ImportedBomLine,
  type Part,
} from '../api/types';
import Modal from '../components/Modal';

const STATUS_LABELS: Record<BomLineStatus, string> = {
  UNMATCHED: 'Not matched',
  MATCHED: 'Matched',
  PROVIDED: 'Provided',
  EXCLUDED: 'Excluded',
};

const STATUS_COLORS: Record<BomLineStatus, string> = {
  UNMATCHED: 'bg-amber-100 text-amber-800',
  MATCHED: 'bg-green-100 text-green-700',
  PROVIDED: 'bg-blue-100 text-blue-700',
  EXCLUDED: 'bg-gray-100 text-gray-500',
};

const ROLE_LABELS: Record<BomColumnRole, string> = {
  REFERENCES: 'Designators',
  VALUE: 'Value',
  FOOTPRINT: 'Footprint',
  QUANTITY: 'Quantity',
  MPN: 'Part number (MPN)',
  MANUFACTURER: 'Manufacturer',
  DESCRIPTION: 'Description',
  DATASHEET: 'Datasheet',
  DNP: 'Do not populate',
};

type Filter = 'ALL' | BomLineStatus | 'CHANGED';

export default function ProjectBomPage() {
  const { id } = useParams<{ id: string }>();
  const projectId = Number(id);

  const [bom, setBom] = useState<ImportedBom | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [filter, setFilter] = useState<Filter>('ALL');
  const [search, setSearch] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setBom(await getImportedBom(projectId));
      setError(null);
    } catch (err: unknown) {
      setError((err as Error).message);
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => {
    void load();
  }, [load]);

  // ── Import ────────────────────────────────────────────────────────────────
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [pendingFile, setPendingFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<BomImportPreview | null>(null);
  const [mapping, setMapping] = useState<BomColumnMapping>({});
  const [importing, setImporting] = useState(false);
  const [importError, setImportError] = useState<string | null>(null);

  const runPreview = async (file: File, withMapping?: BomColumnMapping) => {
    setImporting(true);
    setImportError(null);
    try {
      const result = await importBomFile(projectId, file, { mapping: withMapping, commit: false });
      setPreview(result);
      setMapping(result.mapping);
    } catch (err: unknown) {
      setImportError((err as Error).message);
      setPreview(null);
    } finally {
      setImporting(false);
    }
  };

  const handleFilePicked = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    // Reset so re-picking the same file after a correction still fires onChange.
    e.target.value = '';
    setPendingFile(file);
    await runPreview(file);
  };

  const handleCommit = async () => {
    if (!pendingFile) return;
    setImporting(true);
    setImportError(null);
    try {
      const result = await importBomFile(projectId, pendingFile, { mapping, commit: true });
      closeImport();
      setNotice(
        `Imported ${result.totalLines} lines — ${result.added} added, ${result.updated} updated, ` +
          `${result.removed} removed, ${result.autoMatched} matched automatically.`,
      );
      await load();
    } catch (err: unknown) {
      setImportError((err as Error).message);
    } finally {
      setImporting(false);
    }
  };

  const closeImport = () => {
    setPendingFile(null);
    setPreview(null);
    setMapping({});
    setImportError(null);
  };

  // ── Matching ──────────────────────────────────────────────────────────────
  const [matchLine, setMatchLine] = useState<ImportedBomLine | null>(null);
  const [candidates, setCandidates] = useState<BomCandidate[]>([]);
  const [candidatesLoading, setCandidatesLoading] = useState(false);
  const [partSearch, setPartSearch] = useState('');
  const [searchResults, setSearchResults] = useState<Part[]>([]);
  const [searchLoading, setSearchLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  const openMatch = async (line: ImportedBomLine) => {
    setMatchLine(line);
    setPartSearch('');
    setSearchResults([]);
    setCandidates([]);
    setCandidatesLoading(true);
    try {
      setCandidates(await getBomLineCandidates(projectId, line.id));
    } catch {
      setCandidates([]);
    } finally {
      setCandidatesLoading(false);
    }
  };

  useEffect(() => {
    if (!partSearch.trim()) {
      setSearchResults([]);
      return;
    }
    const timer = setTimeout(() => {
      setSearchLoading(true);
      getParts(partSearch)
        .then(setSearchResults)
        .catch(() => setSearchResults([]))
        .finally(() => setSearchLoading(false));
    }, 300);
    return () => clearTimeout(timer);
  }, [partSearch]);

  const decide = async (line: ImportedBomLine, partId: number | null, status?: BomLineStatus) => {
    setSaving(true);
    try {
      const updated = await setBomLineMatch(projectId, line.id, {
        partId,
        status,
        notes: line.notes,
      });
      setBom((current) =>
        current ? { ...current, ...recount(current, updated) } : current,
      );
      setMatchLine(null);
      setError(null);
    } catch (err: unknown) {
      setError((err as Error).message);
    } finally {
      setSaving(false);
    }
  };

  // ── Apply ─────────────────────────────────────────────────────────────────
  const [applying, setApplying] = useState(false);
  const [applyResult, setApplyResult] = useState<BomApplyResult | null>(null);

  const handleApply = async () => {
    setApplying(true);
    try {
      setApplyResult(await applyImportedBom(projectId));
      setError(null);
    } catch (err: unknown) {
      setError((err as Error).message);
    } finally {
      setApplying(false);
    }
  };

  const [confirmDelete, setConfirmDelete] = useState(false);
  const handleDelete = async () => {
    try {
      await deleteImportedBom(projectId);
      setConfirmDelete(false);
      setNotice('The imported BOM was deleted. The project BOM itself is untouched.');
      await load();
    } catch (err: unknown) {
      setError((err as Error).message);
    }
  };

  const visibleLines = useMemo(() => {
    if (!bom) return [];
    const term = search.trim().toLowerCase();
    return bom.lines.filter((line) => {
      if (filter === 'CHANGED' && !line.changed) return false;
      if (filter !== 'ALL' && filter !== 'CHANGED' && line.status !== filter) return false;
      if (!term) return true;
      return [line.designators, line.value, line.footprint, line.mpn, line.partNumber]
        .some((field) => field?.toLowerCase().includes(term));
    });
  }, [bom, filter, search]);

  if (loading) {
    return <div className="py-12 text-center text-sm text-gray-400">Loading…</div>;
  }

  return (
    <div className="max-w-6xl mx-auto space-y-6">
      <nav className="text-sm text-gray-500">
        <Link to="/projects" className="hover:underline">Projects</Link>
        {' › '}
        <Link to={`/projects/${projectId}`} className="hover:underline">
          {bom?.projectName ?? 'Project'}
        </Link>
        {' › '}
        <span className="text-gray-700">Bill of materials</span>
      </nav>

      {error && (
        <div className="flex items-start justify-between rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          <span>{error}</span>
          <button onClick={() => setError(null)} className="ml-4 text-red-400 hover:text-red-600">Dismiss</button>
        </div>
      )}
      {notice && (
        <div className="flex items-start justify-between rounded-lg border border-green-200 bg-green-50 px-4 py-3 text-sm text-green-700">
          <span>{notice}</span>
          <button onClick={() => setNotice(null)} className="ml-4 text-green-500 hover:text-green-700">Dismiss</button>
        </div>
      )}

      <input
        ref={fileInputRef}
        type="file"
        accept=".csv,.tsv,.txt,text/csv,text/plain"
        className="hidden"
        onChange={handleFilePicked}
      />

      {!bom ? (
        <EmptyState onImport={() => fileInputRef.current?.click()} busy={importing} />
      ) : (
        <>
          <header className="rounded-lg border border-gray-200 bg-surface p-6 shadow-sm">
            <div className="flex flex-wrap items-start justify-between gap-4">
              <div>
                <h1 className="text-xl font-semibold text-gray-900">{bom.filename ?? 'Imported BOM'}</h1>
                <p className="mt-1 text-sm text-gray-500">
                  {bom.totalLines} lines · imported {new Date(bom.importedAt).toLocaleString()}
                  {bom.importedByName && ` by ${bom.importedByName}`}
                  {bom.instanceCount !== 1 && ` · ${bom.instanceCount} instances`}
                </p>
                <div className="mt-3 flex flex-wrap gap-2">
                  <Pill label={`${bom.matchedCount} matched`} color={STATUS_COLORS.MATCHED} />
                  <Pill label={`${bom.unmatchedCount} not matched`} color={STATUS_COLORS.UNMATCHED} />
                  <Pill label={`${bom.providedCount} provided`} color={STATUS_COLORS.PROVIDED} />
                  <Pill label={`${bom.excludedCount} excluded`} color={STATUS_COLORS.EXCLUDED} />
                  {bom.changedCount > 0 && (
                    <Pill label={`${bom.changedCount} changed — review`} color="bg-purple-100 text-purple-700" />
                  )}
                </div>
              </div>
              <div className="flex flex-wrap items-center gap-2">
                <button
                  onClick={() => fileInputRef.current?.click()}
                  disabled={importing}
                  className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
                >
                  Re-import file
                </button>
                <a
                  href={bomFileUrl(projectId)}
                  className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50"
                >
                  Download original
                </a>
                <button
                  onClick={() => setConfirmDelete(true)}
                  className="rounded-lg border border-red-200 px-3 py-1.5 text-sm font-medium text-red-600 hover:bg-red-50"
                >
                  Delete BOM
                </button>
                <button
                  onClick={handleApply}
                  disabled={applying || !bom.canApply || bom.matchedCount === 0}
                  title={
                    !bom.canApply
                      ? 'The project BOM can only be changed while the project is in Planning'
                      : bom.matchedCount === 0
                      ? 'Match at least one line first'
                      : undefined
                  }
                  className="rounded-lg bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
                >
                  {applying ? 'Applying…' : 'Apply to project BOM'}
                </button>
              </div>
            </div>
            {bom.unmatchedCount > 0 && (
              <p className="mt-4 rounded-lg bg-gray-50 px-3 py-2 text-xs text-gray-500">
                Applying writes only the matched lines into the project BOM. You can stop and come
                back — every decision here is saved as you make it.
              </p>
            )}
          </header>

          <div className="rounded-lg border border-gray-200 bg-surface shadow-sm">
            <div className="flex flex-wrap items-center gap-3 border-b border-gray-100 px-6 py-3">
              <div className="flex flex-wrap gap-1">
                {(['ALL', 'UNMATCHED', 'MATCHED', 'PROVIDED', 'EXCLUDED', 'CHANGED'] as Filter[]).map((f) => (
                  <button
                    key={f}
                    onClick={() => setFilter(f)}
                    className={`rounded-lg px-2.5 py-1 text-xs font-medium ${
                      filter === f ? 'bg-blue-600 text-white' : 'text-gray-600 hover:bg-gray-100'
                    }`}
                  >
                    {f === 'ALL' ? 'All' : f === 'CHANGED' ? 'Changed' : STATUS_LABELS[f as BomLineStatus]}
                  </button>
                ))}
              </div>
              <input
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Filter by designator, value, footprint…"
                className="ml-auto w-64 rounded-lg border border-gray-300 px-3 py-1.5 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
            </div>

            {visibleLines.length === 0 ? (
              <div className="px-6 py-10 text-center text-sm text-gray-400">
                No lines match this filter.
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="min-w-full divide-y divide-gray-100">
                  <thead className="bg-gray-50">
                    <tr>
                      <Th>Designators</Th>
                      <Th>Value</Th>
                      <Th>Footprint</Th>
                      <Th className="text-right">Qty</Th>
                      <Th className="text-right">Needed</Th>
                      <Th>Matched part</Th>
                      <Th className="text-right">On hand</Th>
                      <Th>Status</Th>
                      <th className="px-4 py-3" />
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-50">
                    {visibleLines.map((line) => (
                      <tr
                        key={line.id}
                        // A translucent tint rather than bg-purple-50: a light fixed colour sits
                        // on top of the row in dark mode and washes the text out to unreadable.
                        className={`hover:bg-gray-50 ${line.changed ? 'bg-purple-500/10' : ''}`}
                      >
                        <td className="px-4 py-2 text-sm font-medium text-gray-900">
                          {line.designators || <span className="text-gray-400">—</span>}
                          {line.changed && (
                            <span className="ml-2 rounded bg-purple-100 px-1.5 py-0.5 text-[10px] font-medium text-purple-700">
                              changed
                            </span>
                          )}
                        </td>
                        <td className="px-4 py-2 text-sm text-gray-700">{line.value ?? '—'}</td>
                        <td className="px-4 py-2 text-sm text-gray-500">{line.footprint ?? '—'}</td>
                        <td className="px-4 py-2 text-right text-sm text-gray-700">{line.quantity}</td>
                        <td className="px-4 py-2 text-right text-sm text-gray-700">{line.totalNeeded}</td>
                        <td className="px-4 py-2 text-sm">
                          {line.partId ? (
                            <>
                              <Link to={`/parts/${line.partId}`} className="font-medium text-blue-600 hover:underline">
                                {line.partNumber}
                              </Link>
                              {line.matchSource === 'AUTO' && (
                                <span className="ml-2 text-[10px] uppercase tracking-wide text-gray-400">auto</span>
                              )}
                            </>
                          ) : (
                            <span className="text-gray-400">{line.mpn ?? '—'}</span>
                          )}
                        </td>
                        <td className="px-4 py-2 text-right text-sm">
                          {line.partId ? (
                            <span className={shortfall(line) ? 'font-medium text-red-600' : 'text-green-700'}>
                              {line.onHand ?? 0}
                            </span>
                          ) : (
                            <span className="text-gray-300">—</span>
                          )}
                        </td>
                        <td className="px-4 py-2">
                          <span className={`inline-flex rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_COLORS[line.status]}`}>
                            {STATUS_LABELS[line.status]}
                          </span>
                        </td>
                        <td className="px-4 py-2 text-right">
                          <button
                            onClick={() => void openMatch(line)}
                            className="rounded-lg border border-gray-300 px-2.5 py-1 text-xs font-medium text-gray-700 hover:bg-gray-50"
                          >
                            {line.partId ? 'Change' : 'Match'}
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>
      )}

      {/* Import preview */}
      <Modal open={pendingFile !== null} onClose={closeImport} title="Import BOM file" wide>
        {importing && !preview ? (
          <p className="py-6 text-center text-sm text-gray-500">Reading {pendingFile?.name}…</p>
        ) : importError && !preview ? (
          <div className="space-y-4">
            <p className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{importError}</p>
            <button onClick={closeImport} className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm">Close</button>
          </div>
        ) : preview ? (
          <div className="space-y-5">
            <p className="text-sm text-gray-600">
              <span className="font-medium">{pendingFile?.name}</span> · {preview.totalLines} lines ·
              delimiter “{preview.delimiter}”
            </p>

            {preview.warnings.length > 0 && (
              <ul className="space-y-1 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">
                {preview.warnings.map((w) => <li key={w}>{w}</li>)}
              </ul>
            )}

            <div>
              <h3 className="mb-2 text-sm font-medium text-gray-900">Columns</h3>
              <p className="mb-3 text-xs text-gray-500">
                Detected from the file's headers. Correct anything that is wrong — unmapped columns
                are still kept with the line.
              </p>
              <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
                {BOM_COLUMN_ROLES.map((role) => (
                  <label key={role} className="flex items-center gap-2 text-sm">
                    <span className="w-40 shrink-0 text-gray-600">{ROLE_LABELS[role]}</span>
                    <select
                      value={mapping[role] ?? ''}
                      onChange={(e) => {
                        const next = { ...mapping };
                        if (e.target.value) next[role] = e.target.value;
                        else delete next[role];
                        setMapping(next);
                      }}
                      className="flex-1 rounded-lg border border-gray-300 px-2 py-1 text-sm"
                    >
                      <option value="">— not used —</option>
                      {preview.headers.map((h) => <option key={h} value={h}>{h}</option>)}
                    </select>
                  </label>
                ))}
              </div>
              <button
                onClick={() => pendingFile && runPreview(pendingFile, mapping)}
                disabled={importing}
                className="mt-3 rounded-lg border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
              >
                Re-read with these columns
              </button>
            </div>

            <div>
              <h3 className="mb-2 text-sm font-medium text-gray-900">What importing would do</h3>
              <div className="flex flex-wrap gap-2 text-xs">
                <Pill label={`${preview.added} new lines`} color="bg-green-100 text-green-700" />
                <Pill label={`${preview.updated} updated`} color="bg-blue-100 text-blue-700" />
                <Pill label={`${preview.unchanged} unchanged`} color="bg-gray-100 text-gray-600" />
                <Pill label={`${preview.removed} removed`} color="bg-red-100 text-red-700" />
                <Pill label={`${preview.autoMatched} matched automatically`} color="bg-green-100 text-green-700" />
                {preview.changed > 0 && (
                  <Pill label={`${preview.changed} to review`} color="bg-purple-100 text-purple-700" />
                )}
              </div>
              {preview.removed > 0 && (
                <p className="mt-2 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">
                  {preview.removed} line{preview.removed === 1 ? '' : 's'} in the stored BOM
                  {preview.removed === 1 ? ' is' : ' are'} not in this file and will be deleted,
                  along with any match on {preview.removed === 1 ? 'it' : 'them'}.
                </p>
              )}
            </div>

            <div className="max-h-64 overflow-auto rounded-lg border border-gray-200">
              <table className="min-w-full divide-y divide-gray-100 text-sm">
                <thead className="sticky top-0 bg-gray-50">
                  <tr>
                    <Th>Action</Th>
                    <Th>Designators</Th>
                    <Th>Value</Th>
                    <Th className="text-right">Qty</Th>
                    <Th>Match</Th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-50">
                  {preview.lines.map((line, i) => (
                    <tr key={`${line.designators}-${i}`} className={line.action === 'REMOVED' ? 'bg-red-500/10' : ''}>
                      <td className="px-4 py-1.5 text-xs font-medium text-gray-500">{line.action}</td>
                      <td className="px-4 py-1.5">{line.designators ?? '—'}</td>
                      <td className="px-4 py-1.5 text-gray-600">{line.value ?? '—'}</td>
                      <td className="px-4 py-1.5 text-right text-gray-600">{line.quantity}</td>
                      <td className="px-4 py-1.5 text-gray-600">{line.matchedPartNumber ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {importError && (
              <p className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{importError}</p>
            )}

            <div className="flex justify-end gap-2 border-t border-gray-100 pt-4">
              <button onClick={closeImport} className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50">
                Cancel
              </button>
              <button
                onClick={handleCommit}
                disabled={importing}
                className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
              >
                {importing ? 'Importing…' : 'Import'}
              </button>
            </div>
          </div>
        ) : null}
      </Modal>

      {/* Match one line */}
      <Modal
        open={matchLine !== null}
        onClose={() => setMatchLine(null)}
        title={`Match ${matchLine?.designators || matchLine?.value || 'line'}`}
        wide
      >
        {matchLine && (
          <div className="space-y-5">
            <dl className="grid grid-cols-2 gap-x-6 gap-y-2 rounded-lg bg-gray-50 px-4 py-3 text-sm sm:grid-cols-4">
              <Field label="Designators" value={matchLine.designators} />
              <Field label="Value" value={matchLine.value} />
              <Field label="Footprint" value={matchLine.footprint} />
              <Field label="Part number" value={matchLine.mpn} />
              <Field label="Manufacturer" value={matchLine.manufacturer} />
              <Field label="Per board" value={String(matchLine.quantity)} />
              <Field label="Whole build" value={String(matchLine.totalNeeded)} />
              <Field label="Status" value={STATUS_LABELS[matchLine.status]} />
            </dl>

            {matchLine.changed && (
              <p className="rounded-lg border border-purple-200 bg-purple-50 px-3 py-2 text-xs text-purple-800">
                This line's value or footprint changed in the last import while it was already
                matched. Confirm the part is still right — saving anything here clears the flag.
              </p>
            )}

            {matchLine.partId && (
              <div className="flex items-center justify-between rounded-lg border border-green-200 bg-green-50 px-3 py-2 text-sm">
                <span>
                  Currently matched to{' '}
                  <Link to={`/parts/${matchLine.partId}`} className="font-medium text-blue-600 hover:underline">
                    {matchLine.partNumber}
                  </Link>
                </span>
                <button
                  onClick={() => void decide(matchLine, null, 'UNMATCHED')}
                  disabled={saving}
                  className="text-xs font-medium text-red-600 hover:underline disabled:opacity-50"
                >
                  Remove match
                </button>
              </div>
            )}

            <div>
              <h3 className="mb-2 text-sm font-medium text-gray-900">Suggestions</h3>
              {candidatesLoading ? (
                <p className="text-sm text-gray-400">Looking…</p>
              ) : candidates.length === 0 ? (
                <p className="text-sm text-gray-400">
                  Nothing in the catalogue looks like this line. Search below, or add it.
                </p>
              ) : (
                <CandidateTable
                  rows={candidates.map((c) => ({
                    part: c.part,
                    badge: c.exact ? 'exact' : `${Math.round(c.score * 100)}%`,
                    exact: c.exact,
                  }))}
                  onPick={(part) => void decide(matchLine, part.id, 'MATCHED')}
                  saving={saving}
                />
              )}
            </div>

            <div>
              <h3 className="mb-2 text-sm font-medium text-gray-900">Search the catalogue</h3>
              <input
                value={partSearch}
                onChange={(e) => setPartSearch(e.target.value)}
                placeholder="Part number, description…"
                className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
              {searchLoading && <p className="mt-1 text-xs text-gray-400">Searching…</p>}
              {searchResults.length > 0 && (
                <div className="mt-2">
                  <CandidateTable
                    rows={searchResults.map((part) => ({ part, badge: null, exact: false }))}
                    onPick={(part) => void decide(matchLine, part.id, 'MATCHED')}
                    saving={saving}
                  />
                </div>
              )}
            </div>

            <div className="flex flex-wrap items-center justify-between gap-2 border-t border-gray-100 pt-4">
              <div className="flex flex-wrap gap-2">
                <button
                  onClick={() => void decide(matchLine, null, 'PROVIDED')}
                  disabled={saving}
                  className="rounded-lg border border-blue-200 px-3 py-1.5 text-sm font-medium text-blue-700 hover:bg-blue-50 disabled:opacity-50"
                  title="An uncatalogued commodity you already have — a resistor from the drawer"
                >
                  Assume provided
                </button>
                <button
                  onClick={() => void decide(matchLine, null, 'EXCLUDED')}
                  disabled={saving}
                  className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
                  title="Deliberately not fitted"
                >
                  Exclude
                </button>
                <Link
                  to={`/quick-add?q=${encodeURIComponent(matchLine.mpn || matchLine.value || '')}`}
                  className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50"
                >
                  Add to catalogue
                </Link>
              </div>
              <button onClick={() => setMatchLine(null)} className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50">
                Close
              </button>
            </div>
          </div>
        )}
      </Modal>

      {/* Apply result */}
      <Modal open={applyResult !== null} onClose={() => { setApplyResult(null); void load(); }} title="Applied to project BOM">
        {applyResult && (
          <div className="space-y-4 text-sm">
            <ul className="space-y-1 text-gray-700">
              <li>{applyResult.created} part{applyResult.created === 1 ? '' : 's'} added to the project BOM</li>
              <li>{applyResult.updated} quantit{applyResult.updated === 1 ? 'y' : 'ies'} updated</li>
              <li>{applyResult.unchanged} already correct</li>
            </ul>
            {(applyResult.skippedUnmatched > 0 || applyResult.skippedProvided > 0 || applyResult.skippedExcluded > 0) && (
              <p className="rounded-lg bg-gray-50 px-3 py-2 text-xs text-gray-600">
                Skipped: {applyResult.skippedUnmatched} not matched, {applyResult.skippedProvided} provided,
                {' '}{applyResult.skippedExcluded} excluded.
              </p>
            )}
            {applyResult.unaccountedProjectParts > 0 && (
              <p className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">
                {applyResult.unaccountedProjectParts} part
                {applyResult.unaccountedProjectParts === 1 ? '' : 's'} in the project BOM
                {applyResult.unaccountedProjectParts === 1 ? ' is' : ' are'} not in this file. They
                were left alone — remove them on the project page if they no longer belong.
              </p>
            )}
            <div className="flex justify-end gap-2">
              <Link to={`/projects/${projectId}`} className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700">
                Open the project
              </Link>
            </div>
          </div>
        )}
      </Modal>

      <Modal open={confirmDelete} onClose={() => setConfirmDelete(false)} title="Delete the imported BOM?">
        <div className="space-y-4 text-sm text-gray-700">
          <p>
            This removes the uploaded file and every line, including all matching decisions. Parts
            already applied to the project BOM stay where they are.
          </p>
          <div className="flex justify-end gap-2">
            <button onClick={() => setConfirmDelete(false)} className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50">
              Cancel
            </button>
            <button onClick={handleDelete} className="rounded-lg bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700">
              Delete
            </button>
          </div>
        </div>
      </Modal>
    </div>
  );
}

// ── Bits ────────────────────────────────────────────────────────────────────

function EmptyState({ onImport, busy }: { onImport: () => void; busy: boolean }) {
  return (
    <div className="rounded-lg border border-dashed border-gray-300 bg-surface px-6 py-16 text-center">
      <h1 className="text-lg font-semibold text-gray-900">No BOM imported yet</h1>
      <p className="mx-auto mt-2 max-w-lg text-sm text-gray-500">
        Upload the CSV your EDA tool exports — KiCad, Eagle, Altium or a distributor's — and match
        its lines to parts in the catalogue. The columns are detected for you and you can correct
        them before anything is stored.
      </p>
      <button
        onClick={onImport}
        disabled={busy}
        className="mt-6 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
      >
        Import a BOM file
      </button>
    </div>
  );
}

function Pill({ label, color }: { label: string; color: string }) {
  return <span className={`inline-flex rounded-full px-2.5 py-0.5 text-xs font-medium ${color}`}>{label}</span>;
}

function Th({ children, className = '' }: { children?: React.ReactNode; className?: string }) {
  return (
    <th className={`px-4 py-3 text-left text-xs font-medium uppercase tracking-wider text-gray-500 ${className}`}>
      {children}
    </th>
  );
}

function Field({ label, value }: { label: string; value?: string | null }) {
  return (
    <div>
      <dt className="text-xs uppercase tracking-wide text-gray-400">{label}</dt>
      <dd className="text-gray-800">{value || '—'}</dd>
    </div>
  );
}

function CandidateTable({
  rows,
  onPick,
  saving,
}: {
  rows: { part: Part; badge: string | null; exact: boolean }[];
  onPick: (part: Part) => void;
  saving: boolean;
}) {
  return (
    <div className="max-h-56 overflow-auto rounded-lg border border-gray-200">
      <table className="min-w-full divide-y divide-gray-100">
        <tbody className="divide-y divide-gray-50">
          {rows.map(({ part, badge, exact }) => (
            <tr key={part.id} className="hover:bg-blue-50">
              <td className="px-3 py-2 text-sm">
                <span className="font-medium text-gray-900">{part.partNumber}</span>
                {badge && (
                  <span
                    className={`ml-2 rounded px-1.5 py-0.5 text-[10px] font-medium ${
                      exact ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'
                    }`}
                  >
                    {badge}
                  </span>
                )}
                <div className="max-w-md truncate text-xs text-gray-500">{part.description ?? '—'}</div>
              </td>
              <td className="whitespace-nowrap px-3 py-2 text-right text-sm">
                <span className={(part.totalQuantity ?? 0) > 0 ? 'font-medium text-green-700' : 'text-gray-400'}>
                  {part.totalQuantity ?? 0} on hand
                </span>
              </td>
              <td className="px-3 py-2 text-right">
                <button
                  onClick={() => onPick(part)}
                  disabled={saving}
                  className="rounded-lg bg-blue-600 px-2.5 py-1 text-xs font-medium text-white hover:bg-blue-700 disabled:opacity-50"
                >
                  Use
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function shortfall(line: ImportedBomLine) {
  return line.status === 'MATCHED' && (line.onHand ?? 0) < line.totalNeeded;
}

/**
 * Folds one updated line back into the loaded BOM and recomputes the header counts, so a decision
 * shows immediately without refetching the whole BOM after every click.
 */
function recount(bom: ImportedBom, updated: ImportedBomLine): Partial<ImportedBom> {
  const lines = bom.lines.map((l) => (l.id === updated.id ? updated : l));
  const count = (status: BomLineStatus) => lines.filter((l) => l.status === status).length;
  return {
    lines,
    matchedCount: count('MATCHED'),
    unmatchedCount: count('UNMATCHED'),
    providedCount: count('PROVIDED'),
    excludedCount: count('EXCLUDED'),
    changedCount: lines.filter((l) => l.changed).length,
  };
}
