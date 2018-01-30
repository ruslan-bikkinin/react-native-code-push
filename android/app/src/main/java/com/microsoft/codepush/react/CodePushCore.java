package com.microsoft.codepush.react;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.support.annotation.Nullable;
import android.view.View;

import com.facebook.react.ReactApplication;
import com.facebook.react.ReactInstanceManager;
import com.facebook.react.ReactRootView;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.JSBundleLoader;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.ChoreographerCompat;
import com.facebook.react.modules.core.ReactChoreographer;
import com.microsoft.codepush.common.DownloadProgress;
import com.microsoft.codepush.common.connection.PackageDownloader;
import com.microsoft.codepush.common.datacontracts.CodePushDeploymentStatusReport;
import com.microsoft.codepush.common.datacontracts.CodePushLocalPackage;
import com.microsoft.codepush.common.datacontracts.CodePushRemotePackage;
import com.microsoft.codepush.common.datacontracts.CodePushSyncOptions;
import com.microsoft.codepush.common.datacontracts.CodePushUpdateDialog;
import com.microsoft.codepush.common.enums.CodePushDeploymentStatus;
import com.microsoft.codepush.common.enums.CodePushInstallMode;
import com.microsoft.codepush.common.enums.CodePushSyncStatus;
import com.microsoft.codepush.common.enums.CodePushUpdateState;
import com.microsoft.codepush.common.interfaces.CodePushBinaryVersionMismatchListener;
import com.microsoft.codepush.common.interfaces.CodePushDownloadProgressListener;
import com.microsoft.codepush.common.interfaces.CodePushSyncStatusListener;
import com.microsoft.codepush.common.interfaces.DownloadProgressCallback;
import com.microsoft.codepush.common.managers.CodePushUpdateManager;
import com.microsoft.codepush.common.managers.CodePushUpdateManagerDeserializer;
import com.microsoft.codepush.common.utils.StringUtils;
import com.microsoft.codepush.react.exceptions.CodePushInvalidPublicKeyException;
import com.microsoft.codepush.react.exceptions.CodePushInvalidUpdateException;
import com.microsoft.codepush.react.exceptions.CodePushNotInitializedException;
import com.microsoft.codepush.react.exceptions.CodePushUnknownException;
import com.microsoft.codepush.react.interfaces.ReactInstanceHolder;
import com.microsoft.codepush.react.managers.CodePushAcquisitionManager;
import com.microsoft.codepush.react.managers.CodePushRestartManager;
import com.microsoft.codepush.react.managers.CodePushTelemetryManager;
import com.microsoft.codepush.react.managers.CodePushTelemetryManagerDeserializer;
import com.microsoft.codepush.react.managers.SettingsManager;
import com.microsoft.codepush.react.utils.CodePushRNUtils;
import com.microsoft.codepush.react.utils.CodePushUpdateUtils;
import com.microsoft.codepush.react.utils.CodePushUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static com.microsoft.codepush.common.enums.CodePushCheckFrequency.ON_APP_START;
import static com.microsoft.codepush.common.enums.CodePushInstallMode.IMMEDIATE;
import static com.microsoft.codepush.common.enums.CodePushInstallMode.ON_NEXT_RESTART;
import static com.microsoft.codepush.common.utils.StringUtils.isNullOrEmpty;

public class CodePushCore {
    private static boolean sIsRunningBinaryVersion = false;
    private static boolean sNeedToReportRollback = false;
    private static boolean sTestConfigurationFlag = false;
    private static String sAppVersion = null;

    private boolean mDidUpdate = false;

    private String mAssetsBundleFileName;

    // Helper classes.
    private CodePushUpdateManager mUpdateManager;
    private CodePushUpdateManagerDeserializer mUpdateManagerDeserializer;
    private CodePushTelemetryManager mTelemetryManager;
    private CodePushTelemetryManagerDeserializer mTelemetryManagerDeserializer;
    private SettingsManager mSettingsManager;
    private CodePushRestartManager mRestartManager;

    // Config properties.
    private String mDeploymentKey;
    private static String mServerUrl = "https://codepush.azurewebsites.net/";
    private static String mPublicKey;

    private Context mContext;
    private final boolean mIsDebugMode;

    private static ReactInstanceHolder mReactInstanceHolder;
    private static CodePushCore mCurrentInstance;
    private static ReactApplicationContext mReactApplicationContext;

    private static CodePushNativeModule mCodePushModule;
    private static CodePushDialog mDialogModule;

    private List<CodePushSyncStatusListener> mSyncStatusListeners = new ArrayList<>();
    private List<CodePushDownloadProgressListener> mDownloadProgressListeners = new ArrayList<>();
    private List<CodePushBinaryVersionMismatchListener> mBinaryVersionMismatchListeners = new ArrayList<>();

    private LifecycleEventListener mLifecycleEventListener = null;
    private LifecycleEventListener mLifecycleEventListenerForReport = null;
    private int mMinimumBackgroundDuration = 0;

    private boolean mSyncInProgress = false;
    private CodePushInstallMode mCurrentInstallModeInProgress = ON_NEXT_RESTART;

    public CodePushCore(
            String deploymentKey,
            Context context,
            boolean isDebugMode,
            @Nullable String serverUrl,
            @Nullable Integer publicKeyResourceDescriptor,
            @Nullable String jsBundleFileName
    ) {
        mDeploymentKey = deploymentKey;
        mContext = context.getApplicationContext();
        mIsDebugMode = isDebugMode;

        if (serverUrl != null) {
            mServerUrl = serverUrl;
        }

        if (publicKeyResourceDescriptor != null) {
            mPublicKey = getPublicKeyByResourceDescriptor(publicKeyResourceDescriptor);
        }

        if (jsBundleFileName == null) {
            mAssetsBundleFileName = getJSBundleFile();
        } else {
            mAssetsBundleFileName = getJSBundleFile(jsBundleFileName);
        }

        if (sAppVersion == null) {
            try {
                PackageInfo pInfo = mContext.getPackageManager().getPackageInfo(mContext.getPackageName(), 0);
                sAppVersion = pInfo.versionName;
            } catch (PackageManager.NameNotFoundException e) {
                throw new CodePushUnknownException("Unable to get package info for " + mContext.getPackageName(), e);
            }
        }

        mUpdateManager = new CodePushUpdateManager(context.getFilesDir().getAbsolutePath());
        mUpdateManagerDeserializer = new CodePushUpdateManagerDeserializer(mUpdateManager);
        mTelemetryManager = new CodePushTelemetryManager(mContext);
        mTelemetryManagerDeserializer = new CodePushTelemetryManagerDeserializer(mTelemetryManager);

        mSettingsManager = new SettingsManager(mContext);
        mRestartManager = new CodePushRestartManager(this);

        mCurrentInstance = this;

        clearDebugCacheIfNeeded();
        initializeUpdateAfterRestart();
    }

