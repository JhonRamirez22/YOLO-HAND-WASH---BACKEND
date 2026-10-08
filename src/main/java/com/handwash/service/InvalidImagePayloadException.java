package com.handwash.service;

import java.io.IOException;

/** Indicates that an uploaded payload is not a readable image. */
public class InvalidImagePayloadException extends IOException {
    public InvalidImagePayloadException(String message) {
        super(message);
    }

    public InvalidImagePayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
