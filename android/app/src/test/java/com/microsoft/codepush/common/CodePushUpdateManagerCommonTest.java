package com.microsoft.codepush.common;

import android.os.Environment;

import com.microsoft.codepush.common.connection.PackageDownloader;
import com.microsoft.codepush.common.exceptions.CodePushDownloadPackageException;
import com.microsoft.codepush.common.exceptions.CodePushUnzipException;
import com.microsoft.codepush.common.interfaces.DownloadProgressCallback;
import com.microsoft.codepush.common.managers.CodePushUpdateManager;
import com.microsoft.codepush.common.utils.FileUtils;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.io.File;
import java.io.IOException;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.mockito.PowerMockito.spy;

/**
 * This class is for testing those {@link CodePushUpdateManager} test cases that depend on {@link FileUtils} static methods failure.
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest(FileUtils.class)
public class CodePushUpdateManagerCommonTest {

    @Rule
    public final ExpectedException exception = ExpectedException.none();

    /**
     * Instance of testable {@link CodePushUpdateManager}.
     */
    private CodePushUpdateManager codePushUpdateManager;

    @Before
    public void setUp() {
        codePushUpdateManager = new CodePushUpdateManager(new File(Environment.getExternalStorageDirectory(), "/Test").getPath());
    }

    /**
     * Download package should throw a {@link CodePushDownloadPackageException} if an {@link IOException} is thrown during {@link FileUtils#deleteDirectoryAtPath(String)}.
     * If deleting file at path where a new update should be located fails, the whole method should fail.
     */
    @Test(expected = CodePushDownloadPackageException.class)
    public void downloadFailsIfDeleteNewUpdateFolderPathFails() throws Exception {
        mockStatic(FileUtils.class);
        PowerMockito.doThrow(new IOException()).when(FileUtils.class, "deleteDirectoryAtPath", anyString());
        PowerMockito.doReturn(true).when(FileUtils.class, "fileAtPathExists", anyString());
        codePushUpdateManager.downloadPackage(mock(JSONObject.class), mock(DownloadProgressCallback.class), mock(PackageDownloader.class));
    }

    /**
     * Unzip should throw a {@link CodePushUnzipException} if an {@link IOException} is thrown during {@link FileUtils#unzipFile(File, File)}.
     */
    @Test(expected = CodePushUnzipException.class)
    public void unzipFailsIfUnzipFileFails() throws Exception {
        mockStatic(FileUtils.class);
        PowerMockito.doThrow(new IOException()).when(FileUtils.class, "unzipFile", any(File.class), any(File.class));
        codePushUpdateManager = spy(codePushUpdateManager);
        PowerMockito.when(codePushUpdateManager, "getUnzippedFolderPath").thenReturn("");
        codePushUpdateManager.unzipPackage(mock(File.class));
    }

    /**
     * Download package should throw a {@link CodePushDownloadPackageException} if an {@link InterruptedException} is thrown during {@link PackageDownloader#get()}.
     * If executing an {@link android.os.AsyncTask} fails, downloading package should fail, too.
     */
    @Test(expected = CodePushDownloadPackageException.class)
    public void downloadFailsIfPackageDownloaderFails() throws Exception {
        codePushUpdateManager = spy(codePushUpdateManager);
        doReturn(new File(Environment.getExternalStorageDirectory(), "/Test/HASH").getPath()).when(codePushUpdateManager).getPackageFolderPath(anyString());
        PackageDownloader packageDownloader = PowerMockito.mock(PackageDownloader.class);
        PowerMockito.when(packageDownloader.get()).thenThrow(new InterruptedException());
        codePushUpdateManager.downloadPackage(mock(JSONObject.class), mock(DownloadProgressCallback.class), packageDownloader);
    }
}