    private String getPublicKeyByResourceDescriptor(int publicKeyResourceDescriptor) {
        String publicKey;
        try {
            publicKey = mContext.getString(publicKeyResourceDescriptor);
        } catch (Resources.NotFoundException e) {
            throw new CodePushInvalidPublicKeyException(
                    "Unable to get public key, related resource descriptor " +
                            publicKeyResourceDescriptor +
                            " can not be found", e
            );
        }

        if (publicKey.isEmpty()) {
            throw new CodePushInvalidPublicKeyException("Specified public key is empty");
        }
        return publicKey;
    }

    public void addSyncStatusListener(CodePushSyncStatusListener syncStatusListener) {
        mSyncStatusListeners.add(syncStatusListener);
    }

    public void addDownloadProgressListener(CodePushDownloadProgressListener downloadProgressListener) {
        mDownloadProgressListeners.add(downloadProgressListener);
    }

    public void addBinaryVersionMismatchListener(CodePushBinaryVersionMismatchListener listener) {
        mBinaryVersionMismatchListeners.add(listener);
    }

    private void syncStatusChange(CodePushSyncStatus syncStatus) {
        for (CodePushSyncStatusListener syncStatusListener : mSyncStatusListeners) {
            try {
                syncStatusListener.syncStatusChanged(syncStatus);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        switch (syncStatus) {
            case CHECKING_FOR_UPDATE: {
                CodePushRNUtils.log("Checking for update.");
                break;
            }
            case AWAITING_USER_ACTION: {
                CodePushRNUtils.log("Awaiting user action.");
                break;
            }
            case DOWNLOADING_PACKAGE: {
                CodePushRNUtils.log("Downloading package.");
                break;
            }
            case INSTALLING_UPDATE: {
                CodePushRNUtils.log("Installing update.");
                break;
            }
            case UP_TO_DATE: {
                CodePushRNUtils.log("App is up to date.");
                break;
            }
            case UPDATE_IGNORED: {
                CodePushRNUtils.log("User cancelled the update.");
                break;
            }
            case UPDATE_INSTALLED: {
                if (mCurrentInstallModeInProgress == ON_NEXT_RESTART) {
                    CodePushRNUtils.log("Update is installed and will be run on the next app restart.");
                } else if (mCurrentInstallModeInProgress == CodePushInstallMode.ON_NEXT_RESUME) {
                    CodePushRNUtils.log("Update is installed and will be run after the app has been in the background for at least " + mMinimumBackgroundDuration + " seconds.");
                } else {
                    CodePushRNUtils.log("Update is installed and will be run when the app next resumes.");
                }
                break;
            }
            case UNKNOWN_ERROR: {
                CodePushRNUtils.log("An unknown error occurred.");
                break;
            }
        }
    }

    private void downloadProgressChange(long receivedBytes, long totalBytes) {
        for (CodePushDownloadProgressListener downloadProgressListener : mDownloadProgressListeners) {
            try {
                downloadProgressListener.downloadProgressChanged(receivedBytes, totalBytes);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void binaryVersionMismatchChange(CodePushRemotePackage update) {
        for (CodePushBinaryVersionMismatchListener listener : mBinaryVersionMismatchListeners) {
            try {
                listener.binaryVersionMismatchChanged(update);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void clearDebugCacheIfNeeded() {
        if (mIsDebugMode && mSettingsManager.isPendingUpdate(null)) {
            // This needs to be kept in sync with https://github.com/facebook/react-native/blob/master/ReactAndroid/src/main/java/com/facebook/react/devsupport/DevSupportManager.java#L78
            File cachedDevBundle = new File(mContext.getFilesDir(), "ReactNativeDevBundle.js");
            if (cachedDevBundle.exists()) {
                cachedDevBundle.delete();
            }
        }
    }

    public boolean didUpdate() {
        return mDidUpdate;
    }

    public String getAppVersion() {
        return sAppVersion;
    }

    public String getAssetsBundleFileName() {
        return mAssetsBundleFileName;
    }

    private long getBinaryResourcesModifiedTime() {
        try {
            String packageName = this.mContext.getPackageName();
            int codePushApkBuildTimeId = this.mContext.getResources().getIdentifier(CodePushConstants.CODE_PUSH_APK_BUILD_TIME_KEY, "string", packageName);
            // double quotes replacement is needed for correct restoration of long value from strings.xml
            // https://github.com/Microsoft/cordova-plugin-code-push/issues/264
            String codePushApkBuildTime = this.mContext.getResources().getString(codePushApkBuildTimeId).replaceAll("\"", "");
            return Long.parseLong(codePushApkBuildTime);
        } catch (Exception e) {
            throw new CodePushUnknownException("Error in getting binary resources modified time", e);
        }
    }

    @Deprecated
    public static String getBundleUrl() {
        return getJSBundleFile();
    }

    @Deprecated
    public static String getBundleUrl(String assetsBundleFileName) {
        return getJSBundleFile(assetsBundleFileName);
    }

    public Context getContext() {
        return mContext;
    }

    public String getDeploymentKey() {
        return mDeploymentKey;
    }

    public static String getJSBundleFile() {
        return CodePushCore.getJSBundleFile(CodePushConstants.DEFAULT_JS_BUNDLE_NAME);
    }

    public static String getJSBundleFile(String assetsBundleFileName) {
        if (mCurrentInstance == null) {
            throw new CodePushNotInitializedException("A CodePush instance has not been created yet. Have you added it to your app's list of ReactPackages?");
        }

        return mCurrentInstance.getJSBundleFileInternal(assetsBundleFileName);
    }

    public String getJSBundleFileInternal(String assetsBundleFileName) {
        this.mAssetsBundleFileName = assetsBundleFileName;
        String binaryJsBundleUrl = CodePushConstants.ASSETS_BUNDLE_PREFIX + assetsBundleFileName;

        String packageFilePath = mUpdateManager.getCurrentPackageEntryPath(this.mAssetsBundleFileName);
        if (packageFilePath == null) {
            // There has not been any downloaded updates.
            CodePushRNUtils.logBundleUrl(binaryJsBundleUrl);
            sIsRunningBinaryVersion = true;
            return binaryJsBundleUrl;
        }

        JSONObject packageMetadata = this.mUpdateManager.getCurrentPackage();
        if (isPackageBundleLatest(packageMetadata)) {
            CodePushRNUtils.logBundleUrl(packageFilePath);
            sIsRunningBinaryVersion = false;
            return packageFilePath;
        } else {
            // The binary version is newer.
            this.mDidUpdate = false;
            if (!this.mIsDebugMode || hasBinaryVersionChanged(packageMetadata)) {
                this.clearUpdates();
            }

            CodePushRNUtils.logBundleUrl(binaryJsBundleUrl);
            sIsRunningBinaryVersion = true;
            return binaryJsBundleUrl;
        }
    }

    public static String getServerUrl() {
        return mServerUrl;
    }

    void initializeUpdateAfterRestart() {
        // Reset the state which indicates that
        // the app was just freshly updated.
        mDidUpdate = false;

        JSONObject pendingUpdate = mSettingsManager.getPendingUpdate();
        if (pendingUpdate != null) {
            JSONObject packageMetadata = this.mUpdateManager.getCurrentPackage();
            if (packageMetadata == null || !isPackageBundleLatest(packageMetadata) && hasBinaryVersionChanged(packageMetadata)) {
                CodePushRNUtils.log("Skipping initializeUpdateAfterRestart(), binary version is newer");
                return;
            }

            try {
                boolean updateIsLoading = pendingUpdate.getBoolean(CodePushConstants.PENDING_UPDATE_IS_LOADING_KEY);
                if (updateIsLoading) {
                    // Pending update was initialized, but notifyApplicationReady was not called.
                    // Therefore, deduce that it is a broken update and rollback.
                    CodePushRNUtils.log("Update did not finish loading the last time, rolling back to a previous version.");
                    sNeedToReportRollback = true;
                    rollbackPackage();
                } else {
                    // There is in fact a new update running for the first
                    // time, so update the local state to ensure the client knows.
                    mDidUpdate = true;

                    // Mark that we tried to initialize the new update, so that if it crashes,
                    // we will know that we need to rollback when the app next starts.
                    mSettingsManager.savePendingUpdate(pendingUpdate.getString(CodePushConstants.PENDING_UPDATE_HASH_KEY),
                            /* isLoading */true);
                }
            } catch (JSONException e) {
                // Should not happen.
                throw new CodePushUnknownException("Unable to read pending update metadata stored in SharedPreferences", e);
            }
        }
    }

    void invalidateCurrentInstance() {
        mCurrentInstance = null;
    }

    boolean isDebugMode() {
        return mIsDebugMode;
    }

    boolean isRunningBinaryVersion() {
        return sIsRunningBinaryVersion;
    }

    private boolean isPackageBundleLatest(JSONObject packageMetadata) {
        try {
            Long binaryModifiedDateDuringPackageInstall = null;
            String binaryModifiedDateDuringPackageInstallString = packageMetadata.optString(CodePushConstants.BINARY_MODIFIED_TIME_KEY, null);
            if (binaryModifiedDateDuringPackageInstallString != null) {
                binaryModifiedDateDuringPackageInstall = Long.parseLong(binaryModifiedDateDuringPackageInstallString);
            }
            String packageAppVersion = packageMetadata.optString("appVersion", null);
            long binaryResourcesModifiedTime = getBinaryResourcesModifiedTime();
            return binaryModifiedDateDuringPackageInstall != null &&
                    binaryModifiedDateDuringPackageInstall == binaryResourcesModifiedTime &&
                    (isUsingTestConfiguration() || sAppVersion.equals(packageAppVersion));
        } catch (NumberFormatException e) {
            throw new CodePushUnknownException("Error in reading binary modified date from package metadata", e);
        }
    }

    private boolean hasBinaryVersionChanged(JSONObject packageMetadata) {
        String packageAppVersion = packageMetadata.optString("appVersion", null);
        return !sAppVersion.equals(packageAppVersion);
    }

    boolean needToReportRollback() {
        return sNeedToReportRollback;
    }

    public static void overrideAppVersion(String appVersionOverride) {
        sAppVersion = appVersionOverride;
    }

    private void rollbackPackage() {
        JSONObject failedPackage = mUpdateManager.getCurrentPackage();
        mSettingsManager.saveFailedUpdate(failedPackage);
        mUpdateManager.rollbackPackage();
        mSettingsManager.removePendingUpdate();
    }

    public void setNeedToReportRollback(boolean needToReportRollback) {
        CodePushCore.sNeedToReportRollback = needToReportRollback;
    }

    /* The below 3 methods are used for running tests.*/
    public static boolean isUsingTestConfiguration() {
        return sTestConfigurationFlag;
    }

    public void setDeploymentKey(String deploymentKey) {
        mDeploymentKey = deploymentKey;
    }

    public static void setUsingTestConfiguration(boolean shouldUseTestConfiguration) {
        sTestConfigurationFlag = shouldUseTestConfiguration;
    }

    public void clearUpdates() {
        mUpdateManager.clearUpdates();
        mSettingsManager.removePendingUpdate();
        mSettingsManager.removeFailedUpdates();
    }

    public static void setReactInstanceHolder(ReactInstanceHolder reactInstanceHolder) {
        mReactInstanceHolder = reactInstanceHolder;
    }

    static ReactInstanceManager getReactInstanceManager() {
        if (mReactInstanceHolder == null) {
            return null;
        }
        return mReactInstanceHolder.getReactInstanceManager();
    }

    public List<NativeModule> createNativeModules(ReactApplicationContext reactApplicationContext) {
        mReactApplicationContext = reactApplicationContext;
        mCodePushModule = new CodePushNativeModule(mReactApplicationContext, this);
        mDialogModule = new CodePushDialog(mReactApplicationContext);

        addSyncStatusListener(mCodePushModule);
        addDownloadProgressListener(mCodePushModule);

        List<NativeModule> nativeModules = new ArrayList<>();
        nativeModules.add(mCodePushModule);
        nativeModules.add(mDialogModule);
        return nativeModules;
    }

    public CodePushConfiguration getConfiguration() {
        return new CodePushConfiguration(
                sAppVersion,
                Settings.Secure.getString(getContext().getContentResolver(), Settings.Secure.ANDROID_ID),
                getDeploymentKey(),
                getServerUrl(),
                CodePushUpdateUtils.getHashForBinaryContents(getContext(), isDebugMode())
        );
    }

    public CodePushRemotePackage checkForUpdate() {
        CodePushConfiguration nativeConfiguration = getConfiguration();
        return checkForUpdate(nativeConfiguration.DeploymentKey);
    }

    public CodePushRemotePackage checkForUpdate(String deploymentKey) {
        //todo check that its correct!
        CodePushConfiguration nativeConfiguration = getConfiguration();
        CodePushConfiguration configuration = new CodePushConfiguration(
                nativeConfiguration.AppVersion,
                nativeConfiguration.ClientUniqueId,
                deploymentKey != null ? deploymentKey : nativeConfiguration.DeploymentKey,
                nativeConfiguration.ServerUrl,
                nativeConfiguration.PackageHash
        );

        CodePushLocalPackage localPackage = getCurrentPackage();
        CodePushLocalPackage queryPackage;

        if (localPackage != null) {
            queryPackage = localPackage;
        } else {
            queryPackage = CodePushLocalPackage.createQueryPackage(configuration.AppVersion);
        }

        CodePushRemotePackage update = new CodePushAcquisitionManager(configuration).queryUpdateWithCurrentPackage(queryPackage);

        if (update == null || update.isUpdateAppVersion() ||
                localPackage != null && (update.getPackageHash().equals(localPackage.getPackageHash())) ||
                (localPackage == null || localPackage.isDebugOnly()) && configuration.PackageHash.equals(update.getPackageHash())) {
            if (update != null && update.isUpdateAppVersion()) {
                CodePushRNUtils.log("An update is available but it is not targeting the binary version of your app.");
                binaryVersionMismatchChange(update);
            }
            return null;
        } else {
            String deploymentKeyFinal = deploymentKey != null ? deploymentKey : update.getDeploymentKey();
            update.setDeploymentKey(deploymentKeyFinal);
            boolean isFailedUpdate = isFailedUpdate(update.getPackageHash());
            update.setFailedInstall(isFailedUpdate);
            return update;
        }
    }

    public CodePushLocalPackage getCurrentPackage() {
        return getUpdateMetadata(CodePushUpdateState.LATEST);
    }

    public CodePushLocalPackage getUpdateMetadata(CodePushUpdateState updateState) {
        if (updateState == null) {
            updateState = CodePushUpdateState.RUNNING;
        }

        CodePushLocalPackage currentPackage = mUpdateManagerDeserializer.getCurrentPackage();

        if (currentPackage == null) {
            return null;
        }

        Boolean currentUpdateIsPending = false;
        Boolean isDebugOnly = false;

        String currentHash = currentPackage.getPackageHash();
        if (currentHash != null && !currentHash.isEmpty()) {
            currentUpdateIsPending = mSettingsManager.isPendingUpdate(currentHash);
        }

        if (updateState == CodePushUpdateState.PENDING && !currentUpdateIsPending) {
            // The caller wanted a pending update
            // but there isn't currently one.
            return null;
        } else if (updateState == CodePushUpdateState.RUNNING && currentUpdateIsPending) {
            // The caller wants the running update, but the current
            // one is pending, so we need to grab the previous.
            CodePushLocalPackage previousPackage = mUpdateManagerDeserializer.getPreviousPackage();

            if (previousPackage == null) {
                return null;
            }

            return previousPackage;
        } else {
            // The current package satisfies the request:
            // 1) Caller wanted a pending, and there is a pending update
            // 2) Caller wanted the running update, and there isn't a pending
            // 3) Caller wants the latest update, regardless if it's pending or not
            if (isRunningBinaryVersion()) {
                // This only matters in Debug builds. Since we do not clear "outdated" updates,
                // we need to indicate to the JS side that somehow we have a current update on
                // disk that is not actually running.
                isDebugOnly = true;
            }

            // Enable differentiating pending vs. non-pending updates
            boolean isFailedUpdate = isFailedUpdate(currentPackage.getPackageHash());
            currentPackage.setFailedInstall(isFailedUpdate);
            boolean isFirstRun = isFirstRun(currentPackage.getPackageHash());
            currentPackage.setFirstRun(isFirstRun);
            currentPackage.setPending(currentUpdateIsPending);
            currentPackage.setDebugOnly(isDebugOnly);
            return currentPackage;
        }
    }

    private CodePushSyncOptions getDefaultSyncOptions() {
        return new CodePushSyncOptions(mDeploymentKey);
    }

    public void sync() {
        sync(getDefaultSyncOptions(), null);
    }

    public void sync(CodePushSyncOptions syncOptions) {
        sync(syncOptions, null);
    }

    public void sync(CodePushSyncOptions syncOptions, final Promise promise) {
        if (mSyncInProgress) {
            syncStatusChange(CodePushSyncStatus.SYNC_IN_PROGRESS);
            CodePushRNUtils.log("Sync already in progress.");
            return;
        }

        if (syncOptions == null) {
            syncOptions = getDefaultSyncOptions();
        }

        if (isNullOrEmpty(syncOptions.getDeploymentKey())) {
            syncOptions.setDeploymentKey(mDeploymentKey);
        }
        if (syncOptions.getInstallMode() == null) {
            syncOptions.setInstallMode(ON_NEXT_RESTART);
        }
        if (syncOptions.getMandatoryInstallMode() == null) {
            syncOptions.setMandatoryInstallMode(IMMEDIATE);
        }
        if (syncOptions.getCheckFrequency() == null) {
            syncOptions.setCheckFrequency(ON_APP_START);
        }

        CodePushConfiguration nativeConfiguration = getConfiguration();
        final CodePushConfiguration configuration = new CodePushConfiguration(
                nativeConfiguration.AppVersion,
                nativeConfiguration.ClientUniqueId,
                syncOptions.getDeploymentKey() != null ? syncOptions.getDeploymentKey() : nativeConfiguration.DeploymentKey,
                nativeConfiguration.ServerUrl,
                nativeConfiguration.PackageHash
        );

        mSyncInProgress = true;
        notifyApplicationReady();
        syncStatusChange(CodePushSyncStatus.CHECKING_FOR_UPDATE);
        final CodePushRemotePackage remotePackage = checkForUpdate(syncOptions.getDeploymentKey());

        final boolean updateShouldBeIgnored = remotePackage != null && (remotePackage.isFailedInstall() && syncOptions.isIgnoreFailedUpdates());
        if (remotePackage == null || updateShouldBeIgnored) {
            if (updateShouldBeIgnored) {
                CodePushRNUtils.log("An update is available, but it is being ignored due to having been previously rolled back.");
            }

            CodePushLocalPackage currentPackage = getCurrentPackage();
            if (currentPackage != null && currentPackage.isPending()) {
                syncStatusChange(CodePushSyncStatus.UPDATE_INSTALLED);
                mSyncInProgress = false;
                if (promise != null) promise.resolve("");
                return;
            } else {
                syncStatusChange(CodePushSyncStatus.UP_TO_DATE);
                mSyncInProgress = false;
                if (promise != null) promise.resolve("");
                return;
            }
        } else if (syncOptions.getUpdateDialog() != null) {

            String message;
            String button1Text;
            String button2Text = syncOptions.getUpdateDialog().getOptionalIgnoreButtonLabel();

            if (remotePackage.isMandatory()) {
                message = syncOptions.getUpdateDialog().getMandatoryUpdateMessage();
                button1Text = syncOptions.getUpdateDialog().getMandatoryContinueButtonLabel();
            } else {
                message = syncOptions.getUpdateDialog().getOptionalUpdateMessage();
                button1Text = syncOptions.getUpdateDialog().getOptionalIgnoreButtonLabel();
            }

            if (syncOptions.getUpdateDialog().getAppendReleaseDescription() && remotePackage.getDescription() != null && !remotePackage.getDescription().isEmpty()) {
                message = syncOptions.getUpdateDialog().getDescriptionPrefix() + " " + remotePackage.getDescription();
            }

            final CodePushSyncOptions syncOptionsFinal = syncOptions;
            final Callback successCallback = new Callback() {
                @Override
                public void invoke(Object... args) {
                    String buttonNumber = args[0].toString();

                    if (buttonNumber.equals("0")) {
                        AsyncTask<Void, Void, Void> asyncTask = new AsyncTask<Void, Void, Void>() {
                            @Override
                            protected Void doInBackground(Void... params) {
                                try {
                                    doDownloadAndInstall(remotePackage, syncOptionsFinal, configuration);
                                    if (promise != null) promise.resolve("");
                                } catch (Exception e) {
                                    mSyncInProgress = false;
                                    if (promise != null) promise.resolve("");
                                    CodePushRNUtils.log(e.toString());
                                }
                                return null;
                            }
                        };
                        asyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                    } else if (buttonNumber.equals("1")) {
                        syncStatusChange(CodePushSyncStatus.UPDATE_IGNORED);
                        mSyncInProgress = false;
                        if (promise != null) promise.resolve("");
                    } else {
                        mSyncInProgress = false;
                        if (promise != null) promise.resolve("");
                        throw new CodePushUnknownException("Unknown button ID pressed.");
                    }
                }
            };

            final Callback errorCallback = new Callback() {
                @Override
                public void invoke(Object... args) {
                    syncStatusChange(CodePushSyncStatus.UNKNOWN_ERROR);
                    mSyncInProgress = false;
                    if (promise != null) promise.resolve("");
                    throw new CodePushUnknownException(Arrays.toString(args));
                }
            };

            final String titleFinal = syncOptions.getUpdateDialog().getTitle();
            final String messageFinal = message;
            final String button1TextFinal = button1Text;
            final String button2TextFinal = button2Text;

            syncStatusChange(CodePushSyncStatus.AWAITING_USER_ACTION);
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        // errorCallback is not used inside mDialogModule.showDialog()
                        mDialogModule.showDialog(titleFinal, messageFinal, button1TextFinal, button2TextFinal, successCallback, errorCallback);
                    } catch (Exception e) {
                        syncStatusChange(CodePushSyncStatus.UNKNOWN_ERROR);
                        mSyncInProgress = false;
                        if (promise != null) promise.resolve("");
                        CodePushRNUtils.log(e.toString());
                    }
                }
            });
        } else {
            try {
                doDownloadAndInstall(remotePackage, syncOptions, configuration);
            } catch (Exception e) {
                syncStatusChange(CodePushSyncStatus.UNKNOWN_ERROR);
                mSyncInProgress = false;
                if (promise != null) promise.reject(e);
            }
            if (promise != null) promise.resolve("");
        }
    }

    private void doDownloadAndInstall(final CodePushRemotePackage remotePackage, final CodePushSyncOptions syncOptions, final CodePushConfiguration configuration) throws Exception {
        syncStatusChange(CodePushSyncStatus.DOWNLOADING_PACKAGE);
        CodePushLocalPackage localPackage = downloadUpdate(remotePackage);
        if (localPackage.getDownloadException() != null) {
            throw localPackage.getDownloadException();
        }
        new CodePushAcquisitionManager(configuration).reportStatusDownload(localPackage);

        CodePushInstallMode resolvedInstallMode = localPackage.isMandatory() ? syncOptions.getMandatoryInstallMode() : syncOptions.getInstallMode();
        mCurrentInstallModeInProgress = resolvedInstallMode;
        syncStatusChange(CodePushSyncStatus.INSTALLING_UPDATE);
        installUpdate(localPackage, resolvedInstallMode, syncOptions.getMinimumBackgroundDuration());
        syncStatusChange(CodePushSyncStatus.UPDATE_INSTALLED);
        mSyncInProgress = false;
        if (resolvedInstallMode == IMMEDIATE) {
            mRestartManager.restartApp(false);
        } else {
            mRestartManager.clearPendingRestart();
        }
    }

    public CodePushLocalPackage downloadUpdate(final CodePushRemotePackage updatePackage) {
        CodePushLocalPackage newPackage;
        try {
            JSONObject mutableUpdatePackage = CodePushUtils.convertObjectToJsonObject(updatePackage);
            CodePushUtils.setJSONValueForKey(mutableUpdatePackage, CodePushConstants.BINARY_MODIFIED_TIME_KEY, "" + getBinaryResourcesModifiedTime());

            DownloadProgressCallback downloadProgressCallback = new DownloadProgressCallback() {

                private boolean hasScheduledNextFrame = false;
                private DownloadProgress latestDownloadProgress = null;

                @Override
                public void call(final DownloadProgress downloadProgress) {
                    latestDownloadProgress = downloadProgress;
                    // If the download is completed, synchronously send the last event.
                    if (latestDownloadProgress.isCompleted()) {
                        downloadProgressChange(downloadProgress.getReceivedBytes(), downloadProgress.getTotalBytes());
                        return;
                    }

                    if (hasScheduledNextFrame) {
                        return;
                    }

                    hasScheduledNextFrame = true;
                    // if ReactNative app wasn't been initialized, no need to send download progress to it
                    if (mReactApplicationContext != null) {
                        mReactApplicationContext.runOnUiQueueThread(new Runnable() {
                            @Override
                            public void run() {
                                ReactChoreographer.getInstance().postFrameCallback(ReactChoreographer.CallbackType.TIMERS_EVENTS, new ChoreographerCompat.FrameCallback() {
                                    @Override
                                    public void doFrame(long frameTimeNanos) {
                                        if (!latestDownloadProgress.isCompleted()) {
                                            downloadProgressChange(downloadProgress.getReceivedBytes(), downloadProgress.getTotalBytes());
                                        }

                                        hasScheduledNextFrame = false;
                                    }
                                });
                            }
                        });
                    } else {
                        downloadProgressChange(downloadProgress.getReceivedBytes(), downloadProgress.getTotalBytes());
                    }
                }
            };

            PackageDownloader packageDownloader = new PackageDownloader();
            final String downloadUrlString = updatePackage.getDownloadUrl();
            packageDownloader.setParameters(downloadUrlString, new File(getAssetsBundleFileName()), downloadProgressCallback);

            mUpdateManager.downloadPackage(mutableUpdatePackage, downloadProgressCallback, packageDownloader);

            newPackage = mUpdateManagerDeserializer.getPackage(updatePackage.getPackageHash());
            return newPackage;
        } catch (IOException e) {
            e.printStackTrace();
            return CodePushLocalPackage.createFailedLocalPackage(e);
        } catch (CodePushInvalidUpdateException e) {
            e.printStackTrace();
            mSettingsManager.saveFailedUpdate(CodePushUtils.convertObjectToJsonObject(updatePackage));
            return CodePushLocalPackage.createFailedLocalPackage(e);
        }
    }

    public void installUpdate(final CodePushLocalPackage updatePackage, final CodePushInstallMode installMode, final int minimumBackgroundDuration) {
        mUpdateManager.installPackage(CodePushUtils.convertObjectToJsonObject(updatePackage), mSettingsManager.isPendingUpdate(null));

        String pendingHash = updatePackage.getPackageHash();
        if (pendingHash == null) {
            throw new CodePushUnknownException("Update package to be installed has no hash.");
        } else {
            mSettingsManager.savePendingUpdate(pendingHash, /* isLoading */false);
        }

        if (installMode == CodePushInstallMode.ON_NEXT_RESUME ||
                // We also add the resume listener if the installMode is IMMEDIATE, because
                // if the current activity is backgrounded, we want to reload the bundle when
                // it comes back into the foreground.
                installMode == IMMEDIATE ||
                installMode == CodePushInstallMode.ON_NEXT_SUSPEND) {

            // Store the minimum duration on the native module as an instance
            // variable instead of relying on a closure below, so that any
            // subsequent resume-based installs could override it.
            mMinimumBackgroundDuration = minimumBackgroundDuration;

            if (mLifecycleEventListener == null) {
                // Ensure we do not add the listener twice.
                mLifecycleEventListener = new LifecycleEventListener() {
                    private Date lastPausedDate = null;
                    private Handler appSuspendHandler = new Handler(Looper.getMainLooper());
                    private Runnable loadBundleRunnable = new Runnable() {
                        @Override
                        public void run() {
                            CodePushRNUtils.log("Loading bundle on suspend");
                            mRestartManager.restartApp(false);
                        }
                    };

                    @Override
                    public void onHostResume() {
                        appSuspendHandler.removeCallbacks(loadBundleRunnable);
                        // As of RN 36, the resume handler fires immediately if the app is in
                        // the foreground, so explicitly wait for it to be backgrounded first
                        if (lastPausedDate != null) {
                            long durationInBackground = (new Date().getTime() - lastPausedDate.getTime()) / 1000;
                            if (installMode == IMMEDIATE
                                    || durationInBackground >= mMinimumBackgroundDuration) {
                                CodePushRNUtils.log("Loading bundle on resume");
                                mRestartManager.restartApp(false);
                            }
                        }
                    }

                    @Override
                    public void onHostPause() {
                        // Save the current time so that when the app is later
                        // resumed, we can detect how long it was in the background.
                        lastPausedDate = new Date();

                        if (installMode == CodePushInstallMode.ON_NEXT_SUSPEND && mSettingsManager.isPendingUpdate(null)) {
                            appSuspendHandler.postDelayed(loadBundleRunnable, minimumBackgroundDuration * 1000);
                        }
                    }

                    @Override
                    public void onHostDestroy() {
                    }
                };

                if (mReactApplicationContext != null) {
                    mReactApplicationContext.addLifecycleEventListener(mLifecycleEventListener);
                }
            }
        }
    }

    public boolean isFailedUpdate(String packageHash) {
        return mSettingsManager.isFailedHash(packageHash);
    }

    public boolean isFirstRun(String packageHash) {
        return didUpdate()
                && !isNullOrEmpty(packageHash)
                && packageHash.equals(mUpdateManager.getCurrentPackageHash());
    }

    public void removePendingUpdate() {
        mSettingsManager.removePendingUpdate();
    }

    public void notifyApplicationReady() {
        mSettingsManager.removePendingUpdate();
        final CodePushDeploymentStatusReport statusReport = getNewStatusReport();
        if (statusReport != null) {
            tryReportStatus(statusReport);
        }
    }

    private void loadBundle() {
        clearLifecycleEventListener();
        clearDebugCacheIfNeeded();
        try {
            // #1) Get the ReactInstanceManager instance, which is what includes the
            //     logic to reload the current React context.
            final ReactInstanceManager instanceManager = resolveInstanceManager();
            if (instanceManager == null) {
                return;
            }

            String latestJSBundleFile = getJSBundleFileInternal(getAssetsBundleFileName());

            // #2) Update the locally stored JS bundle file path
            setJSBundle(instanceManager, latestJSBundleFile);

            // #3) Get the context creation method and fire it on the UI thread (which RN enforces)
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        // We don't need to resetReactRootViews anymore 
                        // due the issue https://github.com/facebook/react-native/issues/14533
                        // has been fixed in RN 0.46.0
                        //resetReactRootViews(instanceManager);

                        instanceManager.recreateReactContextInBackground();
                        initializeUpdateAfterRestart();
                    } catch (Exception e) {
                        // The recreation method threw an unknown exception
                        // so just simply fallback to restarting the Activity (if it exists)
                        loadBundleLegacy();
                    }
                }
            });

        } catch (Exception e) {
            // Our reflection logic failed somewhere
            // so fall back to restarting the Activity (if it exists)
            loadBundleLegacy();
        }
    }

