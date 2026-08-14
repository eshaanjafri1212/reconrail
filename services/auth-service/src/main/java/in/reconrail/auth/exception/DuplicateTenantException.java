package in.reconrail.auth.exception;

public class DuplicateTenantException extends RuntimeException {
    public DuplicateTenantException(String message) {
        super(message);
    }
}
