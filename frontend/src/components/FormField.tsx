import { type InputHTMLAttributes, type SelectHTMLAttributes, type TextareaHTMLAttributes } from 'react';

interface BaseProps {
  label: string;
  error?: string;
}

type InputProps = BaseProps & InputHTMLAttributes<HTMLInputElement> & { as?: 'input' };
type TextareaProps = BaseProps & TextareaHTMLAttributes<HTMLTextAreaElement> & { as: 'textarea' };
type SelectProps = BaseProps & SelectHTMLAttributes<HTMLSelectElement> & {
  as: 'select';
  children: React.ReactNode;
};

type FormFieldProps = InputProps | TextareaProps | SelectProps;

const inputClass =
  'mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500';
const errorClass = 'mt-1 text-xs text-red-600';

export default function FormField(props: FormFieldProps) {
  const { label, error, as = 'input', ...rest } = props;

  return (
    <div className="mb-4">
      <label className="block text-sm font-medium text-gray-700">{label}</label>
      {as === 'textarea' ? (
        <textarea
          className={inputClass}
          {...(rest as TextareaHTMLAttributes<HTMLTextAreaElement>)}
        />
      ) : as === 'select' ? (
        <select
          className={inputClass}
          {...(rest as SelectHTMLAttributes<HTMLSelectElement>)}
        >
          {(props as SelectProps).children}
        </select>
      ) : (
        <input className={inputClass} {...(rest as InputHTMLAttributes<HTMLInputElement>)} />
      )}
      {error && <p className={errorClass}>{error}</p>}
    </div>
  );
}
