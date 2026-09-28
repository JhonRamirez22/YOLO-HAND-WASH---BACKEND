package com.handwash.service;

/** Creates and disables operator logins in the identity provider; the grant table stays the authority. */
public interface OperatorAccountDirectory {
    CreatedAccount create(String email);

    /** Disables the login and revokes its refresh tokens. Unknown accounts are ignored. */
    void disable(String subject);

    boolean available();

    record CreatedAccount(String subject, String temporaryPassword) {}

    class UnavailableException extends RuntimeException {
        public UnavailableException(String message) { super(message); }
        public UnavailableException(String message, Throwable cause) { super(message, cause); }
    }

    class AlreadyExistsException extends RuntimeException {
        public AlreadyExistsException(String message) { super(message); }
    }
}
