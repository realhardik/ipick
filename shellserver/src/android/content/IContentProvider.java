package android.content;

public interface IContentProvider {
    android.os.Bundle call(AttributionSource source, String authority, String method,
            String arg, android.os.Bundle extras) throws android.os.RemoteException;
}
