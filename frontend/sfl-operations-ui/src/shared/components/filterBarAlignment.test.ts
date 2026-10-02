import { describe, expect, it } from 'vitest';

/**
 * Every `FilterBar` child must carry a label, or reserve the height of one.
 *
 * <h2>Why this is a source scan and not a render test</h2>
 *
 * <p>The defect is one rule broken at N call sites, and it does not show up in the component that
 * owns the rule - `FilterBar` renders perfectly well either way. It shows up on whichever page
 * happened to reach for a bare `Select`. A render test would have to mount all thirty-seven register
 * screens with their queries stubbed to catch what a scan catches by reading them, and it would
 * still only cover the pages somebody remembered to add.
 *
 * <h2>The rule</h2>
 *
 * <p>`FilterBar` lays its children out with `items-start`, so every cell's *top* edge lines up. A
 * cell's height is label + control, so a control that renders no label line sits a label's height
 * above its neighbours and the row of controls an operator reads comes out ragged. Four bars had
 * this: the asset register (three bare selects), device references, the audit trail and bookable
 * resources.
 *
 * <p>Two ways to satisfy it. A **label**, which is preferred and is what all four were fixed with -
 * an operator should not have to open a dropdown to learn what it filters. Or a
 * **`FieldLabelSpacer`**, for a control where a label genuinely does not belong, which is what
 * `FacetFilter` and the bare `Button` in the fuel policies bar use.
 */

/**
 * Every page's source, read through Vite rather than through `node:fs`.
 *
 * <p>`import.meta.glob` on purpose: the application's `tsc` build compiles this file along with the
 * rest of `src`, and it has no Node type declarations - so `readFileSync` type-checks under vitest
 * and breaks `npm run build`. Going through the bundler's own module graph needs no `@types/node`
 * and no second tsconfig, and it reads exactly the files Vite would ship.
 */
const sources = import.meta.glob('/src/**/*.tsx', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>;

/** Components that render their own label line, so they need no help. */
const SELF_LABELLING =
  /^(SiteSelect|SelectInput|EnumSelect|TextInput|NumberInput|TextAreaInput|DateField|DateTimeField|SearchInput|FieldShell|FacetFilter|DriverSelect|VehicleSelect|SpacePicker|FloorPicker)$/;

const pages = Object.entries(sources).filter(([path]) => !path.endsWith('.test.tsx'));

/** Index of the `>` closing the JSX tag opened at `from`, ignoring `>` inside braces and strings. */
const tagEnd = (source: string, from: number): number => {
  let depth = 0;
  for (let index = from; index < source.length; index += 1) {
    const character = source[index];
    if (character === '{') {
      depth += 1;
    } else if (character === '}') {
      depth -= 1;
    } else if (character === "'" || character === '"' || character === '`') {
      index += 1;
      while (index < source.length && source[index] !== character) {
        index += source[index] === '\\' ? 2 : 1;
      }
    } else if (character === '>' && depth === 0) {
      return index;
    }
  }
  return source.length - 1;
};

interface Child {
  tag: string;
  labelled: boolean;
}

/** The direct children of every `<FilterBar>` in one file. */
const filterBarChildren = (source: string): Child[][] => {
  const bars: Child[][] = [];
  let start = source.indexOf('<FilterBar');
  while (start >= 0) {
    const open = tagEnd(source, start);
    const close = source.indexOf('</FilterBar>', open);
    if (close < 0) {
      break;
    }
    const body = source.slice(open + 1, close);
    const children: Child[] = [];
    let depth = 0;
    let spacerPending = false;

    for (let index = 0; index < body.length; index += 1) {
      const character = body[index];
      if (character === '{') {
        depth += 1;
      } else if (character === '}') {
        depth -= 1;
      } else if (character === '<' && depth === 0) {
        const name = /^<([A-Z][A-Za-z0-9]*)\b/.exec(body.slice(index, index + 40));
        if (!name) {
          continue;
        }
        const end = tagEnd(body, index);
        const props = body.slice(index, end + 1);
        if (name[1] === 'FieldLabelSpacer') {
          // A spacer applies to the control it precedes inside the same cell.
          spacerPending = true;
        } else {
          children.push({
            tag: name[1],
            labelled: SELF_LABELLING.test(name[1]) || /\blabel\s*=/.test(props) || spacerPending,
          });
          spacerPending = false;
        }
        index = body[end - 1] === '/' ? end : (body.indexOf(`</${name[1]}>`, end) ?? end);
        if (index < 0) {
          index = end;
        }
      }
    }
    bars.push(children);
    start = source.indexOf('<FilterBar', close);
  }
  return bars;
};

describe('every FilterBar control carries a label or reserves the height of one', () => {
  const bars = pages.flatMap(([path, source]) =>
    filterBarChildren(source).map((children) => ({ path, children })),
  );

  it('finds no unlabelled control in any filter bar in the application', () => {
    const offenders = bars.flatMap(({ path, children }) =>
      children.filter((child) => !child.labelled).map((child) => `${path} → <${child.tag}>`),
    );
    expect(offenders).toEqual([]);
  });

  it('is actually reading filter bars, so an empty result means clean rather than nothing scanned', () => {
    // The library migration moved most registers to TableFilter. If this ever drops to zero the
    // scan has broken, and a broken scan reports the same green as a clean application.
    expect(bars.length).toBeGreaterThan(0);
  });

  it('would catch a bare Select, which is the shape the four ragged bars had', () => {
    const regressed = filterBarChildren(
      '<FilterBar>\n  <SiteSelect value={site} onChange={setSite} />\n' +
        '  <Select value={type} onChange={setType} placeholder="Any type" options={options} />\n' +
        '</FilterBar>',
    );
    expect(regressed[0].map((child) => child.labelled)).toEqual([true, false]);
  });
});
