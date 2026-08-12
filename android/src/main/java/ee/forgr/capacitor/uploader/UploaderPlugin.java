package ee.forgr.capacitor.uploader;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.webkit.MimeTypeMap;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.gotev.uploadservice.data.UploadInfo;
import net.gotev.uploadservice.network.ServerResponse;
import net.gotev.uploadservice.observer.request.GlobalRequestObserver;
import net.gotev.uploadservice.observer.request.RequestObserverDelegate;
import org.json.JSONException;
import org.json.JSONObject;

@CapacitorPlugin(name = "Uploader")
public class UploaderPlugin extends Plugin {

    private static final String CAPACITOR_FILE_PATH_PREFIX = "/_capacitor_file_";

    private static final String CAPACITOR_CONTENT_PATH_PREFIX = "/_capacitor_content_";

    private final String pluginVersion = "8.3.7";

    private Uploader implementation;
    private boolean initialResumePending = true;

    private static final String CHANNEL_ID = "ee.forgr.capacitor.uploader.notification_channel_id";
    private static final String CHANNEL_NAME = "Uploader Notifications";
    private static final String CHANNEL_DESCRIPTION = "Notifications for file uploads";
    private static final String UPLOAD_ID_PREFIX = "capacitor-uploader-";

    private static final String PREFS_NAME = "CapacitorUploaderPrefs";
    private static final String PENDING_EVENTS_KEY = "pending_events";
    private static final String TAG = "UploaderPlugin";

    private static final Object PENDING_EVENTS_LOCK = new Object();
    private static GlobalRequestObserver processObserver;
    private static Uploader processUploader;
    private static volatile WeakReference<UploaderPlugin> activePlugin = new WeakReference<>(null);

    private static SharedPreferences pendingEventsPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static void saveEventToPrefs(Context context, String eventId, JSObject event) {
        synchronized (PENDING_EVENTS_LOCK) {
            SharedPreferences prefs = pendingEventsPrefs(context);
            String existingJson = prefs.getString(PENDING_EVENTS_KEY, "{}");
            try {
                JSONObject pendingEvents = new JSONObject(existingJson);
                pendingEvents.put(eventId, new JSONObject(event.toString()));
                prefs.edit().putString(PENDING_EVENTS_KEY, pendingEvents.toString()).apply();
            } catch (JSONException e) {
                Log.e(TAG, "Failed to persist upload event", e);
            }
        }
    }

    private static void removeEventFromPrefs(Context context, String eventId) {
        synchronized (PENDING_EVENTS_LOCK) {
            SharedPreferences prefs = pendingEventsPrefs(context);
            String existingJson = prefs.getString(PENDING_EVENTS_KEY, "{}");
            try {
                JSONObject pendingEvents = new JSONObject(existingJson);
                pendingEvents.remove(eventId);
                prefs.edit().putString(PENDING_EVENTS_KEY, pendingEvents.toString()).apply();
            } catch (JSONException e) {
                Log.e(TAG, "Failed to remove upload event from prefs", e);
            }
        }
    }

