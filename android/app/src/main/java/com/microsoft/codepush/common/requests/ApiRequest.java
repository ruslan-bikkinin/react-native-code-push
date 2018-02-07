package com.microsoft.codepush.common.requests;

import com.microsoft.codepush.common.exceptions.ApiRequestException;

import java.util.concurrent.ExecutionException;

public class ApiRequest<T> {

    private RequestTask<T> mRequestTask;

    public ApiRequest(RequestTask<T> mRequestTask) {
        this.mRequestTask = mRequestTask;
    }

    public T makeRequest() throws ApiRequestException {
        T taskResult;
        mRequestTask.execute();
        try {
            taskResult = mRequestTask.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new ApiRequestException(e);
        }

        ApiRequestException innerException = mRequestTask.getInnerException();
        if (innerException != null) {
            throw innerException;
        }

        return taskResult;
    }
}
