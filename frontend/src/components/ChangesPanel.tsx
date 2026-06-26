interface Props {
  html: string;
  latestDate: string;
  onMarkRead: () => void;
  onClose: () => void;
}

const closeIcon = (
  <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor"
       strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round">
    <path d="M18 6 6 18M6 6l12 12" />
  </svg>
);

const bellIcon = (
  <svg viewBox="0 0 24 24" className="h-5 w-5 shrink-0" fill="none" stroke="currentColor"
       strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round">
    <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" />
    <path d="M13.73 21a2 2 0 0 1-3.46 0" />
  </svg>
);

export default function ChangesPanel({ html, latestDate, onMarkRead, onClose }: Props) {
  return (
    <div className="fixed inset-0 z-50 flex justify-end" role="dialog" aria-modal="true" aria-label="What's New">
      {/* Backdrop */}
      <div
        className="absolute inset-0 bg-black/40 backdrop-blur-[1px]"
        onClick={onClose}
        aria-hidden="true"
      />

      {/* Drawer */}
      <div className="relative flex h-full w-[520px] max-w-full flex-col bg-white shadow-2xl">
        {/* Header */}
        <div className="flex items-center gap-3 border-b border-gray-200 px-5 py-4">
          <span className="text-blue-500">{bellIcon}</span>
          <div className="flex-1 min-w-0">
            <h2 className="text-sm font-semibold text-gray-900">What's New</h2>
            <p className="text-xs text-gray-500">Updates since your last visit</p>
          </div>
          <button
            onClick={onClose}
            className="rounded-md p-1.5 text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600"
            aria-label="Close"
          >
            {closeIcon}
          </button>
        </div>

        {/* Scrollable content */}
        <div
          className="changelog-body flex-1 overflow-y-auto px-5 py-4 text-sm text-gray-700"
          // Content comes from trusted admin-authored files on the server.
          dangerouslySetInnerHTML={{ __html: html }}
        />

        {/* Footer */}
        <div className="flex items-center justify-between border-t border-gray-200 px-5 py-3">
          <p className="text-xs text-gray-400">Latest: {latestDate}</p>
          <div className="flex gap-2">
            <button
              onClick={onClose}
              className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-xs font-medium text-gray-700 transition-colors hover:bg-gray-50"
            >
              Close
            </button>
            <button
              onClick={onMarkRead}
              className="rounded-md bg-blue-600 px-3 py-1.5 text-xs font-medium text-white transition-colors hover:bg-blue-700"
            >
              Mark as read
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
