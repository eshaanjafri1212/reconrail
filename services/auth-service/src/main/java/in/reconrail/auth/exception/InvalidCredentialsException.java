package in.reconrail.auth.exception;

public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Invalid Credentials");
    }
}
