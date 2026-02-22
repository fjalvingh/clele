import { Dialog, DialogPanel, DialogTitle } from '@headlessui/react';
import { type ReactNode } from 'react';

interface ModalProps {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
}

export default function Modal({ open, onClose, title, children }: ModalProps) {
  return (
    <Dialog open={open} onClose={onClose} className="relative z-50">
      <div className="fixed inset-0 bg-black/40" aria-hidden="true" />
      <div className="fixed inset-0 flex items-center justify-center p-4">
        <DialogPanel className="w-full max-w-lg rounded-xl bg-white shadow-2xl">
          <div className="flex items-center justify-between border-b px-6 py-4">
            <DialogTitle className="text-lg font-semibold text-gray-900">{title}</DialogTitle>
            <button
              onClick={onClose}
              className="text-gray-400 hover:text-gray-600 text-xl font-bold leading-none"
            >
              ×
            </button>
          </div>
          <div className="px-6 py-4">{children}</div>
        </DialogPanel>
      </div>
    </Dialog>
  );
}
