import type { PluginListenerHandle } from '@capacitor/core';

export interface uploadOption {
  /**
   * @since 1.0.0
   * @description The file path of the file to upload
   */
  filePath: string;
  /**
   * @since 1.0.0
   * @description The url of the server
   */
  serverUrl: string;
  /**
   * @since 1.0.0
   * @default 'Uploading'
   * @description The title of the notification
   * Android only
   */
  notificationTitle?: number;
  /**
   * @since 1.0.0
   * @description The headers to send with the request
   */
  headers: {
    [key: string]: string;
  };
  /**
   * @since 1.0.0
   * @description The method to use for the request
   * @default 'POST'
   */
  method?: 'PUT' | 'POST';
  /**
   * @since 1.0.0
   * @description The mime type to use for the request
   */
  mimeType?: string;
  /**
   * @since 1.0.0
   * @description The parameters to send with the request
   */
  parameters?: { [key: string]: string };
  /**
   * @since 1.0.0
   * @description The maximum number of retries
   */
  maxRetries?: number;
}
export interface UploadEvent {
  /**
   * Current status of upload, between 0 and 100.
   *
   * @since 1.0.0
   */
  name: 'uploading' | 'completed' | 'failed';
  /**
   * @since 1.0.0
   * @description The payload of the event
   * @default { percent: 0, error: '', statusCode: 0 }
   */
  payload: {
    /**
     * @since 1.0.0
     * @description The percent of the upload
     */
    percent?: number;
    /**
     * @since 1.0.0
     * @description The error of the upload
     */
    error?: string;
    /**
     * @since 1.0.0
     * @description The status code of the upload
     */
    statusCode?: number;
  };
  /**
   * @since 1.0.0
   * @description The id of the upload
   */
  id: string;
}

export interface UploaderPlugin {
  /**
   * @since 1.0.0
   * @description Start the upload
   * @param options uploadOption
   * @returns { id: string }
   */
  startUpload(options: uploadOption): Promise<{ id: string }>;
  /**
   * @since 1.0.0
   * @description Remove the upload
   * @param options
   * @returns { void }
   */
  removeUpload(options: { id: string }): Promise<void>;
  /**
   * @since 1.0.0
   * @description Add a listener for the upload events
   * @param eventName
   * @param listenerFunc
   * @returns { PluginListenerHandle }
   */
  addListener(
    eventName: 'events',
    listenerFunc: (state: UploadEvent) => void,
  ): Promise<PluginListenerHandle>;
}
