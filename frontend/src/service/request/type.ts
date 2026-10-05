import type { RefreshState } from '@/store/modules/auth/auth-session';

declare module 'axios' {
  interface AxiosRequestConfig {
    /** Local-only origin identity; never serialized into request headers. */
    authSessionEpoch?: number;
  }
}

export interface RequestInstanceState extends RefreshState {
  /** the request error message stack */
  errMsgStack: string[];
}