    // This workaround has been implemented in order to fix https://github.com/facebook/react-native/issues/14533
    // resetReactRootViews allows to call recreateReactContextInBackground without any exceptions
    // This fix also relates to https://github.com/Microsoft/react-native-code-push/issues/878
    private void resetReactRootViews(ReactInstanceManager instanceManager) throws NoSuchFieldException, IllegalAccessException {
        Field mAttachedRootViewsField = instanceManager.getClass().getDeclaredField("mAttachedRootViews");
        mAttachedRootViewsField.setAccessible(true);
        List<ReactRootView> mAttachedRootViews = (List<ReactRootView>) mAttachedRootViewsField.get(instanceManager);
        for (ReactRootView reactRootView : mAttachedRootViews) {
            reactRootView.removeAllViews();
            reactRootView.setId(View.NO_ID);
        }
        mAttachedRootViewsField.set(instanceManager, mAttachedRootViews);
    }

    private void clearLifecycleEventListener() {
        // Remove LifecycleEventListener to prevent infinite restart loop
        if (mLifecycleEventListener != null) {
            mReactApplicationContext.removeLifecycleEventListener(mLifecycleEventListener);
            mLifecycleEventListener = null;
        }
    }

    private void clearLifecycleEventListenerForReport() {
        // Remove LifecycleEventListener to prevent infinite restart loop
        if (mLifecycleEventListenerForReport != null) {
            mReactApplicationContext.removeLifecycleEventListener(mLifecycleEventListenerForReport);
            mLifecycleEventListenerForReport = null;
        }
    }

