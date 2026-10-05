import { useAuthStore } from '@/store/modules/auth';
import { refreshForLogin } from '@/store/modules/auth/auth-session';
import { localStg } from '@/utils/storage';
import { fetchRefreshToken } from '../api';
import type { RequestInstanceState } from './type';

export function getAuthorization() {
  const token = localStg.get('token');
  const Authorization = token ? `Bearer ${token}` : null;

  return Authorization;
}

export function handleExpiredRequest(state: RequestInstanceState) {
  const auth = useAuthStore();
  return refreshForLogin(state, {
    getEpoch: auth.getLoginEpoch,
    readRefreshToken: () => localStg.get('refreshToken') || '',
    fetchRefresh: fetchRefreshToken,
    applyCredentials: data => {
      auth.setToken(data.token);
      localStg.set('refreshToken', data.refreshToken);
    },
    onFailure: () => {
      auth.resetStore();
    },
    scheduleCleanup: callback => {
      setTimeout(callback, 1000);
    }
  });
}

export function showErrorMsg(state: RequestInstanceState, message: string) {
  if (!state.errMsgStack?.length) {
    state.errMsgStack = [];
  }

  const isExist = state.errMsgStack.includes(message);

  if (!isExist) {
    state.errMsgStack.push(message);

    window.$message?.error(message, {
      onLeave: () => {
        state.errMsgStack = state.errMsgStack.filter(msg => msg !== message);

        setTimeout(() => {
          state.errMsgStack = [];
        }, 5000);
      }
    });
  }
}
