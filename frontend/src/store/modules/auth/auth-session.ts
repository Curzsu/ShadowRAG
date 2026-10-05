export interface LoginCredentials {
  token: string;
  refreshToken: string;
}

/** Identity is a closure, so resetting reactive stores cannot reuse an old epoch. */
export function createLoginEpoch() {
  let value = 0;
  return {
    current: () => value,
    advance: () => {
      value += 1;
      return value;
    }
  };
}

export function createAuthResponseGuard(getEpoch: () => number) {
  const captured = new WeakMap<object, number>();
  return {
    capture: (config: object, epoch = getEpoch()) => {
      captured.set(config, epoch);
    },
    isCurrent: (config?: object) => Boolean(config && captured.get(config) === getEpoch()),
    handleHttpAuthFailure: (config: object | undefined, status: number | undefined, reset: () => void) => {
      if ((status !== 401 && status !== 403) || !config || captured.get(config) !== getEpoch()) return false;
      reset();
      return true;
    }
  };
}

interface AuthDependencies<T> {
  getEpoch: () => number;
  fetchUserInfo: () => Promise<{ data: T | null; error: unknown }>;
  applyUserInfo: (info: T) => void;
  saveCredentials: (credentials: LoginCredentials) => void;
  readToken: () => string;
  applyToken: (token: string) => void;
}

export function createAuthActions<T>(deps: AuthDependencies<T>) {
  async function getUserInfo(epoch = deps.getEpoch()) {
    if (epoch !== deps.getEpoch()) return false;
    const { data, error } = await deps.fetchUserInfo();
    if (epoch !== deps.getEpoch() || error || !data) return false;
    deps.applyUserInfo(data);
    return true;
  }
  async function loginByToken(credentials: LoginCredentials, epoch = deps.getEpoch()) {
    if (epoch !== deps.getEpoch()) return false;
    deps.saveCredentials(credentials);
    const pass = await getUserInfo(epoch);
    if (!pass || epoch !== deps.getEpoch()) return false;
    deps.applyToken(deps.readToken());
    return true;
  }
  return { getUserInfo, loginByToken };
}

export interface RefreshState {
  refreshTokenFn: Promise<boolean> | null;
  refreshTokenEpoch?: number;
}

interface RefreshDependencies {
  getEpoch: () => number;
  readRefreshToken: () => string;
  fetchRefresh: (token: string) => Promise<{ data: LoginCredentials | null; error: unknown }>;
  applyCredentials: (credentials: LoginCredentials) => void;
  onFailure: () => void;
  scheduleCleanup: (callback: () => void) => void;
}

export async function refreshForLogin(state: RefreshState, deps: RefreshDependencies): Promise<boolean> {
  const epoch = deps.getEpoch();
  if (!state.refreshTokenFn || state.refreshTokenEpoch !== epoch) {
    state.refreshTokenEpoch = epoch;
    state.refreshTokenFn = (async () => {
      try {
        const { data, error } = await deps.fetchRefresh(deps.readRefreshToken());
        if (epoch !== deps.getEpoch()) return false;
        if (!error && data) {
          deps.applyCredentials(data);
          return true;
        }
      } catch {
        if (epoch !== deps.getEpoch()) return false;
      }
      deps.onFailure();
      return false;
    })();
  }
  const pending = state.refreshTokenFn;
  const success = await pending;
  deps.scheduleCleanup(() => {
    if (state.refreshTokenFn === pending && state.refreshTokenEpoch === epoch) state.refreshTokenFn = null;
  });
  return epoch === deps.getEpoch() && success;
}
