package com.handwash.observer;

/** A failed validation stage makes the session result untrustworthy. */
public class DetectionPipelineException extends RuntimeException {
    public DetectionPipelineException(String message, Throwable cause) {
        super(message, cause);
    }
}
