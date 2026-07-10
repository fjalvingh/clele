import { useEffect, useRef, useState } from 'react';
import { searchTags } from '../api';
import type { Tag } from '../api/types';

interface Props {
  value: string[];
  onChange: (tags: string[]) => void;
}

const normalize = (name: string) => name.trim().replace(/\s+/g, ' ');

export default function TagInput({ value, onChange }: Props) {
  const [input, setInput] = useState('');
  const [suggestions, setSuggestions] = useState<Tag[]>([]);
  const [showSuggestions, setShowSuggestions] = useState(false);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Only offer suggestions once the user has typed enough to narrow things down.
  useEffect(() => {
    const term = input.trim();
    if (debounceRef.current) clearTimeout(debounceRef.current);
    if (term.length < 3) {
      setSuggestions([]);
      return;
    }
    debounceRef.current = setTimeout(() => {
      searchTags(term).then(setSuggestions).catch(() => setSuggestions([]));
    }, 200);
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, [input]);

  const addTag = (name: string) => {
    if (!value.some((t) => t.toLowerCase() === name.toLowerCase())) {
      onChange([...value, name]);
    }
    setInput('');
    setSuggestions([]);
    setShowSuggestions(false);
  };

  const removeTag = (name: string) => {
    onChange(value.filter((t) => t !== name));
  };

  // Commit whatever's typed: reuse the existing tag's casing if one already matches
  // case-insensitively, otherwise confirm before creating a brand-new tag.
  const commit = async (raw: string) => {
    const name = normalize(raw);
    if (!name) return;
    const known = suggestions.find((t) => t.name.toLowerCase() === name.toLowerCase());
    if (known) {
      addTag(known.name);
      return;
    }
    let exact: Tag | undefined;
    try {
      const matches = await searchTags(name);
      exact = matches.find((t) => t.name.toLowerCase() === name.toLowerCase());
    } catch {
      exact = undefined;
    }
    if (exact) {
      addTag(exact.name);
      return;
    }
    if (confirm(`Tag "${name}" doesn't exist yet. Create it?`)) {
      addTag(name);
    } else {
      setInput('');
      setShowSuggestions(false);
    }
  };

  const visibleSuggestions = suggestions.filter(
    (t) => !value.some((v) => v.toLowerCase() === t.name.toLowerCase())
  );

  return (
    <div className="mb-4">
      <label className="block text-sm font-medium text-gray-700">Tags</label>
      <div className="mt-1 flex flex-wrap items-center gap-2 rounded-md border border-gray-300 p-2 focus-within:border-blue-500 focus-within:ring-1 focus-within:ring-blue-500">
        {value.map((tag) => (
          <span
            key={tag}
            className="inline-flex items-center gap-1 rounded-full bg-blue-50 px-2 py-0.5 text-xs font-medium text-blue-700 ring-1 ring-inset ring-blue-600/20"
          >
            {tag}
            <button
              type="button"
              onClick={() => removeTag(tag)}
              aria-label={`Remove tag ${tag}`}
              className="text-blue-400 hover:text-blue-700"
            >
              ×
            </button>
          </span>
        ))}
        <div className="relative min-w-[8rem] flex-1">
          <input
            type="text"
            value={input}
            onChange={(e) => {
              setInput(e.target.value);
              setShowSuggestions(true);
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                commit(input);
              } else if (e.key === 'Backspace' && input === '' && value.length > 0) {
                removeTag(value[value.length - 1]);
              }
            }}
            onFocus={() => setShowSuggestions(true)}
            onBlur={() => setTimeout(() => setShowSuggestions(false), 150)}
            placeholder="Add a tag…"
            className="block w-full border-0 p-0.5 text-sm focus:outline-none focus:ring-0"
          />
          {showSuggestions && visibleSuggestions.length > 0 && (
            <ul className="absolute z-10 mt-1 max-h-40 w-full min-w-[10rem] overflow-y-auto rounded-md border border-gray-200 bg-white shadow-lg">
              {visibleSuggestions.map((t) => (
                <li key={t.id}>
                  <button
                    type="button"
                    onMouseDown={(e) => e.preventDefault()}
                    onClick={() => addTag(t.name)}
                    className="block w-full px-3 py-1.5 text-left text-sm hover:bg-gray-50"
                  >
                    {t.name}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </div>
  );
}
