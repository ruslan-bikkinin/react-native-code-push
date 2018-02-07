package com.microsoft.codepush.common.exceptions;

public class ApiRequestException extends Exception {

    public ApiRequestException(Throwable throwable) {
        super(throwable);
    }
}
