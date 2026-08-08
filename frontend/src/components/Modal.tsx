import { Dialog, DialogBackdrop, DialogPanel, DialogTitle } from '@headlessui/react';
import { useEffect, type ReactNode } from 'react';

interface ModalProps {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  wide?: boolean;
  /**
   * Allow a click on the backdrop to close the dialog. Only for dialogs that show a message —
   * a dialog holding data the user has entered must never be dismissed by a stray click, so
   * this defaults to false and closing goes through the X, Cancel or Escape.
   */
  dismissable?: boolean;
}

export default function Modal({ open, onClose, title, children, wide, dismissable }: ModalProps) {
  // Escape still closes — that is a deliberate keypress, not a stray click. Headless UI routes it
  // through the same onClose we neutralise for the backdrop, so handle it ourselves.
  useEffect(() => {
    if (!open || dismissable) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [open, dismissable, onClose]);

  return (
    <Dialog open={open} onClose={dismissable ? onClose : () => {}} className="relative z-50">
      <DialogBackdrop
        transition
        className="fixed inset-0 bg-black/40 backdrop-blur-sm transition-opacity duration-200 data-[closed]:opacity-0"
      />
      <div className="fixed inset-0 flex items-center justify-center p-4">
        <DialogPanel
          transition
          className={`flex max-h-[calc(100vh-2rem)] w-full flex-col ${wide ? 'max-w-3xl' : 'max-w-lg'} rounded-2xl bg-surface shadow-2xl ring-1 ring-black/5 dark:ring-white/10 transition duration-200 ease-out data-[closed]:scale-95 data-[closed]:opacity-0`}
        >
          <div className="flex shrink-0 items-center justify-between border-b border-gray-200 px-6 py-4">
            <DialogTitle className="text-lg font-semibold text-gray-900">{title}</DialogTitle>
            <button
              onClick={onClose}
              aria-label="Close"
              className="-mr-1 flex h-8 w-8 items-center justify-center rounded-lg text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600"
            >
              <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
                <path d="m6 6 12 12M18 6 6 18" />
              </svg>
            </button>
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto px-6 py-4">{children}</div>
        </DialogPanel>
      </div>
    </Dialog>
  );
}
