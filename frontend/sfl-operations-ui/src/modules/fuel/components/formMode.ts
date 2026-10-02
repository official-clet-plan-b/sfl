import { createContext, useContext } from 'react';

/**
 * Whether the fields around it should say so when they are optional.
 *
 * The design marks every required field with an asterisk and every other field "(Optional)", but only
 * inside a form: a filter in a table header is optional by nature and says nothing. So the form
 * shell turns this on and the fields read it, rather than every caller passing a flag per field.
 */
export const FormModeContext = createContext({ markOptional: false });

export const useFormMode = () => useContext(FormModeContext);