    // Use reflection to find the ReactInstanceManager. See #556 for a proposal for a less brittle way to approach this.
    private ReactInstanceManager resolveInstanceManager() throws NoSuchFieldException, IllegalAccessException {
        ReactInstanceManager instanceManager = CodePushCore.getReactInstanceManager();
        if (instanceManager != null) {
            return instanceManager;
        }

        final Activity currentActivity = mReactApplicationContext.getCurrentActivity();
        if (currentActivity == null) {
            return null;
        }

        ReactApplication reactApplication = (ReactApplication) currentActivity.getApplication();
        instanceManager = reactApplication.getReactNativeHost().getReactInstanceManager();

        return instanceManager;
    }

    // Use reflection to find and set the appropriate fields on ReactInstanceManager. See #556 for a proposal for a less brittle way
    // to approach this.
    private void setJSBundle(ReactInstanceManager instanceManager, String latestJSBundleFile) throws IllegalAccessException {
        try {
            JSBundleLoader latestJSBundleLoader;
            if (latestJSBundleFile.toLowerCase().startsWith("assets://")) {
                latestJSBundleLoader = JSBundleLoader.createAssetLoader(mReactApplicationContext, latestJSBundleFile, false);
            } else {
                latestJSBundleLoader = JSBundleLoader.createFileLoader(latestJSBundleFile);
            }

            Field bundleLoaderField = instanceManager.getClass().getDeclaredField("mBundleLoader");
            bundleLoaderField.setAccessible(true);
            bundleLoaderField.set(instanceManager, latestJSBundleLoader);
        } catch (Exception e) {
            CodePushRNUtils.log("Unable to set JSBundle - CodePush may not support this version of React Native");
            throw new IllegalAccessException("Could not setJSBundle");
        }
    }

