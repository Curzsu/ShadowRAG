import type { PluginOption } from 'vite';
import VueDevtools from 'vite-plugin-vue-devtools';

export function setupDevtoolsPlugin(viteEnv: Env.ImportMeta): PluginOption | null {
  const { VITE_DEVTOOLS, VITE_DEVTOOLS_LAUNCH_EDITOR } = viteEnv;

  if (VITE_DEVTOOLS !== 'Y') {
    return null;
  }

  return VueDevtools({
    launchEditor: VITE_DEVTOOLS_LAUNCH_EDITOR
  });
}
