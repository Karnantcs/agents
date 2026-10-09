package dev.agentbox.orchestrator;

public final class AgentErrors {

    private AgentErrors() {}

    public static final class NotFound extends RuntimeException {
        public NotFound(String message) {
            super(message);
        }
    }

    public static final class Exists extends RuntimeException {
        public Exists(String message) {
            super(message);
        }
    }

    public static final class StartFailed extends RuntimeException {
        public StartFailed(String message) {
            super(message);
        }
    }

    public static final class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }
    }

    public static final class CallFailed extends RuntimeException {
        private final int status;

        public CallFailed(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
