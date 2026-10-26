package lk.sahan.parking.desktop;

import java.util.concurrent.*;

/** Permission failures are actionable access errors, not retryable connection problems. */
final class MessageErrors {
    enum Action { LIST, VIEW, SEND, REPLY, READ, RESOLVE }
    static Throwable cause(Throwable error) {
        while((error instanceof CompletionException||error instanceof ExecutionException)&&error.getCause()!=null)error=error.getCause();
        return error;
    }
    static boolean forbidden(Throwable error){return cause(error) instanceof ApiClient.HttpFailure http&&http.statusCode()==403;}
    static String describe(Throwable error,Action action,boolean draft) {
        error=cause(error);String text;
        if(error instanceof ApiClient.HttpFailure http) {
            text=switch(http.statusCode()) {
                case 403 -> switch(action){
                    case SEND,REPLY -> "You do not have permission to send this message.";
                    case READ -> "You do not have permission to update this message's read state.";
                    case RESOLVE -> "You do not have permission to change this conversation's status.";
                    default -> "You do not have permission to view these messages.";
                };
                case 401 -> "Your sign-in is no longer valid. Sign out and sign in again.";
                case 404 -> "This conversation is unavailable or does not belong to your account.";
                case 409,400 -> http.getMessage();
                default -> http.statusCode()>=500?"The server could not complete the request. Retry / Refresh to try again.":"The request could not be completed. "+http.getMessage();
            };
        } else if(error instanceof ApiClient.NetworkFailure) text="Could not connect to the server. Check your connection, then Retry / Refresh.";
        else text="The request could not be completed. "+(error.getMessage()==null?"Refresh to try again.":error.getMessage());
        return text+(draft?" Your draft is preserved.":"");
    }
    private MessageErrors(){}
}
