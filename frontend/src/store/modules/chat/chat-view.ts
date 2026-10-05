interface ViewDependencies {
  getLoginEpoch: () => number;
  isLoggedIn: () => boolean;
  onHistoryLoading: (loading: boolean) => void;
}

export function createChatViewLifecycle(deps: ViewDependencies) {
  let view = 0;
  let history: object | undefined;
  let creation: { view: number; login: number; pending: Promise<void> } | undefined;
  function capture() {
    const origin = view;
    const login = deps.getLoginEpoch();
    return () => origin === view && login === deps.getLoginEpoch() && deps.isLoggedIn();
  }
  function invalidate() {
    view += 1;
    history = undefined;
    deps.onHistoryLoading(false);
  }
  async function loadHistory<T>(load: () => Promise<T>, apply: (value: T) => void) {
    const identity = {};
    const current = capture();
    history = identity;
    deps.onHistoryLoading(true);
    try {
      const value = await load();
      if (current() && history === identity) apply(value);
    } finally {
      if (history === identity) {
        history = undefined;
        deps.onHistoryLoading(false);
      }
    }
  }
  async function createConversation<T>(load: () => Promise<T>, apply: (value: T) => void) {
    const login = deps.getLoginEpoch();
    if (creation?.view === view && creation.login === login) {
      await creation.pending;
      return;
    }
    const current = capture();
    const pending = (async () => {
      const value = await load();
      if (current()) apply(value);
    })();
    creation = { view, login, pending };
    try {
      await pending;
    } finally {
      if (creation?.pending === pending) creation = undefined;
    }
  }
  return { capture, invalidate, canSend: () => !history, loadHistory, createConversation };
}

export function draftAfterSubmission(submitted: string, current: string) {
  return current === submitted ? '' : current;
}
