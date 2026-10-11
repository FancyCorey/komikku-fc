package eu.kanade.tachiyomi.data.updater;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Test-APK process that independently observes the real installer's session lifecycle.
 * Keep this isolated process Java-only: the instrumentation APK does not package Kotlin's
 * runtime into this process, and the observer must survive target-app replacement.
 */
public final class PackageInstallerCallbackObserverService extends Service {
    private static final String EXPECTED_PACKAGE = "app.komikku.kmk";
    private static final int NOTIFICATION_ID = 0xB808;
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private SharedPreferences preferences;
    private HandlerThread callbackThread;
    private PackageInstaller.SessionCallback callback;
    private Messenger serviceMessenger;

    @Override
    public void onCreate() {
        super.onCreate();
        startObserverForeground();
        preferences = getSharedPreferences(B8InstallerObserverContract.PREFERENCES_NAME, MODE_PRIVATE);
        callbackThread = new HandlerThread("kmk-b8-external-session-observer");
        callbackThread.start();

        PackageInstaller installer = getPackageManager().getPackageInstaller();
        callback = new PackageInstaller.SessionCallback() {
            @Override
            public void onCreated(int sessionId) {
                if (!preferences.getBoolean(B8InstallerObserverContract.KEY_WATCHING, false)) return;
                String expectedPackage = preferences.getString(
                    B8InstallerObserverContract.KEY_EXPECTED_PACKAGE,
                    null
                );
                if (expectedPackage == null) return;
                int expectedUid = preferences.getInt(B8InstallerObserverContract.KEY_EXPECTED_UID, -1);

                PackageInstaller.SessionInfo info;
                try {
                    info = installer.getSessionInfo(sessionId);
                } catch (RuntimeException error) {
                    // A callback can outlive a session that is unrelated to this attempt.
                    // It is not attributable until its owner can be resolved; the submit
                    // assertion still requires the exact target-owned session to be observed.
                    return;
                }
                if (info == null) return;
                String sessionInstallerPackage = info.getInstallerPackageName();
                int sessionInstallerUid;
                String installerIdentity;
                if (sessionInstallerPackage != null) {
                    if (!sessionInstallerPackage.equals(expectedPackage)) return;
                    try {
                        sessionInstallerUid = getPackageManager()
                            .getApplicationInfo(sessionInstallerPackage, 0).uid;
                    } catch (PackageManager.NameNotFoundException error) {
                        recordError("could not resolve installer UID for the expected target package");
                        return;
                    }
                    if (sessionInstallerUid != expectedUid) {
                        recordError("observed target-package session UID did not match the expected target UID");
                        return;
                    }
                    installerIdentity = "PACKAGE_NAME_AND_UID";
                } else {
                    // PackageInstaller documents the package name as nullable. On API 34+
                    // its owner UID is available; accept that fallback only when the UID
                    // maps exclusively to the exact target package. Otherwise the callback
                    // is unclassifiable and must not poison this attempt's observer state.
                    if (Build.VERSION.SDK_INT < 34) return;
                    sessionInstallerUid = info.getInstallerUid();
                    if (sessionInstallerUid != expectedUid) return;
                    String[] packagesForUid = getPackageManager().getPackagesForUid(sessionInstallerUid);
                    if (packagesForUid == null || packagesForUid.length != 1
                        || !expectedPackage.equals(packagesForUid[0])) return;
                    installerIdentity = "API34_INSTALLER_UID_AND_EXCLUSIVE_PACKAGE_MAPPING";
                }

                Set<String> existing = preferences.getStringSet(
                    B8InstallerObserverContract.KEY_CREATED_SESSIONS,
                    Collections.emptySet()
                );
                Set<String> sessions = existing == null ? new HashSet<>() : new HashSet<>(existing);
                sessions.add(Integer.toString(sessionId));
                SharedPreferences.Editor editor = preferences.edit()
                    .putStringSet(B8InstallerObserverContract.KEY_CREATED_SESSIONS, sessions)
                    .putInt(B8InstallerObserverContract.KEY_OBSERVED_INSTALLER_UID, sessionInstallerUid)
                    .putString(B8InstallerObserverContract.KEY_OBSERVED_INSTALLER_IDENTITY, installerIdentity);
                if (sessionInstallerPackage == null) {
                    editor.remove(B8InstallerObserverContract.KEY_OBSERVED_INSTALLER_PACKAGE);
                } else {
                    editor.putString(B8InstallerObserverContract.KEY_OBSERVED_INSTALLER_PACKAGE, sessionInstallerPackage);
                }
                boolean saved = editor.commit();
                if (!saved) recordError("could not durably persist the exact created installer session");
            }

            @Override
            public void onFinished(int sessionId, boolean success) {
                Set<String> sessions = preferences.getStringSet(
                    B8InstallerObserverContract.KEY_CREATED_SESSIONS,
                    Collections.emptySet()
                );
                if (sessions == null || !sessions.contains(Integer.toString(sessionId))) return;
                boolean saved = preferences.edit()
                    .putString(B8InstallerObserverContract.KEY_FINISHED_SESSION, Integer.toString(sessionId))
                    .putBoolean(B8InstallerObserverContract.KEY_SESSION_SUCCESS, success)
                    .putLong(B8InstallerObserverContract.KEY_FINISHED_AT, System.currentTimeMillis())
                    .commit();
                if (!saved) recordError("could not durably persist the exact finished installer session");
            }

            @Override
            public void onBadgingChanged(int sessionId) {}

            @Override
            public void onActiveChanged(int sessionId, boolean active) {}

            @Override
            public void onProgressChanged(int sessionId, float progress) {}
        };
        installer.registerSessionCallback(callback, new Handler(callbackThread.getLooper()));
        serviceMessenger = new Messenger(new Handler(callbackThread.getLooper(), this::handleMessage));
        preferences.edit()
            .putInt(B8InstallerObserverContract.KEY_OBSERVER_UID, Process.myUid())
            .putString(B8InstallerObserverContract.KEY_OBSERVER_PACKAGE, getPackageName())
            .putBoolean(B8InstallerObserverContract.KEY_FOREGROUND_ACTIVE, true)
            .commit();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !B8InstallerObserverContract.ACTION_KEEP_ALIVE.equals(intent.getAction())) {
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return serviceMessenger == null ? null : serviceMessenger.getBinder();
    }

