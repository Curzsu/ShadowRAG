export enum SetupStoreId {
  App = 'app-store',
  Theme = 'theme-store',
  Auth = 'auth-store',
  Route = 'route-store',
  Tab = 'tab-store',
  KnowledgeBase = 'knowledge-base-store',
  Chat = 'chat-store'
}
export enum UploadStatus {
  Uploading = 0,
  Completed = 1,
  Pending = 2,
  Paused = 3,
  Break = 4
}
/**
 * 文件解析状态（对应后端 FileUpload.parseStatus）
 * 表示合并后的解析与向量化进度，与 UploadStatus（上传进度）含义不同，不要混用
 */
export enum ParseStatus {
  Pending = 0,
  Processing = 1,
  Completed = 2,
  /** 单次处理异常，后台仍在重试，不代表最终失败 */
  Error = 3,
  /** 进入死信队列，最终失败 */
  DeadLetter = 4
}
