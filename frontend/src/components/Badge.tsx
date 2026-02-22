interface BadgeProps {
  children: React.ReactNode;
  variant?: 'red' | 'green' | 'blue' | 'gray' | 'yellow';
}

const variantClasses: Record<string, string> = {
  red: 'bg-red-100 text-red-800',
  green: 'bg-green-100 text-green-800',
  blue: 'bg-blue-100 text-blue-800',
  gray: 'bg-gray-100 text-gray-700',
  yellow: 'bg-yellow-100 text-yellow-800',
};

export default function Badge({ children, variant = 'gray' }: BadgeProps) {
  return (
    <span className={`inline-block rounded-full px-2 py-0.5 text-xs font-medium ${variantClasses[variant]}`}>
      {children}
    </span>
  );
}
