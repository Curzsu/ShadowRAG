import { computed, reactive, ref } from 'vue';
import { useRoute } from 'vue-router';
import { defineStore } from 'pinia';
import { useLoading } from '@sa/hooks';
import { fetchGetUserInfo, fetchLogin, fetchLogout } from '@/service/api';
import { getAuthorization } from '@/service/request/shared';
import { useRouterPush } from '@/hooks/common/router';
import { localStg } from '@/utils/storage';
import { SetupStoreId } from '@/enum';
import { $t } from '@/locales';
import { useRouteStore } from '../route';
import { useTabStore } from '../tab';
import { clearAuthStorage, getToken } from './shared';
import { createAuthActions, createLoginEpoch } from './auth-session';

export const useAuthStore = defineStore(SetupStoreId.Auth, () => {
  const route = useRoute();
  const routeStore = useRouteStore();
  const tabStore = useTabStore();
  const { toLogin, redirectFromLogin } = useRouterPush(false);
  const { loading: loginLoading, startLoading, endLoading } = useLoading();

  const token = ref(getToken());
  const loginEpoch = createLoginEpoch();
  const getLoginEpoch = loginEpoch.current;

  const userInfo: Api.Auth.UserInfo = reactive({
    id: 0,
    username: '',
    role: 'USER',
    orgTags: [],
    primaryOrg: ''
  });

  const isAdmin = computed(() => userInfo.role === 'ADMIN');

  /** is super role in static route */
  const isStaticSuper = computed(() => {
    const { VITE_AUTH_ROUTE_MODE, VITE_STATIC_SUPER_ROLE } = import.meta.env;

    return VITE_AUTH_ROUTE_MODE === 'static' && userInfo.role === VITE_STATIC_SUPER_ROLE;
  });

  /** Is login */
  const isLogin = computed(() => Boolean(token.value));

  /** Reset auth store */
  async function resetStore() {
    useChatStore().disposeActiveRequest('logout');
    const epoch = loginEpoch.advance();
    const authStore = useAuthStore();

    recordUserId();

    clearAuthStorage();

    authStore.$reset();
    // The reset plugin may restore a startup token; logout always clears it explicitly.
    token.value = '';

    if (!route.meta.constant) {
      await toLogin();
    }

    if (epoch !== getLoginEpoch()) return;
    tabStore.cacheTabs();
    routeStore.resetStore();
  }

  /** Record the user ID of the previous login session Used to compare with the current user ID on next login */
  function recordUserId() {
    if (!userInfo.id) {
      return;
    }

    // Store current user ID locally for next login comparison
    localStg.set('lastLoginUserId', userInfo.id);
  }

  /**
   * Check if current login user is different from previous login user If different, clear all tabs
   *
   * @returns {boolean} Whether to clear all tabs
   */
  function checkTabClear(): boolean {
    if (!userInfo.id) {
      return false;
    }

    const lastLoginUserId = localStg.get('lastLoginUserId');

    // Clear all tabs if current user is different from previous user
    if (!lastLoginUserId || lastLoginUserId !== userInfo.id) {
      localStg.remove('globalTabs');
      tabStore.clearTabs();

      localStg.remove('lastLoginUserId');
      return true;
    }

    localStg.remove('lastLoginUserId');
    return false;
  }

  const { loginByToken, getUserInfo } = createAuthActions<Api.Auth.UserInfo>({
    getEpoch: getLoginEpoch,
    fetchUserInfo: fetchGetUserInfo,
    applyUserInfo: info => {
      Object.assign(userInfo, info);
    },
    saveCredentials: credentials => {
      localStg.set('token', credentials.token);
      localStg.set('refreshToken', credentials.refreshToken);
    },
    readToken: getToken,
    applyToken: setToken
  });

  /**
   * Login
   *
   * @param userName User name
   * @param password Password
   * @param [redirect=true] Whether to redirect after login. Default is `true`
   */
  async function login(userName: string, password: string, redirect = true) {
    useChatStore().disposeActiveRequest('logout');
    const epoch = loginEpoch.advance();
    startLoading();

    const { data: loginToken, error } = await fetchLogin(userName, password);
    if (epoch !== getLoginEpoch()) return;

    if (!error) {
      const pass = await loginByToken(loginToken, epoch);
      if (epoch !== getLoginEpoch()) return;

      if (pass) {
        // Check if the tab needs to be cleared
        const isClear = checkTabClear();
        let needRedirect = redirect;

        if (isClear) {
          // If the tab needs to be cleared,it means we don't need to redirect.
          needRedirect = false;
        }
        await redirectFromLogin(needRedirect);
        if (epoch !== getLoginEpoch()) return;

        window.$notification?.success({
          title: $t('page.login.common.loginSuccess'),
          content: $t('page.login.common.welcomeBack', { userName: userInfo.username }),
          duration: 4500
        });
      }
    } else {
      resetStore();
    }

    endLoading();
  }

  async function initUserInfo() {
    const epoch = getLoginEpoch();
    const hasToken = getToken();

    if (hasToken) {
      const pass = await getUserInfo();

      if (!pass && epoch === getLoginEpoch()) {
        resetStore();
      }
    }
  }

  /** Set token (used for seamless token refresh) */
  function setToken(newToken: string) {
    token.value = newToken;
    localStg.set('token', newToken);
  }

  async function logout() {
    const context = { authorization: getAuthorization(), epoch: getLoginEpoch() };
    useChatStore().disposeActiveRequest('logout');
    const pending = fetchLogout(context);
    resetStore();
    useKnowledgeBaseStore().$reset();
    await pending;
  }

  return {
    token,
    userInfo,
    isStaticSuper,
    isLogin,
    isAdmin,
    loginLoading,
    resetStore,
    login,
    logout,
    initUserInfo,
    setToken,
    getLoginEpoch
  };
});
