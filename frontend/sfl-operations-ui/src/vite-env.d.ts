/// <reference types="vite/client" />

/** Set by Vite at build time (see `vite.config.ts`) so the running bundle can identify itself. */
declare const __BUILD_STAMP__: string;

interface ImportMetaEnv {
  readonly VITE_BASENAME?: string;
  readonly VITE_AUTH_PROVIDER?: string;
  readonly VITE_APP_PORT?: string;
  readonly VITE_FLEET_API_BASE_URL?: string;
  readonly VITE_SAFETY_SECURITY_API_BASE_URL?: string;
  readonly VITE_SFL_USER?: string;
  readonly VITE_SFL_DISPLAY_NAME?: string;
  readonly VITE_SFL_ROLES?: string;
  readonly VITE_SFL_SITES?: string;
  readonly VITE_SFL_PROGRAMMES?: string;
  readonly VITE_FLEET_DEV_FALLBACK?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
