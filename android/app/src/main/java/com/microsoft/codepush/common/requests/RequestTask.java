package com.microsoft.codepush.common.requests;

import android.os.AsyncTask;

import com.microsoft.appcenter.utils.AppCenterLog;
import com.microsoft.codepush.common.CodePush;
import com.microsoft.codepush.common.exceptions.ApiRequestException;
import com.microsoft.codepush.common.exceptions.CodePushDownloadPackageException;
import com.microsoft.codepush.common.exceptions.CodePushFinalizeException;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

public abstract class RequestTask<T> extends AsyncTask<Void, Void, T> {

    protected CodePushDownloadPackageException mExecutionException;

    protected CodePushFinalizeException mFinalizeException;

    /**
     * Opens url connection for the provided url.
     *
     * @param urlString url to open.
     * @return instance of url connection.
     * @throws IOException read/write error occurred while accessing the file system.
     */
    protected static HttpURLConnection createConnection(String urlString) throws IOException {
        URL url = new URL(urlString);
        return (HttpURLConnection) url.openConnection();
    }

    public ApiRequestException getInnerException() {
        ApiRequestException innerException = null;
        if (mExecutionException != null) {
            innerException = new ApiRequestException(mExecutionException);
        }
        if (mFinalizeException != null) {
            if (innerException != null) {
                //suppress finalize exception
                AppCenterLog.error(CodePush.LOG_TAG, mFinalizeException.getMessage(), mFinalizeException);
            } else {
                innerException = new ApiRequestException(mFinalizeException);
            }
        }

        return innerException;
    }
}
