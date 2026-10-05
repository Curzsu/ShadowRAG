/** Types for the Markdown-it plugins used by vue-markdown-shiki 2.0.0. */
declare module 'markdown-it-attrs' {
  interface AttrsOptions {
    leftDelimiter?: string;
    rightDelimiter?: string;
    allowedAttributes?: (string | RegExp)[];
  }
  const plugin: import('markdown-it').PluginWithOptions<AttrsOptions>;
  export default plugin;
}

declare module 'markdown-it-emoji' {
  interface EmojiOptions {
    defs?: Record<string, string>;
    enabled?: string[];
    shortcuts?: Record<string, string | string[]>;
  }
  export const full: import('markdown-it').PluginWithOptions<EmojiOptions>;
  export const light: typeof full;
  export const bare: typeof full;
}

declare module 'markdown-it-container' {
  interface ContainerOptions {
    marker?: string;
    validate?: (params: string, markup: string) => boolean;
    render?: import('markdown-it/lib/renderer.mjs').RenderRule;
  }
  export default function container(md: import('markdown-it'), name: string, options?: ContainerOptions): void;
}
