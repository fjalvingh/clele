interface BadgeProps {
  children: React.ReactNode;
  variant?: 'red' | 'green' | 'blue' | 'gray' | 'yellow';
}

// Soft-tinted pill with an inset ring — reads as a status chip rather than a
// flat color block, and the ring keeps it legible on white or tinted rows.
const variantClasses: Record<string, string> = {
  red: 'bg-red-50 text-red-700 ring-red-600/20',
  green: 'bg-green-50 text-green-700 ring-green-600/20',
  blue: 'bg-blue-50 text-blue-700 ring-blue-600/20',
  gray: 'bg-gray-100 text-gray-600 ring-gray-500/20',
  yellow: 'bg-amber-50 text-amber-700 ring-amber-600/20',
};

export default function Badge({ children, variant = 'gray' }: BadgeProps) {
  return (
    <span
      className={`inline-block rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${variantClasses[variant]}`}
    >
      {children}
    </span>
  );
}