    private void loadBundleLegacy() {
        final Activity currentActivity = mReactApplicationContext.getCurrentActivity();
        if (currentActivity == null) {
            // The currentActivity can be null if it is backgrounded / destroyed, so we simply
            // no-op to prevent any null pointer exceptions.
            return;
        }
        invalidateCurrentInstance();

        currentActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                currentActivity.recreate();
            }
        });
    }

    public boolean restartApp(boolean onlyIfUpdateIsPending) {
        // If this is an unconditional restart request, or there
        // is current pending update, then reload the app.
        if (!onlyIfUpdateIsPending || mSettingsManager.isPendingUpdate(null)) {
            loadBundle();
            return true;
        }

        return false;
    }

    private void tryReportStatus(final CodePushDeploymentStatusReport statusReport) {
        boolean succeeded = true;
        try {
            CodePushConfiguration nativeConfiguration = getConfiguration();
            String appVersion = statusReport.getAppVersion();
            if (appVersion != null && !appVersion.isEmpty()) {
                CodePushRNUtils.log("Reporting binary update (" + appVersion + ")");
                succeeded = new CodePushAcquisitionManager(nativeConfiguration).reportStatusDeploy(statusReport);
            } else {
                if (statusReport.getStatus() == CodePushDeploymentStatus.SUCCEEDED) {
                    CodePushRNUtils.log("Reporting CodePush update success (" + statusReport.Label + ")");
                } else {
                    CodePushRNUtils.log("Reporting CodePush update rollback (" + statusReport.Label + ")");
                }

                CodePushConfiguration configuration = new CodePushConfiguration(
                        nativeConfiguration.AppVersion,
                        nativeConfiguration.ClientUniqueId,
                        statusReport.getDeploymentKey(),
                        nativeConfiguration.ServerUrl,
                        nativeConfiguration.PackageHash
                );
                if (new CodePushAcquisitionManager(configuration).reportStatusDeploy(statusReport)) {
                    recordStatusReported(statusReport);
                } else {
                    succeeded = false;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            succeeded = false;
        }

        if (!succeeded) {
            CodePushRNUtils.log("Report status failed: " + statusReport.toString());
            saveStatusReportForRetry(statusReport);

            // Try again when the app resumes
            if (mLifecycleEventListenerForReport == null) {
                mLifecycleEventListenerForReport = new LifecycleEventListener() {

                    @Override
                    public void onHostResume() {
                        final CodePushDeploymentStatusReport statusReport = getNewStatusReport();
                        if (statusReport != null) {
                            tryReportStatus(statusReport);
                        }
                    }

                    @Override
                    public void onHostPause() {

                    }

                    @Override
                    public void onHostDestroy() {

                    }
                };

                mReactApplicationContext.addLifecycleEventListener(mLifecycleEventListenerForReport);
            }
        } else {
            // If there was several attempts to send error reports
            if (mLifecycleEventListenerForReport != null) {
                clearLifecycleEventListenerForReport();
            }
        }
    }

    public void recordStatusReported(CodePushDeploymentStatusReport statusReport) {
        mTelemetryManager.recordStatusReported(statusReport);
    }

    public CodePushDeploymentStatusReport getNewStatusReport() {
        if (needToReportRollback()) {
            setNeedToReportRollback(false);
            JSONArray failedUpdates = mSettingsManager.getFailedUpdates();
            if (failedUpdates != null && failedUpdates.length() > 0) {
                try {
                    JSONObject lastFailedPackageJSON = failedUpdates.getJSONObject(failedUpdates.length() - 1);
                    WritableMap lastFailedPackage = CodePushRNUtils.convertJsonObjectToWritable(lastFailedPackageJSON);
                    CodePushDeploymentStatusReport failedStatusReport = mTelemetryManagerDeserializer.getRollbackReport(lastFailedPackage);
                    if (failedStatusReport != null) {
                        return failedStatusReport;
                    }
                } catch (JSONException e) {
                    throw new CodePushUnknownException("Unable to read failed updates information stored in SharedPreferences.", e);
                }
            }
        } else if (didUpdate()) {
            JSONObject currentPackage = mUpdateManager.getCurrentPackage();
            if (currentPackage != null) {
                CodePushDeploymentStatusReport newPackageStatusReport = mTelemetryManagerDeserializer.getUpdateReport(CodePushRNUtils.convertJsonObjectToWritable(currentPackage));
                if (newPackageStatusReport != null) {
                    return newPackageStatusReport;
                }
            }
        } else if (isRunningBinaryVersion()) {
            CodePushDeploymentStatusReport newAppVersionStatusReport = mTelemetryManagerDeserializer.getBinaryUpdateReport(getAppVersion());
            if (newAppVersionStatusReport != null) {
                return newAppVersionStatusReport;
            }
        } else {
            CodePushDeploymentStatusReport retryStatusReport = mTelemetryManagerDeserializer.getRetryStatusReport();
            if (retryStatusReport != null) {
                return retryStatusReport;
            }
        }

        return null;
    }

    public CodePushRestartManager getRestartManager() {
        return mRestartManager;
    }

    public void downloadAndReplaceCurrentBundle(String remoteBundleUrl) {
        if (isUsingTestConfiguration()) {
            try {
                mUpdateManager.downloadAndReplaceCurrentUpdate(remoteBundleUrl, getAssetsBundleFileName());
            } catch (IOException e) {
                throw new CodePushUnknownException("Unable to replace current bundle", e);
            }
        }
    }

    public void saveStatusReportForRetry(CodePushDeploymentStatusReport statusReport) {
        mTelemetryManager.saveStatusReportForRetry(statusReport);
    }
}
