package mcht;

final class ApiError extends RuntimeException {

    private static final long serialVersionUID = 1L;
    final int code;

    ApiError(int code, String message) {
        super(message);
        this.code = code;
    }
}
