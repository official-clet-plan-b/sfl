import { createContext, type ReactNode, useContext, useEffect, useMemo, useState } from 'react';

const STORAGE_KEY = 'sfl-font-scale';
const MIN_SCALE = 1;
const MAX_SCALE = 1.5;
const STEP = 0.1;

interface SystemPreferencesValue {
  fontScale: number;
  setFontScale: (scale: number) => void;
  resetFontScale: () => void;
}

const PreferencesContext = createContext<SystemPreferencesValue | null>(null);

const clamp = (value: number): number =>
  Math.min(MAX_SCALE, Math.max(MIN_SCALE, Math.round(value / STEP) * STEP));

const initialScale = (): number => {
  if (typeof window === 'undefined') return MIN_SCALE;
  const stored = Number(window.localStorage.getItem(STORAGE_KEY));
  return Number.isFinite(stored) ? clamp(stored) : MIN_SCALE;
};

export const SystemPreferencesProvider = ({ children }: { children: ReactNode }) => {
  const [fontScale, setFontScaleState] = useState(initialScale);

  useEffect(() => {
    document.documentElement.style.setProperty('--clet-font-scale', String(fontScale));
    document.documentElement.style.setProperty('--clet-text-scale', String(fontScale));
    document.documentElement.dataset.fontScale = String(fontScale);
    window.localStorage.setItem(STORAGE_KEY, String(fontScale));
  }, [fontScale]);

  const value = useMemo<SystemPreferencesValue>(
    () => ({
      fontScale,
      setFontScale: (scale) => setFontScaleState(clamp(scale)),
      resetFontScale: () => setFontScaleState(MIN_SCALE),
    }),
    [fontScale],
  );

  return <PreferencesContext.Provider value={value}>{children}</PreferencesContext.Provider>;
};

export const useSystemPreferences = (): SystemPreferencesValue => {
  const value = useContext(PreferencesContext);
  if (!value) throw new Error('useSystemPreferences must be used inside SystemPreferencesProvider');
  return value;
};

export const FONT_SCALE_MIN = MIN_SCALE;
export const FONT_SCALE_MAX = MAX_SCALE;
export const FONT_SCALE_STEP = STEP;