    private void replayPendingEvents() {
        ArrayList<JSObject> events = new ArrayList<>();
        synchronized (PENDING_EVENTS_LOCK) {
            SharedPreferences prefs = pendingEventsPrefs(getContext());
            String existingJson = prefs.getString(PENDING_EVENTS_KEY, "{}");
            try {
                JSONObject pendingEvents = new JSONObject(existingJson);
                Iterator<String> keys = pendingEvents.keys();
                while (keys.hasNext()) {
                    events.add(JSObject.fromJSONObject(pendingEvents.getJSONObject(keys.next())));
                }
            } catch (JSONException e) {
                Log.e(TAG, "Failed to replay pending upload events", e);
            }
        }

        for (JSObject event : events) {
            notifyListeners("events", event, true);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);

            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(CHANNEL_DESCRIPTION);

            notificationManager.createNotificationChannel(channel);
        }
    }

    private static UploaderPlugin currentPlugin() {
        return activePlugin.get();
    }

    private static void emitEvent(String name, JSObject event, boolean retainUntilConsumed) {
        UploaderPlugin plugin = currentPlugin();
        if (plugin == null || plugin.getBridge() == null) {
            return;
        }
        if (retainUntilConsumed) {
            plugin.notifyListeners(name, event, true);
        } else {
            plugin.notifyListeners(name, event);
        }
    }

    private static JSObject createEvent(String name, String uploadId, JSObject payload) {
        JSObject event = new JSObject();
        event.put("name", name);
        event.put("id", uploadId);
        if (payload != null) {
            event.put("payload", payload);
        }
        return event;
    }

    private static void clearTempBody(String uploadId) {
        if (processUploader != null) {
            processUploader.clearTempMultipartBody(uploadId);
        }
    }

    private static void emitTerminalEvent(Context context, String name, UploadInfo uploadInfo, JSObject payload) {
        String uploadId = uploadInfo.getUploadId();
        clearTempBody(uploadId);
        JSObject event = createEvent(name, uploadId, payload);
        String eventId = UUID.randomUUID().toString();
        event.put("eventId", eventId);
        saveEventToPrefs(context, eventId, event);
        emitEvent("events", event, true);
    }

    private static synchronized void ensureProcessObserver(Application application) {
        if (processObserver != null) {
            return;
        }
        processUploader = new Uploader(application);
        processObserver = new GlobalRequestObserver(
            application,
            new RequestObserverDelegate() {
                @Override
                public void onProgress(Context context, UploadInfo uploadInfo) {
                    JSObject payload = new JSObject();
                    payload.put("percent", uploadInfo.getProgressPercent());
                    emitEvent("events", createEvent("uploading", uploadInfo.getUploadId(), payload), false);
                }

                @Override
                public void onSuccess(Context context, UploadInfo uploadInfo, ServerResponse serverResponse) {
                    JSObject payload = new JSObject();
                    payload.put("statusCode", serverResponse.getCode());
                    emitTerminalEvent(context, "completed", uploadInfo, payload);
                }

                @Override
                public void onError(Context context, UploadInfo uploadInfo, Throwable exception) {
                    JSObject payload = new JSObject();
                    payload.put("error", exception.getMessage());
                    emitTerminalEvent(context, "failed", uploadInfo, payload);
                }

                @Override
                public void onCompleted(Context context, UploadInfo uploadInfo) {
                    clearTempBody(uploadInfo.getUploadId());
                    emitEvent("events", createEvent("finished", uploadInfo.getUploadId(), null), false);
                }

                @Override
                public void onCompletedWhileNotObserving() {
                    // A GlobalRequestObserver remains registered for the process lifetime.
                }
            },
            (uploadInfo) -> uploadInfo.getUploadId().startsWith(UPLOAD_ID_PREFIX)
        );
    }

    @Override
    public void load() {
        createNotificationChannel();
        activePlugin = new WeakReference<>(this);
        Application application = (Application) getContext().getApplicationContext();
        ensureProcessObserver(application);
        implementation = processUploader;
        replayPendingEvents();
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        // load() already replayed once before the first resume.
        if (initialResumePending) {
            initialResumePending = false;
            return;
        }
        replayPendingEvents();
    }

    @Override
    protected void handleOnDestroy() {
        // Keep the process observer alive so terminal events are persisted while no Bridge exists.
        if (activePlugin.get() == this) {
            activePlugin.clear();
        }
        super.handleOnDestroy();
    }

    public static String getMimeType(String url) {
        String type = null;
        String extension = MimeTypeMap.getFileExtensionFromUrl(url);
        if (extension != null) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        }
        return type;
    }

    private static String createUploadId() {
        return UPLOAD_ID_PREFIX + UUID.randomUUID();
    }

    @PluginMethod
    public void startUpload(PluginCall call) {
        String filePath = call.getString("filePath");
        JSArray filesArray = call.getArray("files");
        String serverUrl = call.getString("serverUrl");

        if (serverUrl == null || serverUrl.isEmpty()) {
            call.reject("Missing required parameter: serverUrl");
            return;
        }

        JSObject headersObj = call.getObject("headers", new JSObject());
        JSObject parametersObj = call.getObject("parameters", new JSObject());
        String httpMethod = call.getString("method", "POST");
        String notificationTitle = call.getString("notificationTitle", "File Upload");
        int maxRetries = call.getInt("maxRetries", 2);
        String uploadType = call.getString("uploadType");
        if (uploadType == null || uploadType.isEmpty()) {
            uploadType = "PUT".equalsIgnoreCase(httpMethod) ? "binary" : "multipart";
        }
        String fileField = call.getString("fileField", "file");

        Map<String, String> headers = JSObjectToMap(headersObj);
        Map<String, String> parameters = JSObjectToMap(parametersObj);

        try {
            ArrayList<Uploader.UploadFile> filesToUpload = new ArrayList<>();

            if (filesArray != null && filesArray.length() > 0) {
                for (int i = 0; i < filesArray.length(); i++) {
                    JSONObject fileObj = filesArray.getJSONObject(i);
                    String rawPath = fileObj.optString("filePath", null);
                    if (rawPath == null || rawPath.isEmpty()) {
                        call.reject("Missing required parameter: files[" + i + "].filePath");
                        return;
                    }

                    // Convert Capacitor web-accessible URLs to paths native code can open.
                    // Capacitor 8+ removed Bridge.getLocalUrl(String); mirror AndroidProtocolHandler logic.
                    String localPath = resolveCapacitorPath(rawPath);
                    String fieldName = fileObj.optString("fieldName", fileField);

                    String mimeType = null;
                    if (fileObj.has("mimeType")) {
                        mimeType = fileObj.optString("mimeType", null);
                    } else {
                        mimeType = call.getString("mimeType", null);
                    }
                    if (mimeType == null || mimeType.isEmpty()) {
                        mimeType = getMimeType(localPath);
                    }

                    filesToUpload.add(new Uploader.UploadFile(localPath, fieldName, mimeType));
                }
            } else {
                if (filePath == null || filePath.isEmpty()) {
                    call.reject("Missing required parameter: filePath or files");
                    return;
                }
                String localFilePath = resolveCapacitorPath(filePath);
                String mimeType = call.getString("mimeType", getMimeType(localFilePath));
                filesToUpload.add(new Uploader.UploadFile(localFilePath, fileField, mimeType));
            }

            String uploadId = createUploadId();
            String id = implementation.startUpload(
                uploadId,
                filesToUpload,
                serverUrl,
                headers,
                parameters,
                httpMethod,
                notificationTitle,
                maxRetries,
                uploadType
            );
            JSObject result = new JSObject();
            result.put("id", id);
            call.resolve(result);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void uploadMultipart(PluginCall call) {
        String serverUrl = call.getString("url");
        if (serverUrl == null || serverUrl.isEmpty()) {
            call.reject("Missing required parameter: url");
            return;
        }

        String filePath = call.getString("filePath");
        if (filePath == null || filePath.isEmpty()) {
            call.reject("Missing required parameter: filePath");
            return;
        }

        String fieldName = call.getString("fieldName");
        if (fieldName == null || fieldName.isEmpty()) {
            call.reject("Missing required parameter: fieldName");
            return;
        }

        JSObject headersObj = call.getObject("headers", new JSObject());
        JSObject fieldsObj = call.getObject("fields", new JSObject());
        Map<String, String> headers = JSObjectToMap(headersObj);
        Map<String, String> fields = JSObjectToMap(fieldsObj);

        try {
            String localFilePath = resolveCapacitorPath(filePath);
            String mimeType = getMimeType(localFilePath);
            ArrayList<Uploader.UploadFile> filesToUpload = new ArrayList<>();
            filesToUpload.add(new Uploader.UploadFile(localFilePath, fieldName, mimeType));

            String uploadId = createUploadId();
            String id = implementation.startUpload(
                uploadId,
                filesToUpload,
                serverUrl,
                headers,
                fields,
                "POST",
                "File Upload",
                2,
                "multipart"
            );
            JSObject result = new JSObject();
            result.put("id", id);
            call.resolve(result);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void removeUpload(PluginCall call) {
        String id = call.getString("id");
        if (id == null || id.isEmpty()) {
            call.reject("Missing required parameter: id");
            return;
        }
        try {
            implementation.removeUpload(id);
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    /**
     * Maps WebView URLs (e.g. http(s)://localhost/_capacitor_file_/...) to filesystem or content
     * paths, matching {@link com.getcapacitor.AndroidProtocolHandler}. Plain absolute paths and
     * unrecognized URLs are returned unchanged.
     */
    private static String resolveCapacitorPath(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return filePath;
        }
        Uri uri = Uri.parse(filePath);
        String path = uri.getPath();
        if (path != null) {
            if (path.startsWith(CAPACITOR_FILE_PATH_PREFIX)) {
                return path.substring(CAPACITOR_FILE_PATH_PREFIX.length());
            }
            if (path.startsWith(CAPACITOR_CONTENT_PATH_PREFIX)) {
                String scheme = uri.getScheme();
                String host = uri.getHost();
                if (scheme != null && host != null) {
                    String baseUrl = scheme + "://" + host;
                    if (uri.getPort() != -1) {
                        baseUrl += ":" + uri.getPort();
                    }
                    return filePath.replace(baseUrl + CAPACITOR_CONTENT_PATH_PREFIX, "content://");
                }
                return filePath.replace(CAPACITOR_CONTENT_PATH_PREFIX, "content://");
            }
        }
        if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            return uri.getPath();
        }
        return filePath;
    }

    private Map<String, String> JSObjectToMap(JSObject object) {
        Map<String, String> map = new HashMap<>();
        if (object != null) {
            for (Iterator<String> it = object.keys(); it.hasNext(); ) {
                String key = it.next();
                String value = object.getString(key);
                // Only add non-null and non-empty values to prevent upload service errors
                if (value != null && !value.isEmpty()) {
                    map.put(key, value);
                }
            }
        }
        return map;
    }

    @PluginMethod
    public void acknowledgeEvent(PluginCall call) {
        String eventId = call.getString("eventId");
        if (eventId == null || eventId.isEmpty()) {
            call.reject("Missing required parameter: eventId");
            return;
        }
        removeEventFromPrefs(getContext(), eventId);
        call.resolve();
    }

    @PluginMethod
    public void getPluginVersion(final PluginCall call) {
        try {
            final JSObject ret = new JSObject();
            ret.put("version", this.pluginVersion);
            call.resolve(ret);
        } catch (final Exception e) {
            call.reject("Could not get plugin version", e);
        }
    }
}
