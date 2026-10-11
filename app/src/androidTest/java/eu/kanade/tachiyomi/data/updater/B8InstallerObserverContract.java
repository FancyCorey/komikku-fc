package eu.kanade.tachiyomi.data.updater;

/** Shared test-APK IPC and persistence keys for the isolated installer observer. */
public final class B8InstallerObserverContract {
    private B8InstallerObserverContract() {}

    public static final String ACTION_KEEP_ALIVE =
        "eu.kanade.tachiyomi.test.B8_INSTALLER_OBSERVER_KEEP_ALIVE";
    public static final int MSG_PREPARE = 1;
    public static final int MSG_START_WATCHING = 2;
    public static final int MSG_SNAPSHOT = 3;
    public static final int MSG_SET_PHASE = 4;
    public static final int MSG_STOP = 5;
    public static final int MSG_RESPONSE = 6;
    public static final String EXTRA_PHASE = "phase";
    public static final String EXTRA_OK = "ok";
    public static final String EXTRA_ERROR = "error";

    public static final String PREFERENCES_NAME = "kmk_b8_owned_installer_test_v1";
    public static final String KEY_ATTEMPT_ID = "attempt_id";
    public static final String KEY_PROFILE_ID = "profile_id";
    public static final String KEY_EXPECTED_APK_SHA256 = "expected_apk_sha256";
    public static final String KEY_PACKAGE_NAME = "package_name";
    public static final String KEY_UID = "uid";
    public static final String KEY_VERSION_CODE = "version_code";
    public static final String KEY_TEST_FILE_NAME = "test_file_name";
    public static final String KEY_PHASE_STATE = "phase_state";
    public static final String KEY_CREATED_SESSIONS = "created_sessions";
    public static final String KEY_BOUND_SESSION = "bound_session";
    public static final String KEY_EXPECTED_PACKAGE = "observer_expected_package";
    public static final String KEY_EXPECTED_UID = "observer_expected_uid";
    public static final String KEY_OBSERVER_UID = "observer_uid";
    public static final String KEY_OBSERVER_PACKAGE = "observer_package";
    public static final String KEY_START_NONCE = "observer_start_nonce";
    public static final String KEY_FOREGROUND_ACTIVE = "observer_foreground_active";
    public static final String KEY_WATCHING = "observer_watching";
    public static final String KEY_OBSERVER_ERROR = "observer_error";
    public static final String KEY_OBSERVED_INSTALLER_PACKAGE = "observer_installer_package";
    public static final String KEY_OBSERVED_INSTALLER_UID = "observer_installer_uid";
    public static final String KEY_OBSERVED_INSTALLER_IDENTITY = "observer_installer_identity";
    public static final String KEY_FINISHED_SESSION = "observer_finished_session";
    public static final String KEY_SESSION_SUCCESS = "observer_session_success";
    public static final String KEY_FINISHED_AT = "observer_finished_at";
}