    @Override
    public void onDestroy() {
        if (callback != null) {
            try {
                getPackageManager().getPackageInstaller().unregisterSessionCallback(callback);
            } catch (RuntimeException ignored) {
                // The process is already shutting down; preserve the original service outcome.
            }
        }
        if (callbackThread != null) callbackThread.quitSafely();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private void startObserverForeground() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        String channelId = "kmk-b8-installer-observer";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(
                channelId,
                "Installer callback test",
                NotificationManager.IMPORTANCE_LOW
            ));
        }
        Notification notification = new Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Installer callback test")
            .setContentText("Observing the test installation session")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private boolean handleMessage(Message message) {
        int targetUid;
        try {
            ApplicationInfo target = getPackageManager().getApplicationInfo(EXPECTED_PACKAGE, 0);
            targetUid = target.uid;
        } catch (PackageManager.NameNotFoundException error) {
            reply(message, failure("request caller is not the exact target app UID"));
            return true;
        }
        if (message.sendingUid != targetUid) {
            reply(message, failure("request caller is not the exact target app UID"));
            return true;
        }

        Bundle result;
        switch (message.what) {
            case B8InstallerObserverContract.MSG_PREPARE:
                result = prepare(message.getData(), targetUid);
                break;
            case B8InstallerObserverContract.MSG_START_WATCHING:
                result = startWatching(targetUid);
                break;
            case B8InstallerObserverContract.MSG_SNAPSHOT:
                result = snapshot();
                break;
            case B8InstallerObserverContract.MSG_SET_PHASE:
                result = setPhase(message.getData());
                break;
            case B8InstallerObserverContract.MSG_STOP:
                result = stopWatching(message.getData());
                break;
            default:
                result = failure("unknown observer request");
                break;
        }
        reply(message, result);
        return true;
    }

    private Bundle prepare(Bundle data, int targetUid) {
        String attemptId = value(data, B8InstallerObserverContract.KEY_ATTEMPT_ID).trim();
        String profileId = value(data, B8InstallerObserverContract.KEY_PROFILE_ID).trim();
        String apkHash = value(data, B8InstallerObserverContract.KEY_EXPECTED_APK_SHA256).toLowerCase(java.util.Locale.ROOT);
        String fileName = value(data, B8InstallerObserverContract.KEY_TEST_FILE_NAME);
        int suppliedUid = data.getInt(B8InstallerObserverContract.KEY_UID, -1);
        if (attemptId.isEmpty() || profileId.isEmpty() || !SHA256_PATTERN.matcher(apkHash).matches()
            || !fileName.equals(attemptId + ".apk") || suppliedUid != targetUid) {
            return failure("observer prepare request did not match the exact attempt, package, UID, and APK hash");
        }
        if (preferences.contains(B8InstallerObserverContract.KEY_ATTEMPT_ID)) {
            return failure("refusing to overwrite a previous installer observation attempt");
        }

        boolean saved = preferences.edit()
            .putString(B8InstallerObserverContract.KEY_ATTEMPT_ID, attemptId)
            .putString(B8InstallerObserverContract.KEY_PROFILE_ID, profileId)
            .putString(B8InstallerObserverContract.KEY_EXPECTED_APK_SHA256, apkHash)
            .putString(B8InstallerObserverContract.KEY_PACKAGE_NAME, EXPECTED_PACKAGE)
            .putInt(B8InstallerObserverContract.KEY_UID, targetUid)
            .putLong(B8InstallerObserverContract.KEY_VERSION_CODE,
                data.getLong(B8InstallerObserverContract.KEY_VERSION_CODE, -1L))
            .putString(B8InstallerObserverContract.KEY_TEST_FILE_NAME, fileName)
            .putString(B8InstallerObserverContract.KEY_EXPECTED_PACKAGE, EXPECTED_PACKAGE)
            .putInt(B8InstallerObserverContract.KEY_EXPECTED_UID, targetUid)
            .putStringSet(B8InstallerObserverContract.KEY_CREATED_SESSIONS, new HashSet<>())
            .remove(B8InstallerObserverContract.KEY_BOUND_SESSION)
            .remove(B8InstallerObserverContract.KEY_OBSERVED_INSTALLER_PACKAGE)
            .remove(B8InstallerObserverContract.KEY_OBSERVED_INSTALLER_UID)
            .remove(B8InstallerObserverContract.KEY_OBSERVED_INSTALLER_IDENTITY)
            .putBoolean(B8InstallerObserverContract.KEY_WATCHING, false)
            .remove(B8InstallerObserverContract.KEY_FINISHED_SESSION)
            .remove(B8InstallerObserverContract.KEY_SESSION_SUCCESS)
            .remove(B8InstallerObserverContract.KEY_FINISHED_AT)
            .remove(B8InstallerObserverContract.KEY_OBSERVER_ERROR)
            .putString(B8InstallerObserverContract.KEY_PHASE_STATE, "PREPARING")
            .commit();
        return saved ? success() : failure("observer could not durably prepare the attempt");
    }

    private Bundle startWatching(int targetUid) {
        String attemptId = preferences.getString(B8InstallerObserverContract.KEY_ATTEMPT_ID, null);
        if (attemptId == null || attemptId.trim().isEmpty()
            || !EXPECTED_PACKAGE.equals(preferences.getString(B8InstallerObserverContract.KEY_EXPECTED_PACKAGE, null))
            || preferences.getInt(B8InstallerObserverContract.KEY_EXPECTED_UID, -1) != targetUid) {
            return failure("observer has no matching prepared target attempt");
        }
        boolean saved = preferences.edit()
            .putBoolean(B8InstallerObserverContract.KEY_WATCHING, true)
            .putString(B8InstallerObserverContract.KEY_PHASE_STATE, "WATCHING_REAL_PACKAGEINSTALLER")
            .commit();
        return saved ? success() : failure("observer could not durably start session watching");
    }

    private Bundle setPhase(Bundle data) {
        String phase = data.getString(B8InstallerObserverContract.EXTRA_PHASE);
        if (phase == null || phase.trim().isEmpty()) return failure("phase update was empty");
        String boundSession = data.getString(B8InstallerObserverContract.KEY_BOUND_SESSION);
        if (boundSession != null) {
            Set<String> sessions = preferences.getStringSet(
                B8InstallerObserverContract.KEY_CREATED_SESSIONS,
                Collections.emptySet()
            );
            if (sessions == null || !sessions.contains(boundSession)) {
                return failure("cannot bind a session that the independent callback observer did not capture");
            }
        }
        SharedPreferences.Editor editor = preferences.edit().putString(
            B8InstallerObserverContract.KEY_PHASE_STATE,
            phase
        );
        if (boundSession != null) editor.putString(B8InstallerObserverContract.KEY_BOUND_SESSION, boundSession);
        boolean saved = editor.commit();
        return saved ? success() : failure("observer could not durably update phase");
    }

    private Bundle stopWatching(Bundle data) {
        String phase = data.getString(B8InstallerObserverContract.EXTRA_PHASE);
        boolean saved = preferences.edit()
            .putBoolean(B8InstallerObserverContract.KEY_WATCHING, false)
            .putString(B8InstallerObserverContract.KEY_PHASE_STATE, phase == null ? "" : phase)
            .commit();
        stopSelf();
        return saved ? success() : failure("observer could not durably stop watching");
    }

    private void recordError(String message) {
        preferences.edit().putString(B8InstallerObserverContract.KEY_OBSERVER_ERROR, message).commit();
    }

    private Bundle snapshot() {
        Bundle result = new Bundle();
        result.putBoolean(B8InstallerObserverContract.EXTRA_OK, true);
        Map<String, ?> all = preferences.getAll();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String) result.putString(key, (String) value);
            else if (value instanceof Boolean) result.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) result.putInt(key, (Integer) value);
            else if (value instanceof Long) result.putLong(key, (Long) value);
            else if (value instanceof Set<?>) {
                ArrayList<String> strings = new ArrayList<>();
                for (Object item : (Set<?>) value) if (item instanceof String) strings.add((String) item);
                result.putStringArrayList(key, strings);
            }
        }
        result.putInt(B8InstallerObserverContract.KEY_OBSERVER_UID, Process.myUid());
        result.putString(B8InstallerObserverContract.KEY_OBSERVER_PACKAGE, getPackageName());
        return result;
    }

    private void reply(Message request, Bundle data) {
        Message response = Message.obtain(
            null,
            B8InstallerObserverContract.MSG_RESPONSE,
            request.arg1,
            0
        );
        response.setData(data);
        try {
            if (request.replyTo != null) request.replyTo.send(response);
        } catch (android.os.RemoteException ignored) {
            // The caller may have timed out or disconnected; durable observer state remains authoritative.
        }
    }

    private Bundle success() {
        Bundle result = new Bundle();
        result.putBoolean(B8InstallerObserverContract.EXTRA_OK, true);
        return result;
    }

    private Bundle failure(String message) {
        Bundle result = new Bundle();
        result.putBoolean(B8InstallerObserverContract.EXTRA_OK, false);
        result.putString(B8InstallerObserverContract.EXTRA_ERROR, message);
        return result;
    }

    private static String value(Bundle data, String key) {
        String value = data.getString(key);
        return value == null ? "" : value;
    }
}
