import { useEffect, useId, useRef, useState } from 'react';
import {
  fetchPlaceSuggestions,
  newSessionToken,
  placesConfigured,
  type PlaceSuggestion,
} from 'shared/places/openStreetMapPlaces';
import { TextInput } from './fields';

/**
 * A place, chosen rather than typed.
 *
 * <p>Origin and destination were free text on both the trip form and the driver logbook, which meant
 * "Kumasi Centre", "Kumasi centre", "KUMASI EXAM CTR" and "Kumasi" were four different places as far
 * as any report was concerned. Picking from Places makes them one string, spelt the same way every
 * time, which is what makes route reporting possible at all.
 *
 * <h2>It degrades, deliberately and quietly</h2>
 *
 * <p>The field is a normal text input that happens to offer suggestions. With no key configured, with
 * Google blocked or unreachable, or with the API disabled, the suggestions simply never arrive and
 * what remains is exactly the field that was there before - including the recent-value list, which
 * keeps working offline because it is stored in the browser.
 *
 * <p>That is a requirement, not politeness. A driver recording fuel at a station with no signal has to
 * be able to say where they are, and an examination centre must keep operating through WAN loss
 * (SRS §2.5, §23.3). A picker that refused free text would make the platform less usable in exactly
 * the conditions it is meant to survive.
 *
 * <h2>What is stored</h2>
 *
 * <p>The formatted description, as a string, because that is what the services accept - `origin` and
 * `destination` are `VARCHAR(200)`. The `placeId` and coordinates are deliberately *not* kept: there
 * is nowhere to put them without a migration, and inventing a column for data nothing reads yet would
 * be worse than waiting. When route mapping or geofencing arrives (S167 telematics, Phase 2), that is
 * the moment to add them, and this component already has the id in hand.
 */

interface PlaceFieldProps {
  label: string;
  value: string;
  onChange: (value: string) => void;
  /** Offline fallback: values this operator typed before, at this site. */
  recent?: string[];
  required?: boolean;
  error?: boolean;
  helperText?: string;
  onBlur?: () => void;
  disabled?: boolean;
  className?: string;
}

const PlaceField = ({
  label,
  value,
  onChange,
  recent,
  required,
  error,
  helperText,
  onBlur,
  disabled,
  className,
}: PlaceFieldProps) => {
  const listboxId = useId();
  const [suggestions, setSuggestions] = useState<PlaceSuggestion[]>([]);
  const [open, setOpen] = useState(false);
  /**
   * Why suggestions are not coming, when they are not.
   *
   * <p>Without this the field is indistinguishable from one that is working and finding nothing,
   * which is precisely how a project with the Places API switched off went unnoticed: the user types,
   * nothing appears, and there is no way to tell that from an unrecognised street name.
   */
  const [unavailable, setUnavailable] = useState<string | null>(null);
  const session = useRef<unknown>(null);
  /** What the user last typed, so a fetch that resolves late cannot overwrite a newer query. */
  const latestQuery = useRef('');

  useEffect(() => {
    if (disabled) {
      return undefined;
    }
    if (!placesConfigured()) {
      // Deliberately not surfaced: an unset key is the documented offline posture, not a fault.
      return undefined;
    }
    latestQuery.current = value;
    if (value.trim().length < 3) {
      setSuggestions([]);
      return undefined;
    }
    // Debounced: Places bills per request, and a keystroke is not a question worth asking.
    const timer = setTimeout(() => {
      const asked = value;
      void (async () => {
        if (!session.current) {
          session.current = await newSessionToken();
        }
        const found = await fetchPlaceSuggestions(asked, session.current);
        // Discard a stale answer rather than flashing the wrong list.
        if (latestQuery.current === asked) {
          setSuggestions(found.suggestions);
          setUnavailable(found.unavailable);
        }
      })();
    }, 300);
    return () => clearTimeout(timer);
  }, [value, disabled]);

  const choose = (suggestion: PlaceSuggestion) => {
    onChange(suggestion.description);
    setSuggestions([]);
    setOpen(false);
    // The token is spent on selection; the next place starts a new billing session.
    session.current = null;
  };

  const showList = open && suggestions.length > 0;

  return (
    <div className={className}>
      <div className="relative">
        <TextInput
          label={label}
          required={required}
          value={value}
          onChange={(next) => {
            onChange(next);
            setOpen(true);
          }}
          error={error}
          disabled={disabled}
          // The recent list stays as the datalist. It is the offline answer, and it costs nothing.
          suggestions={recent}
          /*
            One short, non-technical line, and never the reason.

            It used to read "the Places API is not enabled for this key's Google Cloud project",
            which is true, useful, and addressed to entirely the wrong person: the driver reading it
            at a pump cannot enable anything, and naming an internal service in a form field turns a
            minor degradation into something that looks broken. The diagnosis still exists in full -
            it goes to the console once, where whoever fixes the key will look.
          */
          helperText={
            unavailable
              ? 'Suggestions are unavailable right now. Type the place - it will be saved as you enter it.'
              : helperText
          }
          onBlur={() => {
            // Delayed so a click on a suggestion lands before the list closes underneath it.
            setTimeout(() => setOpen(false), 150);
            onBlur?.();
          }}
        />
        {showList && (
          <ul
            id={listboxId}
            role="listbox"
            className="absolute z-50 mt-1 max-h-64 w-full overflow-y-auto rounded-lg border border-gray-200 bg-white py-1 shadow-theme-lg"
          >
            {suggestions.map((suggestion) => (
              <li key={suggestion.placeId || suggestion.description} role="option" aria-selected="false">
                <button
                  type="button"
                  // `onMouseDown` rather than `onClick`: blur fires first on a click and would close
                  // the list before the selection was registered.
                  onMouseDown={(event) => {
                    event.preventDefault();
                    choose(suggestion);
                  }}
                  className="block w-full px-3 py-2 text-left hover:bg-gray-50"
                >
                  <span className="block truncate text-theme-sm text-gray-900">{suggestion.primary}</span>
                  {suggestion.secondary && (
                    <span className="block truncate text-theme-xs text-gray-500">
                      {suggestion.secondary}
                    </span>
                  )}
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
};

export default PlaceField;
